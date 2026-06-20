package net.zelanton.processkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** Real-subprocess merged stdout+stderr event stream (Windows + Linux; macOS self-skips). */
class OutputEventsTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 3f backend covers Windows + Linux")

    // Emits out1 (stdout), err1 (stderr), out2 (stdout).
    private fun outAndErr(): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "echo out1& echo err1 1>&2& echo out2")
        } else {
            Command("sh", "-c", "echo out1; echo err1 1>&2; echo out2")
        }

    @Test
    fun `outputEvents merges stdout and stderr tagged by source`() =
        runBlocking {
            assumeSupported()
            outAndErr().start().use { run ->
                val events = run.outputEvents().toList()
                val stdout = events.filterIsInstance<OutputEvent.Stdout>().map { it.line.trim() }
                val stderr = events.filterIsInstance<OutputEvent.Stderr>().map { it.line.trim() }
                // Within-stream order is preserved; cross-stream interleaving is not.
                assertEquals(listOf("out1", "out2"), stdout)
                assertEquals(listOf("err1"), stderr)
            }
        }

    @Test
    fun `finish after outputEvents reports the exit code and an empty stderr`() =
        runBlocking {
            assumeSupported()
            outAndErr().start().use { run ->
                run.outputEvents().toList()
                val finished = run.finish()
                assertEquals(0, finished.exitCode)
                assertEquals("", finished.stderr, "the event stream already carried stderr")
            }
        }

    @Test
    fun `outputEvents and stdoutLines are mutually exclusive`(): Unit =
        runBlocking {
            assumeSupported()
            outAndErr().start().use { run ->
                run.outputEvents().toList()
                assertFailsWith<IllegalStateException> { run.stdoutLines().toList() }
            }
        }

    @Test
    fun `outputEvents after a stderr-draining verb fails loud, not double-reads`(): Unit =
        runBlocking {
            assumeSupported()
            outAndErr().start().use { run ->
                run.waitFor() // starts the background stderr byte-drain
                assertFailsWith<IllegalStateException> { run.outputEvents().toList() }
            }
        }

    @Test
    fun `a readiness probe keeps stderr draining so a flooding child can exit`() =
        runBlocking {
            // 256 KiB of stderr exceeds the OS pipe buffer: without a background
            // drain the child blocks before exiting and the probe never sees it stop.
            assumeTrue(Os.current == Os.LINUX, "deterministic large stderr flood")
            val flood = Command("sh", "-c", "head -c 262144 /dev/zero | tr '\\0' 'x' 1>&2")
            flood.start().use { run ->
                run.waitUntil(10.seconds) { !run.isAlive }
                assertFalse(run.isAlive, "child should exit once its stderr is drained")
            }
        }
}
