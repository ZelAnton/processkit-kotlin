package net.zelanton.processkit

/**
 * A portable signal to broadcast to a whole process tree via
 * [ProcessGroup.signal].
 *
 * The curated cases map to the POSIX signal of the same name on Unix. On Windows
 * only [Kill] is deliverable (it maps to the Job Object terminate, the same hard
 * kill as [ProcessGroup.close]); every other case yields
 * [ProcessException.Unsupported].
 *
 * [Other] is an escape hatch carrying a raw signal number on Unix (e.g. a
 * platform-specific signal); it is always unsupported on Windows.
 *
 * `SIGSTOP`/`SIGCONT` are deliberately absent from the curated set — pause and
 * resume the whole tree with [ProcessGroup.suspend] / [ProcessGroup.resume]
 * instead; `Signal.Other(19)` (Linux `SIGSTOP`) remains available when the raw
 * signal is specifically wanted on Unix.
 *
 * The Unix numbers are the common Linux values (x86-64 / arm64), matching this
 * library's current Linux backend.
 */
public sealed class Signal(
    internal val rawUnix: kotlin.Int,
) {
    /** `SIGTERM` — polite request to exit. */
    public data object Term : Signal(15)

    /** `SIGKILL` — unblockable kill. On Windows: terminate the Job Object. */
    public data object Kill : Signal(9)

    /** `SIGINT` — keyboard interrupt. */
    public data object Int : Signal(2)

    /** `SIGHUP` — hangup; conventionally "reload configuration". */
    public data object Hup : Signal(1)

    /** `SIGQUIT` — quit, typically with a core dump. */
    public data object Quit : Signal(3)

    /** `SIGUSR1` — user-defined. */
    public data object Usr1 : Signal(10)

    /** `SIGUSR2` — user-defined. */
    public data object Usr2 : Signal(12)

    /**
     * A raw signal number, passed through verbatim (Unix only). It lands in the
     * *signal* argument of `kill(pid, sig)`, so an out-of-range value simply fails
     * the send; it cannot retarget the signal.
     */
    public data class Other(
        val number: kotlin.Int,
    ) : Signal(number)

    /** Whether this signal is delivered as the whole-tree hard kill (SIGKILL). */
    internal val deliversAsKill: Boolean get() = rawUnix == Kill.rawUnix
}
