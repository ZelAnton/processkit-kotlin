package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Real-subprocess streaming via [RunningProcess] (Windows + Linux; macOS self-skips). */
class RunningProcessTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 3 backend covers Windows + Linux")

    private fun threeLines(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "echo a&echo b&echo c")
        } else {
            Command("sh", "-c", "printf 'a\\nb\\nc\\n'")
        }

    private fun longRunning(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 30")
        }

    @Test
    fun `streams stdout line by line and finishes cleanly`() =
        runBlocking {
            assumeSupported()
            threeLines().start().use { run ->
                val lines = run.stdoutLines().toList().map { it.trim() }
                assertEquals(listOf("a", "b", "c"), lines)
                val finished = run.finish()
                assertEquals(0, finished.exitCode)
                assertTrue(finished.isSuccess)
            }
        }

    @Test
    fun `finish without streaming still returns the result`() =
        runBlocking {
            assumeSupported()
            threeLines().start().use { run ->
                assertEquals(0, run.finish().exitCode)
            }
        }

    @Test
    fun `finish is idempotent`() =
        runBlocking {
            assumeSupported()
            threeLines().start().use { run ->
                val first = run.finish()
                val second = run.finish()
                assertEquals(first.exitCode, second.exitCode)
                assertEquals(first.stderr, second.stderr)
            }
        }

    @Test
    fun `stderr is captured in the background`() =
        runBlocking {
            assumeSupported()
            val command =
                if (Os.current == Os.WINDOWS) {
                    Command("cmd", "/c", "echo oops 1>&2")
                } else {
                    Command("sh", "-c", "echo oops 1>&2")
                }
            command.start().use { run ->
                assertEquals("oops", run.finish().stderr.trim())
            }
        }

    @Test
    fun `a timeout bounds the stream and is captured`() =
        runBlocking {
            assumeSupported()
            longRunning().timeout(800.milliseconds).start().use { run ->
                run.stdoutLines().toList() // ends when the watchdog kills the tree
                assertTrue(run.finish().timedOut)
            }
        }

    @Test
    fun `close kills a streaming child`() =
        runBlocking {
            assumeSupported()
            val run = longRunning().start()
            val pid = run.pid
            delay(300)
            assertTrue(run.isAlive)
            run.close()
            val handle = ProcessHandle.of(pid)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && handle.map { it.isAlive }.orElse(false)) {
                Thread.sleep(50)
            }
            assertFalse(handle.map { it.isAlive }.orElse(false), "the child should be dead after close()")
        }

    @Test
    fun `start within a group streams and finishes`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                group.start(threeLines()).use { run ->
                    assertEquals(listOf("a", "b", "c"), run.stdoutLines().toList().map { it.trim() })
                    assertEquals(0, run.finish().exitCode)
                }
            }
        }
}
