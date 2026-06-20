package net.zelanton.processkit

/** What to do when a bounded output buffer is full. */
public enum class OverflowMode {
    /** Ring-buffer / "tail" — discard the oldest line so the most recent survives. */
    DROP_OLDEST,

    /**
     * "Head" — keep what is buffered and discard new lines. With only [maxLines]
     * set this is a strict prefix (the first `n` lines); when a [maxBytes] cap is
     * also set it keeps lines greedily — a later, smaller line may still be kept
     * after a larger one was skipped — so the retained set need not be contiguous.
     */
    DROP_NEWEST,

    /**
     * Fail-loud ceiling: once the cap is reached the run errors with
     * [ProcessException.OutputTooLarge] rather than dropping lines. The pipe is
     * still drained (the child never blocks); excess lines are counted, not kept.
     * With no cap set this is **zero-tolerance** — any line-pumped output errors.
     */
    ERROR,
}

/**
 * Caps how much captured output the **bulk verbs** (`run` / `outputString` / …)
 * retain in memory, line by line. The pump still drains the OS pipe (so the child
 * never blocks); this only bounds the in-memory backlog.
 *
 * Two independent ceilings — [maxLines] and [maxBytes] (the sum of retained lines'
 * bytes) — either or both may be set. On overflow, [overflow] decides whether to
 * drop the oldest/newest line or fail loud. When lines are dropped the result is
 * flagged [ProcessResult.truncated]; the success-checking verbs (`run` / `parse`)
 * reject a truncated capture (they present stdout as complete).
 *
 * Applies to the bulk capture path; a streamed run ([RunningProcess.stdoutLines] /
 * [RunningProcess.outputEvents]) is bounded by the consumer instead, and a
 * [pipeline][Command.pipe] does not honor it (its stages capture unbounded). The
 * byte cap bounds *retained* memory, not a single never-terminated line still
 * being assembled — pair with [Command.timeout] to bound a pathological flood.
 */
public class OutputBufferPolicy private constructor(
    /** Max retained lines: `null` unbounded, `0` retains nothing, `n` keeps at most `n`. */
    public val maxLines: Int?,
    /** Max retained bytes (sum of retained lines' byte lengths): `null` unbounded. */
    public val maxBytes: Int?,
    /** Which line to drop (or whether to error) when full. */
    public val overflow: OverflowMode,
) {
    // Error with no cap is "zero-tolerance", so it still needs the line pump.
    internal val isUnbounded: Boolean
        get() = maxLines == null && maxBytes == null && overflow != OverflowMode.ERROR

    /** Add a retained-byte ceiling (composable with any policy). */
    public fun withMaxBytes(maxBytes: Int): OutputBufferPolicy {
        require(maxBytes >= 0) { "maxBytes must be >= 0, was $maxBytes" }
        return OutputBufferPolicy(maxLines, maxBytes, overflow)
    }

    /** Change the overflow behavior. */
    public fun withOverflow(overflow: OverflowMode): OutputBufferPolicy =
        OutputBufferPolicy(maxLines, maxBytes, overflow)

    override fun toString(): String = "OutputBufferPolicy(maxLines=$maxLines, maxBytes=$maxBytes, overflow=$overflow)"

    public companion object {
        /** Retain everything (the default). */
        public fun unbounded(): OutputBufferPolicy = OutputBufferPolicy(null, null, OverflowMode.DROP_OLDEST)

        /** Retain at most [maxLines], dropping the oldest when full. */
        public fun bounded(maxLines: Int): OutputBufferPolicy {
            require(maxLines >= 0) { "maxLines must be >= 0, was $maxLines" }
            return OutputBufferPolicy(maxLines, null, OverflowMode.DROP_OLDEST)
        }

        /** Retain at most [maxLines] and error ([OverflowMode.ERROR]) when full. */
        public fun failLoud(maxLines: Int): OutputBufferPolicy {
            require(maxLines >= 0) { "maxLines must be >= 0, was $maxLines" }
            return OutputBufferPolicy(maxLines, null, OverflowMode.ERROR)
        }
    }
}
