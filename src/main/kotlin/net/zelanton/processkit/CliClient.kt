package net.zelanton.processkit

import java.nio.file.Path
import kotlin.time.Duration

/**
 * A small, reusable core for building typed wrappers around a CLI tool (`git`,
 * `jj`, `gh`, …).
 *
 * It owns the program name, a [ProcessRunner], and optional client-wide defaults
 * (a timeout and environment variables); hands back preconfigured [Command]s; and
 * provides the terminal run/parse verbs a wrapper would otherwise repeat. Because
 * the runner is injectable, a wrapper built on it is hermetically testable — pass
 * a [ScriptedRunner] or a [RecordingRunner] in tests.
 *
 * Compose it into a typed facade (Kotlin needs no codegen for this):
 *
 * ```
 * class GitClient(runner: ProcessRunner = JobRunner) {
 *     private val cli = CliClient("git", runner).defaultEnv("GIT_TERMINAL_PROMPT", "0")
 *     suspend fun head(dir: Path): String = cli.run(cli.commandIn(dir, "rev-parse", "HEAD"))
 *     suspend fun isClean(dir: Path): Boolean = cli.probe(cli.commandIn(dir, "diff", "--quiet"))
 *     suspend fun branches(dir: Path): List<String> =
 *         cli.parse(cli.commandIn(dir, "branch")) { out -> out.lines().map { it.trim() } }
 * }
 * ```
 *
 * Every verb accepts either an argument list (`cli.run("status")`) — built into a
 * [Command] for the client's program with the defaults applied — or a ready-made
 * [Command] (`cli.run(cli.command("push").timeout(d))`), whose own explicit
 * settings win while the client defaults fill the gaps it left. A ready-made
 * command is never mutated: the defaults are filled into a private copy, so one
 * command can be reused across clients without leaking a client's defaults
 * (a token, a timeout) into another.
 *
 * Holds mutable default state; configure it (the `default*` builders) before
 * sharing it across coroutines. It is otherwise safe to run verbs concurrently
 * when the injected runner is (the real [JobRunner] is).
 */
public class CliClient(
    private val program: String,
    private val runner: ProcessRunner = JobRunner,
) {
    private var defaultTimeout: Duration? = null
    private val defaultEnvironment: LinkedHashMap<String, String> = LinkedHashMap()

    /** Apply a default timeout to every command this client builds. */
    public fun defaultTimeout(timeout: Duration): CliClient = apply { defaultTimeout = timeout }

    /**
     * Set an environment variable on every command this client builds — e.g.
     * `GIT_TERMINAL_PROMPT=0` so a probe can never block on a credential prompt.
     * A per-command [Command.env] for the same key wins.
     */
    public fun defaultEnv(
        name: String,
        value: String,
    ): CliClient = apply { defaultEnvironment[name] = value }

    /** Set several default environment variables (see [defaultEnv]). */
    public fun defaultEnv(entries: Map<String, String>): CliClient = apply { defaultEnvironment.putAll(entries) }

    /** A [Command] for `program <args>`, the client defaults pre-applied. */
    public fun command(vararg args: String): Command = fillDefaults(Command(program, *args))

    /** A [Command] for `program <args>`, the client defaults pre-applied. */
    public fun command(args: List<String>): Command = fillDefaults(Command(program, args))

    /** A [Command] for `program <args>` run in [dir], the client defaults pre-applied. */
    public fun commandIn(
        dir: Path,
        vararg args: String,
    ): Command = fillDefaults(Command(program, *args).workingDir(dir))

    /** A [Command] for `program <args>` run in [dir], the client defaults pre-applied. */
    public fun commandIn(
        dir: Path,
        args: List<String>,
    ): Command = fillDefaults(Command(program, args).workingDir(dir))

    /**
     * Fill the client's defaults into [command] (which the caller owns: a fresh
     * one from [command]/[commandIn], or a [copy] of a caller-supplied command),
     * but only where it has not set them itself — so an explicit per-command
     * setting wins and only the gaps are filled. Mutates and returns [command].
     */
    private fun fillDefaults(command: Command): Command {
        val timeout = defaultTimeout
        if (timeout != null && command.timeoutOrNull == null) {
            command.timeout(timeout)
        }
        for ((name, value) in defaultEnvironment) {
            if (name !in command.environmentOverrides) {
                command.env(name, value)
            }
        }
        return command
    }

    // Defaults are filled into a copy so passing a caller-supplied command to a
    // verb never mutates it (a command reused across clients/calls can't leak one
    // client's defaults into another).
    private fun effective(command: Command): Command = fillDefaults(command.copy())

    /** Require a zero exit and return trimmed stdout. */
    public suspend fun run(command: Command): String = runner.run(effective(command))

    /** Require a zero exit and return trimmed stdout. */
    public suspend fun run(vararg args: String): String = runner.run(command(*args))

    /** Require a zero exit, discarding the output. */
    public suspend fun runUnit(command: Command): Unit = runner.runUnit(effective(command))

    /** Require a zero exit, discarding the output. */
    public suspend fun runUnit(vararg args: String): Unit = runner.runUnit(command(*args))

    /** Capture the full result with stdout as text; a non-zero exit is not an error. */
    public suspend fun outputString(command: Command): ProcessResult<String> = runner.outputString(effective(command))

    /** Capture the full result with stdout as text; a non-zero exit is not an error. */
    public suspend fun outputString(vararg args: String): ProcessResult<String> = runner.outputString(command(*args))

    /** Capture the full result with stdout as raw bytes; a non-zero exit is not an error. */
    public suspend fun outputBytes(command: Command): ProcessResult<ByteArray> = runner.outputBytes(effective(command))

    /** Capture the full result with stdout as raw bytes; a non-zero exit is not an error. */
    public suspend fun outputBytes(vararg args: String): ProcessResult<ByteArray> = runner.outputBytes(command(*args))

    /** The exit code; a timed-out run throws instead of inventing one. */
    public suspend fun exitCode(command: Command): Int = runner.exitCode(effective(command))

    /** The exit code; a timed-out run throws instead of inventing one. */
    public suspend fun exitCode(vararg args: String): Int = runner.exitCode(command(*args))

    /** A yes/no probe: exit `0` → `true`, `1` → `false`, anything else throws. */
    public suspend fun probe(command: Command): Boolean = runner.probe(effective(command))

    /** A yes/no probe: exit `0` → `true`, `1` → `false`, anything else throws. */
    public suspend fun probe(vararg args: String): Boolean = runner.probe(command(*args))

    /**
     * Require a zero exit and feed the (untrimmed) stdout to [transform] — the
     * shape of struct-returning CLI commands. A throwing [transform] propagates
     * (the fallible-parse case); the run honors the command's [Command.retry]
     * policy, but a [transform] failure is not retried.
     */
    public suspend fun <T> parse(
        command: Command,
        transform: (String) -> T,
    ): T = runner.parse(effective(command), transform)

    /** Require a zero exit and feed the (untrimmed) stdout to [transform]. */
    public suspend fun <T> parse(
        vararg args: String,
        transform: (String) -> T,
    ): T = runner.parse(command(*args), transform)
}
