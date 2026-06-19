package net.zelanton.processkit.internal

import net.zelanton.processkit.Mechanism
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

    // setsid makes the leader's pgid == its pid, so we track pids as pgids.
    private val groupLeaders = mutableListOf<Long>()

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
        synchronized(groupLeaders) { groupLeaders.add(process.pid()) }
        return process
    }

    override fun killAll() {
        synchronized(groupLeaders) {
            for (pgid in groupLeaders) {
                Libc.killGroup(pgid, Libc.SIGKILL)
            }
            groupLeaders.clear()
        }
    }

    override fun requestStop(): Boolean {
        synchronized(groupLeaders) {
            for (pgid in groupLeaders) {
                Libc.killGroup(pgid, Libc.SIGTERM)
            }
        }
        return true
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
        Win32.assignToJob(job, process.pid())
        return process
    }

    override fun killAll() {
        Win32.terminateJob(job)
    }

    // Job Objects have no graceful-stop signal; close()/killAll() is atomic.
    override fun requestStop(): Boolean = false

    override fun close() {
        try {
            Win32.terminateJob(job)
        } finally {
            Win32.closeHandle(job)
        }
    }
}

/** Minimal libc bindings (POSIX). */
internal object Libc {
    internal const val SIGKILL: Int = 9
    internal const val SIGTERM: Int = 15

    private val linker: Linker = Linker.nativeLinker()

    private val killHandle: MethodHandle =
        linker.downcallHandle(
            linker
                .defaultLookup()
                .find("kill")
                .orElseThrow { UnsatisfiedLinkError("libc!kill not found") },
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )

    /** `kill(-pgid, signal)` — signal the whole process group led by [pgid]. */
    internal fun killGroup(
        pgid: Long,
        signal: Int,
    ): Int = killHandle.invoke(-pgid.toInt(), signal) as Int
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
