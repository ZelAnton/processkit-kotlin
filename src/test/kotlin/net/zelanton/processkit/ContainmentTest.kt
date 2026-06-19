package net.zelanton.processkit

import net.zelanton.processkit.internal.Containment
import net.zelanton.processkit.internal.Os
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Phase 0 proof: a spawned child and its grandchildren are reaped as a unit when
 * the container is torn down. Runs for real on Windows (Job Object) and Linux
 * (process group); self-skips elsewhere until the `posix_spawn`/cgroup backends
 * land.
 */
class ContainmentTest {
    @Test
    fun `killAll reaps the whole process tree, grandchildren included`() {
        assumeTrue(
            Os.current == Os.WINDOWS || Os.current == Os.LINUX,
            "Phase 0 containment covers Windows (Job Object) and Linux (process group)",
        )

        val containment = Containment.create()
        val child = containment.spawn(childTreeCommand())
        try {
            val childHandle = child.toHandle()

            // The child spawns a grandchild; wait for the subtree to materialize.
            val descendants = awaitDescendants(childHandle, deadlineMillis = 15_000)
            assertTrue(
                descendants.isNotEmpty(),
                "expected the child to spawn a grandchild subtree (mechanism=${containment.mechanism})",
            )

            val tree = listOf(childHandle) + descendants
            assertTrue(tree.all { it.isAlive }, "the whole tree should be alive before teardown")

            containment.killAll()

            awaitAllDead(tree, deadlineMillis = 15_000)
            val survivors = tree.filter { it.isAlive }.map { it.pid() }
            assertTrue(
                survivors.isEmpty(),
                "killAll must reap the whole tree (mechanism=${containment.mechanism}); survivors=$survivors",
            )
        } finally {
            containment.close()
            child.destroyForcibly()
        }
    }

    private fun childTreeCommand(): List<String> =
        if (Os.current == Os.WINDOWS) {
            // The leading `ping -n 3` (~2s) delays the grandchild spawn so the
            // post-start Job assignment wins the documented race deterministically
            // (step 1 makes it race-free via CREATE_SUSPENDED). cmd then launches a
            // detached cmd->ping (grandchild) and a foreground ping (child).
            listOf(
                "cmd",
                "/c",
                "ping -n 3 127.0.0.1 >nul & start /b cmd /c ping -n 600 127.0.0.1 >nul & ping -n 600 127.0.0.1 >nul",
            )
        } else {
            // outer sh -> (inner sh -> sleep == grandchild) + foreground sleep (child)
            listOf("sh", "-c", "sh -c 'sleep 600' & sleep 600")
        }

    private fun awaitDescendants(
        handle: ProcessHandle,
        deadlineMillis: Long,
    ): List<ProcessHandle> {
        val deadline = System.nanoTime() + deadlineMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            val descendants = handle.descendants().toList()
            if (descendants.isNotEmpty()) {
                return descendants
            }
            Thread.sleep(50)
        }
        return emptyList()
    }

    private fun awaitAllDead(
        handles: List<ProcessHandle>,
        deadlineMillis: Long,
    ) {
        val deadline = System.nanoTime() + deadlineMillis * 1_000_000
        while (System.nanoTime() < deadline && handles.any { it.isAlive }) {
            Thread.sleep(50)
        }
    }
}
