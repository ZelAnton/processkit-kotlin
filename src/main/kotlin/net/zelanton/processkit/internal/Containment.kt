package net.zelanton.processkit.internal

import net.zelanton.processkit.Mechanism
import net.zelanton.processkit.ProcessException
import net.zelanton.processkit.Signal
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/** The host operating system, detected once. */
internal enum class Os {
    WINDOWS,
    LINUX,
    MACOS,
    OTHER,
    ;

    internal companion object {
        internal val current: Os =
            System.getProperty("os.name").orEmpty().lowercase().let { name ->
                when {
                    name.contains("win") -> WINDOWS
                    name.contains("linux") -> LINUX
                    name.contains("mac") || name.contains("darwin") -> MACOS
                    else -> OTHER
                }
            }
    }
}

/**
 * A kill-on-close container for a process tree.
 *
 * Phase 0 spike: proves the spawn + whole-tree teardown primitive on Windows
 * (Job Object) and Linux (POSIX process group). The real `Command`/`ProcessGroup`
 * surface is built on this seam in later steps.
 */
internal interface Containment : AutoCloseable {
    val mechanism: Mechanism

    /** Spawn [command] as a member of this container. */
    fun spawn(
        command: List<String>,
        workingDir: Path? = null,
        environment: Map<String, String> = emptyMap(),
        clearEnvironment: Boolean = false,
    ): Process

    /** Hard-kill every member of the container (grandchildren included). */
    fun killAll()

    /**
     * Ask members to stop gracefully (POSIX `SIGTERM`). Returns `true` if a
     * graceful signal was actually sent; `false` where the mechanism has no
     * graceful stop (Windows Job Objects — use [killAll] there).
     */
    fun requestStop(): Boolean

    /** Broadcast [signal] to every member (best-effort; exited members skipped). */
    fun signal(signal: Signal)

    /** Suspend (freeze) every member of the container. */
    fun suspendAll()

    /** Resume every member suspended by [suspendAll]. */
    fun resumeAll()

    /** A point-in-time snapshot of the pids currently in the container. */
    fun members(): List<Long>

    /** Bring an externally-started process (by [pid]) under this container. */
    fun adopt(pid: Long)

    override fun close() {
        killAll()
    }

    companion object {
        fun create(): Containment =
            when (Os.current) {
                Os.WINDOWS -> WindowsJobContainment()
                Os.LINUX -> PosixGroupContainment()
                // macOS lacks the `setsid` launcher this backend uses; the native
                // posix_spawn backend lands in a later increment.
                Os.MACOS -> throw IOException(
                    "processkit: the macOS backend is not implemented yet (Windows and Linux are supported)",
                )
                Os.OTHER -> throw IOException("processkit has no containment backend for this OS")
            }
    }
}

/** Build a [ProcessBuilder] with the working directory and environment applied. */
internal fun newProcessBuilder(
    command: List<String>,
    workingDir: Path?,
    environment: Map<String, String>,
    clearEnvironment: Boolean,
): ProcessBuilder {
    val builder = ProcessBuilder(command)
    if (workingDir != null) {
        builder.directory(workingDir.toFile())
    }
    val env = builder.environment()
    if (clearEnvironment) {
        env.clear()
    }
    for ((name, value) in environment) {
        env[name] = value
    }
    return builder
}

/**
 * Linux/macOS containment via a POSIX process group.
 *
 * The child is launched through `setsid`, which puts it in a fresh session and
 * process group whose id equals the child's pid; `kill(-pgid, SIGKILL)` then
 * reaps the whole group. A `setsid` descendant can still escape — the honest
 * weakness of this mechanism, reported as [Mechanism.PROCESS_GROUP].
 */
internal class PosixGroupContainment : Containment {
    override val mechanism: Mechanism = Mechanism.PROCESS_GROUP

    private val lock = Any()

