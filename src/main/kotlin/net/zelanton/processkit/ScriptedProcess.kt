package net.zelanton.processkit

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/**
 * A fake [Process] serving canned stdout/stderr and a fixed exit code — no OS
 * child. It backs [ScriptedRunner.start] so a scripted run streams through the
 * exact same [RunningProcess] machinery (`stdoutLines` / `waitForLine` / `finish`)
 * as a real child.
 *
 * The canned output is available immediately (the "process" has effectively
 * already exited), so this models a **finite** stream, not timing-paced or
 * never-exiting output. Has no OS identity: [pid] reports [NO_PID].
 */
internal class ScriptedProcess(
    stdout: ByteArray,
    stderr: ByteArray,
    private val exitCode: Int,
) : Process() {
    private val stdoutStream: InputStream = ByteArrayInputStream(stdout)
    private val stderrStream: InputStream = ByteArrayInputStream(stderr)
    private val stdinStream: OutputStream = OutputStream.nullOutputStream()

    override fun getOutputStream(): OutputStream = stdinStream

    override fun getInputStream(): InputStream = stdoutStream

    override fun getErrorStream(): InputStream = stderrStream

    override fun waitFor(): Int = exitCode

    override fun exitValue(): Int = exitCode

    override fun isAlive(): Boolean = false

    override fun pid(): Long = NO_PID

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    internal companion object {
        /** A scripted handle has no OS process id; [RunningProcess.pid] reports this. */
        internal const val NO_PID: Long = -1L
    }
}
