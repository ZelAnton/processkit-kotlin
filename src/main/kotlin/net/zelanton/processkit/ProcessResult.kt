package net.zelanton.processkit

/**
 * The full outcome of a finished run — the honest result type.
 *
 * A non-zero [exitCode] is *data*, not an error: the capturing verbs
 * ([Command.outputString] / [Command.outputBytes]) always return a
 * `ProcessResult`, and you decide what counts as success. A run that hit its
 * deadline is *captured* as [timedOut] (the tree was killed), not raised.
 *
 * This is a plain class (not a `data class`) so future fields can be added
 * without breaking source compatibility (no generated `copy`/`componentN`).
 */
public class ProcessResult<out T> internal constructor(
    /** The program that produced this result (used in error messages). */
    public val program: String,
    /** Captured standard output (`String` text or raw `ByteArray`). */
    public val stdout: T,
    /** Captured standard error, decoded as text with `\n` line endings. */
    public val stderr: String,
    /**
     * The process exit value. On Unix a signal-terminated process reports
     * `128 + signal` (the shell convention the JVM exposes).
     */
    public val exitCode: Int,
    /** `true` if the run hit its [Command.timeout] and the tree was killed. */
    public val timedOut: Boolean,
) {
    /** A clean run: exit code `0` and not timed out. */
    public val isSuccess: Boolean get() = !timedOut && exitCode == 0

    /**
     * Returns `this` if the run [isSuccess], otherwise throws —
     * [ProcessException.Timeout] when it timed out, [ProcessException.Exit] for a
     * non-zero exit.
     */
    public fun ensureSuccess(): ProcessResult<T> {
        if (timedOut) {
            throw ProcessException.Timeout(program, null)
        }
        if (exitCode != 0) {
            throw ProcessException.Exit(program, exitCode, stderr)
        }
        return this
    }

    override fun toString(): String = "ProcessResult(program=$program, exitCode=$exitCode, timedOut=$timedOut)"
}