    // setsid makes the leader's pgid == its pid, so we track pids as pgids and
    // signal the whole group with `kill(-pgid, sig)`.
    private val groupLeaders = mutableListOf<Long>()

    // Adopted children cannot be re-grouped (POSIX forbids re-grouping after exec),
    // so they are tracked individually and signalled with `kill(pid, sig)` — their
    // own descendants are not captured.
    private val adoptedPids = mutableListOf<Long>()

    override fun spawn(
        command: List<String>,
        workingDir: Path?,
        environment: Map<String, String>,
        clearEnvironment: Boolean,
    ): Process {
        // `setsid` runs the target in a fresh session/group; working dir and env
        // are applied to setsid and inherited by the target it execs.
        val process =
            newProcessBuilder(listOf("setsid") + command, workingDir, environment, clearEnvironment).start()
        synchronized(lock) {
            pruneDead()
            groupLeaders.add(process.pid())
        }
        return process
    }

    override fun killAll() {
        synchronized(lock) {
            broadcast(Libc.SIGKILL)
            groupLeaders.clear()
            adoptedPids.clear()
        }
    }

    override fun requestStop(): Boolean {
        synchronized(lock) { broadcast(Libc.SIGTERM) }
        return true
    }

    override fun signal(signal: Signal) {
        // SIGKILL is the whole-tree hard kill, routed through killAll so it can't
        // miss a process forked mid-broadcast; other signals are a per-member send.
        if (signal.deliversAsKill) {
            killAll()
        } else {
            synchronized(lock) { broadcast(signal.rawUnix) }
        }
    }

    override fun suspendAll() {
        synchronized(lock) { broadcast(Libc.SIGSTOP) }
    }

    override fun resumeAll() {
        synchronized(lock) { broadcast(Libc.SIGCONT) }
    }

    override fun members(): List<Long> =
        synchronized(lock) {
            pruneDead()
            groupLeaders + adoptedPids
        }

    override fun adopt(pid: Long) {
        synchronized(lock) { adoptedPids.add(pid) }
    }

    // Caller holds [lock]. Group leaders take the whole group (`kill(-pgid)`);
    // adopted children are signalled individually.
    private fun broadcast(signal: Int) {
        pruneDead()
        for (pgid in groupLeaders) {
            Libc.killGroup(pgid, signal)
        }
        for (pid in adoptedPids) {
            Libc.killProcess(pid, signal)
        }
    }

    // Caller holds [lock]. Drop groups/children that no longer exist so a signal
    // can never land on a recycled pid. `kill(target, 0)` is the existence probe:
    // 0 means the target still exists (a group is live while ANY member —
    // descendants included — survives), non-zero means it is gone.
    //
    // This closes the common "fully exited, then signalled" window and bounds the
    // tracking lists. A residual race remains inherent to the process-group
    // mechanism: a pid reaped and then reused as a *new* group leader is
    // indistinguishable here from the original; the Job Object / cgroup mechanisms
    // do not have this limitation.
    //
    // A non-zero result is treated as "gone" (`ESRCH`). The only other realistic
    // `kill(_, 0)` failure, `EPERM`, cannot arise here: every member is a
    // same-uid child this process spawned (or adopted).
    private fun pruneDead() {
        groupLeaders.removeAll { pgid -> Libc.killGroup(pgid, 0) != 0 }
        adoptedPids.removeAll { pid -> Libc.killProcess(pid, 0) != 0 }
    }
}

/**
 * Windows containment via a Job Object created with
 * `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`: every assigned process — and everything
 * it spawns — is terminated when the job is closed or terminated.
 */
internal class WindowsJobContainment : Containment {
    override val mechanism: Mechanism = Mechanism.JOB_OBJECT

    private val job: MemorySegment = Win32.createKillOnCloseJob()
    private val lock = Any()
    private var closed = false

    // Pids we assigned to the job — the roots of the contained tree, used to
    // enumerate [members] via their live descendants.
    private val spawnedPids = mutableListOf<Long>()

