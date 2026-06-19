package net.zelanton.processkit

/**
 * The outcome of a streamed run, returned by [RunningProcess.finish].
 *
 * Mirrors the exit fields of [ProcessResult] (a non-zero [exitCode] is data, a
 * deadline is captured as [timedOut]); stdout is absent because it was streamed.
 */
public class Finished internal constructor(
    /**
     * The process exit value. On Unix a signal-terminated process reports
     * `128 + signal` (the JVM convention).
     */
    public val exitCode: Int,
    /** Standard error captured in the background, decoded with `\n` line endings. */
    public val stderr: String,
    /** `true` if the run hit its [Command.timeout] and the tree was killed. */
    public val timedOut: Boolean,
) {
    /** A clean run: exit code `0` and not timed out. */
    public val isSuccess: Boolean get() = !timedOut && exitCode == 0

    override fun toString(): String = "Finished(exitCode=$exitCode, timedOut=$timedOut)"
}
