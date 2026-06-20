package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real-subprocess stdin sources via `sort` (Windows + Linux; macOS self-skips). */
class StdinTest {
    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "step 3 backend covers Windows + Linux")

    @Test
    fun `feeds a string to stdin`() =
        runBlocking {
            assumeSupported()
            val result = Command("sort").stdin(Stdin.fromString("banana\napple\ncherry\n")).outputString()
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
    fun `the default closed stdin lets a reader finish instead of hanging`() =
        runBlocking {
            assumeSupported()
            // `sort` reads to EOF; with the default closed stdin it exits cleanly
            // and empty (it would hang forever if stdin stayed open).
            val result = Command("sort").outputString()
            assertEquals(0, result.exitCode)
            assertEquals("", result.stdout.trim())
        }

    @Test
    fun `feeds a file to stdin`() =
        runBlocking {
            assumeSupported()
            val file = Files.createTempFile("pk-stdin", ".txt")
            try {
                // Use a longer ASCII payload: Windows sort.exe misdetects the
                // encoding of very short input, unrelated to stdin delivery.
                Files.writeString(file, "banana\napple\ncherry\n")
                val result = Command("sort").stdin(Stdin.fromFile(file)).outputString()
                assertEquals(
                    listOf("apple", "banana", "cherry"),
                    result.stdout
                        .trim()
                        .lines()
                        .map { it.trim() },
                )
            } finally {
                Files.deleteIfExists(file)
            }
        }
}