    override fun spawn(
        command: List<String>,
        workingDir: Path?,
        environment: Map<String, String>,
        clearEnvironment: Boolean,
    ): Process {
        // ProcessBuilder gives no race-free assignment hook, so the child is
        // assigned immediately after start. The residual window (a child that
        // forks before assignment) is closed by CREATE_SUSPENDED in a later step.
        val process = newProcessBuilder(command, workingDir, environment, clearEnvironment).start()
        try {
            Win32.assignToJob(job, process.pid())
        } catch (failure: Throwable) {
            // The child was started but never joined the job, so closing the job
            // would not reap it; kill it directly so it cannot escape containment.
            process.destroyForcibly()
            throw failure
        }
        synchronized(lock) { spawnedPids.add(process.pid()) }
        return process
    }

    override fun killAll() {
        synchronized(lock) {
            if (closed) return
            Win32.terminateJob(job)
        }
    }

    // Job Objects have no graceful-stop signal; close()/killAll() is atomic.
    override fun requestStop(): Boolean = false

    override fun signal(signal: Signal) {
        // Only SIGKILL is deliverable on Windows — it maps to the Job Object
        // terminate. Every other signal has no Windows equivalent.
        if (signal.deliversAsKill) {
            killAll()
        } else {
            throw ProcessException.Unsupported("signal($signal)")
        }
    }

    // Suspending a Job Object means suspending every thread of every member; the
    // thread-enumeration backend lands in a later increment.
    override fun suspendAll(): Unit = throw ProcessException.Unsupported("suspend")

    override fun resumeAll(): Unit = throw ProcessException.Unsupported("resume")

    override fun members(): List<Long> {
        val roots = synchronized(lock) { spawnedPids.toList() }
        val live = LinkedHashSet<Long>()
        val dead = mutableListOf<Long>()
        for (pid in roots) {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle != null && handle.isAlive) {
                live.add(pid)
                handle.descendants().forEach { live.add(it.pid()) }
            } else {
                dead.add(pid)
            }
        }
        // Drop roots that have exited so the tracking list stays bounded.
        if (dead.isNotEmpty()) {
            synchronized(lock) { spawnedPids.removeAll(dead.toSet()) }
        }
        return live.toList()
    }

    override fun adopt(pid: Long) {
        synchronized(lock) {
            // Assign under the lock with a closed-guard: a CloseHandle from a
            // concurrent close() must not race assignment into a recycled handle.
            check(!closed) { "process group is closed" }
            Win32.assignToJob(job, pid)
            spawnedPids.add(pid)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                Win32.terminateJob(job)
            } finally {
                Win32.closeHandle(job)
            }
        }
    }
}

/** Minimal libc bindings (POSIX). */
internal object Libc {
    internal const val SIGKILL: Int = 9
    internal const val SIGTERM: Int = 15

    // Common Linux (x86-64 / arm64) values for the stop/continue signals.
    internal const val SIGSTOP: Int = 19
    internal const val SIGCONT: Int = 18

    private val linker: Linker = Linker.nativeLinker()

    private val killHandle: MethodHandle =
        linker.downcallHandle(
            linker
                .defaultLookup()
                .find("kill")
                .orElseThrow { UnsatisfiedLinkError("libc!kill not found") },
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )

    // `pid_t` is a 32-bit `int` on Linux, so narrowing the JVM `Long` pid is the
    // faithful ABI conversion (a real pid never exceeds `Int.MAX_VALUE`).

    /** `kill(-pgid, signal)` — signal the whole process group led by [pgid]. */
    internal fun killGroup(
        pgid: Long,
        signal: Int,
    ): Int = killHandle.invoke(-pgid.toInt(), signal) as Int

    /** `kill(pid, signal)` — signal a single process. */
    internal fun killProcess(
        pid: Long,
        signal: Int,
    ): Int = killHandle.invoke(pid.toInt(), signal) as Int
}

