package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RunProfile + RunningProcess.{cpuTime, peakMemoryBytes, profile}. */
class ProfileTest {
    // --- hermetic: avgCpu math (no subprocess) ---

    @Test
    fun `avgCpu is cpuTime over duration in cores`() {
        assertEquals(0.5, RunProfile(0, 2.seconds, 1.seconds, null, 8).avgCpu())
    }

    @Test
    fun `avgCpu is null without cpu time`() {
        assertNull(RunProfile(0, 1.seconds, null, null, 0).avgCpu())
    }

    @Test
    fun `avgCpu is null without duration`() {
        assertNull(RunProfile(0, Duration.ZERO, 1.seconds, null, 1).avgCpu())
    }

    // --- hermetic: a scripted handle has no OS process, so metrics are null ---

    @Test
    fun `profile on a scripted handle yields null metrics but the exit code`(): Unit =
        runBlocking {
            ScriptedRunner().fallback(Reply.ok("done\n")).start(Command("x")).use { run ->
                val profile = run.profile(5.milliseconds)
                assertEquals(0, profile.exitCode)
                assertNull(profile.cpuTime, "a scripted handle has no OS process")
                assertNull(profile.peakMemoryBytes)
            }
        }

    @Test
    fun `profile reports a null exit code for a timed-out run`(): Unit =
        runBlocking {
            // A pending scripted run never exits on its own; the watchdog kills it.
            ScriptedRunner().fallback(Reply.pending()).start(Command("svc").timeout(80.milliseconds)).use { run ->
                val profile = run.profile(20.milliseconds)
                assertNull(profile.exitCode, "a timed-out run has no exit code")
            }
        }

    // --- real subprocess (Windows + Linux; macOS self-skips) ---

    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "per-process metrics backend")

    private fun briefly(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 2 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 0.4")
        }

    private fun longish(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 10 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 3")
        }

    @Test
    fun `profile samples a real run and reports its metrics`(): Unit =
        runBlocking {
            assumeSupported()
            val profile = briefly().start().use { it.profile(50.milliseconds) }
            assertEquals(0, profile.exitCode)
            assertTrue(profile.samples > 0, "a ~0.5s run sampled every 50ms should tick; samples=${profile.samples}")
            assertNotNull(profile.cpuTime, "Linux/Windows report per-process CPU time")
            val peak = assertNotNull(profile.peakMemoryBytes, "Linux/Windows report peak memory")
            assertTrue(peak > 0, "a real process has resident memory")
            assertTrue(profile.duration > Duration.ZERO)
        }

    @Test
    fun `cpuTime and peakMemoryBytes report on a running child`(): Unit =
        runBlocking {
            assumeSupported()
            longish().start().use { run ->
                delay(200) // let it schedule and allocate
                val peak = assertNotNull(run.peakMemoryBytes, "a running child has a resident set")
                assertTrue(peak > 0)
                assertNotNull(run.cpuTime, "a running child reports CPU time")
            }
        }
}
