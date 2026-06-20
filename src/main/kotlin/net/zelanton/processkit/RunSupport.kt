package net.zelanton.processkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Containment
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration

/**
 * Create a fresh private containment, mapping a creation failure to a typed error.
 * An unenforceable [limits] surfaces as [ProcessException.ResourceLimit] (thrown by
 * the backend, propagated unchanged).
 */
internal fun newContainment(
    program: String,
    limits: ResourceLimits = ResourceLimits(),
): Containment =
    try {
        Containment.create(limits)
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
    command: Command,
    killRun: () -> Unit,
): ProcessResult<ByteArray> =
    withContext(Dispatchers.IO) {
        try {
            applyStdin(this, process, command.stdinSource)
            val stdoutSink = lineSinkFor(command.stdoutLineHandler, command.stdoutTeeSink)
            val stderrSink = lineSinkFor(command.stderrLineHandler, command.stderrTeeSink)
            val stdout = async { pumpStream(process.inputStream, command.stdoutCharset, stdoutSink) }
            val stderr = async { pumpStream(process.errorStream, command.stderrCharset, stderrSink) }
            val timedOut = awaitProcessExit(process, command.timeoutOrNull, killRun)
            ProcessResult(
                program = command.program,
                stdout = stdout.await(),
                stderr = String(stderr.await(), command.stderrCharset).normalizeNewlines(),
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

/**
 * Apply the [stdin] source: close the pipe (EOF), or write bytes / a file to it
 * on a background coroutine. A broken pipe (a child that stops reading) is
 * tolerated.
 */
internal fun applyStdin(
    scope: CoroutineScope,
    process: Process,
    stdin: Stdin,
) {
    when (stdin) {
        Stdin.None -> runCatching { process.outputStream.close() }
        is Stdin.Bytes ->
            scope.launch {
                runCatching { process.outputStream.use { it.write(stdin.data) } }
            }
        is Stdin.FromFile ->
            scope.launch {
                runCatching {
                    process.outputStream.use { out -> Files.newInputStream(stdin.path).use { it.copyTo(out) } }
                }
            }
    }
}

private fun IOException.isProgramNotFound(): Boolean {
    val text = (message ?: "").lowercase()
    return "no such file" in text ||
        "cannot find the file" in text ||
        "cannot run program" in text ||
        "error=2," in text
}
