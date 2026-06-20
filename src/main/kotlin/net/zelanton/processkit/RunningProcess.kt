package net.zelanton.processkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Containment
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A live handle on a started child — stream its output, then collect the outcome.
 *
 * ```
 * Command("git", "log", "--oneline").start().use { run ->
 *     run.stdoutLines().collect { println("commit: $it") }
 *     val finished = run.finish()
 * }
 * ```
 *
 * stderr is drained in the background by the first awaiting/streaming verb
 * ([stdoutLines] / [waitForLine] / [waitFor] / [waitForPort] / [waitUntil] /
 * [finish]), or carried by [outputEvents]; a
 * [Command.timeout] is enforced by a watchdog that bounds the stream. Always
 * [close] the handle (use `use { }`): on a dropped or cancelled run that is what
 * reaps the tree.
 *
 * **stdout is single-shot, and so is the run's output.** Exactly one of
 * [stdoutLines], [waitForLine], or [outputEvents] may consume stdout, and [finish]
 * drains whatever is left; a second consumer throws `IllegalStateException`. Use a
 * single consumer sequentially — don't, say, [finish] while another coroutine is
 * still collecting. [waitFor] does *not* consume stdout — if the child is chatty,
 * pair it with one of the consumers (or [finish]) so it cannot block on a full
 * stdout pipe.
 */
