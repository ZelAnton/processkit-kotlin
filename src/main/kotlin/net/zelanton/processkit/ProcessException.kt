package net.zelanton.processkit

import kotlin.time.Duration

/**
 * Base type for the typed failures processkit raises.
 *
 * The hierarchy is **open** (not sealed) on purpose: new failure modes can be
 * added in later releases without breaking a consumer's `catch`/`when`. Consumers
 * cannot construct or subclass these (the constructors are internal) — they catch
 * [ProcessException] or a specific subtype.
 *
 * Cancellation is deliberately **not** modelled here: a cancelled run propagates
 * a coroutine [kotlinx.coroutines.CancellationException], the idiomatic JVM way,
 * so structured concurrency keeps working.
 */
public abstract class ProcessException internal constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** A run that required success finished with a non-zero exit code. */
    public class Exit internal constructor(
        public val program: String,
        public val exitCode: Int,
        public val stderr: String,
    ) : ProcessException("`$program` exited with code $exitCode")

    /** A run that required success did not finish within its [timeout]. */
    public class Timeout internal constructor(
        public val program: String,
        public val timeout: Duration?,
    ) : ProcessException(
            buildString {
                append("`$program` timed out")
                if (timeout != null) append(" after $timeout")
            },
        )

    /** The program could not be found (e.g. not on `PATH`). */
    public class NotFound internal constructor(
        public val program: String,
        cause: Throwable?,
    ) : ProcessException("program not found: `$program`", cause)

    /** The process could not be started for some other reason. */
    public class Spawn internal constructor(
        public val program: String,
        cause: Throwable,
    ) : ProcessException("failed to start `$program`: ${cause.message}", cause)

    /** A readiness probe did not pass within its deadline. Distinct from [Timeout]. */
    public class NotReady internal constructor(
        public val program: String,
        public val timeout: Duration,
    ) : ProcessException("`$program` was not ready within $timeout")

    /**
     * A process-control [operation] is not supported on this platform — e.g. a
     * non-[Signal.Kill] signal, or [ProcessGroup.suspend]/[resume][ProcessGroup.resume],
     * on Windows.
     */
    public class Unsupported internal constructor(
        public val operation: String,
    ) : ProcessException("operation not supported on this platform: $operation")

    /**
     * A [ResourceLimits] cap could not be enforced — the platform/mechanism has no
     * whole-tree limit primitive (the [Mechanism.PROCESS_GROUP] backend), or the
     * specific cap is not yet implemented. Raised eagerly at `ProcessGroup`
     * construction, never silently leaving the tree unbounded.
     */
    public class ResourceLimit internal constructor(
        message: String,
    ) : ProcessException(message)
}
