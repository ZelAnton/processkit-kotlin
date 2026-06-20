package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real [ProcessRunner]: spawns each run into its **own private**
 * kill-on-close container (Windows Job Object / Linux process group), captures
 * stdout and stderr concurrently, and tears the whole tree down on completion,
 * timeout, or cancellation.
 *
 * For several children that should share one container — and be reaped as a unit —
 * use a [ProcessGroup] instead.
 *
 * Stateless and safe to share: every call allocates its own container, so the
 * single instance can be used from any number of coroutines at once.
 */
public object JobRunner : ProcessRunner {
    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val containment = newContainment(command.program)
        try {
            val process = withContext(Dispatchers.IO) { containment.spawnChecked(command) }
            log.debug("run: started `{}` ({})", command.program, containment.mechanism)
            val result = captureRun(process, command) { containment.killAll() }
            log.debug("run: `{}` finished (exit={}, timedOut={})", command.program, result.exitCode, result.timedOut)
            return result
        } finally {
            // Kill-on-close: reaps the tree on success, timeout, and cancellation.
            containment.close()
        }
    }

    override suspend fun start(command: Command): RunningProcess {
        val containment = newContainment(command.program)
        val process =
            try {
                withContext(Dispatchers.IO) { containment.spawnChecked(command) }
            } catch (failure: Throwable) {
                containment.close()
                throw failure
            }
        return RunningProcess(
            process,
            command.program,
            containment,
            ownsContainer = true,
            command.timeoutOrNull,
            command.stdinSource,
            command.stdoutCharset,
            command.stderrCharset,
        )
    }
}
