package net.zelanton.processkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // A child that steadily emits stdout lines, so suspend/resume can be proven by
    // observing the output flow stop and restart (the whole tree, not just a root
    // that merely stays alive). On Windows the cmd→ping tree means the *grandchild*
    // ping is the emitter, so a frozen-tree assertion covers the per-thread walk.
    private fun ticker(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "ping -n 30 127.0.0.1")
        } else {
            Command("sh", "-c", "while true; do echo tick; sleep 0.2; done")
        }

    // Collect a run's stdout lines into a channel on an IO thread until cancelled or
    // the stream closes (on teardown).
    private fun CoroutineScope.collectLines(run: RunningProcess): Channel<String> {
        val lines = Channel<String>(Channel.UNLIMITED)
        launch(Dispatchers.IO) {
            try {
                run.stdoutLines().collect { lines.trySend(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                // stream closed on teardown — expected
            }
        }
        return lines
    }

    // After a suspend lands, absorb any lines emitted/buffered just before the freeze.
    private suspend fun Channel<String>.drainAfterSuspend() {
        delay(500)
        while (tryReceive().isSuccess) { /* drop pre-freeze backlog */ }
    }

    private fun descendantsOf(pid: Long): List<Long> =
        ProcessHandle.of(pid).map { handle -> handle.descendants().map { it.pid() }.toList() }.orElse(emptyList())

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
                val grandchild =
                    withTimeoutOrNull(5.seconds) {
                        var kids = descendantsOf(run.pid)
                        while (kids.isEmpty()) {
                            delay(100)
                            kids = descendantsOf(run.pid)
                        }
                        kids.first()
                    }
                assertNotNull(grandchild, "cmd should spawn a ping grandchild")
                val members = group.members()
                assertTrue(run.pid in members, "the cmd root should be a member; members=$members")
                assertTrue(
                    grandchild in members,
                    "the ping grandchild should be in the kernel member list; members=$members",
                )
            }
        }

    @Test
    fun `an empty group reports no members and accepts suspend and resume`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                assertTrue(group.members().isEmpty(), "a fresh group has no members")
                group.suspend() // no-op on an empty job
                group.resume()
            }
        }

    @Test
    fun `a closed group is an idempotent no-op for members, suspend, resume, and signal`() =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                group.start(longRunning())
                group.close()
                // Post-close calls are no-ops, never use-after-free or a thrown exception.
                assertTrue(group.members().isEmpty(), "a closed group has no members")
                group.suspend()
                group.resume()
                group.signal(Signal.Kill)
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
    fun `suspend stalls the whole tree's output until resume`(): Unit =
        runBlocking {
            assumeSupported()
            ProcessGroup().use { group ->
                val run = group.start(ticker())
                val lines = collectLines(run)
                try {
                    assertNotNull(
                        withTimeoutOrNull(10.seconds) { lines.receive() },
                        "the tree should emit before suspend",
                    )
                    group.suspend()
                    lines.drainAfterSuspend()
                    // A frozen tree — root AND grandchild — produces nothing more.
                    assertNull(withTimeoutOrNull(2.seconds) { lines.receive() }, "a frozen tree must not emit")
                    group.resume()
                    assertNotNull(withTimeoutOrNull(10.seconds) { lines.receive() }, "a resumed tree emits again")
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        }

    @Test
    fun `nested suspend needs matching resumes on Windows`(): Unit =
        runBlocking {
            assumeWindows() // per-thread suspend counts; on Unix SIGCONT thaws regardless of depth
            ProcessGroup().use { group ->
                val run = group.start(ticker())
                val lines = collectLines(run)
                try {
                    assertNotNull(
                        withTimeoutOrNull(10.seconds) { lines.receive() },
                        "the tree should emit before suspend",
                    )
                    group.suspend()
                    group.suspend() // per-thread count now 2
                    lines.drainAfterSuspend()
                    group.resume() // count 1 — still frozen
                    assertNull(
                        withTimeoutOrNull(2.seconds) { lines.receive() },
                        "one resume must not thaw two suspends",
                    )
                    group.resume() // count 0 — thawed
                    assertNotNull(withTimeoutOrNull(10.seconds) { lines.receive() }, "a balanced resume thaws the tree")
                } finally {
                    coroutineContext.cancelChildren()
                }
            }
        }

    @Test
    fun `a non-Kill signal is unsupported on Windows`(): Unit =
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
