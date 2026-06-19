package net.zelanton.processkit

/**
 * The kernel containment primitive in effect for a process tree.
 *
 * Every child started by processkit lives in one of these containers, so the
 * whole tree (grandchildren included) can be torn down as a unit. The active
 * mechanism is observable rather than hidden: where a platform offers only a
 * weaker guarantee, that is reported here instead of being silently downgraded.
 */
public enum class Mechanism {
    /** Windows **Job Object** — kill-on-close, kernel-enforced over the whole tree. */
    JOB_OBJECT,

    /** Linux **cgroup v2** — kill-on-close over the whole subtree (requires delegation). */
    CGROUP_V2,

    /** POSIX **process group** — `killpg` over the group (a `setsid` child can escape). */
    PROCESS_GROUP,

    /** No whole-tree containment available on this target. */
    NONE,
}
