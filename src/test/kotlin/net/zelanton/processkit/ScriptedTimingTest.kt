package net.zelanton.processkit

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reply.pending / Reply.withLineDelay — hermetic timing behaviour (no real
 * subprocess, so these run on every OS). The bulk-verb pending tests resolve on a
 * virtual clock ([runTest]); the streaming tests use small real delays because the
 * [RunningProcess] watchdog and the paced stream run on [Dispatchers.IO].
 */
class ScriptedTimingTest {
    @Test
    fun `a pending bulk run surfaces a Command timeout as timed out`() =
        runTest {
            val runner = ScriptedRunner().on("svc", reply = Reply.pending())
            val result = runner.outputString(Command("svc").timeout(50.milliseconds))
            assertTrue(result.timedOut)
        }

    @Test
    fun `a pending bulk run with no timeout parks until the caller cancels`() =
        runTest {
            val runner = ScriptedRunner().on("svc", reply = Reply.pending())
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(50.milliseconds) { runner.outputString(Command("svc")) }
            }
        }

    @Test
    fun `a pending streamed run is reaped by the timeout watchdog`() =
        runBlocking {
            val runner = ScriptedRunner().on("server", reply = Reply.pending())
            runner.start(Command("server").timeout(80.milliseconds)).use { run ->
                val finished = run.finish()
                assertTrue(finished.timedOut, "the watchdog should kill a never-exiting scripted child")
            }
        }

    @Test
    fun `a line-delayed stream still delivers every line`() =
        runBlocking {
            val runner = ScriptedRunner().on("log", reply = Reply.ok("a\nb\nc\n").withLineDelay(20.milliseconds))
            runner.start(Command("log")).use { run ->
                assertEquals(listOf("a", "b", "c"), run.stdoutLines().toList())
            }
        }

    @Test
    fun `a timeout shorter than the line-delay lifetime kills mid-stream`() =
        runBlocking {
            // 5 lines * 50ms = 250ms of lifetime; an 70ms deadline fires well before that.
            val runner = ScriptedRunner().on("log", reply = Reply.ok("a\nb\nc\nd\ne\n").withLineDelay(50.milliseconds))
            runner.start(Command("log").timeout(70.milliseconds)).use { run ->
                val finished = run.finish()
                assertTrue(finished.timedOut, "the watchdog should kill a still-streaming scripted child")
            }
        }
}
