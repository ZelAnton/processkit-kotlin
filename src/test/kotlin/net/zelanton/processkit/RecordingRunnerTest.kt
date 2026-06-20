package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Input assertions via [RecordingRunner] / [Invocation]. */
class RecordingRunnerTest {
    @Test
    fun `captures program args cwd and flag absence`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok("https://gh/pr/2\n"))
            val client = CliClient("gh", rec)
            client.run(client.commandIn(Path.of("/repo"), "pr", "create", "--title", "T"))

            val call = rec.onlyCall()
            assertEquals("gh", call.program)
            assertEquals(listOf("pr", "create", "--title", "T"), call.args)
            assertEquals(Path.of("/repo"), call.workingDir)
            assertTrue(call.hasFlag("--title"))
            assertFalse(call.hasFlag("--base"), "no --base flag was passed")
            assertFalse(call.hasStdin)
        }

    @Test
    fun `records that stdin was supplied`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok())
            rec.run(Command("sort").stdin(Stdin.fromString("b\na\n")))
            assertTrue(rec.onlyCall().hasStdin)
        }

    @Test
    fun `records the default env that reaches the invocation`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok("ok\n"))
            val client = CliClient("git", rec).defaultEnv("GIT_TERMINAL_PROMPT", "0")
            client.run("status")
            assertEquals("0", rec.onlyCall().environment["GIT_TERMINAL_PROMPT"])
        }

    @Test
    fun `records one invocation per retry attempt`() =
        runTest {
            val onExit: (ProcessException) -> Boolean = { it is ProcessException.Exit }
            val rec = RecordingRunner(ScriptedRunner().fallback(Reply.fail(1, "boom")))
            val command = Command("flaky").retry(maxAttempts = 3, backoff = Duration.ZERO, retryIf = onExit)
            assertFailsWith<ProcessException.Exit> { rec.run(command) }
            assertEquals(3, rec.calls.size)
        }

    @Test
    fun `onlyCall requires exactly one call`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok())
            assertFailsWith<IllegalStateException> { rec.onlyCall() }
            rec.run(Command("a"))
            rec.run(Command("b"))
            assertEquals(2, rec.calls.size)
            assertFailsWith<IllegalStateException> { rec.onlyCall() }
        }

    @Test
    fun `toString redacts argv and env values but keeps env names`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok())
            rec.run(Command("git", "--token=secret123").env("API_KEY", "topsecret-value"))
            val text = rec.onlyCall().toString()
            assertFalse(text.contains("secret123"), "argv values must not appear: $text")
            assertFalse(text.contains("topsecret-value"), "env values must not appear: $text")
            assertTrue(text.contains("API_KEY"), "env names should appear: $text")
        }
}
