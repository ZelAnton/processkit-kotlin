package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * An interactive writer for a running child's standard input — handed out once by
 * [RunningProcess.takeStdin] when the command was built with
 * [Command.keepStdinOpen]. Feed the child incrementally, then [close] it (EOF) so a
 * stdin-reading child can finish.
 *
 * ```
 * // `sort` buffers all input until EOF, so write-then-read is safe here.
 * Command("sort").keepStdinOpen().start().use { run ->
 *     run.takeStdin()!!.use { stdin ->
 *         stdin.writeLine("banana"); stdin.writeLine("apple")
 *     } // close() sends EOF
 *     run.stdoutLines().collect(::println)
 * }
 * ```
 *
 * For an **interactive** child (one that interleaves output with input), collect
 * [RunningProcess.stdoutLines] **concurrently** with writing — e.g. `launch` the
 * collector before you write. Writing everything and only then reading deadlocks a
 * child that emits before EOF or produces more than a pipe buffer.
 *
 * Writes block (on [Dispatchers.IO]) when the child isn't reading and its stdin
 * pipe fills — apply normal backpressure rather than buffering unbounded. A write
 * after the child has exited (or the handle was closed/killed) throws `IOException`.
 */
public class ProcessStdin internal constructor(
    private val stream: OutputStream,
) : AutoCloseable {
    /**
     * Write raw [bytes] (not flushed — pair with [flush], or use [writeLine]).
     * Don't mutate [bytes] until the call returns (it is written, not copied).
     */
    public suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) { stream.write(bytes) }

    /** Write [line] followed by `\n` (UTF-8) and flush it — for line-oriented input. */
    public suspend fun writeLine(line: String): Unit =
        withContext(Dispatchers.IO) {
            stream.write((line + "\n").toByteArray())
            stream.flush()
        }

    /** Flush buffered writes to the child. */
    public suspend fun flush(): Unit = withContext(Dispatchers.IO) { stream.flush() }

    /** Close stdin (the child sees EOF). Idempotent / best-effort. */
    override fun close() {
        runCatching { stream.close() }
    }
}
