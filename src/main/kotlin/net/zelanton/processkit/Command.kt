package net.zelanton.processkit

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
 */
public class Command(
    /** The program to run (resolved against `PATH` by the OS). */
    public val program: String,
    vararg args: String,
) {
    private val argumentList: MutableList<String> = args.toMutableList()
    private val environment: LinkedHashMap<String, String> = LinkedHashMap()

    internal var workingDirectory: Path? = null
        private set
    internal var environmentCleared: Boolean = false
        private set
    internal var timeoutOrNull: Duration? = null
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

    override fun toString(): String = "Command(${commandLine.joinToString(" ")})"
}
