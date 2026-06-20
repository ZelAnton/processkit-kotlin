package net.zelanton.processkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The default batch fan-out width: the number of available processors. A sensible
 * cap for spawning local children; raise [concurrency] for IO- or network-bound
 * commands that spend most of their time waiting.
 */
public val DEFAULT_CONCURRENCY: Int get() = Runtime.getRuntime().availableProcessors()

/**
 * Run [commands] with at most [concurrency] alive at once, returning every
 * outcome in input order.
 *
 * **Collect-all:** each element is one command's independent [Result], so a
 * non-zero exit (a successful `Result` holding a non-zero [ProcessResult]) — or a
 * thrown failure like [ProcessException.NotFound] — never short-circuits the
 * batch. Pass a [ProcessGroup] as [runner] to keep every child in one shared
 * kill-on-close group.
 */
public suspend fun outputAll(
    commands: Iterable<Command>,
    concurrency: Int = DEFAULT_CONCURRENCY,
    runner: ProcessRunner = JobRunner,
): List<Result<ProcessResult<String>>> = fanOut(commands, concurrency) { runner.outputString(it) }

/** Like [outputAll], but each result captures stdout as raw bytes. */
public suspend fun outputAllBytes(
    commands: Iterable<Command>,
    concurrency: Int = DEFAULT_CONCURRENCY,
    runner: ProcessRunner = JobRunner,
): List<Result<ProcessResult<ByteArray>>> = fanOut(commands, concurrency) { runner.outputBytes(it) }

private suspend fun <T> fanOut(
    commands: Iterable<Command>,
    concurrency: Int,
    run: suspend (Command) -> T,
): List<Result<T>> {
    require(concurrency >= 1) { "concurrency must be >= 1, was $concurrency" }
    val gate = Semaphore(concurrency)
    return coroutineScope {
        commands
            .map { command ->
                async {
                    gate.withPermit {
                        try {
                            Result.success(run(command))
                        } catch (cancellation: CancellationException) {
                            throw cancellation // never swallow cancellation
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                    }
                }
            }.awaitAll()
    }
}
