package net.zelanton.processkit

import java.nio.file.Path

/**
 * A captured record of one command a runner was asked to run — the *routing*
 * knobs (program, args, working directory, environment overrides, whether stdin
 * was supplied), for input assertions in tests.
 *
 * [toString] is redacted: it surfaces the argument **count** and the environment
 * variable **names** (sorted), never the argv or env **values** — so a logged or
 * assertion-failure render cannot leak a secret passed as an argument or an env
 * value (the routing fields [program] and [workingDir] are shown). The public
 * [args] / [environment] fields stay available for tests that assert exact values.
 */
public class Invocation internal constructor(
    /** The program name. */
    public val program: String,
    /** The arguments, in order (without the program). */
    public val args: List<String>,
    /** The working directory, if one was set. */
    public val workingDir: Path?,
    /** Environment overrides applied to the command, in registration order. */
    public val environment: Map<String, String>,
    /** Whether a non-empty stdin source was supplied. */
    public val hasStdin: Boolean,
) {
    /** Whether [flag] appears among the arguments. */
    public fun hasFlag(flag: String): Boolean = flag in args

    override fun toString(): String =
        "Invocation(program=$program, args=${args.size}, workingDir=$workingDir, " +
            "envNames=${environment.keys.sorted()}, hasStdin=$hasStdin)"

    internal companion object {
        internal fun from(command: Command): Invocation =
            Invocation(
                program = command.program,
                args = command.commandLine.drop(1),
                workingDir = command.workingDirectory,
                environment = command.environmentOverrides.toMap(),
                hasStdin = command.stdinSource !is Stdin.None,
            )
    }
}

/**
 * Wraps another [ProcessRunner], recording every [Invocation] before delegating,
 * so tests can assert exactly what was run.
 *
 * ```
 * val rec = RecordingRunner.replying(Reply.ok("https://gh/pr/2\n"))
 * GhClient(rec).createPr(dir, title = "T")
 * val call = rec.onlyCall()
 * assertEquals(listOf("pr", "create", "--title", "T"), call.args)
 * assertFalse(call.hasFlag("--base"))
 * ```
 *
 * A command run with a [retry][Command.retry] policy records one [Invocation] per
 * attempt, so the retry count is assertable. Safe for concurrent use.
 */
public class RecordingRunner(
    private val inner: ProcessRunner,
) : ProcessRunner {
    private val recorded = mutableListOf<Invocation>()
    private val lock = Any()

    /** A snapshot of every recorded invocation, in order. */
    public val calls: List<Invocation> get() = synchronized(lock) { recorded.toList() }

    /** The single recorded invocation; throws unless exactly one was made. */
    public fun onlyCall(): Invocation {
        val snapshot = calls
        check(snapshot.size == 1) { "expected exactly one call, got ${snapshot.size}" }
        return snapshot.single()
    }

    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        synchronized(lock) { recorded.add(Invocation.from(command)) }
        return inner.execute(command)
    }

    override suspend fun start(command: Command): RunningProcess {
        // Recorded before delegating, so a streamed run is captured even if its
        // stream is never consumed.
        synchronized(lock) { recorded.add(Invocation.from(command)) }
        return inner.start(command)
    }

    public companion object {
        /** A recorder whose inner runner replies with [reply] to everything. */
        public fun replying(reply: Reply): RecordingRunner = RecordingRunner(ScriptedRunner().fallback(reply))
    }
}
