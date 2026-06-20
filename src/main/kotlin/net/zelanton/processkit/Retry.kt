package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A retry policy attached to a [Command] via [Command.retry], honored by the
 * success-checking verbs ([run][ProcessRunner.run], [runUnit][ProcessRunner.runUnit],
 * [exitCode][ProcessRunner.exitCode], [probe][ProcessRunner.probe]).
 */
internal class RetryPolicy(
    val maxAttempts: Int,
    val backoff: Duration,
    val classifier: (ProcessException) -> Boolean,
)

/**
 * Run [attempt] once, or up to [RetryPolicy.maxAttempts] times when [command]
 * carries a retry policy, sleeping [RetryPolicy.backoff] between tries while the
 * thrown [ProcessException] is classified retryable.
 *
 * A cancelled run is terminal (cancellation propagates, never retried), and any
 * non-[ProcessException] failure propagates unchanged.
 */
internal suspend fun <T> retrying(
    command: Command,
    attempt: suspend () -> T,
): T {
    val policy = command.retryPolicy ?: return attempt()
    var tries = 0
    while (true) {
        tries++
        try {
            return attempt()
        } catch (failure: ProcessException) {
            if (tries < policy.maxAttempts && policy.classifier(failure)) {
                log.debug(
                    "retry: `{}` attempt {}/{} failed ({}); retrying after {}",
                    command.program,
                    tries,
                    policy.maxAttempts,
                    failure.message,
                    policy.backoff,
                )
                delay(policy.backoff)
            } else {
                throw failure
            }
        }
    }
}

/**
 * Ready-made classifiers for [Command.retry]. Pick the condition a bare replay can
 * actually clear — whether a given exit code is retryable is domain-specific, so
 * spell those out with [exitCode].
 *
 * ```
 * Command("flaky-fetch").retry(maxAttempts = 4, backoff = 1.seconds, RetryWhen.transient).run()
 * Command("deploy").retry(maxAttempts = 3, backoff = 2.seconds, RetryWhen.exitCode(75)).runUnit()
 * ```
 */
public object RetryWhen {
    /** Retry only on a [ProcessException.Timeout]. */
    public val timedOut: (ProcessException) -> Boolean = { it is ProcessException.Timeout }

    /**
     * Retry on conditions that are commonly transient — a timeout or a spawn
     * failure ([ProcessException.Spawn]). A missing program ([ProcessException.NotFound])
     * and a tool's non-zero exit are *not* included: the first cannot clear by
     * replay, the second is domain-specific (use [exitCode]).
     */
    public val transient: (ProcessException) -> Boolean = {
        it is ProcessException.Timeout || it is ProcessException.Spawn
    }

    /** Retry when the failure is a rejected exit with one of [codes]. */
    public fun exitCode(vararg codes: Int): (ProcessException) -> Boolean =
        { failure ->
            failure is ProcessException.Exit && failure.exitCode in codes
        }
}
