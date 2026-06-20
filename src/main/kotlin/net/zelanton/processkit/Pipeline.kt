package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * A shell-free pipeline: each stage's stdout is connected to the next stage's
 * stdin in-process (a relay, not a shell string — so no quoting or injection
 * surface). Every stage lives in one shared kill-on-close container.
 *
 * ```
 * val authors = Command("git", "log", "--format=%an")
 *     .pipe(Command("sort"))
 *     .pipe(Command("uniq").arg("-c"))
 *     .run()
 * ```
 *
 * The outcome is **pipefail**: stdout is the last stage's output, while the exit
 * code, stderr, and reported program come from the first stage that exited
 * non-zero (or the last stage when all succeed). The first stage's
 * [Command.stdin] source is honored; inner stages read from the pipe.
 *
 * Note: a stage that legitimately stops reading early (the `producer | head`
 * shape) can make the producer's broken-pipe death win pipefail; an
 * `uncheckedInPipe` opt-out for that case is planned.
 */
public class Pipeline internal constructor(
    first: Command,
    second: Command,
) {
    private val stages: MutableList<Command> = mutableListOf(first, second)
    private var timeoutOrNull: Duration? = null

    /** Append another stage; its stdin comes from the previous stage's stdout. */
    public fun pipe(next: Command): Pipeline = apply { stages.add(next) }

    /** Bound the whole chain: at the deadline every stage is killed. */
    public fun timeout(duration: Duration): Pipeline = apply { timeoutOrNull = duration }

    /** Require every stage to succeed and return the trimmed final stdout. */
    public suspend fun run(): String = outputString().ensureSuccess().stdout.trimEnd()

    /** Capture the pipefail result with the final stdout as text. */
    public suspend fun outputString(): ProcessResult<String> {
        val result = runPipeline()
        return ProcessResult(
            program = result.program,
            stdout = result.stdout.decodeToString().normalizeNewlines(),
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
        )
    }

    /** Capture the pipefail result with the final stdout as raw bytes. */
    public suspend fun outputBytes(): ProcessResult<ByteArray> = runPipeline()

    /** The pipefail exit code; a timed-out chain throws instead of inventing one. */
    public suspend fun exitCode(): Int {
        val result = runPipeline()
        if (result.timedOut) {
            throw ProcessException.Timeout(result.program, timeoutOrNull)
        }
        return result.exitCode
    }

    private suspend fun runPipeline(): ProcessResult<ByteArray> {
        val containment = newContainment(stages.first().program)
        try {
            return withContext(Dispatchers.IO) {
                val processes = stages.map { containment.spawnChecked(it) }
                coroutineScope {
                    try {
                        // First stage's stdin source; inner stages are fed by relays.
                        applyStdin(this, processes.first(), stages.first().stdinSource)
                        for (i in 0 until processes.size - 1) {
                            val upstream = processes[i].inputStream
                            val downstream = processes[i + 1].outputStream
                            launch {
                                runCatching { upstream.use { input -> downstream.use { input.copyTo(it) } } }
                            }
                        }
                        val lastStdout = async { processes.last().inputStream.readBytes() }
                        val stderrCaptures = processes.map { p -> async { p.errorStream.readBytes() } }
                        val timedOut = awaitAllExit(processes, timeoutOrNull) { containment.killAll() }
                        val failing =
                            processes
                                .indexOfFirst { it.exitValue() != 0 }
                                .let { if (it < 0) processes.lastIndex else it }
                        ProcessResult(
                            program = stages[failing].program,
                            stdout = lastStdout.await(),
                            stderr = stderrCaptures[failing].await().decodeToString().normalizeNewlines(),
                            exitCode = processes[failing].exitValue(),
                            timedOut = timedOut,
                        )
                    } catch (failure: Throwable) {
                        // Kill the whole chain so the relays/captures unblock.
                        containment.killAll()
                        throw failure
                    }
                }
            }
        } finally {
            containment.close()
        }
    }

    private suspend fun awaitAllExit(
        processes: List<Process>,
        timeout: Duration?,
        onTimeout: () -> Unit,
    ): Boolean {
        suspend fun awaitEach() = processes.forEach { it.onExit().await() }
        if (timeout == null) {
            awaitEach()
            return false
        }
        if (withTimeoutOrNull(timeout) { awaitEach() } != null) {
            return false
        }
        onTimeout()
        awaitEach()
        return true
    }
}