/** Minimal kernel32 (Win32 Job Object) bindings. */
internal object Win32 {
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS: Int = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: Int = 0x2000
    private const val PROCESS_TERMINATE: Int = 0x0001
    private const val PROCESS_SET_QUOTA: Int = 0x0100

    private val linker: Linker = Linker.nativeLinker()
    private val k32: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())

    private fun bind(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle =
        linker.downcallHandle(
            k32.find(name).orElseThrow { UnsatisfiedLinkError("kernel32!$name not found") },
            descriptor,
        )

    private val createJobObjectW =
        bind("CreateJobObjectW", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val setInformationJobObject =
        bind(
            "SetInformationJobObject",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    private val openProcess =
        bind(
            "OpenProcess",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
    private val assignProcessToJobObject =
        bind(
            "AssignProcessToJobObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
    private val terminateJobObject =
        bind(
            "TerminateJobObject",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
    private val closeHandleFn =
        bind("CloseHandle", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    private val getLastError =
        bind("GetLastError", FunctionDescriptor.of(ValueLayout.JAVA_INT))

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION (x64). Padding is explicit so the
    // LimitFlags offset is derived from the layout, not hand-counted.
    private val extendedLimitLayout: MemoryLayout =
        MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("PerProcessUserTimeLimit"),
            ValueLayout.JAVA_LONG.withName("PerJobUserTimeLimit"),
            ValueLayout.JAVA_INT.withName("LimitFlags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("MinimumWorkingSetSize"),
            ValueLayout.JAVA_LONG.withName("MaximumWorkingSetSize"),
            ValueLayout.JAVA_INT.withName("ActiveProcessLimit"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_LONG.withName("Affinity"),
            ValueLayout.JAVA_INT.withName("PriorityClass"),
            ValueLayout.JAVA_INT.withName("SchedulingClass"),
            // IO_COUNTERS
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            // memory limits tail
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
        )
    private val limitFlagsOffset: Long =
        extendedLimitLayout.byteOffset(MemoryLayout.PathElement.groupElement("LimitFlags"))

    internal fun createKillOnCloseJob(): MemorySegment {
        val job = createJobObjectW.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (job.address() == 0L) {
            throwLastError("CreateJobObjectW")
        }
        // The struct is only needed for the duration of the SetInformationJobObject
        // call; the returned job HANDLE is a kernel handle (a plain address value)
        // that outlives the arena.
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(extendedLimitLayout)
            info.set(ValueLayout.JAVA_INT, limitFlagsOffset, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            val ok =
                setInformationJobObject.invoke(
                    job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                    info,
                    extendedLimitLayout.byteSize().toInt(),
                ) as Int
            if (ok == 0) {
                throwLastError("SetInformationJobObject")
            }
        }
        return job
    }

    internal fun assignToJob(
        job: MemorySegment,
        pid: Long,
    ) {
        val process = openProcess.invoke(PROCESS_TERMINATE or PROCESS_SET_QUOTA, 0, pid.toInt()) as MemorySegment
        if (process.address() == 0L) {
            throwLastError("OpenProcess(pid=$pid)")
        }
        try {
            val ok = assignProcessToJobObject.invoke(job, process) as Int
            if (ok == 0) {
                throwLastError("AssignProcessToJobObject(pid=$pid)")
            }
        } finally {
            closeHandleFn.invoke(process) as Int
        }
    }

    internal fun terminateJob(job: MemorySegment) {
        terminateJobObject.invoke(job, 1) as Int
    }

    internal fun closeHandle(handle: MemorySegment) {
        closeHandleFn.invoke(handle) as Int
    }

    private fun throwLastError(call: String): Nothing {
        // Best-effort error code: without Linker.Option.captureCallState the value
        // can be stale if an intervening call clears it. The real Windows backend
        // (step 1) will capture GetLastError per call.
        val code = getLastError.invoke() as Int
        throw IOException("$call failed (GetLastError=$code)")
    }
}
