package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.zelanton.processkit.internal.Containment
import kotlin.time.Duration
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
 */
public class ProcessGroup :
    ProcessRunner,
    AutoCloseable {
    private val containment: Containment = newContainment("process-group")
    private val lock = Any()
    private var closed = false

    /** The kernel containment mechanism in effect for this group. */
    public val mechanism: Mechanism get() = containment.mechanism

    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val process = withContext(Dispatchers.IO) { containment.spawnChecked(command) }
        return captureRun(process, command.program, command.timeoutOrNull, command.stdinSource) { killSubtree(process) }
    }

    /**
     * Start [command] in this group and return a live [RunningProcess] for
     * streaming. The handle's `close()` kills only this child's subtree; the
     * group's own [close] reaps the whole tree.
     */
    public suspend fun start(command: Command): RunningProcess {
        val process = withContext(Dispatchers.IO) { containment.spawnChecked(command) }
        return RunningProcess(
            process,
            command.program,
            containment,
            ownsContainer = false,
            command.timeoutOrNull,
            command.stdinSource,
        )
    }

    /**
     * Graceful teardown: ask the tree to stop (SIGTERM on Unix), wait up to
     * [grace], then hard-kill whatever remains. On Windows there is no graceful
     * signal, so this is an immediate atomic kill.
     */
    public suspend fun shutdown(grace: Duration = 5.seconds) {
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
