package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Real-subprocess readiness probes (Windows + Linux; macOS self-skips). */
class ProbeTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 6 backend covers Windows + Linux")

    private fun longRunning(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 30")
        }

    // Echo-then-exit: stdout flushes reliably (Windows `cmd` block-buffers a piped
    // stdout for a long-running process, so we don't rely on mid-run streaming here).
    private fun banner(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "echo starting&echo READY")
        } else {
            Command("sh", "-c", "echo starting; echo READY")
        }

    @Test
    fun `waitForLine returns the matching banner line`() =
        runBlocking {
            assumeSupported()
            banner().start().use { run ->
                val line = run.waitForLine(10.seconds) { it.contains("READY") }
                assertTrue(line.contains("READY"))
            }
        }

    @Test
    fun `waitForLine throws NotReady when the line never appears`(): Unit =
        runBlocking {
            assumeSupported()
            val command =
                if (Os.current == Os.WINDOWS) Command("cmd", "/c", "echo nope") else Command("sh", "-c", "echo nope")
            command.start().use { run ->
                assertFailsWith<ProcessException.NotReady> { run.waitForLine(5.seconds) { it.contains("READY") } }
            }
        }

    @Test
    fun `waitForLine times out promptly and leaves the child running`() =
        runBlocking {
            assumeSupported()
            longRunning().start().use { run ->
                val deadline = 500.milliseconds
                assertFailsWith<ProcessException.NotReady> {
                    run.waitForLine(deadline) { it.contains("READY") }
                }
                assertTrue(run.isAlive, "a timed-out probe must not kill the child")
            }
        }

    @Test
    fun `waitForPort host-port overload succeeds once the port accepts`() =
        runBlocking {
            assumeSupported()
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                longRunning().start().use { run ->
                    run.waitForPort(InetAddress.getLoopbackAddress().hostAddress, server.localPort, 10.seconds)
                }
            }
        }

    @Test
    fun `waitForPort succeeds once the port accepts`() =
        runBlocking {
            assumeSupported()
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
                val address = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
                longRunning().start().use { run ->
                    run.waitForPort(address, 10.seconds)
                }
            }
        }

    @Test
    fun `waitForPort throws NotReady for a closed port`(): Unit =
        runBlocking {
            assumeSupported()
            val freePort = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
            longRunning().start().use { run ->
                assertFailsWith<ProcessException.NotReady> {
                    run.waitForPort(InetSocketAddress(InetAddress.getLoopbackAddress(), freePort), 500.milliseconds)
                }
            }
        }

    @Test
    fun `waitUntil passes and times out`(): Unit =
        runBlocking {
            assumeSupported()
            longRunning().start().use { run ->
                run.waitUntil(5.seconds) { true }
                assertFailsWith<ProcessException.NotReady> { run.waitUntil(300.milliseconds) { false } }
            }
        }
}
