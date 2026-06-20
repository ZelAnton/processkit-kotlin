package net.zelanton.processkit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.coroutines.cancellation.CancellationException

private const val NEWLINE: Byte = '\n'.code.toByte()
private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

/**
 * Read [stream] to EOF, returning the **exact** raw bytes. If [sink] is non-null,
 * each line (split on `\n`, a trailing `\r` stripped, decoded with [charset]) is
 * delivered to it as it is read — so a handler/tee observes output live while the
 * capture still gets the verbatim bytes. With no [sink], a plain bulk read.
 */
internal fun pumpStream(
    stream: InputStream,
    charset: Charset,
    sink: ((String) -> Unit)?,
): ByteArray {
    if (sink == null) return stream.readBytes()
    val raw = ByteArrayOutputStream()
    val line = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        raw.write(buffer, 0, read)
        var start = 0
        for (i in 0 until read) {
            if (buffer[i] == NEWLINE) {
                line.write(buffer, start, i - start)
                sink(decodeLine(line, charset))
                line.reset()
                start = i + 1
            }
        }
        line.write(buffer, start, read - start)
    }
    if (line.size() > 0) {
        sink(decodeLine(line, charset))
    }
    return raw.toByteArray()
}

private fun decodeLine(
    line: ByteArrayOutputStream,
    charset: Charset,
): String {
    var bytes = line.toByteArray()
    if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN) {
        bytes = bytes.copyOf(bytes.size - 1) // CRLF → drop the CR
    }
    return String(bytes, charset)
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
        pumpStream(ByteArrayInputStream(stdout), command.stdoutCharset, it)
    }
    lineSinkFor(command.stderrLineHandler, command.stderrTeeSink)?.let {
        pumpStream(ByteArrayInputStream(stderr), command.stderrCharset, it)
    }
}
