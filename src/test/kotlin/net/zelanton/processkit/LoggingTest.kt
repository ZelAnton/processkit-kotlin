package net.zelanton.processkit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import ch.qos.logback.classic.Logger as LogbackLogger

/** SLF4J lifecycle logging: it fires, and it never leaks argv or stderr secrets. */
class LoggingTest {
    private fun captureProcesskitLogs(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("net.zelanton.processkit") as LogbackLogger
        logger.level = Level.DEBUG
        logger.isAdditive = false // keep DEBUG events out of the root console appender
        logger.detachAndStopAllAppenders()
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return appender
    }

    @Test
    fun `retry logs the program but never a secret arg or stderr`() =
        runTest {
            val logs = captureProcesskitLogs()
            val runner = ScriptedRunner().fallback(Reply.fail(1, "secret-stderr-xyz"))
            val command =
                Command("echo", "--token=topsecret-abc")
                    .retry(maxAttempts = 2, backoff = Duration.ZERO) { it is ProcessException.Exit }
            assertFailsWith<ProcessException.Exit> { runner.run(command) }

            val messages = logs.list.map { it.formattedMessage }
            assertTrue(
                messages.any { it.contains("retry") && it.contains("echo") },
                "expected a retry log naming the program: $messages",
            )
            assertTrue(
                messages.none { it.contains("topsecret-abc") },
                "argv arguments must never be logged: $messages",
            )
            assertTrue(
                messages.none { it.contains("secret-stderr-xyz") },
                "stderr must never be logged: $messages",
            )
        }
}
