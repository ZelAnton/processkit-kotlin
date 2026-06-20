package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Real-subprocess pipelines (Windows + Linux; macOS self-skips). */
class PipelineTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 4 backend covers Windows + Linux")

    private fun emit(lines: String): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", lines.trim().split("\n").joinToString("&") { "echo $it" })
        } else {
            Command("sh", "-c", "printf '${lines.replace("\n", "\\n")}'")
        }

    @Test
    fun `pipes stdout into the next stage's stdin`() =
        runBlocking {
            assumeSupported()
            val result = emit("banana\napple\ncherry\n").pipe(Command("sort")).outputString()
            assertEquals(0, result.exitCode)
            assertEquals(
                listOf("apple", "banana", "cherry"),
                result.stdout
                    .trim()
                    .lines()
                    .map { it.trim() },
            )
        }

    @Test
    fun `run returns the final stage's trimmed stdout`() =
        runBlocking {
            assumeSupported()
            val sorted = emit("banana\napple\ncherry\n").pipe(Command("sort")).run()
            assertEquals(listOf("apple", "banana", "cherry"), sorted.lines().map { it.trim() })
        }

    @Test
    fun `pipefail attributes a failing earlier stage`() =
        runBlocking {
            assumeSupported()
            val failing =
                if (Os.current == Os.WINDOWS) {
                    Command("cmd", "/c", "echo data&exit 2")
                } else {
                    Command("sh", "-c", "echo data; exit 2")
                }
            val result = failing.pipe(Command("sort")).outputString()
            assertEquals(2, result.exitCode)
            assertFalse(result.isSuccess)
        }
}
