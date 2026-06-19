package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Real-subprocess ProcessGroup behavior (Windows + Linux; macOS self-skips). */
class ProcessGroupTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 2 backend covers Windows + Linux")

    private fun shell(script: String): Command =
        if (Os.current == Os.WINDOWS) Command("cmd", "/c", script) else Command("sh", "-c", script)

    private fun longRunningScript(): String = if (Os.current == Os.WINDOWS) "ping -n 30 127.0.0.1 >nul" else "sleep 30"

    @Test
    fun `runs commands in a shared group and reports its mechanism`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                assertTrue(group.mechanism == Mechanism.JOB_OBJECT || group.mechanism == Mechanism.PROCESS_GROUP)
                assertEquals("hi", group.run(shell("echo hi")))
                assertEquals(0, group.outputString(shell("echo x")).exitCode)
            }
        }

    @Test
    fun `close reaps an in-flight child promptly`() =
        runBlocking {
            assumeSupported()
            val group = ProcessGroup()
            val job = launch(Dispatchers.Default) { runCatching { group.run(shell(longRunningScript())) } }
            delay(700) // let the child start
            val elapsedMillis =
                measureTimeMillis {
                    group.close()
                    job.join()
                }
            assertTrue(
                elapsedMillis < 10_000,
                "close should reap the in-flight child promptly, took ${elapsedMillis}ms",
            )
        }

    @Test
    fun `shutdown tears the group down without hanging`() =
        runBlocking {
            assumeSupported()
            val group = ProcessGroup()
            val job = launch(Dispatchers.Default) { runCatching { group.run(shell(longRunningScript())) } }
            delay(500)
            val elapsedMillis =
                measureTimeMillis {
                    group.shutdown(200.milliseconds)
                    job.join()
                }
            assertTrue(elapsedMillis < 10_000, "shutdown should complete promptly, took ${elapsedMillis}ms")
        }

    @Test
    fun `a per-run timeout kills that child and is captured`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                val result = group.outputString(shell(longRunningScript()).timeout(800.milliseconds))
                assertTrue(result.timedOut, "the run should be marked timed out")
            }
        }

    @Test
    fun `outputAll can share one group`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                val results = outputAll(listOf(shell("echo 1"), shell("echo 2")), concurrency = 2, runner = group)
                assertEquals(2, results.count { it.isSuccess })
            }
        }
}
