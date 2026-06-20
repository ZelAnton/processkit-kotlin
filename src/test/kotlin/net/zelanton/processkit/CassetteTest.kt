package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hermetic record/replay: record through a fake inner runner, replay from the file. */
class CassetteTest {
    private val tempFiles = mutableListOf<Path>()

    private fun tempCassette(): Path = Files.createTempFile("cassette", ".json").also { tempFiles.add(it) }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    /** Returns each reply in turn, then repeats the last (no real subprocess). */
    private class SequenceRunner(
        private val replies: List<ProcessResult<ByteArray>>,
    ) : ProcessRunner {
        private var index = 0

        override suspend fun execute(command: Command): ProcessResult<ByteArray> =
            replies[minOf(index++, replies.size - 1)]
    }

    private fun result(
        program: String,
        stdout: String = "",
        stderr: String = "",
        exitCode: Int = 0,
    ) = ProcessResult(program, stdout.encodeToByteArray(), stderr, exitCode, timedOut = false)

    @Test
    fun `record then replay round-trips a result`() =
        runTest {
            val file = tempCassette()
            val inner = ScriptedRunner().on("git", "rev-parse", "HEAD", reply = Reply.ok("abc123\n"))
            RecordReplayRunner.record(file, inner).use { it.run(Command("git", "rev-parse", "HEAD")) }

            val replayed = RecordReplayRunner.replay(file).run(Command("git", "rev-parse", "HEAD"))
            assertEquals("abc123", replayed)
        }

    @Test
    fun `replay throws CassetteMiss for an unrecorded command`() =
        runTest {
            val file = tempCassette()
            RecordReplayRunner.record(file, ScriptedRunner().fallback(Reply.ok("x"))).use {
                it.run(Command("git", "status"))
            }
            val runner = RecordReplayRunner.replay(file)
            assertFailsWith<ProcessException.CassetteMiss> { runner.run(Command("git", "log")) }
        }

    @Test
    fun `the cassette stores env names but never env values`() =
        runTest {
            val file = tempCassette()
            RecordReplayRunner.record(file, ScriptedRunner().fallback(Reply.ok("ok"))).use {
                it.run(Command("tool", "--flag").env("API_SECRET", "topsecret-value"))
            }
            val text = Files.readString(file)
            assertTrue(text.contains("API_SECRET"), "env name should be stored: $text")
            assertFalse(text.contains("topsecret-value"), "env value must never be stored: $text")
        }

    @Test
    fun `replay rejects an unknown cassette version`() =
        runTest {
            val file = tempCassette()
            Files.writeString(file, """{"version":999,"entries":[]}""")
            assertFailsWith<java.io.IOException> { RecordReplayRunner.replay(file) }
        }

    @Test
    fun `duplicate invocations replay in order then repeat the last`() =
        runTest {
            val file = tempCassette()
            val inner =
                SequenceRunner(
                    listOf(result("svc", exitCode = 1), result("svc", stdout = "ready\n", exitCode = 0)),
                )
            RecordReplayRunner.record(file, inner).use { runner ->
                runner.outputString(Command("svc")) // records exit 1
                runner.outputString(Command("svc")) // records exit 0 "ready"
            }

            val replay = RecordReplayRunner.replay(file)
            assertEquals(1, replay.outputString(Command("svc")).exitCode)
            assertEquals("ready", replay.outputString(Command("svc")).stdout.trim())
            // Sequence exhausted — the last entry repeats.
            assertEquals("ready", replay.outputString(Command("svc")).stdout.trim())
        }

    @Test
    fun `the cassette file is tightened to owner-only on POSIX`() =
        runTest {
            assumeTrue(Os.current == Os.LINUX, "POSIX file permissions")
            val file = tempCassette()
            // Pre-loosen so the test proves writeCassette tightens, not the temp default.
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
            RecordReplayRunner.record(file, ScriptedRunner().fallback(Reply.ok("x"))).use {
                it.run(Command("tool"))
            }
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
            )
        }

    @Test
    fun `base64 round-trips binary stdout exactly`() =
        runTest {
            val file = tempCassette()
            val binary = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte(), 0x00, 0xfe.toByte())
            RecordReplayRunner.record(file, ScriptedRunner().fallback(Reply.okBytes(binary))).use {
                it.outputBytes(Command("cat", "blob"))
            }
            val replayed = RecordReplayRunner.replay(file).outputBytes(Command("cat", "blob"))
            assertContentEquals(binary, replayed.stdout)
        }
}