public class RunningProcess internal constructor(
    private val process: Process,
    private val program: String,
    // Null for a scripted handle (which owns no kernel container); always present
    // when [ownsContainer] is true.
    private val container: Containment?,
    private val ownsContainer: Boolean,
    timeout: Duration?,
    stdin: Stdin,
    private val stdoutCharset: Charset = Charsets.UTF_8,
    private val stderrCharset: Charset = Charsets.UTF_8,
    private val keepStdinOpen: Boolean = false,
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stdoutConsumed = AtomicBoolean(false)

    // stderr has exactly one consumer, decided by a single atomic: the background
    // byte drain (started lazily by the first awaiting/streaming verb) OR
    // outputEvents reading it line by line. Whichever claims it first wins; the
    // other path then skips stderr, so the stream is never read twice.
    private val stderrConsumer = AtomicReference(StderrConsumer.UNCLAIMED)
    private val stderrBytes: Deferred<ByteArray> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        scope.async { process.errorStream.readBytes() }
    }
    private val finishStarted = AtomicBoolean(false)
    private val finishResult = CompletableDeferred<Finished>()
    private val closed = AtomicBoolean(false)

    // Claimed when the writer is taken OR when finish/waitFor auto-closes an
    // untaken stdin — one CAS makes those two outcomes mutually exclusive.
    private val stdinClaimed = AtomicBoolean(false)

    @Volatile
    private var timedOut = false

    init {
        // With keepStdinOpen the caller owns stdin (via takeStdin), so leave the
        // pipe open and don't apply any stdin source.
        if (!keepStdinOpen) {
            applyStdin(scope, process, stdin)
        }
        if (timeout != null) {
            scope.launch {
                delay(timeout)
                if (process.isAlive) {
                    timedOut = true
                    killTree()
                }
            }
        }
    }

    /** The OS process id of the started child; `-1` for a scripted handle (no OS process). */
    public val pid: Long get() = process.pid()

    /** Whether the child is still running. */
    public val isAlive: Boolean get() = process.isAlive

    /**
     * Take the interactive stdin writer — non-null only if the command was built
     * with [Command.keepStdinOpen], and only on the first call (`null` afterwards,
     * or once [finish]/[waitFor] has auto-closed an untaken stdin). You own the
     * returned writer: [close][ProcessStdin.close] it (EOF) when done so a
     * stdin-reading child exits.
     *
     * **Consume stdout concurrently with writing.** Launch a [stdoutLines]
     * collector before/while you write — a child that interleaves output with
     * input (a REPL, `cat`, …) or emits more than a pipe buffer will otherwise
     * deadlock (it blocks writing stdout while you block writing its stdin).
     * Writing everything then reading is safe only for a child that buffers all
     * input until EOF, like `sort`.
     */
    public fun takeStdin(): ProcessStdin? {
        if (!keepStdinOpen) return null
        if (!stdinClaimed.compareAndSet(false, true)) return null
        return ProcessStdin(process.outputStream)
    }

    /**
     * Stream stdout line by line as it arrives. The returned [Flow] is cold and
     * single-shot — collect it once. Lines are decoded with the command's
     * [Command.stdoutEncoding] (UTF-8 by default).
     */
    public fun stdoutLines(): Flow<String> =
        flow {
            check(stdoutConsumed.compareAndSet(false, true)) { "stdout has already been consumed" }
            startStderrDrain()
            process.inputStream.bufferedReader(stdoutCharset).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    emit(line)
                    line = reader.readLine()
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Stream stdout and stderr together as a single, cold, single-shot [Flow] of
     * [OutputEvent]s, each line tagged with its source — the way to observe both
     * streams interleaved in arrival order. Lines are decoded with the command's
     * encodings. Consumes both streams (it is mutually exclusive with [stdoutLines],
     * and afterwards [finish] reports an empty stderr — the events carried it).
     *
     * Collect inside `use { }`. Cancelling the collector early (e.g. `take(n)`, a
     * `withTimeout`, or a throwing collector) cancels the reader coroutines, but a
     * blocking pipe read does not unblock until the child writes more, exits, or the
     * handle is [close]d — so always [close] the handle to reap a partially-consumed
     * stream. Like [stdoutLines], a single consumer is assumed: don't [finish] (or
     * collect again) while another coroutine is still collecting.
     */
    public fun outputEvents(): Flow<OutputEvent> =
        channelFlow {
            // Claim stderr first: if the byte drain already started (e.g. after a
            // waitFor/readiness probe), fail loud rather than reading the stream
            // twice. Every stdout consumer also claims stderr, so a lost stderr race
            // here always trips before the stdout claim — stdout stays free.
            check(stderrConsumer.compareAndSet(StderrConsumer.UNCLAIMED, StderrConsumer.EVENTS)) {
                "stderr has already been consumed"
            }
            check(stdoutConsumed.compareAndSet(false, true)) { "stdout has already been consumed" }
            val stdoutReader =
                launch {
                    process.inputStream.bufferedReader(stdoutCharset).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            send(OutputEvent.Stdout(line))
                            line = reader.readLine()
                        }
                    }
                }
            val stderrReader =
                launch {
                    process.errorStream.bufferedReader(stderrCharset).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            send(OutputEvent.Stderr(line))
                            line = reader.readLine()
                        }
                    }
                }
            stdoutReader.join()
            stderrReader.join()
        }.flowOn(Dispatchers.IO)

    /**
     * Wait for the child to exit and return its outcome plus the captured stderr.
     * If stdout was never consumed it is drained here so the child can't block on
     * a full pipe. Idempotent: every call returns the same [Finished] (or rethrows
     * the same failure).
     */
    public suspend fun finish(): Finished {
        if (finishStarted.compareAndSet(false, true)) {
            try {
                closeUntakenStdin()
                startStderrDrain()
                val drain =
                    if (stdoutConsumed.compareAndSet(false, true)) {
                        scope.async { runCatching { process.inputStream.readBytes() } }
                    } else {
                        null
                    }
                process.onExit().await()
                drain?.await()
                // outputEvents already delivered stderr; otherwise drain the bytes.
                val stderr =
                    if (stderrConsumer.get() == StderrConsumer.EVENTS) {
                        ""
                    } else {
                        String(stderrBytes.await(), stderrCharset).normalizeNewlines()
                    }
                finishResult.complete(Finished(process.exitValue(), stderr, timedOut))
            } catch (failure: Throwable) {
                killTree()
                finishResult.completeExceptionally(failure)
                throw failure
            } finally {
                scope.cancel()
            }
        }
        return finishResult.await()
    }

    /**
     * Wait for the child to exit and return its exit code, without consuming
     * output or releasing the handle. Use [finish] to also collect stderr, or
     * [close] to release resources.
     *
     * This does not drain stdout, so a child that writes more than a pipe buffer
     * of stdout and is never consumed will block before exiting and this call will
     * never return — consume stdout ([stdoutLines]/[waitForLine]) or use [finish].
     */
    public suspend fun waitFor(): Int {
        closeUntakenStdin()
        startStderrDrain()
        process.onExit().await()
        return process.exitValue()
    }

    // A kept-open stdin that was never taken must be closed before waiting for exit,
    // or a stdin-reading child blocks forever (no input, no EOF). The CAS claims
    // stdin so this can't race a concurrent takeStdin (whichever wins decides).
    private fun closeUntakenStdin() {
        if (keepStdinOpen && stdinClaimed.compareAndSet(false, true)) {
            runCatching { process.outputStream.close() }
        }
    }

    // Start the background stderr byte-drain (so a chatty stderr can't block the
    // child) unless outputEvents already claimed stderr. The CAS makes the first
    // caller the byte-drain owner and starts it exactly once; later callers (and the
    // outputEvents path) see the claim and skip, so the stream is never read twice.
    private fun startStderrDrain() {
        if (stderrConsumer.compareAndSet(StderrConsumer.UNCLAIMED, StderrConsumer.BYTES)) {
            stderrBytes.start()
        }
    }

    /**
     * Wait until a stdout line matches [predicate] and return it. Consumes stdout
     * (it counts as the single-shot stdout consumer); stdout keeps draining in the
     * background after the match so the child never blocks on a full pipe, and
     * [finish] still collects the exit code and stderr afterwards. Throws
     * [ProcessException.NotReady] if the deadline passes or stdout closes without a
     * match; does not kill the child (a timed-out probe leaves it running and the
     * stream draining — [close] the handle to stop).
     */
    public suspend fun waitForLine(
        timeout: Duration,
        predicate: (String) -> Boolean,
    ): String {
        check(stdoutConsumed.compareAndSet(false, true)) { "stdout has already been consumed" }
        startStderrDrain()
        val matched = CompletableDeferred<String?>()
        // A background reader drains stdout for the child's whole life: it reports
        // the first matching line, then keeps reading so a chatty child can't block
        // on a full pipe. A timed-out wait does not stop it (the child stays alive);
        // close()/killTree closes the stream, which unblocks the read.
        scope.launch {
            runCatching {
                process.inputStream.bufferedReader(stdoutCharset).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (!matched.isCompleted && predicate(line)) {
                            matched.complete(line)
                        }
                        line = reader.readLine()
                    }
                }
            }
            matched.complete(null) // stdout closed without a (further) match
        }
        return withTimeoutOrNull(timeout) { matched.await() }
            ?: throw ProcessException.NotReady(program, timeout)
    }

    /**
     * Wait until a TCP connection to [host]:[port] is accepted. Throws
     * [ProcessException.NotReady] on the deadline; does not kill the child.
     */
    public suspend fun waitForPort(
        host: String,
        port: Int,
        timeout: Duration,
    ): Unit = waitForPort(InetSocketAddress(host, port), timeout)

    /**
     * Wait until [address] accepts a TCP connection. Throws
     * [ProcessException.NotReady] on the deadline; does not kill the child.
     */
    public suspend fun waitForPort(
        address: InetSocketAddress,
        timeout: Duration,
    ) {
        startStderrDrain() // a chatty server's stderr must not fill the pipe while we poll
        val ready =
            withTimeoutOrNull(timeout) {
                while (!canConnect(address)) {
                    delay(PROBE_POLL_INTERVAL)
                }
                true
            }
        if (ready == null) {
            throw ProcessException.NotReady(program, timeout)
        }
    }

    /**
     * Wait until [check] returns `true`. Throws [ProcessException.NotReady] on the
     * deadline; does not kill the child.
     */
    public suspend fun waitUntil(
        timeout: Duration,
        check: suspend () -> Boolean,
    ) {
        startStderrDrain() // a chatty child's stderr must not fill the pipe while we poll
        val ready =
            withTimeoutOrNull(timeout) {
                while (!check()) {
                    delay(PROBE_POLL_INTERVAL)
                }
                true
            }
        if (ready == null) {
            throw ProcessException.NotReady(program, timeout)
        }
    }

    private suspend fun canConnect(address: InetSocketAddress): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(address, CONNECT_TIMEOUT.inWholeMilliseconds.toInt()) }
                true
            } catch (failure: IOException) {
                false
            }
        }

    /** Hard-kill the child's tree and release resources. Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            killTree()
        } finally {
            scope.cancel()
            if (ownsContainer) {
                container?.close()
            }
        }
    }

    private fun killTree() {
        if (ownsContainer) {
            container?.killAll()
        } else {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private companion object {
        private val PROBE_POLL_INTERVAL = 50.milliseconds
        private val CONNECT_TIMEOUT = 250.milliseconds
    }
}

/** Which path owns stderr: nobody yet, the background byte drain, or [RunningProcess.outputEvents]. */
private enum class StderrConsumer { UNCLAIMED, BYTES, EVENTS }
