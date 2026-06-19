package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hermetic verb semantics, driven through [ScriptedRunner] — no subprocess. */
class CommandVerbsTest {
    @Test
    fun `outputString captures stdout, stderr and exit - non-zero is data`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.fail(1, "nope"))
            val result = runner.outputString(Command("tool"))
            assertEquals(1, result.exitCode)
            assertEquals("nope", result.stderr)
            assertFalse(result.isSuccess)
        }

    @Test
    fun `run trims trailing whitespace and keeps leading`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.ok("  v1.2\n\n"))
            assertEquals("  v1.2", runner.run(Command("tool")))
        }

    @Test
    fun `run throws Exit on a non-zero exit`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.fail(2, "boom"))
            val error = assertFailsWith<ProcessException.Exit> { runner.run(Command("tool")) }
            assertEquals(2, error.exitCode)
        }

    @Test
    fun `exitCode returns the code`() =
        runTest {
            assertEquals(3, ScriptedRunner().fallback(Reply.fail(3)).exitCode(Command("tool")))
        }

    @Test
    fun `probe maps 0 to true, 1 to false, and throws otherwise`() =
        runTest {
            assertTrue(ScriptedRunner().fallback(Reply.ok()).probe(Command("t")))
            assertFalse(ScriptedRunner().fallback(Reply.fail(1)).probe(Command("t")))
            assertFailsWith<ProcessException.Exit> { ScriptedRunner().fallback(Reply.fail(2)).probe(Command("t")) }
        }

    @Test
    fun `a timed-out reply is captured and raised appropriately`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.timedOut())
            assertTrue(runner.outputString(Command("t")).timedOut)
            assertFailsWith<ProcessException.Timeout> { runner.run(Command("t")) }
            assertFailsWith<ProcessException.Timeout> { runner.exitCode(Command("t")) }
        }

    @Test
    fun `rules match by exact command line and by prefix - first match wins`() =
        runTest {
            val runner =
                ScriptedRunner()
                    .on("git", "rev-parse", "HEAD", reply = Reply.ok("abc123"))
                    .onPrefix("git", reply = Reply.fail(1, "other git"))
                    .fallback(Reply.fail(127, "unknown"))
            assertEquals("abc123", runner.run(Command("git", "rev-parse", "HEAD")))
            assertEquals(1, runner.outputString(Command("git", "status")).exitCode)
            assertEquals(127, runner.outputString(Command("ls")).exitCode)
        }

    @Test
    fun `outputBytes returns raw stdout bytes`() =
        runTest {
            val bytes = byteArrayOf(0, 1, 2, 3, 65, 66)
            val result = ScriptedRunner().fallback(Reply.okBytes(bytes)).outputBytes(Command("t"))
            assertContentEquals(bytes, result.stdout)
            assertEquals(0, result.exitCode)
        }

    @Test
    fun `outputString normalizes CRLF to LF`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.ok("a\r\nb\r\n"))
            assertEquals("a\nb\n", runner.outputString(Command("t")).stdout)
        }

    @Test
    fun `an unmatched command without a fallback throws`() =
        runTest {
            assertFailsWith<ProcessException.Spawn> { ScriptedRunner().execute(Command("t")) }
        }
}
