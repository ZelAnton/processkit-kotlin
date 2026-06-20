package net.zelanton.processkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The streaming `ProcessRunner` seam, exercised hermetically through [ScriptedRunner]. */
class StreamingSeamTest {
    @Test
    fun `scripted start streams canned lines through stdoutLines`() =
        runTest {
            val runner = ScriptedRunner().on("git", "log", reply = Reply.ok("first\nsecond\nthird\n"))
            runner.start(Command("git", "log")).use { run ->
                assertEquals(ScriptedProcess.NO_PID, run.pid, "a scripted handle has no OS pid")
                assertEquals(listOf("first", "second", "third"), run.stdoutLines().toList())
                assertEquals(0, run.finish().exitCode)
            }
        }

    @Test
    fun `scripted finish reports the canned exit and stderr`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.fail(7, "boom\n"))
            runner.start(Command("svc")).use { run ->
                val finished = run.finish()
                assertEquals(7, finished.exitCode)
                assertEquals("boom", finished.stderr.trim())
                assertFalse(finished.isSuccess)
            }
        }

    @Test
    fun `scripted waitForLine matches a canned banner`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.ok("starting\nREADY\n"))
            runner.start(Command("svc")).use { run ->
                assertTrue(run.waitForLine(5.seconds) { it.contains("READY") }.contains("READY"))
            }
        }

    @Test
    fun `firstLine finds the first matching line via the scripted seam`() =
        runTest {
            val runner = ScriptedRunner().on("git", "log", reply = Reply.ok("alpha\nbeta ready\ngamma\n"))
            assertEquals("beta ready", runner.firstLine(Command("git", "log")) { it.contains("ready") })
        }

    @Test
    fun `firstLine returns null when no line matches`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.ok("a\nb\nc\n"))
            assertNull(runner.firstLine(Command("x")) { it.contains("zzz") })
        }

    @Test
    fun `CliClient firstLine routes through the runner`() =
        runTest {
            val client = CliClient("git", ScriptedRunner().on("git", "log", reply = Reply.ok("one\ntwo\nthree\n")))
            assertEquals("two", client.firstLine("log") { it.startsWith("t") })
        }

    @Test
    fun `onSequence serves replies in order then repeats the last`() =
        runTest {
            val runner =
                ScriptedRunner().onSequence(
                    "git",
                    "push",
                    replies = listOf(Reply.fail(1, "rejected"), Reply.ok("pushed")),
                )
            assertEquals(1, runner.outputString(Command("git", "push")).exitCode)
            assertEquals("pushed", runner.outputString(Command("git", "push")).stdout.trim())
            assertEquals("pushed", runner.outputString(Command("git", "push")).stdout.trim())
        }

    @Test
    fun `retry through onSequence succeeds on the second attempt`() =
        runTest {
            val onExit: (ProcessException) -> Boolean = { it is ProcessException.Exit }
            val runner = ScriptedRunner().onSequence("flaky", replies = listOf(Reply.fail(1), Reply.ok("done")))
            assertEquals(
                "done",
                runner.run(Command("flaky").retry(maxAttempts = 2, backoff = Duration.ZERO, retryIf = onExit)),
            )
        }

    @Test
    fun `RecordingRunner records a start invocation`() =
        runTest {
            val rec = RecordingRunner(ScriptedRunner().fallback(Reply.ok("x")))
            rec.start(Command("gh", "run", "watch")).use { }
            assertEquals(listOf("run", "watch"), rec.onlyCall().args)
        }

    @Test
    fun `the default start throws Unsupported`() =
        runTest {
            val executeOnly =
                object : ProcessRunner {
                    override suspend fun execute(command: Command): ProcessResult<ByteArray> =
                        ProcessResult(command.program, ByteArray(0), "", 0, false)
                }
            assertFailsWith<ProcessException.Unsupported> { executeOnly.start(Command("x")) }
        }
}
