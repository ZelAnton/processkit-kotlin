package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Command.envRemove / CliClient.defaultEnvRemove — the env-removal model. */
class EnvRemoveTest {
    @Test
    fun `envRemove records a null override`() {
        val overrides = Command("x").env("KEEP", "1").envRemove("DROP").environmentOverrides
        assertEquals("1", overrides["KEEP"])
        assertTrue("DROP" in overrides)
        assertNull(overrides["DROP"])
    }

    @Test
    fun `the last env op for a key wins`() {
        assertNull(Command("x").env("A", "1").envRemove("A").environmentOverrides["A"])
        assertEquals("1", Command("x").envRemove("A").env("A", "1").environmentOverrides["A"])
    }

    @Test
    fun `defaultEnvRemove fills a removal that a per-command env overrides`() =
        runTest {
            val rec = RecordingRunner.replying(Reply.ok())
            val cli = CliClient("git", rec).defaultEnvRemove("GIT_PAGER").defaultEnv("GIT_TERMINAL_PROMPT", "0")
            // One command leaves GIT_PAGER to the default removal; another sets it explicitly.
            cli.runUnit("status")
            cli.runUnit(cli.command("log").env("GIT_PAGER", "less"))

            val (statusCall, logCall) = rec.calls
            assertTrue("GIT_PAGER" in statusCall.environment)
            assertNull(statusCall.environment["GIT_PAGER"], "the client default removes GIT_PAGER")
            assertEquals("0", statusCall.environment["GIT_TERMINAL_PROMPT"])
            assertEquals("less", logCall.environment["GIT_PAGER"], "a per-command env wins over the default removal")
        }

    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "real-subprocess backend")

    // Echo PKVAR's value, or "MISSING" if the child did not inherit/receive it.
    private fun echoVar(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "if defined PKVAR (echo %PKVAR%) else (echo MISSING)")
        } else {
            Command("sh", "-c", "printf %s \"\${PKVAR-MISSING}\"")
        }

    @Test
    fun `envRemove drops a variable the child would otherwise receive`() =
        runBlocking {
            assumeSupported()
            val present =
                echoVar()
                    .env("PKVAR", "yes")
                    .outputString()
                    .stdout
                    .trim()
            val removed =
                echoVar()
                    .env("PKVAR", "yes")
                    .envRemove("PKVAR")
                    .outputString()
                    .stdout
                    .trim()
            assertEquals("yes", present)
            assertEquals("MISSING", removed, "envRemove must win over the env that set it")
        }
}
