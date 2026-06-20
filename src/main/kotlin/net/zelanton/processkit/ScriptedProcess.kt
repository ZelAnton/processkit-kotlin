package net.zelanton.processkit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.stream.Stream
import kotlin.time.Duration

private const val NEWLINE: Byte = '\n'.code.toByte()

/**
 * A fake [Process] serving canned stdout/stderr and a fixed exit code — no OS
 * child. It backs [ScriptedRunner.start] so a scripted run streams through the
 * exact same [RunningProcess] machinery (`stdoutLines` / `waitForLine` / `finish`)
 * as a real child.
 *
 * Three lifetimes, chosen by the [Reply]:
 * - **instant** (the default) — all output is available immediately and the
 *   "process" has already exited; models a finite stream with zero overhead.
 * - **line-paced** ([Reply.withLineDelay]) — each line is released after `delay`,
 *   so a hermetic test observes genuinely incremental delivery; the handle "exits"
 *   once the longer stream has drained, and a [Command.timeout] shorter than that
 *   lifetime makes the watchdog kill it mid-stream exactly as for a real child.
 * - **pending** ([Reply.pending]) — never exits on its own; the streams park until
 *   the handle is killed (a [Command.timeout] watchdog or [close][RunningProcess.close]),
 *   modelling a hung child for cancellation/timeout tests.
 *
 * Has no OS identity: [pid] reports [NO_PID].
 */
internal class ScriptedProcess(
    stdout: ByteArray,
    stderr: ByteArray,
    private val exitCode: Int,
    lineDelay: Duration = Duration.ZERO,
    private val pending: Boolean = false,
) : Process() {
    // Counted down when the handle is killed (watchdog / close); doubles as the
    // pacing-and-parking signal the scripted streams wait on.
    private val destroyGate = CountDownLatch(1)

    // Completes when the scripted child "exits": immediately (instant), after the
    // line-delay lifetime, or on kill (pending / a watchdog kill mid-stream).
    private val exit = CompletableFuture<Process>()

    private val paced = pending || lineDelay > Duration.ZERO
    private val startNanos = System.nanoTime()

    private val stdoutStream: InputStream = streamFor(stdout, lineDelay)
    private val stderrStream: InputStream = streamFor(stderr, lineDelay)
    private val stdinStream: OutputStream = OutputStream.nullOutputStream()

    // Schedule the natural exit for a line-paced run (after the longer stream
    // drains). Instant runs complete immediately; pending runs exit only on kill.
    private val lifetimeTask: ScheduledFuture<*>? =
        when {
            pending -> null
            lineDelay > Duration.ZERO -> {
                val lines = maxOf(lineCount(stdout), lineCount(stderr))
                // Nanos (not millis) so a sub-millisecond delay doesn't floor the
                // lifetime to 0 and "exit" before the nanosecond-paced streams drain.
                val lifetimeNanos = saturatingMul(lineDelay.inWholeNanoseconds, lines.toLong())
                scheduler.schedule({ exit.complete(this) }, lifetimeNanos, TimeUnit.NANOSECONDS)
            }
            else -> {
                exit.complete(this)
                null
            }
        }

    private fun streamFor(
        content: ByteArray,
        lineDelay: Duration,
    ): InputStream =
        if (paced) {
            ScriptedStream(
                content,
                lineDelay,
                pending,
                destroyGate,
                startNanos,
            )
        } else {
            ByteArrayInputStream(content)
        }

    override fun getOutputStream(): OutputStream = stdinStream

    override fun getInputStream(): InputStream = stdoutStream

    override fun getErrorStream(): InputStream = stderrStream

    override fun waitFor(): Int {
        exit.get()
        return exitCode
    }

    override fun exitValue(): Int = exitCode

    override fun isAlive(): Boolean = !exit.isDone

    override fun pid(): Long = NO_PID

    override fun onExit(): CompletableFuture<Process> = exit

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun destroy() {
        destroyForcibly()
    }

    override fun destroyForcibly(): Process {
        destroyGate.countDown() // wake any parked/pacing stream read so it EOFs
        lifetimeTask?.cancel(false)
        exit.complete(this)
        return this
    }

    /** A blocking, line-paced (or until-killed) stream — the non-instant backing. */
    private class ScriptedStream(
        content: ByteArray,
        lineDelay: Duration,
        private val pending: Boolean,
        private val gate: CountDownLatch,
        private val startNanos: Long,
    ) : InputStream() {
        private val chunks: List<ByteArray> = splitLines(content)
        private val delayNanos: Long = lineDelay.inWholeNanoseconds
        private var chunkIndex = 0
        private var chunkOffset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0 // InputStream contract: a zero-length read blocks for nothing
            if (pending) {
                gate.await() // park like a hung child's pipe until the handle is killed
                return -1
            }
            if (chunkIndex >= chunks.size) return -1
            if (chunkOffset == 0 && !awaitLineRelease(chunkIndex)) {
                return -1 // killed during pacing → truncate the stream, like a killed child
            }
            val chunk = chunks[chunkIndex]
            val n = minOf(len, chunk.size - chunkOffset)
            System.arraycopy(chunk, chunkOffset, b, off, n)
            chunkOffset += n
            if (chunkOffset == chunk.size) {
                chunkIndex++
                chunkOffset = 0
            }
            return n
        }

        // Wait until line [index] is due (startNanos + (index + 1) * delay), waking
        // early if the handle is killed. Returns false when killed (caller EOFs).
        private fun awaitLineRelease(index: Int): Boolean {
            if (delayNanos == 0L) return true
            val dueAt = startNanos + saturatingMul(delayNanos, (index + 1).toLong())
            val waitNanos = dueAt - System.nanoTime()
            if (waitNanos <= 0) return true
            // await returns true iff the gate fired (killed) before the deadline.
            return !gate.await(waitNanos, TimeUnit.NANOSECONDS)
        }
    }

    internal companion object {
        /** A scripted handle has no OS process id; [RunningProcess.pid] reports this. */
        internal const val NO_PID: Long = -1L

        // One shared daemon timer drives line-paced "exit" for every scripted handle.
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "processkit-scripted-lifetime").apply { isDaemon = true }
            }
        }

        private fun splitLines(content: ByteArray): List<ByteArray> {
            if (content.isEmpty()) return emptyList()
            val result = mutableListOf<ByteArray>()
            var start = 0
            for (i in content.indices) {
                if (content[i] == NEWLINE) {
                    result.add(content.copyOfRange(start, i + 1))
                    start = i + 1
                }
            }
            if (start < content.size) result.add(content.copyOfRange(start, content.size))
            return result
        }

        private fun lineCount(content: ByteArray): Int {
            if (content.isEmpty()) return 0
            var lines = content.count { it == NEWLINE }
            if (content.last() != NEWLINE) lines++ // a final line with no trailing newline
            return lines
        }

        private fun saturatingMul(
            a: Long,
            b: Long,
        ): Long =
            try {
                Math.multiplyExact(a, b)
            } catch (overflow: ArithmeticException) {
                Long.MAX_VALUE
            }
    }
}
