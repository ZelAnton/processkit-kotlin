package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Containment
import java.io.IOException
import kotlin.time.Duration

/** Create a fresh private containment, mapping a creation failure to a typed error. */
internal fun newContainment(program: String): Containment =
    try {
        Containment.create()
    } catch (cause: IOException) {
        throw ProcessException.Spawn(program, cause)
    }

/** Spawn [command] into this containment, mapping spawn failures to typed errors. */
internal fun Containment.spawnChecked(command: Command): Process =
    try {
        spawn(
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
 * Drain stdout/stderr concurrently (so a full pipe can't deadlock) and wait for
 * exit, honoring [timeout]. On a timeout — or any failure, including
 * cancellation — [killRun] is invoked so the blocking pipe reads unblock (process
 * pipes ignore thread interruption; closing the streams by killing the process is
 * what frees them). Returns whether the run timed out.
 */
internal suspend fun captureRun(
    process: Process,
    program: String,
    timeout: Duration?,
    killRun: () -> Unit,
): ProcessResult<ByteArray> =
    withContext(Dispatchers.IO) {
        try {
            val stdout = async { process.inputStream.readBytes() }
            val stderr = async { process.errorStream.readBytes() }
            val timedOut = awaitProcessExit(process, timeout, killRun)
            ProcessResult(
                program = program,
                stdout = stdout.await(),
                stderr = stderr.await().decodeToString().normalizeNewlines(),
                exitCode = process.exitValue(),
                timedOut = timedOut,
            )
        } catch (failure: Throwable) {
            killRun()
            throw failure
        }
    }

private suspend fun awaitProcessExit(
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

private fun IOException.isProgramNotFound(): Boolean {
    val text = (message ?: "").lowercase()
    return "no such file" in text ||
        "cannot find the file" in text ||
        "cannot run program" in text ||
        "error=2," in text
}
