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
    /** Run [command] to completion, capturing raw stdout bytes, stderr, and exit. */
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

/** Require success and return trimmed stdout; throws on a non-zero exit / timeout. */
public suspend fun ProcessRunner.run(command: Command): String = outputString(command).ensureSuccess().stdout.trimEnd()

/** Require success, discarding the output. */
public suspend fun ProcessRunner.runUnit(command: Command) {
    outputString(command).ensureSuccess()
}

/** The exit code; a timed-out run throws rather than inventing a code. */
public suspend fun ProcessRunner.exitCode(command: Command): Int {
    val result = execute(command)
    if (result.timedOut) {
        throw ProcessException.Timeout(command.program, command.timeoutOrNull)
    }
    return result.exitCode
}

/** A yes/no probe: exit `0` → `true`, `1` → `false`, anything else throws. */
public suspend fun ProcessRunner.probe(command: Command): Boolean {
    val result = execute(command)
    if (result.timedOut) {
        throw ProcessException.Timeout(command.program, command.timeoutOrNull)
    }
    return when (result.exitCode) {
        0 -> true
        1 -> false
        else -> throw ProcessException.Exit(command.program, result.exitCode, result.stderr)
    }
}

/** Normalize captured text to `\n` line endings (CRLF and lone CR → LF). */
internal fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')
