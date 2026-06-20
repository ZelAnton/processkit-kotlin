package net.zelanton.processkit

import java.nio.file.Path

/**
 * Where a command's standard input comes from.
 *
 * The default ([none]) closes stdin immediately, so a child that reads stdin sees
 * EOF instead of blocking forever. A source is written on a background coroutine
 * and a broken pipe (a child that stops reading early) is tolerated.
 *
 * ```
 * val sorted = Command("sort").stdin(Stdin.fromString("banana\napple\n")).run()
 * ```
 */
public sealed class Stdin {
    internal object None : Stdin()

    internal class Bytes(
        val data: ByteArray,
    ) : Stdin()

    internal class FromFile(
        val path: Path,
    ) : Stdin()

    public companion object {
        /** Close stdin immediately (the child reads EOF). The default. */
        public fun none(): Stdin = None

        /** Feed [text] (UTF-8) to stdin, then close it. */
        public fun fromString(text: String): Stdin = Bytes(text.encodeToByteArray())

        /** Feed raw [data] to stdin, then close it. */
        public fun fromBytes(data: ByteArray): Stdin = Bytes(data.copyOf())

        /** Stream the contents of [path] to stdin, then close it. */
        public fun fromFile(path: Path): Stdin = FromFile(path)
    }
}

/** Whether this source provides no input (no source, or empty in-memory bytes). */
internal val Stdin.isEmptyStdin: Boolean
    get() =
        when (this) {
            Stdin.None -> true
            is Stdin.Bytes -> data.isEmpty()
            is Stdin.FromFile -> false
        }

/**
 * A stable FNV-1a digest of the stdin **source identity** for cassette matching —
 * never the payload at replay time. In-memory bytes hash their content; a file
 * source hashes its *path* (not its current bytes). Only meaningful for a
 * non-empty source (see [isEmptyStdin]).
 */
internal fun Stdin.contentDigest(): Long {
    val bytes =
        when (this) {
            Stdin.None -> ByteArray(0)
            is Stdin.Bytes -> data
            is Stdin.FromFile -> path.toString().encodeToByteArray()
        }
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis (14695981039346656037)
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xff)
        hash *= 0x100000001b3L // FNV-1a 64-bit prime
    }
    return hash
}
