package net.zelanton.processkit

import kotlin.time.Duration

/**
 * A point-in-time snapshot of a [ProcessGroup]'s resource usage.
 *
 * [totalCpuTime] and [peakMemoryBytes] are `null` where the platform can't report
 * them — notably the [Mechanism.PROCESS_GROUP] backend (no kernel accounting), so
 * they are always `null` there and populated only under a [Mechanism.JOB_OBJECT].
 *
 * A read-only snapshot the library produces (constructed internally); new metrics
 * may be added in a later release without a breaking change.
 */
public class ProcessGroupStats internal constructor(
    /**
     * Number of live processes in the group. Under [Mechanism.PROCESS_GROUP] this
     * counts live process *groups* (a contained child that itself forks helpers
     * still counts once); under [Mechanism.JOB_OBJECT] it is the exact process
     * count.
     */
    public val activeProcessCount: Int,
    /**
     * Total CPU time (user + kernel) accumulated by the group, or `null` if the
     * platform can't report it. On Windows this is cumulative across every process
     * that has ever been in the job, terminated ones included.
     */
    public val totalCpuTime: Duration?,
    /**
     * Peak memory charged to the group in bytes, or `null` if unavailable. On
     * Windows this is the Job Object's peak committed memory (`PeakJobMemoryUsed`),
     * not a working-set figure — its meaning differs by platform and it is not
     * directly comparable across them.
     */
    public val peakMemoryBytes: Long?,
) {
    override fun toString(): String =
        "ProcessGroupStats(activeProcessCount=$activeProcessCount, " +
            "totalCpuTime=$totalCpuTime, peakMemoryBytes=$peakMemoryBytes)"
}
