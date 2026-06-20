package net.zelanton.processkit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Hermetic retry semantics: classifier, attempt cap, backoff timing, verb scope. */
class RetryTest {
    private val onExit: (ProcessException) -> Boolean = { it is ProcessException.Exit }
    private val onTimeout: (ProcessException) -> Boolean = RetryWhen.timedOut

    /** Fails its first [failures] calls (a non-zero exit, or a timeout), then succeeds. */
    private class FlakyRunner(
        private val failures: Int,
        private val timedOut: Boolean = false,
    ) : ProcessRunner {
        var calls = 0
            private set

        override suspend fun execute(command: Command): ProcessResult<ByteArray> {
            calls++
            return if (calls <= failures) {
                ProcessResult(command.program, ByteArray(0), "boom", if (timedOut) 0 else 1, timedOut)
            } else {
                ProcessResult(command.program, "ok".encodeToByteArray(), "", exitCode = 0, timedOut = false)
            }
        }
    }

    @Test
    fun `retry replays until success`() =
        runTest {
            val runner = FlakyRunner(failures = 2)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onExit)
            assertEquals("ok", runner.run(command))
            assertEquals(3, runner.calls)
        }

    @Test
    fun `retry caps at maxAttempts then rethrows`() =
        runTest {
            val runner = FlakyRunner(failures = 5)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onExit)
            assertFailsWith<ProcessException.Exit> { runner.run(command) }
            assertEquals(3, runner.calls)
        }

    @Test
    fun `retry stops when the classifier rejects the failure`() =
        runTest {
            val runner = FlakyRunner(failures = 5)
            // The classifier accepts only timeouts, so an Exit failure is terminal.
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onTimeout)
            assertFailsWith<ProcessException.Exit> { runner.run(command) }
            assertEquals(1, runner.calls)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `retry sleeps the backoff between attempts`() =
        runTest {
            val runner = FlakyRunner(failures = 2)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 100.milliseconds, retryIf = onExit)
            val start = testScheduler.currentTime
            runner.run(command)
            assertEquals(200, testScheduler.currentTime - start) // two backoffs of 100ms before the third try
        }

    @Test
    fun `capturing verbs do not retry`() =
        runTest {
            val runner = FlakyRunner(failures = 5)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onExit)
            val result = runner.outputString(command)
            assertEquals(1, result.exitCode)
            assertEquals(1, runner.calls)
        }

    @Test
    fun `RetryWhen timedOut retries a timeout`() =
        runTest {
            val runner = FlakyRunner(failures = 2, timedOut = true)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onTimeout)
            assertEquals("ok", runner.run(command))
            assertEquals(3, runner.calls)
        }

    @Test
    fun `exitCode honors retry on a timeout`() =
        runTest {
            val runner = FlakyRunner(failures = 2, timedOut = true)
            val command = Command("flaky").retry(maxAttempts = 3, backoff = 10.milliseconds, retryIf = onTimeout)
            assertEquals(0, runner.exitCode(command))
            assertEquals(3, runner.calls)
        }

    @Test
    fun `a command without a policy runs exactly once`() =
        runTest {
            val runner = FlakyRunner(failures = 5)
            assertFailsWith<ProcessException.Exit> { runner.run(Command("flaky")) }
            assertEquals(1, runner.calls)
        }

    @Test
    fun `RetryWhen classifiers select the right failures`() {
        val exit75 = ProcessException.Exit("p", 75, "")
        val timeout = ProcessException.Timeout("p", null)
        assertTrue(RetryWhen.exitCode(75)(exit75))
        assertFalse(RetryWhen.exitCode(1)(exit75))
        assertTrue(RetryWhen.transient(timeout))
        assertFalse(RetryWhen.transient(exit75))
    }

    @Test
    fun `retry rejects a non-positive maxAttempts`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                Command("x").retry(maxAttempts = 0, backoff = 10.milliseconds) { true }
            }
        }
}
