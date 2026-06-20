package net.zelanton.processkit

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** Real-subprocess interactive stdin (Windows + Linux; macOS self-skips). `sort`
 *  reads all stdin, then emits the sorted lines on EOF — a clean way to prove the
 *  interactive writer's bytes reach the child. */
class InteractiveStdinTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 3e backend covers Windows + Linux")

    @Test
    fun `interactive stdin feeds a running process and EOF triggers output`() =
        runBlocking {
            assumeSupported()
            Command("sort").keepStdinOpen().start().use { run ->
                val stdin = run.takeStdin() ?: error("keepStdinOpen should provide a stdin writer")
                stdin.writeLine("banana")
                stdin.writeLine("apple")
                stdin.writeLine("cherry")
                stdin.close() // EOF → sort produces its output
                val lines =
                    run
                        .stdoutLines()
                        .toList()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                assertEquals(listOf("apple", "banana", "cherry"), lines)
            }
        }

    @Test
    fun `takeStdin is null without keepStdinOpen`() =
        runBlocking {
            assumeSupported()
            Command("sort").start().use { run ->
                // stdin auto-closed → sort exits
                assertNull(run.takeStdin())
                run.finish()
            }
        }

    @Test
    fun `takeStdin returns the writer only once`() =
        runBlocking {
            assumeSupported()
            Command("sort").keepStdinOpen().start().use { run ->
                val first = run.takeStdin() ?: error("first takeStdin should be non-null")
                assertNull(run.takeStdin(), "a second takeStdin must return null")
                first.close()
                run.finish()
            }
        }

    @Test
    fun `concurrent collect and write supports a line-echoing child`() =
        runBlocking {
            // `cat` echoes each line as it arrives (interleaves output with input),
            // so the safe pattern is to collect stdout concurrently with writing.
            assumeTrue(Os.current == Os.LINUX, "cat echoes per line; no Windows equivalent")
            Command("cat").keepStdinOpen().start().use { run ->
                val received = mutableListOf<String>()
                val collector = launch { run.stdoutLines().collect { received.add(it.trim()) } }
                val stdin = run.takeStdin() ?: error("expected a stdin writer")
                stdin.writeLine("one")
                stdin.writeLine("two")
                stdin.writeLine("three")
                stdin.close() // EOF → cat exits → stdoutLines completes
                collector.join()
                assertEquals(listOf("one", "two", "three"), received)
            }
        }

    @Test
    fun `finish closes an untaken kept-open stdin so the child can exit`() =
        runBlocking {
            assumeSupported()
            Command("sort").keepStdinOpen().start().use { run ->
                // Never take the writer: finish must close stdin (EOF) so sort exits.
                val finished = assertNotNull(withTimeoutOrNull(10.seconds) { run.finish() })
                assertEquals(0, finished.exitCode)
            }
        }
}
