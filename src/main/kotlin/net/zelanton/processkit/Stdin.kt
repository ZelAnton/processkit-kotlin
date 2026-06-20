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
