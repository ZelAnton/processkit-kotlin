package net.zelanton.processkit

import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ProcessRunner] that serves canned replies — the hermetic test double.
 *
 * Register replies by exact command line or by prefix; the first matching rule
 * wins, else the [fallback]. No subprocess is ever started, so code that shells
 * out can be unit-tested on any OS — through the bulk verbs ([execute] and the
 * `run`/`outputString`/… vocabulary over it) and through [start] (streaming): a
 * scripted [start] hands back a [RunningProcess] whose canned output flows through
 * the same `stdoutLines` / `waitForLine` / `finish` machinery as a real child.
 *
 * ```
 * val runner = ScriptedRunner()
 *     .on("git", "rev-parse", "HEAD", reply = Reply.ok("abc123"))
 *     .onSequence("git", "push", replies = listOf(Reply.fail(1, "rejected"), Reply.ok("pushed")))
 *     .fallback(Reply.fail(1, "unknown command"))
 * ```
 *
 * A scripted handle models a **finite** stream (the canned output is delivered in
 * full); timing-paced and never-exiting scripted streams are not modelled.
 */
public class ScriptedRunner : ProcessRunner {
    private val rules = mutableListOf<Rule>()
    private var fallbackReply: Reply? = null

    /** Match a command whose full command line equals [commandLine]. */
    public fun on(
        vararg commandLine: String,
        reply: Reply,
    ): ScriptedRunner =
        apply {
            val expected = commandLine.toList()
            rules.add(Rule({ line -> line == expected }, listOf(reply)))
        }

    /** Match a command whose command line starts with [prefix]. */
    public fun onPrefix(
        vararg prefix: String,
        reply: Reply,
    ): ScriptedRunner =
        apply {
            val expected = prefix.toList()
            rules.add(
                Rule(
                    { line -> line.size >= expected.size && line.subList(0, expected.size) == expected },
                    listOf(reply),
                ),
            )
        }

    /**
     * Match the exact [commandLine] and serve each of [replies] in turn — the
     * first matching call gets the first reply, and so on; once exhausted, the
     * last reply repeats forever. The declarative form for retry scenarios
     * (fail once, then succeed). Both [execute] and [start] advance the sequence.
     */
    public fun onSequence(
        vararg commandLine: String,
        replies: List<Reply>,
    ): ScriptedRunner =
        apply {
            require(replies.isNotEmpty()) { "onSequence needs at least one reply" }
            val expected = commandLine.toList()
            rules.add(Rule({ line -> line == expected }, replies.toList()))
        }

    /** Reply used when no rule matches. Without one, an unmatched run throws. */
    public fun fallback(reply: Reply): ScriptedRunner = apply { fallbackReply = reply }

    override suspend fun execute(command: Command): ProcessResult<ByteArray> {
        val reply = matchedReply(command)
        // Fire the command's line handlers / tees over the canned output, so
        // progress-reporting code tests hermetically (matching the live pump).
        replayLineHandlers(command, reply.stdout, reply.stderr.encodeToByteArray())
        return ProcessResult(
            program = command.program,
            stdout = reply.stdout,
            stderr = reply.stderr,
            exitCode = reply.exitCode,
            timedOut = reply.timedOut,
        )
    }

    override suspend fun start(command: Command): RunningProcess {
        val reply = matchedReply(command)
        val process = ScriptedProcess(reply.stdout, reply.stderr.encodeToByteArray(), reply.exitCode)
        return RunningProcess(
            process,
            command.program,
            container = null,
            ownsContainer = false,
            command.timeoutOrNull,
            command.stdinSource,
        )
    }

    private fun matchedReply(command: Command): Reply {
        val line = command.commandLine
        return rules.firstOrNull { it.matches(line) }?.nextReply()
            ?: fallbackReply
            // Redacted like `Invocation`: program + arg count, never the argv
            // (so this message is safe even if it reaches a log via a retry).
            ?: throw ProcessException.Spawn(
                command.program,
                IllegalStateException("no scripted reply for `${command.program}` (${line.size - 1} arg(s))"),
            )
    }

    /** A registered match plus the reply (or ordered sequence) it serves. */
    private class Rule(
        val matches: (List<String>) -> Boolean,
        private val replies: List<Reply>,
    ) {
        private val cursor = AtomicInteger(0)

        /** Each call advances the sequence; once exhausted, the last reply repeats. */
        fun nextReply(): Reply = replies[minOf(cursor.getAndIncrement(), replies.size - 1)]
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
