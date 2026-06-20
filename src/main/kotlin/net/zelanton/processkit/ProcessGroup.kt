package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.zelanton.processkit.internal.Containment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A shared kill-on-close container for a set of children.
 *
 * Run commands through the group (it is a [ProcessRunner], so all the verbs work),
 * and every child — and everything those children spawn — lives in one container.
 * Closing the group reaps the whole tree, grandchildren included:
 *
 * ```
 * ProcessGroup().use { group ->
 *     group.run(Command("step-one"))
 *     group.run(Command("step-two"))
 * } // close() reaps anything still alive
 * ```
 *
 * A per-run `timeout` (or a cancelled run) kills only that child's subtree, not
 * its siblings; [close] is the guaranteed backstop for the whole tree.
 *
 * Pass [ResourceLimits] to cap the whole tree's memory / process count. The caps
 * are applied to the kernel container at construction, so an unenforceable limit
 * fails fast here with [ProcessException.ResourceLimit] (see [ResourceLimits]).
 *
 * Safe for concurrent use: [execute] / [start] may be called from many coroutines
 * at once, and [close] / [shutdown] are idempotent.
 */
public class ProcessGroup(
    limits: ResourceLimits = ResourceLimits(),
) : ProcessRunner,
    AutoCloseable {
    private val containment: Containment = newContainment("process-group", limits)
    private val lock = Any()
    private var closed = false

    /** The kernel containment mechanism in effect for this group. */
    public val mechanism: Mechanism get() = containment.mechanism

    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val process = withContext(Dispatchers.IO) { containment.spawnChecked(command) }
        return captureRun(process, command) { killSubtree(process) }
    }

    /**
     * Start [command] in this group and return a live [RunningProcess] for
     * streaming. The handle's `close()` kills only this child's subtree; the
     * group's own [close] reaps the whole tree.
     */
    override suspend fun start(command: Command): RunningProcess {
        val process = withContext(Dispatchers.IO) { containment.spawnChecked(command) }
        return RunningProcess(
            process,
            command.program,
            containment,
            ownsContainer = false,
            command.timeoutOrNull,
            command.stdinSource,
            command.stdoutCharset,
            command.stderrCharset,
        )
    }

    /**
     * Broadcast [signal] to every process in the group (best-effort: members that
     * have already exited are skipped, an empty group is a no-op).
     *
     * On Unix any signal reaches every live member of the tree. On **Windows**
     * only [Signal.Kill] is deliverable (it maps to the Job Object terminate, the
     * same hard kill as [close]); any other signal throws
     * [ProcessException.Unsupported]. [Signal.Kill] is routed through the same
     * whole-tree hard kill as [close] so it cannot miss a process forked
     * mid-broadcast.
     *
     * On the [Mechanism.PROCESS_GROUP] fallback a fully-exited group is dropped
     * before signalling, but a pid reaped and then reused by the OS as a new group
     * leader is indistinguishable from the original — an inherent limit of the
     * process-group mechanism (the [Mechanism.JOB_OBJECT] / cgroup mechanisms are
     * immune).
     */
    public fun signal(signal: Signal) {
        log.debug("group: signal {} ({})", signal, containment.mechanism)
        containment.signal(signal)
    }

    /**
     * Suspend (freeze) every process in the group — `SIGSTOP` to each group on
     * Unix. **Windows is not yet supported** and throws
     * [ProcessException.Unsupported] (per-thread suspend lands in a later
     * increment). A suspended tree can still be hard-killed ([close]); a graceful
     * [shutdown] cannot make progress until the tree is [resume]d first.
     */
    public fun suspend() {
        log.debug("group: suspend ({})", containment.mechanism)
        containment.suspendAll()
    }

    /** Resume a tree suspended by [suspend] (`SIGCONT` on Unix). See [suspend]. */
    public fun resume() {
        log.debug("group: resume ({})", containment.mechanism)
        containment.resumeAll()
    }

    /**
     * A point-in-time snapshot of the pids currently in the group — for
     * diagnostics or targeted per-pid action. A returned pid may exit immediately
     * after, and a process spawned during the call may be missing.
     *
     * On Unix this is the tracked group leaders plus any [adopt]ed child (one pid
     * each; descendants are contained but not enumerated). On Windows it is each
     * spawned/adopted root that is still alive plus its live descendants.
     */
    public fun members(): List<Long> = containment.members()

    /**
     * Bring an externally-started [process] under this group's lifecycle, so it is
     * signalled and reaped with the group.
     *
     * On Windows the process is assigned to the Job Object (its future children
     * are captured too). On the Unix process-group mechanism it is tracked
     * individually — signalled/killed with the group, but its own forks are not
     * captured (POSIX forbids re-grouping a child that has already `exec`'d).
     */
    public fun adopt(process: Process): Unit = containment.adopt(process.pid())

    /**
     * A snapshot of the group's resource usage (live process count and, where the
     * platform supports it, total CPU time and peak memory — `null` on the
     * [Mechanism.PROCESS_GROUP] backend). See [ProcessGroupStats].
     */
    public fun stats(): ProcessGroupStats = containment.stats()

    /**
     * Sample [stats] every [every] as a cold [Flow]: the first snapshot is emitted
     * immediately, then one per interval, until a snapshot can't be read (e.g.
     * after the group is torn down) or the collector stops. A zero/negative
     * interval is clamped to 1 ms. Snapshots are taken on [Dispatchers.IO].
     */
    public fun sampleStats(every: Duration): Flow<ProcessGroupStats> =
        flow {
            val interval = every.coerceAtLeast(1.milliseconds)
            while (true) {
                val snapshot = runCatching { containment.stats() }.getOrNull() ?: break
                emit(snapshot)
                delay(interval)
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Graceful teardown: ask the tree to stop (SIGTERM on Unix), wait up to
     * [grace], then hard-kill whatever remains. On Windows there is no graceful
     * signal, so this is an immediate atomic kill.
     */
    public suspend fun shutdown(grace: Duration = 5.seconds) {
        log.debug("group: shutdown (grace {}, {})", grace, containment.mechanism)
        val gracefulSignalSent = containment.requestStop()
        if (gracefulSignalSent) {
            delay(grace)
        }
        withContext(Dispatchers.IO) { closeOnce() }
    }

    /** Hard-kill the whole group (every child and descendant). */
    override fun close() {
        closeOnce()
    }

    private fun closeOnce() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        containment.close()
    }

    private fun killSubtree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }
}
