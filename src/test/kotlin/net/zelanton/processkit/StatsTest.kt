package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Real-subprocess group stats (Windows + Linux; macOS self-skips). */
class StatsTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 10 backend covers Windows + Linux")

    private fun assumeUnix() = assumeTrue(Os.current == Os.LINUX, "process-group backend semantics")

    private fun assumeWindows() = assumeTrue(Os.current == Os.WINDOWS, "Job Object accounting")

    private fun longRunning(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 30")
        }

    @Test
    fun `stats reports the active process count`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                group.start(longRunning()).use {
                    delay(200)
                    assertTrue(group.stats().activeProcessCount >= 1, "at least one live process expected")
                }
            }
        }

    @Test
    fun `stats reports cpu and memory on Windows`() =
        runBlocking {
            assumeWindows()
            ProcessGroup().use { group ->
                group.start(longRunning()).use {
                    delay(200)
                    val stats = group.stats()
                    assertNotNull(stats.totalCpuTime, "Job Object reports CPU time")
                    assertNotNull(stats.peakMemoryBytes, "Job Object reports peak memory")
                }
            }
        }

    @Test
    fun `cpu and memory are unreported on the process-group backend`() =
        runBlocking {
            assumeUnix()
            ProcessGroup().use { group ->
                group.start(longRunning()).use {
                    val stats = group.stats()
                    assertNull(stats.totalCpuTime, "no kernel CPU accounting without a cgroup")
                    assertNull(stats.peakMemoryBytes, "no kernel memory accounting without a cgroup")
                }
            }
        }

    @Test
    fun `sampleStats emits a series of snapshots`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                group.start(longRunning()).use {
                    val samples = group.sampleStats(50.milliseconds).take(2).toList()
                    assertEquals(2, samples.size)
                    assertTrue(samples.all { it.activeProcessCount >= 0 })
                }
            }
        }
}
