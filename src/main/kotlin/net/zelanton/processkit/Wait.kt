package net.zelanton.processkit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Wait for whichever of [processes] exits **first** and return its index.
 *
 * The handles are only awaited, not consumed: the winner and the losers stay
 * usable afterwards (`finish()` / `waitFor()` / `close()` them). Bound the race
 * with `withTimeout` if you need a deadline.
 */
public suspend fun waitAny(vararg processes: RunningProcess): Int {
    require(processes.isNotEmpty()) { "waitAny requires at least one process" }
    return coroutineScope {
        val racers =
            processes.mapIndexed { index, process ->
                async {
                    process.waitFor()
                    index
                }
            }
        try {
            select { racers.forEach { racer -> racer.onAwait { it } } }
        } finally {
            racers.forEach { it.cancel() }
        }
    }
}

/** Wait for **all** [processes] to exit and return their exit codes in order. */
public suspend fun waitAll(vararg processes: RunningProcess): List<Int> =
    coroutineScope {
        processes.map { process -> async { process.waitFor() } }.awaitAll()
    }
