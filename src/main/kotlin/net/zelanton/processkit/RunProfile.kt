package net.zelanton.processkit

import kotlin.time.Duration

/**
 * A resource-usage summary of one run, produced by [RunningProcess.profile].
 *
 * [cpuTime] and [peakMemoryBytes] are sampled from the started child *process*
 * (the same source as [RunningProcess.cpuTime] / [RunningProcess.peakMemoryBytes]),
 * so they are `null` where per-process metrics are unavailable (macOS, the
 * process-group backend) or when the run exited before the first sample landed.
 *
 * A read-only summary the library produces (constructed internally); new fields
 * may be added in a later release without a breaking change.
 */
public class RunProfile internal constructor(
    /** The exit code, or `null` for a run killed by its [Command.timeout]. */
    public val exitCode: Int?,
    /** Wall-clock duration from start to exit (and output drained). */
    public val duration: Duration,
    /** Cumulative CPU time (user + kernel) at the last successful sample, if reported. */
    public val cpuTime: Duration?,
    /** Peak resident memory observed across the samples in bytes, if reported. */
    public val peakMemoryBytes: Long?,
    /** How many sampling ticks ran (including ones that found no data). */
    public val samples: Int,
) {
    /**
     * Average CPU utilisation over the run, in cores (`0.5` = half a core busy on
     * average; can exceed `1.0` for a multi-threaded child). `null` when CPU time
     * was never observed or the run had no measurable duration.
     */
    public fun avgCpu(): Double? {
        val cpu = cpuTime ?: return null
        if (duration == Duration.ZERO) return null
        return cpu.inWholeNanoseconds.toDouble() / duration.inWholeNanoseconds.toDouble()
    }

    override fun toString(): String =
        "RunProfile(exitCode=$exitCode, duration=$duration, cpuTime=$cpuTime, " +
            "peakMemoryBytes=$peakMemoryBytes, samples=$samples)"
}
