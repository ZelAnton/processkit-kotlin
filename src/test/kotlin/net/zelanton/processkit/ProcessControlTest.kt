package net.zelanton.processkit

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real-subprocess process-control (Windows + Linux; macOS self-skips). */
class ProcessControlTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 9 backend covers Windows + Linux")

    private fun assumeUnix() = assumeTrue(Os.current == Os.LINUX, "Unix-only signal semantics")

    private fun assumeWindows() = assumeTrue(Os.current == Os.WINDOWS, "Windows-only behaviour")

    private fun longRunning(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 30")
        }

    // A child that exits on its own in ~1s — used to prove a frozen child does NOT
    // exit on schedule, then runs to completion once resumed.
    private fun shortLived(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 2 127.0.0.1 >nul")
        } else {
            Command("sh", "-c", "sleep 1")
        }

    private fun awaitDead(
        pid: Long,
        timeoutMillis: Long = 5_000,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        val handle = ProcessHandle.of(pid)
        while (System.nanoTime() < deadline && handle.map { it.isAlive }.orElse(false)) {
            Thread.sleep(50)
        }
        assertFalse(handle.map { it.isAlive }.orElse(false), "process $pid should be dead")
    }

    @Test
    fun `members lists the running children`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                group.start(longRunning()).use { run ->
                    delay(200)
                    val members = group.members()
                    assertTrue(run.pid in members, "the child pid should be a group member; members=$members")
                }
            }
        }

    @Test
    fun `members is kernel-authoritative and includes a grandchild on Windows`() =
        runBlocking {
            assumeWindows()
            // cmd.exe spawns ping.exe; the Job Object's pid list reports both, where
            // the old roots-plus-descendants estimate could miss a re-parented grandchild.
            ProcessGroup().use { group ->
                val run = group.start(longRunning())
                val members =
                    withTimeoutOrNull(5.seconds) {
                        var snapshot = group.members()
                        while (snapshot.size < 2) {
                            delay(100)
                            snapshot = group.members()
                        }
                        snapshot
                    }.orEmpty()
                assertTrue(run.pid in members, "the cmd root should be a member; members=$members")
                assertTrue(members.size >= 2, "the ping grandchild should also be a member; members=$members")
            }
        }

    @Test
    fun `signal Kill terminates the tree`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                val pid = group.start(longRunning()).pid
                delay(200)
                group.signal(Signal.Kill)
                awaitDead(pid)
            }
        }

    @Test
    fun `signal Term reaches a Unix child`() =
        runBlocking {
            assumeUnix()
            ProcessGroup().use { group ->
                val pid = group.start(Command("sh", "-c", "sleep 30")).pid
                delay(200)
                group.signal(Signal.Term)
                awaitDead(pid)
            }
        }

    @Test
    fun `suspend freezes a child until resume`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                val run = group.start(shortLived())
                delay(100)
                group.suspend()
                delay(2_000) // well past the ~1s natural runtime: a frozen child must not have exited
                assertTrue(run.isAlive, "a suspended child must not exit while frozen")
                group.resume()
                assertTrue(withTimeoutOrNull(5.seconds) { run.waitFor() } != null, "a resumed child runs to completion")
            }
        }

    @Test
    fun `a non-Kill signal is unsupported on Windows`() =
        runBlocking {
            assumeWindows()
            ProcessGroup().use { group ->
                group.start(longRunning()).use {
                    assertFailsWith<ProcessException.Unsupported> { group.signal(Signal.Term) }
                }
            }
        }

    @Test
    fun `adopt brings an external child under the group`() =
        runBlocking {
            assumeUnix()
            val external = ProcessBuilder("sleep", "30").start()
            val pid = external.pid()
            try {
                ProcessGroup().use { group ->
                    group.adopt(external)
                    assertTrue(pid in group.members(), "the adopted pid should be a member")
                } // close() reaps the adopted child too
                awaitDead(pid)
            } finally {
                external.destroyForcibly()
            }
        }
}
