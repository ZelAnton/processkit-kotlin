package net.zelanton.processkit

import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.time.Duration

/**
 * A command to run: a program, its arguments, and run options.
 *
 * A mutable fluent builder (like `ProcessBuilder`) — each option returns `this`.
 * Finish with a verb; the verb decides what you get back:
 *
 * ```
 * val version = Command("git", "--version").run()                 // trimmed stdout, success required
 * val result = Command("git", "rev-parse", "HEAD").outputString() // full result, non-zero is data
 * ```
 *
 * The default verbs run on [JobRunner]; pass a [Command] to a [ProcessRunner]
 * (e.g. a [ScriptedRunner]) to run it through an injected seam instead.
 *
 * A `Command` holds mutable builder state and is **not** safe to mutate from
 * multiple threads at once. Building then running (including running the same
 * built command more than once) is fine; concurrent `arg`/`env` mutation is not.
 */
public class Command(
    /** The program to run (resolved against `PATH` by the OS). */
    public val program: String,
    vararg args: String,
) {
    /** Build from a program and a list of arguments. */
    public constructor(program: String, args: List<String>) : this(program, *args.toTypedArray())

    private val argumentList: MutableList<String> = args.toMutableList()
    private val environment: LinkedHashMap<String, String> = LinkedHashMap()

    internal var workingDirectory: Path? = null
        private set
    internal var environmentCleared: Boolean = false
        private set
    internal var timeoutOrNull: Duration? = null
        private set
    internal var stdinSource: Stdin = Stdin.None
        private set
    internal var retryPolicy: RetryPolicy? = null
        private set
    internal var stdoutLineHandler: ((String) -> Unit)? = null
        private set
    internal var stderrLineHandler: ((String) -> Unit)? = null
        private set
    internal var stdoutTeeSink: Appendable? = null
        private set
    internal var stderrTeeSink: Appendable? = null
        private set
    internal var stdoutCharset: Charset = Charsets.UTF_8
        private set
    internal var stderrCharset: Charset = Charsets.UTF_8
        private set

    internal val commandLine: List<String> get() =
        buildList {
            add(program)
            addAll(argumentList)
        }
    internal val environmentOverrides: Map<String, String> get() = environment

    /** Append a single argument. */
    public fun arg(value: String): Command = apply { argumentList.add(value) }

    /** Append several arguments. */
    public fun args(vararg values: String): Command = apply { argumentList.addAll(values) }

    /** Append several arguments. */
    public fun args(values: Iterable<String>): Command = apply { argumentList.addAll(values) }

    /** Run in [dir] instead of the parent's working directory. */
    public fun workingDir(dir: Path): Command = apply { workingDirectory = dir }

    /** Set an environment variable for the child (on top of the inherited env). */
    public fun env(
        name: String,
        value: String,
    ): Command = apply { environment[name] = value }

    /** Set several environment variables for the child. */
    public fun env(entries: Map<String, String>): Command = apply { environment.putAll(entries) }

    /**
     * Start from an empty environment (only the variables set via [env] are
     * passed). Without this the child inherits the parent's environment.
     */
    public fun clearEnv(): Command = apply { environmentCleared = true }

    /**
     * Bound the run: at the deadline the whole tree is killed. The expiry is
     * *captured* ([ProcessResult.timedOut]) for the capturing verbs and *raised*
     * ([ProcessException.Timeout]) for the success-requiring verbs.
     */
    public fun timeout(duration: Duration): Command = apply { timeoutOrNull = duration }

    /**
     * Feed the child's standard input from [source]. The default
     * ([Stdin.none]) closes stdin, so a stdin-reading child sees EOF.
     */
    public fun stdin(source: Stdin): Command = apply { stdinSource = source }

    /**
     * Invoke [handler] for each decoded stdout line as it is read by the capture
     * pump (the bulk verbs: `run` / `outputString` / …). At most one handler per
     * stream — a repeat call replaces it. A handler that throws is caught and
     * disabled for the rest of the run (the run continues). For a streamed run,
     * observe stdout via [RunningProcess.stdoutLines] instead.
     *
     * The handler runs on a background pump thread, and the stdout and stderr
     * handlers run on **separate** threads — guard any state they share.
     */
    public fun onStdoutLine(handler: (String) -> Unit): Command = apply { stdoutLineHandler = handler }

    /** Invoke [handler] for each decoded stderr line. See [onStdoutLine]. */
    public fun onStderrLine(handler: (String) -> Unit): Command = apply { stderrLineHandler = handler }

    /**
     * Tee every decoded stdout line to [sink] (followed by `\n`) as it is read —
     * capture *and* mirror it (e.g. to `System.out`, a file `Writer`, or a
     * `StringBuilder`). Fires independently of [onStdoutLine]. A [sink] that throws
     * is disabled for the rest of the run; capture is unaffected.
     *
     * Like [onStdoutLine], this fires on the capturing verbs only (a streamed run
     * observes via [RunningProcess.stdoutLines]). The library serializes appends to
     * [sink], so one sink shared by both [stdoutTee] and [stderrTee] is safe.
     */
    public fun stdoutTee(sink: Appendable): Command = apply { stdoutTeeSink = sink }

    /** Tee every decoded stderr line to [sink] (followed by `\n`). See [stdoutTee]. */
    public fun stderrTee(sink: Appendable): Command = apply { stderrTeeSink = sink }

    /**
     * Decode stdout with [charset] instead of UTF-8 (for non-UTF-8 tools). Line
     * observation ([onStdoutLine] / [stdoutTee]) splits on the ASCII `\n`/`\r`
     * bytes, so a wide encoding whose units contain those bytes (e.g. UTF-16) is
     * not supported for line splitting — the bulk `outputString` decode is fine.
     */
    public fun stdoutEncoding(charset: Charset): Command = apply { stdoutCharset = charset }

    /** Decode stderr with [charset] instead of UTF-8. */
    public fun stderrEncoding(charset: Charset): Command = apply { stderrCharset = charset }

    /**
     * Retry the run while [retryIf] accepts the failure, up to [maxAttempts] total
     * attempts, sleeping [backoff] between tries (see [RetryWhen] for ready-made
     * classifiers).
     *
     * Honored only by the **success-checking** verbs ([run] / [runUnit] /
     * [exitCode] / [probe]) — the ones that surface failure as a
     * [ProcessException] the classifier can inspect. The capturing verbs
     * ([outputString] / [outputBytes]) hand back every outcome as data and do not
     * retry, and a cancelled run is never retried. Note [probe] returns exit `0`/`1`
     * as data (not a failure), so a [RetryWhen.exitCode] of `0` or `1` never
     * triggers a probe retry.
     *
     * Each attempt re-executes the whole command as a fresh process, so only use
     * it for operations that are safe to repeat — a side effect that already landed
     * once will be replayed. Each attempt also re-reads the [stdin] source from the
     * start, so pair retry only with a replayable source ([Stdin.fromString] /
     * [Stdin.fromBytes], or a stable [Stdin.fromFile]).
     *
     * Through a [ProcessGroup] a failed attempt's child subtree is not reaped until
     * the group closes; for per-attempt cleanup, retry on the default private
     * containment (the [Command] verbs, backed by [JobRunner]).
     */
    public fun retry(
        maxAttempts: Int,
        backoff: Duration,
        retryIf: (ProcessException) -> Boolean,
    ): Command =
        apply {
            require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
            require(!backoff.isNegative()) { "backoff must not be negative, was $backoff" }
            retryPolicy = RetryPolicy(maxAttempts, backoff, retryIf)
        }

    /** Require a zero exit and return trimmed stdout. */
    public suspend fun run(): String = JobRunner.run(this)

    /** Require a zero exit, discarding the output. */
    public suspend fun runUnit(): Unit = JobRunner.runUnit(this)

    /** Capture the full result with stdout as text; a non-zero exit is not an error. */
    public suspend fun outputString(): ProcessResult<String> = JobRunner.outputString(this)

    /** Capture the full result with stdout as raw bytes; a non-zero exit is not an error. */
    public suspend fun outputBytes(): ProcessResult<ByteArray> = JobRunner.outputBytes(this)

    /** The exit code; a timed-out run throws instead of inventing one. */
    public suspend fun exitCode(): Int = JobRunner.exitCode(this)

    /** A yes/no probe: exit `0` → `true`, `1` → `false`, anything else throws. */
    public suspend fun probe(): Boolean = JobRunner.probe(this)

    /**
     * Require a zero exit and feed the (untrimmed) stdout to [transform] — for
     * struct-returning commands. A throwing [transform] propagates; the run honors
     * this command's [retry] policy but a [transform] failure is not retried.
     */
    public suspend fun <T> parse(transform: (String) -> T): T = JobRunner.parse(this, transform)

    /**
     * Start the command and return a live [RunningProcess] for streaming. The
     * run lives in its own kill-on-close container; `use { }` the handle so a
     * dropped or cancelled run reaps the tree.
     */
    public suspend fun start(): RunningProcess = JobRunner.start(this)

    /**
     * Stream stdout and return the first line matching [predicate], **then kill the
     * run** (or `null` if the stream ends with no match). For scanning a *finite*
     * command's output; for readiness use [RunningProcess.waitForLine] (which leaves
     * the child running). Bound an unbounded stream with [timeout].
     */
    public suspend fun firstLine(predicate: (String) -> Boolean): String? = JobRunner.firstLine(this, predicate)

    /** Start a shell-free pipeline: this command's stdout feeds [next]'s stdin. */
    public fun pipe(next: Command): Pipeline = Pipeline(this, next)

    /** An independent copy of this command — mutating either does not affect the other. */
    internal fun copy(): Command {
        val clone = Command(program, *argumentList.toTypedArray())
        clone.workingDirectory = workingDirectory
        clone.environment.putAll(environment)
        clone.environmentCleared = environmentCleared
        clone.timeoutOrNull = timeoutOrNull
        clone.stdinSource = stdinSource
        clone.retryPolicy = retryPolicy
        clone.stdoutLineHandler = stdoutLineHandler
        clone.stderrLineHandler = stderrLineHandler
        clone.stdoutTeeSink = stdoutTeeSink
        clone.stderrTeeSink = stderrTeeSink
        clone.stdoutCharset = stdoutCharset
        clone.stderrCharset = stderrCharset
        return clone
    }

    // Renders the full command line (arguments included), so never pass a Command
    // to a logger — argv can carry secrets (e.g. `--token=…`). Log `program` only.
    override fun toString(): String = "Command(${commandLine.joinToString(" ")})"
}
