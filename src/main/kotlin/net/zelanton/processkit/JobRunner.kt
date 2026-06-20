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
            return captureRun(
                process,
                command.program,
                command.timeoutOrNull,
                command.stdinSource,
            ) { containment.killAll() }
        } finally {
            // Kill-on-close: reaps the tree on success, timeout, and cancellation.
            containment.close()
        }
    }
}
