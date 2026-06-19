package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Containment
import java.io.IOException
import kotlin.time.Duration

/**
 * The real [ProcessRunner]: spawns each run into its own private kill-on-close
 * container (Windows Job Object / Linux cgroup or process group), captures
 * stdout and stderr concurrently (so a full pipe can't deadlock), and tears the
 * whole tree down on completion, timeout, or cancellation.
 *
 * Cancellation is structured-concurrency-native: cancelling the awaiting
 * coroutine kills the tree (in the `finally`) and propagates
 * [kotlinx.coroutines.CancellationException].
 */
public object JobRunner : ProcessRunner {
    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val containment =
            try {
                Containment.create()
            } catch (cause: IOException) {
                throw ProcessException.Spawn(command.program, cause)
            }
        try {
            return withContext(Dispatchers.IO) {
                val process = spawn(containment, command)
                try {
                    // Drain both pipes concurrently so a full pipe can't deadlock.
                    val stdout = async { process.inputStream.readBytes() }
                    val stderr = async { process.errorStream.readBytes() }
                    val timedOut = awaitExit(process, command.timeoutOrNull) { containment.killAll() }
                    ProcessResult(
                        program = command.program,
                        stdout = stdout.await(),
                        stderr = stderr.await().decodeToString().normalizeNewlines(),
                        exitCode = process.exitValue(),
                        timedOut = timedOut,
                    )
                } catch (failure: Throwable) {
                    // Cancellation (or any failure): kill the tree so the blocking pipe
                    // reads unblock. Process-pipe reads ignore thread interruption, so
                    // closing the streams by killing the process is what frees them —
                    // otherwise awaiting the readers here would hang.
                    containment.killAll()
                    throw failure
                }
            }
        } finally {
            // Kill-on-close: reaps the tree on success, timeout, and cancellation.
            containment.close()
        }
    }

    private fun spawn(
        containment: Containment,
        command: Command,
    ): Process =
        try {
            containment.spawn(
                command = command.commandLine,
                workingDir = command.workingDirectory,
                environment = command.environmentOverrides,
                clearEnvironment = command.environmentCleared,
            )
        } catch (cause: IOException) {
            if (cause.isProgramNotFound()) {
                throw ProcessException.NotFound(command.program, cause)
            }
            throw ProcessException.Spawn(command.program, cause)
        }

    /**
     * Await the process exit. With a [timeout], kill the tree on expiry (via
     * [onTimeout]) and wait for the kill to take effect; returns whether the run
     * timed out.
     */
    private suspend fun awaitExit(
        process: Process,
        timeout: Duration?,
        onTimeout: () -> Unit,
    ): Boolean {
        if (timeout == null) {
            process.onExit().await()
            return false
        }
        val finished = withTimeoutOrNull(timeout) { process.onExit().await() }
        if (finished != null) {
            return false
        }
        onTimeout()
        process.onExit().await()
        return true
    }
}

/** Heuristic: did this spawn failure mean the program was not found? */
private fun IOException.isProgramNotFound(): Boolean {
    val text = (message ?: "").lowercase()
    return "no such file" in text ||
        "cannot find the file" in text ||
        "cannot run program" in text ||
        "error=2," in text
}
