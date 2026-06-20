package net.zelanton.processkit

/**
 * Resource caps enforced on a [ProcessGroup] as a whole — pass to the
 * `ProcessGroup(limits)` constructor before any child is started. Every limit
 * bounds the **whole tree**, not a single process, and is applied to the kernel
 * container at creation time.
 *
 * Enforcement needs a real container — a **Windows Job Object** (this library's
 * Windows backend). On the [Mechanism.PROCESS_GROUP] backend there is no
 * whole-tree limit primitive, so requesting *any* limit there fails fast with
 * [ProcessException.ResourceLimit] rather than silently leaving the tree
 * unbounded — an unenforced limit is no protection.
 *
 * Enforced by the Windows Job Object: [memoryMax], [maxProcesses], and [cpuQuota]
 * (CPU rate control). On the [Mechanism.PROCESS_GROUP] backend none can be enforced,
 * so requesting any fails fast (the Linux cgroup backend lands in a later increment).
 */
public class ResourceLimits(
    /** Maximum total committed memory for the tree, in bytes. `null` = unbounded. */
    public val memoryMax: Long? = null,
    /** Maximum number of live processes in the tree. `null` = unbounded. */
    public val maxProcesses: Int? = null,
    /**
     * CPU quota as a fraction of a **single** core (`0.5` = half a core, `2.0` =
     * two cores). `null` = unbounded. Enforced as a Windows Job Object hard CPU-rate
     * cap (a quota at or above the host core count caps at 100%).
     */
    public val cpuQuota: Double? = null,
) {
    init {
        require(memoryMax == null || memoryMax > 0) { "memoryMax must be > 0, was $memoryMax" }
        require(maxProcesses == null || maxProcesses >= 1) { "maxProcesses must be >= 1, was $maxProcesses" }
        require(cpuQuota == null || cpuQuota > 0.0) { "cpuQuota must be > 0, was $cpuQuota" }
    }

    /** Whether any limit is set (so the group needs a limit-capable mechanism). */
    internal fun any(): Boolean = memoryMax != null || maxProcesses != null || cpuQuota != null

    override fun toString(): String =
        "ResourceLimits(memoryMax=$memoryMax, maxProcesses=$maxProcesses, cpuQuota=$cpuQuota)"
}
