package net.zelanton.processkit

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
import net.zelanton.processkit.internal.Containment
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

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
 */
public class RunningProcess internal constructor(
    private val process: Process,
    private val container: Containment,
    private val ownsContainer: Boolean,
    timeout: Duration?,
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stderrCapture: Deferred<ByteArray> = scope.async { process.errorStream.readBytes() }
    private val stdoutConsumed = AtomicBoolean(false)

    @Volatile
    private var timedOut = false

    init {
        if (timeout != null) {
            scope.launch {
                delay(timeout)
                timedOut = true
                killTree()
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
     * If stdout was never streamed it is drained here so the child can't block on
     * a full pipe.
     */
    public suspend fun finish(): Finished {
        try {
            if (stdoutConsumed.compareAndSet(false, true)) {
                scope.launch { process.inputStream.readBytes() }
            }
            process.onExit().await()
            val stderr = stderrCapture.await().decodeToString().normalizeNewlines()
            return Finished(process.exitValue(), stderr, timedOut)
        } catch (failure: Throwable) {
            killTree()
            throw failure
        } finally {
            scope.cancel()
        }
    }

    /** Hard-kill the child's tree and release resources. */
    override fun close() {
        try {
            killTree()
        } finally {
            scope.cancel()
            if (ownsContainer) {
                container.close()
            }
        }
    }

    private fun killTree() {
        if (ownsContainer) {
            container.killAll()
        } else {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
