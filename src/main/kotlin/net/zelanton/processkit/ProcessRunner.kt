package net.zelanton.processkit

/**
 * The seam every run goes through — and the testing seam.
 *
 * A double implements the single [execute] method to return a canned
 * [ProcessResult]; the run vocabulary ([run], [outputString], [exitCode], …) is
 * derived over it, so the same verbs work against the real [JobRunner] and
 * against a [ScriptedRunner] with no subprocess. Inject a `ProcessRunner` (e.g.
 * via constructor) to make code that shells out hermetically testable.
 */
public interface ProcessRunner {
    /**
     * Run [command] to completion, capturing raw stdout bytes, stderr, and exit.
     *
     * Cancelling the calling coroutine kills the child's whole tree before the
     * `CancellationException` propagates — a cancelled run never leaks a process.
     * A timeout is captured ([ProcessResult.timedOut]), not thrown, at this level.
     */
    public suspend fun execute(command: Command): ProcessResult<ByteArray>
}

/** The full captured outcome (raw bytes); a non-zero exit is not an error here. */
public suspend fun ProcessRunner.outputBytes(command: Command): ProcessResult<ByteArray> = execute(command)

/** The full captured outcome with stdout decoded to text (`\n` line endings). */
public suspend fun ProcessRunner.outputString(command: Command): ProcessResult<String> {
    val result = execute(command)
    return ProcessResult(
        program = result.program,
        stdout = result.stdout.decodeToString().normalizeNewlines(),
        stderr = result.stderr,
        exitCode = result.exitCode,
        timedOut = result.timedOut,
    )
}

/**
 * Require success and return trimmed stdout; throws on a non-zero exit / timeout.
 * Honors the command's [retry][Command.retry] policy.
 */
public suspend fun ProcessRunner.run(command: Command): String =
    retrying(command) { outputString(command).ensureSuccess().stdout.trimEnd() }

/** Require success, discarding the output. Honors [retry][Command.retry]. */
public suspend fun ProcessRunner.runUnit(command: Command) {
    retrying(command) { outputString(command).ensureSuccess() }
}

/**
 * The exit code; a timed-out run throws rather than inventing a code. Honors the
 * command's [retry][Command.retry] policy (which can only trigger on the timeout).
 */
public suspend fun ProcessRunner.exitCode(command: Command): Int =
    retrying(command) {
        val result = execute(command)
        if (result.timedOut) {
            throw ProcessException.Timeout(command.program, command.timeoutOrNull)
        }
        result.exitCode
    }

/**
 * A yes/no probe: exit `0` → `true`, `1` → `false`, anything else throws. Honors
 * the command's [retry][Command.retry] policy.
 */
public suspend fun ProcessRunner.probe(command: Command): Boolean =
    retrying(command) {
        val result = execute(command)
        if (result.timedOut) {
            throw ProcessException.Timeout(command.program, command.timeoutOrNull)
        }
        when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw ProcessException.Exit(command.program, result.exitCode, result.stderr)
        }
    }

/**
 * Require success and feed the captured (untrimmed) stdout to [transform] — the
 * building block for typed CLI wrappers. Honors the command's
 * [retry][Command.retry] policy for the run; a throwing [transform] (the
 * fallible-parse case) propagates and is not retried.
 */
public suspend fun <T> ProcessRunner.parse(
    command: Command,
    transform: (String) -> T,
): T {
    val stdout = retrying(command) { outputString(command).ensureSuccess().stdout }
    return transform(stdout)
}

/** Normalize captured text to `\n` line endings (CRLF and lone CR → LF). */
internal fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')
