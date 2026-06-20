package net.zelanton.processkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Real-subprocess run-and-capture through [JobRunner]. Covers Windows (Job
 * Object) and Linux (process group); self-skips on macOS until its native
 * backend lands.
 */
class JobRunnerTest {
    private fun assumeSupported() =
        assumeTrue(
            Os.current == Os.WINDOWS || Os.current == Os.LINUX,
            "step 1 backend covers Windows + Linux",
        )

    private fun shell(script: String): Command =
        if (Os.current == Os.WINDOWS) Command("cmd", "/c", script) else Command("sh", "-c", script)

    @Test
    fun `captures stdout and a clean exit`() =
        runBlocking {
            assumeSupported()
            val result = shell("echo hello").outputString()
            assertEquals("hello", result.stdout.trim())
            assertEquals(0, result.exitCode)
            assertTrue(result.isSuccess)
        }

    @Test
    fun `a non-zero exit is captured as data`() =
        runBlocking {
            assumeSupported()
            val result = shell("exit 3").outputString()
            assertEquals(3, result.exitCode)
            assertFalse(result.isSuccess)
        }

    @Test
    fun `run returns trimmed stdout`() =
        runBlocking {
            assumeSupported()
            assertEquals("hi", shell("echo hi").run())
        }

    @Test
    fun `run throws Exit on a non-zero exit`(): Unit =
        runBlocking {
            assumeSupported()
            assertFailsWith<ProcessException.Exit> { shell("exit 7").run() }
        }

    @Test
    fun `an environment override reaches the child`() =
        runBlocking {
            assumeSupported()
            val script = if (Os.current == Os.WINDOWS) "echo %PK_VAR%" else "echo \$PK_VAR"
            val result = shell(script).env("PK_VAR", "fortytwo").outputString()
            assertEquals("fortytwo", result.stdout.trim())
        }

    @Test
    fun `the working directory is honored`() =
        runBlocking {
            assumeSupported()
            val dir = Files.createTempDirectory("pk-test")
            try {
                val script = if (Os.current == Os.WINDOWS) "cd" else "pwd"
                val result = shell(script).workingDir(dir).outputString()
                assertEquals(dir.toRealPath(), Path.of(result.stdout.trim()).toRealPath())
            } finally {
                Files.deleteIfExists(dir)
            }
        }

    @Test
    fun `a timeout kills the run and is captured promptly`() =
        runBlocking {
            assumeSupported()
            val script = if (Os.current == Os.WINDOWS) "ping -n 20 127.0.0.1 >nul" else "sleep 20"
            val start = System.nanoTime()
            val result = shell(script).timeout(800.milliseconds).outputString()
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000
            assertTrue(result.timedOut, "should be marked timed out")
            assertTrue(elapsedMillis < 10_000, "should return shortly after the deadline, took ${elapsedMillis}ms")
        }

    @Test
    fun `cancelling the awaiting coroutine interrupts the run promptly`() =
        runBlocking {
            assumeSupported()
            val script = if (Os.current == Os.WINDOWS) "ping -n 30 127.0.0.1 >nul" else "sleep 30"
            val job = launch(Dispatchers.Default) { shell(script).outputString() }
            delay(700) // let the process start
            val elapsedMillis = measureTimeMillis { job.cancelAndJoin() }
            assertTrue(job.isCancelled, "the run's coroutine should be cancelled")
            assertTrue(elapsedMillis < 10_000, "cancellation should be prompt, took ${elapsedMillis}ms")
        }

    @Test
    fun `a missing program is reported as NotFound`(): Unit =
        runBlocking {
            // Uniform NotFound on Unix needs the posix_spawn backend (the setsid
            // launcher masks the exec failure as an exit code), so assert on Windows.
            assumeTrue(Os.current == Os.WINDOWS, "uniform NotFound needs the Unix posix_spawn backend")
            assertFailsWith<ProcessException.NotFound> { Command("pk-no-such-binary-zzz").outputString() }
        }
}
