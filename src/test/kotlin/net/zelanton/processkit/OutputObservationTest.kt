package net.zelanton.processkit

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/** Live output observation: line handlers, tee, encoding override, fault isolation. */
class OutputObservationTest {
    @Test
    fun `onStdoutLine fires for each decoded line`() =
        runTest {
            val lines = mutableListOf<String>()
            val runner = ScriptedRunner().fallback(Reply.ok("alpha\nbeta\ngamma\n"))
            runner.run(Command("tool").onStdoutLine { lines.add(it) })
            assertEquals(listOf("alpha", "beta", "gamma"), lines)
        }

    @Test
    fun `onStderrLine fires for each decoded line`() =
        runTest {
            val errs = mutableListOf<String>()
            val runner = ScriptedRunner().fallback(Reply.fail(0, "warn-1\nwarn-2\n"))
            runner.run(Command("tool").onStderrLine { errs.add(it) })
            assertEquals(listOf("warn-1", "warn-2"), errs)
        }

    @Test
    fun `stdoutTee mirrors every line and runs alongside the handler`() =
        runTest {
            val sink = StringBuilder()
            val lines = mutableListOf<String>()
            val runner = ScriptedRunner().fallback(Reply.ok("one\ntwo\n"))
            runner.run(Command("tool").stdoutTee(sink).onStdoutLine { lines.add(it) })
            assertEquals("one\ntwo\n", sink.toString())
            assertEquals(listOf("one", "two"), lines)
        }

    @Test
    fun `a throwing handler is disabled and the run continues`() =
        runTest {
            val seen = mutableListOf<String>()
            val runner = ScriptedRunner().fallback(Reply.ok("one\ntwo\nthree\n"))
            // Throws on the 2nd line; the handler is then disabled, so it never sees the 3rd.
            runner.run(
                Command("tool").onStdoutLine {
                    if (it == "two") throw RuntimeException("boom")
                    seen.add(it)
                },
            )
            assertEquals(listOf("one"), seen)
        }

    @Test
    fun `stdoutEncoding decodes non-UTF8 output`() =
        runTest {
            // 0xE9 is 'é' in ISO-8859-1 but an invalid standalone UTF-8 byte.
            val runner = ScriptedRunner().fallback(Reply.okBytes(byteArrayOf(0xE9.toByte())))
            val result = runner.outputString(Command("tool").stdoutEncoding(Charsets.ISO_8859_1))
            assertEquals("é", result.stdout)
        }

    @Test
    fun `a real child's stdout lines reach the handler`() =
        runBlocking {
            assumeTrue(Os.current == Os.WINDOWS || Os.current == Os.LINUX, "real-subprocess backend")
            val lines = mutableListOf<String>()
            val command =
                if (Os.current == Os.WINDOWS) {
                    Command("cmd", "/c", "echo a&echo b")
                } else {
                    Command("sh", "-c", "printf 'a\\nb\\n'")
                }
            command.onStdoutLine { lines.add(it.trim()) }.run()
            assertEquals(listOf("a", "b"), lines)
        }
}
