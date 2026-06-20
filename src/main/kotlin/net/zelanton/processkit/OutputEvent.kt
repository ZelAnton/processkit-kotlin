package net.zelanton.processkit

/**
 * One line from a merged [RunningProcess.outputEvents] stream, tagged with the
 * stream it came from. `when (event) { is Stdout -> …; is Stderr -> … }`.
 */
public sealed class OutputEvent {
    /** The decoded line (no trailing newline). */
    public abstract val line: String

    /** A line read from standard output. */
    public class Stdout internal constructor(
        override val line: String,
    ) : OutputEvent()

    /** A line read from standard error. */
    public class Stderr internal constructor(
        override val line: String,
    ) : OutputEvent()

    override fun toString(): String = "${this::class.simpleName}($line)"
}
