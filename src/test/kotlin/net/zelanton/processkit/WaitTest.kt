package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real-subprocess waitFor / waitAny / waitAll (Windows + Linux; macOS self-skips). */
class WaitTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 3 backend covers Windows + Linux")

    private fun shell(script: String): Command =
        if (Os.current == Os.WINDOWS) Command("cmd", "/c", script) else Command("sh", "-c", script)

    private fun longRunning(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 30")
        }

    @Test
    fun `waitFor returns the exit code`() =
        runBlocking {
            assumeSupported()
            shell("exit 5").start().use { assertEquals(5, it.waitFor()) }
        }

    @Test
    fun `waitAny returns the index of the first to exit`() =
        runBlocking {
            assumeSupported()
            val quick = shell("exit 0").start()
            val slow = longRunning().start()
            try {
                assertEquals(0, waitAny(quick, slow))
            } finally {
                quick.close()
                slow.close()
            }
        }

    @Test
    fun `waitAll returns exit codes in order`() =
        runBlocking {
            assumeSupported()
            val first = shell("exit 0").start()
            val second = shell("exit 3").start()
            try {
                assertEquals(listOf(0, 3), waitAll(first, second))
            } finally {
                first.close()
                second.close()
            }
        }
}
