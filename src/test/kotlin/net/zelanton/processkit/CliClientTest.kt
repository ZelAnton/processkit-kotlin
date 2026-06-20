package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Hermetic CliClient behaviour, driven through [ScriptedRunner] / [RecordingRunner]. */
class CliClientTest {
    @Test
    fun `run accepts an arg list or a customized command`() =
        runTest {
            val runner = ScriptedRunner().on("git", "status", reply = Reply.ok("clean"))
            val client = CliClient("git", runner)
            assertEquals("clean", client.run("status"))
            assertEquals("clean", client.run(client.command("status")))
        }

    @Test
    fun `run trims trailing whitespace only`() =
        runTest {
            val runner = ScriptedRunner().on("git", "rev-parse", "HEAD", reply = Reply.ok("  abc123 \n"))
            assertEquals("  abc123", CliClient("git", runner).run("rev-parse", "HEAD"))
        }

    @Test
    fun `exitCode and probe map the exit status`() =
        runTest {
            val runner =
                ScriptedRunner()
                    .on("git", "diff", "--quiet", reply = Reply.fail(1, ""))
                    .fallback(Reply.ok())
            val client = CliClient("git", runner)
            assertEquals(1, client.exitCode("diff", "--quiet"))
            assertFalse(client.probe("diff", "--quiet"))
            assertTrue(client.probe("status"))
        }

    @Test
    fun `parse builds a typed value`() =
        runTest {
            val runner = ScriptedRunner().on("git", "branch", reply = Reply.ok("main\nfeature\n"))
            val branches = CliClient("git", runner).parse("branch") { out -> out.lines().filter { it.isNotBlank() } }
            assertEquals(listOf("main", "feature"), branches)
        }

    @Test
    fun `parse propagates a throwing transform`() =
        runTest {
            val client = CliClient("gh", ScriptedRunner().fallback(Reply.ok("not a number")))
            assertFailsWith<NumberFormatException> { client.parse("x") { it.trim().toInt() } }
        }

    @Test
    fun `default timeout is applied to every built command`() =
        runTest {
            val client = CliClient("git").defaultTimeout(7.seconds)
            assertEquals(7.seconds, client.command("status").timeoutOrNull)
            assertEquals(7.seconds, client.commandIn(Path.of("."), "fetch").timeoutOrNull)
        }

    @Test
    fun `default env is applied to every built command`() =
        runTest {
            val client = CliClient("git").defaultEnv("GIT_TERMINAL_PROMPT", "0")
            assertEquals("0", client.command("status").environmentOverrides["GIT_TERMINAL_PROMPT"])
        }

    @Test
    fun `a prebuilt command keeps its explicit env, fills the gaps, and is not mutated`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok())
            val client = CliClient("git", rec).defaultEnv("GIT_TERMINAL_PROMPT", "0").defaultEnv("EXTRA", "x")
            val custom = Command("git", "push").env("GIT_TERMINAL_PROMPT", "1")
            client.run(custom)
            val call = rec.onlyCall()
            assertEquals("1", call.environment["GIT_TERMINAL_PROMPT"], "an explicit per-command env wins")
            assertEquals("x", call.environment["EXTRA"], "the client default fills the gap")
            assertEquals(
                mapOf("GIT_TERMINAL_PROMPT" to "1"),
                custom.environmentOverrides,
                "running through the client must not mutate the caller's command",
            )
        }

    @Test
    fun `a command reused across clients does not leak the first clients default`() =
        runTest {
            val recA = RecordingRunner.replying(Reply.ok())
            val recB = RecordingRunner.replying(Reply.ok())
            val cmd = Command("git", "push")
            CliClient("git", recA).defaultEnv("TOKEN", "secretA").run(cmd)
            CliClient("git", recB).defaultEnv("TOKEN", "secretB").run(cmd)
            assertEquals("secretA", recA.onlyCall().environment["TOKEN"])
            assertEquals("secretB", recB.onlyCall().environment["TOKEN"], "client B must not inherit A's default")
        }

    @Test
    fun `commandIn sets the working directory`() =
        runTest {
            val command = CliClient("git").commandIn(Path.of("/repo"), "status")
            assertEquals(Path.of("/repo"), command.workingDirectory)
        }
}
