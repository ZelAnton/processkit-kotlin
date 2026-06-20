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
import java.util.concurrent.atomic.AtomicBoolean
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
 * stderr is drained in the background from the moment the process starts (so it
 * never blocks), and a [Command.timeout] is enforced by a watchdog that bounds
 * the stream. Always [close] the handle (use `use { }`): on a dropped or
 * cancelled run that is what reaps the tree.
 *
 * **stdout is single-shot.** Exactly one of [stdoutLines] or [waitForLine] may
 * consume it, and [finish] drains whatever is left; a second consumer throws
 * `IllegalStateException`. [waitFor] does *not* consume stdout — if the child is
 * chatty, pair it with one of the consumers (or [finish]) so it cannot block on a
 * full stdout pipe.
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
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stderrCapture: Deferred<ByteArray> = scope.async { process.errorStream.readBytes() }
    private val stdoutConsumed = AtomicBoolean(false)
    private val finishStarted = AtomicBoolean(false)
    private val finishResult = CompletableDeferred<Finished>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var timedOut = false

    init {
        applyStdin(scope, process, stdin)
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

    /** The OS process id of the started child. */
    public val pid: Long get() = process.pid()

    /** Whether the child is still running. */
    public val isAlive: Boolean get() = process.isAlive

    /**
     * Stream stdout line by line as it arrives. The returned [Flow] is cold and
     * single-shot — collect it once. Lines are decoded as UTF-8.
     */
    public fun stdoutLines(): Flow<String> =
        flow {
            check(stdoutConsumed.compareAndSet(false, true)) { "stdout has already been consumed" }
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    emit(line)
                    line = reader.readLine()
                }
            }
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
                val drain =
                    if (stdoutConsumed.compareAndSet(false, true)) {
                        scope.async { runCatching { process.inputStream.readBytes() } }
                    } else {
                        null
                    }
                process.onExit().await()
                drain?.await()
                val stderr = stderrCapture.await().decodeToString().normalizeNewlines()
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
        process.onExit().await()
        return process.exitValue()
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
        val matched = CompletableDeferred<String?>()
        // A background reader drains stdout for the child's whole life: it reports
        // the first matching line, then keeps reading so a chatty child can't block
        // on a full pipe. A timed-out wait does not stop it (the child stays alive);
        // close()/killTree closes the stream, which unblocks the read.
        scope.launch {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
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
