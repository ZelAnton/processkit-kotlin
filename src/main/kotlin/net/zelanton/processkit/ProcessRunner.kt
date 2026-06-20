package net.zelanton.processkit

import kotlinx.coroutines.flow.firstOrNull

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

    /**
     * Start [command] and return a live [RunningProcess] for streaming — the
     * streaming half of the seam. The real [JobRunner] (and [ProcessGroup]) spawn a
     * child; a [ScriptedRunner] hands back a scripted handle whose canned output
     * flows through the same machinery, so [firstLine] and streaming code test
     * hermetically. The default throws [ProcessException.Unsupported] for a runner
     * that has no streaming backing (e.g. a [RecordReplayRunner]).
     */
    public suspend fun start(command: Command): RunningProcess =
        throw ProcessException.Unsupported("start (streaming) on ${this::class.simpleName}")
}

/** The full captured outcome (raw bytes); a non-zero exit is not an error here. */
public suspend fun ProcessRunner.outputBytes(command: Command): ProcessResult<ByteArray> = execute(command)

/** The full captured outcome with stdout decoded to text (`\n` line endings). */
public suspend fun ProcessRunner.outputString(command: Command): ProcessResult<String> {
    val result = execute(command)
    return ProcessResult(
        program = result.program,
        stdout = String(result.stdout, command.stdoutCharset).normalizeNewlines(),
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

/**
 * Stream [command]'s stdout and return the first line matching [predicate], **then
 * kill the run** (or return `null` if the stream ends with no match). For scanning
 * a *finite* command's output — contrast [RunningProcess.waitForLine], a readiness
 * probe that leaves the child running and throws on its deadline.
 *
 * Routes through [start], so it works on any runner (a [ScriptedRunner] in tests).
 * It takes no deadline of its own: bound an unbounded stream with [Command.timeout]
 * (the watchdog kills the tree, ending the stream → `null`). A [Command.retry]
 * policy does not apply.
 */
public suspend fun ProcessRunner.firstLine(
    command: Command,
    predicate: (String) -> Boolean,
): String? {
    val run = start(command)
    return try {
        run.stdoutLines().firstOrNull(predicate)
    } finally {
        run.close()
    }
}

/** Normalize captured text to `\n` line endings (CRLF and lone CR → LF). */
internal fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')
