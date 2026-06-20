package net.zelanton.processkit.internal

import net.zelanton.processkit.Mechanism
import net.zelanton.processkit.ProcessException
import net.zelanton.processkit.ProcessGroupStats
import net.zelanton.processkit.ResourceLimits
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
import java.lang.invoke.VarHandle
import java.nio.file.Path
import kotlin.time.Duration.Companion.nanoseconds

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
        environment: Map<String, String?> = emptyMap(),
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

    /** A snapshot of the container's resource usage. */
    fun stats(): ProcessGroupStats

    override fun close() {
        killAll()
    }

    companion object {
        fun create(limits: ResourceLimits = ResourceLimits()): Containment =
            when (Os.current) {
                Os.WINDOWS -> WindowsJobContainment(limits)
                Os.LINUX -> PosixGroupContainment(limits)
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
    environment: Map<String, String?>,
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
        if (value == null) env.remove(name) else env[name] = value
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
internal class PosixGroupContainment(
    limits: ResourceLimits = ResourceLimits(),
) : Containment {
    init {
        // No whole-tree limit primitive without a cgroup; fail fast rather than
        // leave the tree silently unbounded.
        if (limits.any()) {
            throw ProcessException.ResourceLimit(
                "resource limits need a Job Object or cgroup; the process-group backend cannot enforce them",
            )
        }
    }

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
        environment: Map<String, String?>,
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

    // No kernel accounting without a cgroup, so only the live-group count is
    // available; CPU and memory are unreportable on this backend.
    override fun stats(): ProcessGroupStats =
        synchronized(lock) {
            pruneDead()
            ProcessGroupStats(
                activeProcessCount = groupLeaders.size + adoptedPids.size,
                totalCpuTime = null,
                peakMemoryBytes = null,
            )
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
internal class WindowsJobContainment(
    limits: ResourceLimits = ResourceLimits(),
) : Containment {
    init {
        if (limits.cpuQuota != null) {
            throw ProcessException.ResourceLimit(
                "cpuQuota is not yet enforced (Windows CPU rate control lands in a later increment)",
            )
        }
    }

    override val mechanism: Mechanism = Mechanism.JOB_OBJECT

    private val job: MemorySegment = Win32.createContainedJob(limits.memoryMax, limits.maxProcesses)
    private val lock = Any()
    private var closed = false

    override fun spawn(
        command: List<String>,
        workingDir: Path?,
        environment: Map<String, String?>,
        clearEnvironment: Boolean,
    ): Process {
        // ProcessBuilder gives no race-free assignment hook, so the child is
        // assigned immediately after start. The residual window (a child that
        // forks before assignment) is closed by CREATE_SUSPENDED in a later step.
        // NOTE for that step: once spawn creates a child CREATE_SUSPENDED and then
        // resumes it, spawn MUST serialize its assign→resume under `lock` (the way
        // [suspendAll]/[resumeAll] hold it), or a concurrent suspend walk landing in
        // that window would double-suspend the new child's primary thread and the
        // single spawn-resume would strand it suspended forever.
        val process = newProcessBuilder(command, workingDir, environment, clearEnvironment).start()
        try {
            Win32.assignToJob(job, process.pid())
        } catch (failure: Throwable) {
            // The child was started but never joined the job, so closing the job
            // would not reap it; kill it directly so it cannot escape containment.
            process.destroyForcibly()
            throw failure
        }
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

    // Suspend/resume the whole tree by walking a thread snapshot and Suspend/
    // ResumeThread-ing every thread owned by a member pid (Windows has no
    // process-level freeze). Held under the lock so close() can't free the job
    // handle mid-walk; a closed group is a trivial no-op (like killAll/signal, and
    // like the process-group backend, which has no live members once reaped).
    override fun suspendAll(): Unit =
        synchronized(lock) {
            if (!closed) Win32.suspendOrResumeMembers(job, suspend = true)
        }

    override fun resumeAll(): Unit =
        synchronized(lock) {
            if (!closed) Win32.suspendOrResumeMembers(job, suspend = false)
        }

    // Kernel-authoritative: the pids the Job Object itself reports (whole tree),
    // not an approximation from the spawned roots' live descendants. A closed group
    // has no members (empty), matching the process-group backend.
    override fun members(): List<Long> =
        synchronized(lock) {
            if (closed) emptyList() else Win32.jobMemberPids(job)
        }

    override fun adopt(pid: Long) {
        synchronized(lock) {
            // Assign under the lock with a closed-guard: a CloseHandle from a
            // concurrent close() must not race assignment into a recycled handle.
            check(!closed) { "process group is closed" }
            Win32.assignToJob(job, pid)
        }
    }

    override fun stats(): ProcessGroupStats =
        synchronized(lock) {
            check(!closed) { "process group is closed" }
            val (active, cpu100ns) = Win32.queryAccounting(job)
            ProcessGroupStats(
                activeProcessCount = active,
                // Job accounting CPU is in 100-nanosecond units.
                totalCpuTime = (cpu100ns * 100).nanoseconds,
                peakMemoryBytes = Win32.queryPeakJobMemory(job),
            )
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
    private const val JOB_OBJECT_LIMIT_ACTIVE_PROCESS: Int = 0x8
    private const val JOB_OBJECT_LIMIT_JOB_MEMORY: Int = 0x200
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
            ValueLayout.JAVA_LONG.withName("ProcessMemoryLimit"),
            ValueLayout.JAVA_LONG.withName("JobMemoryLimit"),
            ValueLayout.JAVA_LONG.withName("PeakProcessMemoryUsed"),
            ValueLayout.JAVA_LONG.withName("PeakJobMemoryUsed"),
        )
    private val limitFlagsOffset: Long =
        extendedLimitLayout.byteOffset(MemoryLayout.PathElement.groupElement("LimitFlags"))
    private val activeProcessLimitOffset: Long =
        extendedLimitLayout.byteOffset(MemoryLayout.PathElement.groupElement("ActiveProcessLimit"))
    private val jobMemoryLimitOffset: Long =
        extendedLimitLayout.byteOffset(MemoryLayout.PathElement.groupElement("JobMemoryLimit"))
    private val peakJobMemoryUsedOffset: Long =
        extendedLimitLayout.byteOffset(MemoryLayout.PathElement.groupElement("PeakJobMemoryUsed"))

    private const val JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS: Int = 1
    private val queryInformationJobObject =
        bind(
            "QueryInformationJobObject",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

    // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION (x64).
    private val basicAccountingLayout: MemoryLayout =
        MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("TotalUserTime"),
            ValueLayout.JAVA_LONG.withName("TotalKernelTime"),
            ValueLayout.JAVA_LONG.withName("ThisPeriodTotalUserTime"),
            ValueLayout.JAVA_LONG.withName("ThisPeriodTotalKernelTime"),
            ValueLayout.JAVA_INT.withName("TotalPageFaultCount"),
            ValueLayout.JAVA_INT.withName("TotalProcesses"),
            ValueLayout.JAVA_INT.withName("ActiveProcesses"),
            ValueLayout.JAVA_INT.withName("TotalTerminatedProcesses"),
        )
    private val totalUserTimeOffset: Long =
        basicAccountingLayout.byteOffset(MemoryLayout.PathElement.groupElement("TotalUserTime"))
    private val totalKernelTimeOffset: Long =
        basicAccountingLayout.byteOffset(MemoryLayout.PathElement.groupElement("TotalKernelTime"))
    private val activeProcessesOffset: Long =
        basicAccountingLayout.byteOffset(MemoryLayout.PathElement.groupElement("ActiveProcesses"))

    /**
     * Create a kill-on-close Job Object, optionally capping total committed memory
     * ([memoryMax] bytes) and live process count ([maxProcesses]) for the tree.
     */
    internal fun createContainedJob(
        memoryMax: Long?,
        maxProcesses: Int?,
    ): MemorySegment {
        val job = createJobObjectW.invoke(MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
        if (job.address() == 0L) {
            throwLastError("CreateJobObjectW")
        }
        // The struct is only needed for the duration of the SetInformationJobObject
        // call; the returned job HANDLE is a kernel handle (a plain address value)
        // that outlives the arena.
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(extendedLimitLayout)
            var flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (memoryMax != null) {
                flags = flags or JOB_OBJECT_LIMIT_JOB_MEMORY
                info.set(ValueLayout.JAVA_LONG, jobMemoryLimitOffset, memoryMax)
            }
            if (maxProcesses != null) {
                flags = flags or JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                info.set(ValueLayout.JAVA_INT, activeProcessLimitOffset, maxProcesses)
            }
            info.set(ValueLayout.JAVA_INT, limitFlagsOffset, flags)
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

    /** Active process count and cumulative CPU time (in 100-ns units) for [job]. */
    internal fun queryAccounting(job: MemorySegment): Pair<Int, Long> =
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(basicAccountingLayout)
            val ok =
                queryInformationJobObject.invoke(
                    job,
                    JOB_OBJECT_BASIC_ACCOUNTING_INFORMATION_CLASS,
                    info,
                    basicAccountingLayout.byteSize().toInt(),
                    MemorySegment.NULL,
                ) as Int
            if (ok == 0) {
                throwLastError("QueryInformationJobObject(accounting)")
            }
            val cpu100ns =
                info.get(ValueLayout.JAVA_LONG, totalUserTimeOffset) +
                    info.get(ValueLayout.JAVA_LONG, totalKernelTimeOffset)
            info.get(ValueLayout.JAVA_INT, activeProcessesOffset) to cpu100ns
        }

    /** Peak committed memory (bytes) charged to [job] (`PeakJobMemoryUsed`). */
    internal fun queryPeakJobMemory(job: MemorySegment): Long =
        Arena.ofConfined().use { arena ->
            val info = arena.allocate(extendedLimitLayout)
            val ok =
                queryInformationJobObject.invoke(
                    job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                    info,
                    extendedLimitLayout.byteSize().toInt(),
                    MemorySegment.NULL,
                ) as Int
            if (ok == 0) {
                throwLastError("QueryInformationJobObject(extended)")
            }
            info.get(ValueLayout.JAVA_LONG, peakJobMemoryUsedOffset)
        }

    // --- process-control (9b): per-thread suspend/resume + kernel-authoritative members ---

    private const val TH32CS_SNAPTHREAD: Int = 0x00000004
    private const val THREAD_SUSPEND_RESUME: Int = 0x0002
    private const val JOB_OBJECT_BASIC_PROCESS_ID_LIST_CLASS: Int = 3
    private const val ERROR_MORE_DATA: Int = 234
    private const val ERROR_INVALID_PARAMETER: Int = 87
    private const val INVALID_HANDLE_VALUE: Long = -1L
    private const val SUSPEND_RESUME_FAILED: Int = -1 // (DWORD)-1 from Suspend/ResumeThread

    // Capture GetLastError per call (the bound function's last-error is otherwise
    // racy across intervening calls — see throwLastError). A captured downcall
    // gains a leading MemorySegment param that receives the state.
    private val captureStateLayout: MemoryLayout = Linker.Option.captureStateLayout()
    private val captureCallState: Linker.Option = Linker.Option.captureCallState("GetLastError")
    private val getLastErrorVar: VarHandle =
        captureStateLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"))

    private fun bindCapturing(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle =
        linker.downcallHandle(
            k32.find(name).orElseThrow { UnsatisfiedLinkError("kernel32!$name not found") },
            descriptor,
            captureCallState,
        )

    private fun lastError(capture: MemorySegment): Int = getLastErrorVar.get(capture, 0L) as Int

    // THREADENTRY32 (all DWORD/LONG, so 7 × 4 bytes, no padding).
    private val threadEntryLayout: MemoryLayout =
        MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("dwSize"),
            ValueLayout.JAVA_INT.withName("cntUsage"),
            ValueLayout.JAVA_INT.withName("th32ThreadID"),
            ValueLayout.JAVA_INT.withName("th32OwnerProcessID"),
            ValueLayout.JAVA_INT.withName("tpBasePri"),
            ValueLayout.JAVA_INT.withName("tpDeltaPri"),
            ValueLayout.JAVA_INT.withName("dwFlags"),
        )
    private val th32ThreadIDOffset: Long =
        threadEntryLayout.byteOffset(MemoryLayout.PathElement.groupElement("th32ThreadID"))
    private val th32OwnerProcessIDOffset: Long =
        threadEntryLayout.byteOffset(MemoryLayout.PathElement.groupElement("th32OwnerProcessID"))

    private val createToolhelp32Snapshot =
        bindCapturing(
            "CreateToolhelp32Snapshot",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
    private val thread32First =
        bind("Thread32First", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val thread32Next =
        bind("Thread32Next", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val openThread =
        bindCapturing(
            "OpenThread",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
    private val suspendThread =
        bindCapturing("SuspendThread", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    private val resumeThread =
        bindCapturing("ResumeThread", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    private val queryInformationJobObjectCapturing =
        bindCapturing(
            "QueryInformationJobObject",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

    /**
     * The pids currently assigned to [job] — kernel-authoritative, via
     * `QueryInformationJobObject(JobObjectBasicProcessIdList)`. The list is a
     * variable-length struct (two-`DWORD` header + an inline `ULONG_PTR` array),
     * so query into an 8-byte-aligned buffer and grow on `ERROR_MORE_DATA`.
     */
    internal fun jobMemberPids(job: MemorySegment): List<Long> {
        var cap = 64
        while (true) {
            val bytes = 8L + cap.toLong() * 8L // header (2 × DWORD) + cap × ULONG_PTR
            val pids =
                Arena.ofConfined().use { arena ->
                    val buf = arena.allocate(bytes, 8)
                    val capture = arena.allocate(captureStateLayout)
                    val ok =
                        queryInformationJobObjectCapturing.invoke(
                            capture,
                            job,
                            JOB_OBJECT_BASIC_PROCESS_ID_LIST_CLASS,
                            buf,
                            bytes.toInt(),
                            MemorySegment.NULL,
                        ) as Int
                    if (ok != 0) {
                        val n = buf.get(ValueLayout.JAVA_INT, 4L) // NumberOfProcessIdsInList
                        ArrayList<Long>(n).apply {
                            for (i in 0 until n) add(buf.get(ValueLayout.JAVA_LONG, 8L + i.toLong() * 8L))
                        }
                    } else {
                        val err = lastError(capture)
                        if (err != ERROR_MORE_DATA) {
                            throw IOException("QueryInformationJobObject(pidList) failed (GetLastError=$err)")
                        }
                        val assigned = buf.get(ValueLayout.JAVA_INT, 0L) // NumberOfAssignedProcesses
                        cap = maxOf(assigned, cap) * 2 // always grow so the loop can't spin in place
                        null
                    }
                }
            if (pids != null) return pids
        }
    }

    /**
     * Suspend or resume every thread of every process currently in [job] — Windows
     * has no process-level freeze, so walk a system-wide thread snapshot and
     * Suspend/ResumeThread each thread owned by a member pid. Best-effort, not
     * atomic: threads created mid-walk are missed, and the calls keep per-thread
     * suspend *counts* (a suspend needs a matching resume). A thread that exits
     * mid-walk is vacuously handled; a genuine failure is raised after the walk.
     */
    internal fun suspendOrResumeMembers(
        job: MemorySegment,
        suspend: Boolean,
    ) {
        val members = jobMemberPids(job).toHashSet()
        if (members.isEmpty()) return // an empty job is trivially suspended/resumed
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(captureStateLayout)
            val snapshot = createToolhelp32Snapshot.invoke(capture, TH32CS_SNAPTHREAD, 0) as MemorySegment
            if (snapshot.address() == INVALID_HANDLE_VALUE) {
                throw IOException("CreateToolhelp32Snapshot failed (GetLastError=${lastError(capture)})")
            }
            try {
                val entry = arena.allocate(threadEntryLayout)
                entry.set(ValueLayout.JAVA_INT, 0L, threadEntryLayout.byteSize().toInt()) // dwSize
                var firstError: IOException? = null
                var ok = thread32First.invoke(snapshot, entry) as Int
                while (ok != 0) {
                    val owner = entry.get(ValueLayout.JAVA_INT, th32OwnerProcessIDOffset).toLong() and 0xFFFFFFFFL
                    if (owner in members) {
                        val tid = entry.get(ValueLayout.JAVA_INT, th32ThreadIDOffset).toLong() and 0xFFFFFFFFL
                        try {
                            suspendOrResumeThread(tid, suspend)
                        } catch (failure: IOException) {
                            if (firstError == null) firstError = failure
                        }
                    }
                    ok = thread32Next.invoke(snapshot, entry) as Int
                }
                firstError?.let { throw it }
            } finally {
                runCatching { closeHandleFn.invoke(snapshot) as Int } // never mask the walk's error
            }
        }
    }

    // Suspend (increment) or resume (decrement) a single thread's suspend count.
    private fun suspendOrResumeThread(
        tid: Long,
        suspend: Boolean,
    ): Unit =
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(captureStateLayout)
            val thread = openThread.invoke(capture, THREAD_SUSPEND_RESUME, 0, tid.toInt()) as MemorySegment
            if (thread.address() == 0L) {
                val err = lastError(capture)
                // A stale tid (thread exited between the snapshot and this open) fails
                // ERROR_INVALID_PARAMETER and is vacuously handled; any other failure
                // (e.g. ACCESS_DENIED on a live thread) is genuine and reported.
                if (err == ERROR_INVALID_PARAMETER) return@use
                throw IOException("OpenThread(tid=$tid) failed (GetLastError=$err)")
            }
            try {
                val capture2 = arena.allocate(captureStateLayout)
                val prev = (if (suspend) suspendThread else resumeThread).invoke(capture2, thread) as Int
                if (prev == SUSPEND_RESUME_FAILED) {
                    val verb = if (suspend) "SuspendThread" else "ResumeThread"
                    throw IOException("$verb(tid=$tid) failed (GetLastError=${lastError(capture2)})")
                }
            } finally {
                runCatching { closeHandleFn.invoke(thread) as Int } // never mask the suspend/resume error
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
