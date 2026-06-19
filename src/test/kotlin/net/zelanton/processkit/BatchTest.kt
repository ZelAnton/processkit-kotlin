package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hermetic batch fan-out semantics, driven through [ScriptedRunner]. */
class BatchTest {
    @Test
    fun `outputAll preserves input order and collects all outcomes`() =
        runTest {
            val runner =
                ScriptedRunner()
                    .on("a", reply = Reply.ok("A"))
                    .on("b", reply = Reply.fail(2, "B failed"))
                    .on("c", reply = Reply.ok("C"))
            val results = outputAll(listOf(Command("a"), Command("b"), Command("c")), concurrency = 2, runner = runner)
            assertEquals(3, results.size)
            assertEquals("A", results[0].getOrThrow().stdout)
            // A non-zero exit is data: still a successful Result holding the result.
            assertEquals(2, results[1].getOrThrow().exitCode)
            assertEquals("C", results[2].getOrThrow().stdout)
        }

    @Test
    fun `outputAll captures a thrown failure as a failed Result without short-circuiting`() =
        runTest {
            val runner = ScriptedRunner().on("ok", reply = Reply.ok())
            val results = outputAll(listOf(Command("ok"), Command("missing")), concurrency = 1, runner = runner)
            assertTrue(results[0].isSuccess)
            assertTrue(results[1].isFailure)
            assertTrue(results[1].exceptionOrNull() is ProcessException.Spawn)
        }

    @Test
    fun `outputAllBytes returns raw bytes`() =
        runTest {
            val runner = ScriptedRunner().fallback(Reply.okBytes(byteArrayOf(1, 2, 3)))
            val results = outputAllBytes(listOf(Command("a")), concurrency = 1, runner = runner)
            assertContentEquals(byteArrayOf(1, 2, 3), results[0].getOrThrow().stdout)
        }

    @Test
    fun `outputAll rejects non-positive concurrency`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                outputAll(listOf(Command("a")), concurrency = 0, runner = ScriptedRunner().fallback(Reply.ok()))
            }
        }
}
