package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Resource-limit validation (pure) and enforcement / fail-fast (real-subprocess). */
class LimitsTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 11 backend covers Windows + Linux")

    private fun assumeUnix() = assumeTrue(Os.current == Os.LINUX, "process-group backend can't enforce limits")

    private fun assumeWindows() = assumeTrue(Os.current == Os.WINDOWS, "Job Object enforcement")

    @Test
    fun `rejects non-positive caps`() {
        assertFailsWith<IllegalArgumentException> { ResourceLimits(memoryMax = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxProcesses = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(cpuQuota = 0.0) }
    }

    @Test
    fun `a group with no limits is created on any backend`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { }
            ProcessGroup(ResourceLimits()).use { }
        }

    @Test
    fun `the process-group backend rejects any limit`() =
        runBlocking {
            assumeUnix()
            assertFailsWith<ProcessException.ResourceLimit> { ProcessGroup(ResourceLimits(maxProcesses = 4)) }
            assertFailsWith<ProcessException.ResourceLimit> {
                ProcessGroup(
                    ResourceLimits(memoryMax = 64L * 1024 * 1024),
                )
            }
        }

    @Test
    fun `cpuQuota is not yet enforced and fails fast`() =
        runBlocking {
            assumeSupported()
            assertFailsWith<ProcessException.ResourceLimit> { ProcessGroup(ResourceLimits(cpuQuota = 0.5)) }
        }

    @Test
    fun `Windows enforces a max-process cap`() =
        runBlocking {
            assumeWindows()
            ProcessGroup(ResourceLimits(maxProcesses = 1)).use { group ->
                group.start(Command("ping", "-n", "30", "127.0.0.1")).use {
                    delay(200)
                    // The job is at its 1-process limit, so a second assignment is rejected.
                    assertFailsWith<ProcessException> { group.start(Command("ping", "-n", "30", "127.0.0.1")) }
                }
            }
        }

    @Test
    fun `Windows accepts a memory cap`() =
        runBlocking {
            assumeWindows()
            ProcessGroup(ResourceLimits(memoryMax = 256L * 1024 * 1024)).use { group ->
                group.run(Command("cmd", "/c", "echo ok"))
            }
        }
}
