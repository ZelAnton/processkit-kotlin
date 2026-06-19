package net.zelanton.processkit

/**
 * A [ProcessRunner] that serves canned replies — the hermetic test double.
 *
 * Register replies by exact command line or by prefix; the first matching rule
 * wins, else the [fallback]. No subprocess is ever started, so code that shells
 * out can be unit-tested on any OS.
 *
 * ```
 * val runner = ScriptedRunner()
 *     .on("git", "rev-parse", "HEAD", reply = Reply.ok("abc123"))
 *     .fallback(Reply.fail(1, "unknown command"))
 * assertEquals("abc123", runner.run(Command("git", "rev-parse", "HEAD")))
 * ```
 */
public class ScriptedRunner : ProcessRunner {
    private val rules = mutableListOf<Pair<(List<String>) -> Boolean, Reply>>()
    private var fallbackReply: Reply? = null

    /** Match a command whose full command line equals [commandLine]. */
    public fun on(
        vararg commandLine: String,
        reply: Reply,
    ): ScriptedRunner =
        apply {
            val expected = commandLine.toList()
            rules.add({ line: List<String> -> line == expected } to reply)
        }

    /** Match a command whose command line starts with [prefix]. */
    public fun onPrefix(
        vararg prefix: String,
        reply: Reply,
    ): ScriptedRunner =
        apply {
            val expected = prefix.toList()
            rules.add(
                { line: List<String> -> line.size >= expected.size && line.subList(0, expected.size) == expected } to
                    reply,
            )
        }

    /** Reply used when no rule matches. Without one, an unmatched run throws. */
    public fun fallback(reply: Reply): ScriptedRunner = apply { fallbackReply = reply }

    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val line = command.commandLine
        val reply =
            rules.firstOrNull { it.first(line) }?.second
                ?: fallbackReply
                ?: throw ProcessException.Spawn(
                    command.program,
                    IllegalStateException("no scripted reply for ${line.joinToString(" ")}"),
                )
        return ProcessResult(
            program = command.program,
            stdout = reply.stdout,
            stderr = reply.stderr,
            exitCode = reply.exitCode,
            timedOut = reply.timedOut,
        )
    }
}

/** A canned reply for [ScriptedRunner]. Construct via the [Reply.Companion] factories. */
public class Reply private constructor(
    internal val stdout: ByteArray,
    internal val stderr: String,
    internal val exitCode: Int,
    internal val timedOut: Boolean,
) {
    public companion object {
        /** A clean exit (code 0) with [stdout] as text. */
        public fun ok(stdout: String = ""): Reply = Reply(stdout.encodeToByteArray(), "", 0, false)

        /** A clean exit (code 0) with raw [stdout] bytes. */
        public fun okBytes(stdout: ByteArray): Reply = Reply(stdout, "", 0, false)

        /** A non-zero [exitCode] with [stderr] text. */
        public fun fail(
            exitCode: Int,
            stderr: String = "",
        ): Reply = Reply(ByteArray(0), stderr, exitCode, false)

        /** A run that hit its deadline (tree killed). */
        public fun timedOut(stdout: String = ""): Reply = Reply(stdout.encodeToByteArray(), "", 137, true)
    }
}
