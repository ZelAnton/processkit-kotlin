package net.zelanton.processkit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

private const val NEWLINE: Byte = '\n'.code.toByte()
private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

/** The captured output plus whether the [OutputBufferPolicy] dropped/over-capped it. */
internal class PumpResult(
    val bytes: ByteArray,
    val truncated: Boolean,
    val overLimit: Boolean,
)

/**
 * Read [stream] to EOF. If [sink] is non-null, each line (split on `\n`, a trailing
 * `\r` stripped, decoded with [charset]) is delivered to it as it is read. The
 * returned bytes are the **exact** raw stream under an unbounded [policy], or the
 * retained (capped) lines under a bounded one. The pump always drains the pipe.
 */
internal fun pumpStream(
    stream: InputStream,
    charset: Charset,
    sink: ((String) -> Unit)?,
    policy: OutputBufferPolicy,
): PumpResult {
    if (sink == null && policy.isUnbounded) {
        return PumpResult(stream.readBytes(), truncated = false, overLimit = false)
    }
    val retention = if (policy.isUnbounded) null else LineRetention(policy)
    val raw = if (retention == null) ByteArrayOutputStream() else null
    val line = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        raw?.write(buffer, 0, read)
        var start = 0
        for (i in 0 until read) {
            if (buffer[i] == NEWLINE) {
                line.write(buffer, start, i - start)
                emitLine(line, charset, sink, retention)
                line.reset()
                start = i + 1
            }
        }
        line.write(buffer, start, read - start)
    }
    if (line.size() > 0) {
        emitLine(line, charset, sink, retention)
    }
    return if (retention != null) {
        PumpResult(retention.bytes(), retention.truncated, retention.overLimit)
    } else {
        PumpResult(raw!!.toByteArray(), truncated = false, overLimit = false)
    }
}

private fun emitLine(
    line: ByteArrayOutputStream,
    charset: Charset,
    sink: ((String) -> Unit)?,
    retention: LineRetention?,
) {
    var content = line.toByteArray()
    if (content.isNotEmpty() && content.last() == CARRIAGE_RETURN) {
        content = content.copyOf(content.size - 1) // CRLF → drop the CR
    }
    sink?.invoke(String(content, charset))
    retention?.add(content)
}

/** A bounded backlog of retained line bytes, applying an [OutputBufferPolicy]. */
private class LineRetention(
    private val policy: OutputBufferPolicy,
) {
    private val chunks = ArrayDeque<ByteArray>()
    private var bytes = 0
    var truncated = false
        private set
    var overLimit = false
        private set

    fun add(content: ByteArray) {
        when (policy.overflow) {
            OverflowMode.ERROR -> if (exceedsIfAdded(content.size)) overLimit = true else retain(content)
            OverflowMode.DROP_NEWEST -> if (exceedsIfAdded(content.size)) truncated = true else retain(content)
            OverflowMode.DROP_OLDEST -> {
                retain(content)
                while (overCap()) {
                    bytes -= chunks.removeFirst().size
                    truncated = true
                }
            }
        }
    }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) out.write(NEWLINE.toInt())
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun retain(content: ByteArray) {
        chunks.addLast(content)
        bytes += content.size
    }

    // For ERROR / DROP_NEWEST: would adding this line breach a cap? (No cap set
    // under ERROR means zero-tolerance — any line breaches.)
    private fun exceedsIfAdded(len: Int): Boolean {
        if (policy.maxLines == null && policy.maxBytes == null) return true
        if (policy.maxLines != null && chunks.size + 1 > policy.maxLines) return true
        if (policy.maxBytes != null && bytes + len > policy.maxBytes) return true
        return false
    }

    private fun overCap(): Boolean {
        if (policy.maxLines != null && chunks.size > policy.maxLines) return true
        if (policy.maxBytes != null && bytes > policy.maxBytes) return true
        return false
    }
}

/**
 * Compose a line [handler] and a [tee] into one sink, or `null` if neither is set.
 * Each is fault-isolated: one that throws is disabled for the rest of the run (the
 * other, and capture, continue); cancellation always propagates.
 */
internal fun lineSinkFor(
    handler: ((String) -> Unit)?,
    tee: Appendable?,
): ((String) -> Unit)? {
    if (handler == null && tee == null) return null
    var liveHandler = handler
    var liveTee = tee
    return { line ->
        liveHandler?.let { invoke ->
            try {
                invoke(line)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                liveHandler = null
                log.debug("output line handler threw; disabled for the rest of the run: {}", failure.message)
            }
        }
        liveTee?.let { sink ->
            try {
                // Synchronize on the sink: the stdout and stderr pumps run on
                // separate threads, so a sink shared across both tees (e.g. one
                // StringBuilder for both streams) would otherwise race.
                synchronized(sink) { sink.append(line).append('\n') }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                liveTee = null
                log.debug("output tee threw; disabled for the rest of the run: {}", failure.message)
            }
        }
    }
}

/**
 * Replay [command]'s line handlers / tees over already-captured [stdout] / [stderr]
 * bytes — the hermetic path for a [ScriptedRunner] bulk reply, so progress-reporting
 * code tests without a subprocess. Same fault-isolation as the live pump.
 */
internal fun replayLineHandlers(
    command: Command,
    stdout: ByteArray,
    stderr: ByteArray,
) {
    lineSinkFor(command.stdoutLineHandler, command.stdoutTeeSink)?.let {
        pumpStream(ByteArrayInputStream(stdout), command.stdoutCharset, it, OutputBufferPolicy.unbounded())
    }
    lineSinkFor(command.stderrLineHandler, command.stderrTeeSink)?.let {
        pumpStream(ByteArrayInputStream(stderr), command.stderrCharset, it, OutputBufferPolicy.unbounded())
    }
}
