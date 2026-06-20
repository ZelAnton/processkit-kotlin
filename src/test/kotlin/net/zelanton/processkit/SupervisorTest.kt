package net.zelanton.processkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hermetic supervision logic — a scripted sequence of results, virtual-time backoff. */
class SupervisorTest {
    /** A runner that serves canned replies in order, repeating the last. */
    private class SequenceRunner(
        private vararg val replies: Reply,
    ) : ProcessRunner {
        private var index = 0

        override suspend fun execute(command: Command): ProcessResult<ByteArray> {
            val reply = replies[minOf(index++, replies.lastIndex)]
            return ProcessResult(command.program, reply.stdout, reply.stderr, reply.exitCode, reply.timedOut)
        }
    }

    @Test
    fun `ON_CRASH restarts until a clean exit`() =
        runTest {
            val runner = SequenceRunner(Reply.fail(1), Reply.fail(1), Reply.ok("done"))
            val outcome =
                Supervisor(Command("svc")).restart(RestartPolicy.ON_CRASH).withRunner(runner).run()
            assertEquals(2, outcome.restarts)
            assertEquals(StopReason.STOPPED_BY_POLICY, outcome.stoppedBy)
            assertTrue(outcome.lastResult.isSuccess)
        }

    @Test
    fun `maxRestarts caps the restart count`() =
        runTest {
            val runner = SequenceRunner(Reply.fail(1))
            val outcome =
                Supervisor(Command("svc"))
                    .restart(RestartPolicy.ALWAYS)
                    .maxRestarts(3)
                    .withRunner(runner)
                    .run()
            assertEquals(3, outcome.restarts)
            assertEquals(StopReason.MAX_RESTARTS_REACHED, outcome.stoppedBy)
        }

    @Test
    fun `NEVER runs exactly once`() =
        runTest {
            val runner = SequenceRunner(Reply.fail(2))
            val outcome =
                Supervisor(Command("svc")).restart(RestartPolicy.NEVER).withRunner(runner).run()
            assertEquals(0, outcome.restarts)
            assertEquals(StopReason.STOPPED_BY_POLICY, outcome.stoppedBy)
        }

    @Test
    fun `stopWhen ends supervision`() =
        runTest {
            val runner = SequenceRunner(Reply.fail(1), Reply.fail(42))
            val outcome =
                Supervisor(Command("svc"))
                    .restart(RestartPolicy.ALWAYS)
                    .stopWhen { it.exitCode == 42 }
                    .withRunner(runner)
                    .run()
            assertEquals(1, outcome.restarts)
            assertEquals(StopReason.STOP_CONDITION_MET, outcome.stoppedBy)
        }

    @Test
    fun `ALWAYS restarts even on a clean exit`() =
        runTest {
            val runner = SequenceRunner(Reply.ok())
            val outcome =
                Supervisor(Command("svc"))
                    .restart(RestartPolicy.ALWAYS)
                    .maxRestarts(2)
                    .withRunner(runner)
                    .run()
            assertEquals(2, outcome.restarts)
            assertEquals(StopReason.MAX_RESTARTS_REACHED, outcome.stoppedBy)
        }
}
