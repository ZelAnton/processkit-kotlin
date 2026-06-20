package net.zelanton.processkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `outputEvents and stdoutLines are mutually exclusive`() =
        runBlocking {
            assumeSupported()
            outAndErr().start().use { run ->
                run.outputEvents().toList()
                assertFailsWith<IllegalStateException> { run.stdoutLines().toList() }
            }
        }
}
