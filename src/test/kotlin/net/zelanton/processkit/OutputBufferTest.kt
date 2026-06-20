package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OutputBufferPolicy: hermetic pump-level retention + real-run wiring. */
class OutputBufferTest {
    private fun pump(
        text: String,
        policy: OutputBufferPolicy,
    ): PumpResult = pumpStream(ByteArrayInputStream(text.toByteArray()), Charsets.UTF_8, null, policy)

    @Test
    fun `unbounded returns the exact raw bytes`() {
        val result = pump("a\nb\nc\n", OutputBufferPolicy.unbounded())
        assertEquals("a\nb\nc\n", String(result.bytes))
        assertFalse(result.truncated)
        assertFalse(result.overLimit)
    }

    @Test
    fun `bounded drops the oldest lines`() {
        val result = pump("a\nb\nc\nd\n", OutputBufferPolicy.bounded(2))
        assertEquals("c\nd", String(result.bytes))
        assertTrue(result.truncated)
    }

    @Test
    fun `dropNewest keeps the first lines`() {
        val result = pump("a\nb\nc\n", OutputBufferPolicy.bounded(2).withOverflow(OverflowMode.DROP_NEWEST))
        assertEquals("a\nb", String(result.bytes))
        assertTrue(result.truncated)
    }

    @Test
    fun `failLoud sets overLimit past the ceiling`() {
        val result = pump("a\nb\nc\n", OutputBufferPolicy.failLoud(2))
        assertTrue(result.overLimit)
    }

    @Test
    fun `maxBytes bounds the retained bytes`() {
        val result = pump("aa\nbb\ncc\n", OutputBufferPolicy.unbounded().withMaxBytes(3))
        assertEquals("cc", String(result.bytes))
        assertTrue(result.truncated)
    }

    @Test
    fun `dropNewest with a byte cap keeps lines greedily, not a strict prefix`() {
        // maxBytes=5: aaa(3) fits; bbb(3) (+sep) would overflow and is skipped; c(1) (+sep) still fits.
        val policy = OutputBufferPolicy.unbounded().withMaxBytes(5).withOverflow(OverflowMode.DROP_NEWEST)
        val result = pump("aaa\nbbb\nc\n", policy)
        assertEquals("aaa\nc", String(result.bytes))
        assertTrue(result.truncated)
    }

    @Test
    fun `a byte cap bounds an empty-line flood`() {
        // Empty lines carry no content; the separators must still be counted, or the
        // retained chunk list would grow without limit under a byte-only cap.
        val result = pump("\n".repeat(1000), OutputBufferPolicy.unbounded().withMaxBytes(4))
        assertTrue(result.bytes.size <= 4, "empty-line flood must stay within the byte cap")
        assertTrue(result.truncated)
    }

    @Test
    fun `failLoud with a byte cap errors past the ceiling`() {
        val policy = OutputBufferPolicy.unbounded().withMaxBytes(3).withOverflow(OverflowMode.ERROR)
        val result = pump("aa\nbb\n", policy)
        assertTrue(result.overLimit)
    }

    @Test
    fun `bounded zero retains nothing`() {
        val result = pump("a\nb\n", OutputBufferPolicy.bounded(0))
        assertEquals("", String(result.bytes))
        assertTrue(result.truncated)
    }

    @Test
    fun `an empty stream is not flagged`() {
        val result = pump("", OutputBufferPolicy.bounded(2))
        assertEquals("", String(result.bytes))
        assertFalse(result.truncated)
        assertFalse(result.overLimit)
    }

    @Test
    fun `policy validates non-negative caps`() {
        assertFailsWith<IllegalArgumentException> { OutputBufferPolicy.bounded(-1) }
        assertFailsWith<IllegalArgumentException> { OutputBufferPolicy.failLoud(-1) }
        assertFailsWith<IllegalArgumentException> { OutputBufferPolicy.unbounded().withMaxBytes(-1) }
    }

    private fun assumeSupported() =
        assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "real-subprocess backend")

    private fun manyLines(count: Int): Command =
        if (Os.current == Os.WINDOWS) {
            Command("cmd", "/c", "for /l %i in (1,1,$count) do @echo %i")
        } else {
            Command("seq", "1", count.toString())
        }

    @Test
    fun `a bounded policy truncates a real run`() =
        runBlocking {
            assumeSupported()
            val result = manyLines(20).outputBuffer(OutputBufferPolicy.bounded(5)).outputString()
            assertTrue(result.truncated, "20 lines under a cap of 5 should truncate")
            assertEquals(5, result.stdout.lines().count { it.isNotBlank() })
        }

    @Test
    fun `failLoud rejects a flooding real run`(): Unit =
        runBlocking {
            assumeSupported()
            assertFailsWith<ProcessException.OutputTooLarge> {
                manyLines(20).outputBuffer(OutputBufferPolicy.failLoud(5)).run()
            }
        }
}
