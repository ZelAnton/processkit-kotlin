package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** When a supervised command exits, should it be restarted? */
public enum class RestartPolicy {
    /** Restart on any exit. */
    ALWAYS,

    /** Restart only on a non-zero exit; a clean exit stops supervision. */
    ON_CRASH,

    /** Never restart; run once. */
    NEVER,
}

/** Why a [Supervisor] stopped. */
public enum class StopReason {
    /** The restart policy chose not to restart the last exit. */
    STOPPED_BY_POLICY,

    /** The configured `maxRestarts` cap was reached. */
    MAX_RESTARTS_REACHED,

    /** The `stopWhen` condition matched the last result. */
    STOP_CONDITION_MET,
}

/** The result of [Supervisor.run]: the last run, the restart count, and why it stopped. */
public class SupervisionOutcome internal constructor(
    public val lastResult: ProcessResult<String>,
    public val restarts: Int,
    public val stoppedBy: StopReason,
) {
    override fun toString(): String = "SupervisionOutcome(restarts=$restarts, stoppedBy=$stoppedBy)"
}

/**
 * Keeps a command **alive**: restarts it per [RestartPolicy] whenever it exits,
 * with bounded restarts and exponential backoff (jittered by default so a
 * restarted fleet doesn't stampede).
 *
 * ```
 * val outcome = Supervisor(Command("my-server", "--port", "8080"))
 *     .restart(RestartPolicy.ON_CRASH)
 *     .maxRestarts(5)
 *     .backoff(200.milliseconds, multiplier = 2.0)
 *     .stopWhen { it.exitCode == 0 }
 *     .run()
 * ```
 *
 * Runs through the [ProcessRunner] seam ([withRunner]); pass a [ScriptedRunner]
 * to test supervision logic hermetically, or a [ProcessGroup] to keep every
 * incarnation in one shared kill-on-close group. (A failure-storm guard is a
 * planned addition.)
 */
public class Supervisor(
    private val command: Command,
) {
    private var policy: RestartPolicy = RestartPolicy.ON_CRASH
    private var maxRestarts: Int = Int.MAX_VALUE
    private var backoffBase: Duration = 200.milliseconds
    private var backoffMultiplier: Double = 2.0
    private var maxBackoff: Duration = 30.seconds
    private var jitter: Boolean = true
    private var stopCondition: (ProcessResult<String>) -> Boolean = { false }
    private var runner: ProcessRunner = JobRunner

    /** Choose when to restart (default [RestartPolicy.ON_CRASH]). */
    public fun restart(policy: RestartPolicy): Supervisor = apply { this.policy = policy }

    /** Cap the number of restarts (default unbounded). */
    public fun maxRestarts(count: Int): Supervisor =
        apply {
            require(count >= 0) { "maxRestarts must be >= 0, was $count" }
            maxRestarts = count
        }

    /** Exponential backoff: `base * multiplier^attempt`, capped at [maxBackoff]. */
    public fun backoff(
        base: Duration,
        multiplier: Double,
    ): Supervisor =
        apply {
            backoffBase = base
            backoffMultiplier = multiplier
        }

    /** Cap the backoff delay (default 30s). */
    public fun maxBackoff(duration: Duration): Supervisor = apply { maxBackoff = duration }

    /** Enable or disable backoff jitter (default on). */
    public fun jitter(enabled: Boolean): Supervisor = apply { jitter = enabled }

    /** Stop supervision when [predicate] matches a run's result (e.g. a clean exit). */
    public fun stopWhen(predicate: (ProcessResult<String>) -> Boolean): Supervisor = apply { stopCondition = predicate }

    /** Run each incarnation through [runner] (default [JobRunner]). */
    public fun withRunner(runner: ProcessRunner): Supervisor = apply { this.runner = runner }

    /**
     * Supervise until the policy, the restart cap, or the stop condition ends it.
     *
     * With the defaults ([RestartPolicy.ON_CRASH] and an unbounded [maxRestarts])
     * this suspends indefinitely while the command keeps crashing — set
     * [maxRestarts] and/or [stopWhen] to bound it, or cancel the calling coroutine.
     */
    public suspend fun run(): SupervisionOutcome {
        var restarts = 0
        while (true) {
            val result = runner.outputString(command)
            if (stopCondition(result)) {
                return SupervisionOutcome(result, restarts, StopReason.STOP_CONDITION_MET)
            }
            val shouldRestart =
                when (policy) {
                    RestartPolicy.NEVER -> false
                    RestartPolicy.ALWAYS -> true
                    RestartPolicy.ON_CRASH -> !result.isSuccess
                }
            if (!shouldRestart) {
                return SupervisionOutcome(result, restarts, StopReason.STOPPED_BY_POLICY)
            }
            if (restarts >= maxRestarts) {
                return SupervisionOutcome(result, restarts, StopReason.MAX_RESTARTS_REACHED)
            }
            log.debug("supervisor: restarting `{}` (restart {})", command.program, restarts + 1)
            delay(backoffDelay(restarts))
            restarts++
        }
    }

    private fun backoffDelay(attempt: Int): Duration {
        val factor = backoffMultiplier.pow(attempt)
        val scaled = if (factor.isFinite()) backoffBase * factor else Duration.INFINITE
        val capped = minOf(scaled, maxBackoff)
        return if (jitter) capped * Random.nextDouble(JITTER_FLOOR, 1.0) else capped
    }

    private companion object {
        private const val JITTER_FLOOR = 0.5
    }
}
