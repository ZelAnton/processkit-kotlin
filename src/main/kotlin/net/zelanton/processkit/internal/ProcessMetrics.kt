package net.zelanton.processkit.internal

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Per-process CPU time and peak resident memory; either is `null` when unreported. */
internal class ProcessMetrics(
    val cpuTime: Duration?,
    val peakMemoryBytes: Long?,
) {
    internal companion object {
        internal val EMPTY = ProcessMetrics(null, null)
    }
}

/**
 * Per-process metrics for [pid], or [ProcessMetrics.EMPTY] where unavailable
 * (macOS, the process-group backend, a scripted/exited pid). Linux reads
 * `/proc/<pid>/{stat,status}`; Windows uses `GetProcessTimes` /
 * `K32GetProcessMemoryInfo`.
 */
internal fun processMetrics(pid: Long): ProcessMetrics =
    when (Os.current) {
        Os.WINDOWS -> Win32.processMetrics(pid)
        Os.LINUX -> linuxProcessMetrics(pid)
        else -> ProcessMetrics.EMPTY
    }

private fun linuxProcessMetrics(pid: Long): ProcessMetrics =
    ProcessMetrics(cpuTime = linuxCpuTime(pid), peakMemoryBytes = linuxPeakRss(pid))

// CPU: /proc/<pid>/stat fields utime (14) + stime (15), in clock ticks. The comm
// field (2) can contain spaces/parens, so parse after the last ')'.
private fun linuxCpuTime(pid: Long): Duration? {
    val stat = readOrNull(Path.of("/proc/$pid/stat")) ?: return null
    val afterComm = stat.substringAfterLast(')', missingDelimiterValue = "")
    val fields = afterComm.trim().split(Regex("\\s+"))
    // After ')', index 0 is field 3 (state); utime = field 14 → index 11, stime → index 12.
    if (fields.size <= 12) return null
    val utime = fields[11].toLongOrNull() ?: return null
    val stime = fields[12].toLongOrNull() ?: return null
    val hz = Libc.clockTicksPerSecond()
    if (hz <= 0) return null
    // All-BigInteger so a pathological /proc value saturates rather than throwing
    // (utime/stime are non-negative tick counts): (utime + stime) / hz seconds in nanos.
    val nanos = (utime.toBigInteger() + stime.toBigInteger()) * 1_000_000_000.toBigInteger() / hz.toBigInteger()
    return nanos.min(Long.MAX_VALUE.toBigInteger()).toLong().nanoseconds
}

// Peak memory: /proc/<pid>/status VmHWM (high-water resident set), reported in kB.
private fun linuxPeakRss(pid: Long): Long? {
    val status = readOrNull(Path.of("/proc/$pid/status")) ?: return null
    val line = status.lineSequence().firstOrNull { it.startsWith("VmHWM:") } ?: return null
    val kb =
        line
            .removePrefix("VmHWM:")
            .trim()
            .substringBefore(' ')
            .toLongOrNull() ?: return null
    if (kb < 0) return null
    return if (kb > Long.MAX_VALUE / 1024) Long.MAX_VALUE else kb * 1024 // saturate, don't throw
}

private fun readOrNull(path: Path): String? =
    try {
        Files.readString(path)
    } catch (_: IOException) {
        null // the process exited (or /proc isn't this kind) — no metrics
    }
