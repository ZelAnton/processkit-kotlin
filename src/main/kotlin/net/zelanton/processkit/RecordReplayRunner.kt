package net.zelanton.processkit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** The on-disk cassette format revision; an unknown version fails loudly on load. */
private const val CASSETTE_VERSION: Int = 1

/** Refuse to load a cassette larger than this (corrupt / wrong file guard). */
private const val MAX_CASSETTE_BYTES: Long = 64L shl 20 // 64 MiB

private val cassetteJson =
    Json {
        prettyPrint = true
        encodeDefaults = false // omit fields at their default (cwd, hasStdin, …)
        ignoreUnknownKeys = true // forward-compatible with newer cassettes
    }

/** The whole fixture file: a format version plus the entries in capture order. */
@Serializable
private data class CassetteFile(
    val version: Int,
    val entries: List<Entry>,
)

/**
 * One captured `command → result` pair. Only environment *values* are redacted
 * (stored as sorted variable *names*); `program`, `args`, `cwd`, `stdout`, and
 * `stderr` are stored **verbatim** and can carry secrets — review a cassette
 * before committing it. `stdout` is Base64 (the real result is raw bytes).
 */
@Serializable
private data class Entry(
    // --- the match key ---
    val program: String,
    val args: List<String>,
    val cwd: String? = null,
    val stdinDigest: Long? = null,
    // --- stored for visibility, not matched on ---
    val hasStdin: Boolean = false,
    val envNames: List<String> = emptyList(),
    // --- the captured output ---
    val stdoutBase64: String,
    val stderr: String,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

/** The match key of a live command or a stored entry (both built the same way). */
private data class Key(
    val program: String,
    val args: List<String>,
    val cwd: String?,
    val hasStdin: Boolean,
    val stdinDigest: Long?,
)

/** A key's entries in capture order, with a cursor: replay in order, then repeat the last. */
private class ReplaySlot(
    val entries: MutableList<Entry> = mutableListOf(),
) {
    private var next = 0

    fun play(): Entry {
        check(entries.isNotEmpty()) { "replay slot has no entries" }
        val index = minOf(next, entries.size - 1)
        next += 1
        return entries[index]
    }
}

private sealed interface Mode {
    class Record(
        val inner: ProcessRunner,
        val path: Path,
    ) : Mode {
        val lock = Any()
        val recorded = mutableListOf<Entry>()
        var dirty = false
    }

    class Replay(
        val slots: Map<Key, ReplaySlot>,
    ) : Mode
}

/**
 * A [ProcessRunner] that records real runs to a JSON cassette, or replays a
 * cassette hermetically — the bridge between [ScriptedRunner] and the real
 * backend. Run the tool once in *record* mode and every command→result pair is
 * captured to a human-diffable JSON file; switch to *replay* mode and the cassette
 * serves the recorded results with no subprocess.
 *
 * ```
 * // record (writes on close, or call save())
 * RecordReplayRunner.record(Path.of("git.json")).use { it.run(Command("git", "status")) }
 * // replay (hermetic; a miss throws ProcessException.CassetteMiss)
 * val out = RecordReplayRunner.replay(Path.of("git.json")).run(Command("git", "status"))
 * ```
 *
 * **Matching**: program + args + working dir + stdin source digest. Duplicates
 * replay in capture order, then the last entry repeats. **Secret-safety**: env
 * *values* are never written (sorted names only), but argv / cwd / stdout / stderr
 * are stored verbatim — review fixtures before committing. The file is written
 * owner-only (`0600`) on POSIX; on Windows restrict the containing directory.
 *
 * Covers the capturing/run verbs ([execute]); it does not record streaming
 * (`start`). A replayed timed-out run still raises
 * [ProcessException.Timeout][ProcessException.Timeout] through the success-checking
 * verbs, carrying the replaying command's timeout.
 */
public class RecordReplayRunner private constructor(
    private val mode: Mode,
) : ProcessRunner,
    AutoCloseable {
    override suspend fun execute(command: Command): ProcessResult<ByteArray> =
        when (mode) {
            is Mode.Record -> {
                // A thrown failure (NotFound/Spawn) or cancellation records nothing;
                // a non-zero exit or a captured timeout is a result and is recorded.
                val result = mode.inner.execute(command)
                synchronized(mode.lock) {
                    mode.recorded.add(entryOf(command, result))
                    mode.dirty = true
                }
                result
            }
            is Mode.Replay -> {
                val entry =
                    synchronized(mode) { mode.slots[keyOf(command)]?.play() }
                        ?: throw ProcessException.CassetteMiss(command.program)
                resultOf(command.program, entry)
            }
        }

    /**
     * Write the cassette now (record mode; a no-op in replay). This is the
     * error-surfacing path — [close] flushes best-effort. Idempotent (rewrites the
     * whole file).
     */
    public fun save() {
        val record = mode as? Mode.Record ?: return
        // Encode and write under the lock: serializes concurrent saves (no racing
        // file writes) and clears `dirty` only after the write succeeds (a failed
        // write leaves it dirty for the next save / close).
        synchronized(record.lock) {
            val snapshot = CassetteFile(CASSETTE_VERSION, record.recorded.toList())
            writeCassette(record.path, cassetteJson.encodeToString(snapshot))
            record.dirty = false
        }
    }

    /** Flush the cassette best-effort if there are unsaved runs. Use [save] to surface errors. */
    override fun close() {
        if (mode is Mode.Record && synchronized(mode.lock) { mode.dirty }) {
            runCatching { save() }
        }
    }

    public companion object {
        /**
         * Record every run through [inner] (the real [JobRunner] by default), to be
         * written to [path] by [save] or best-effort on [close]. Nothing touches
         * the filesystem until then.
         */
        public fun record(
            path: Path,
            inner: ProcessRunner = JobRunner,
        ): RecordReplayRunner = RecordReplayRunner(Mode.Record(inner, path))

        /**
         * Load the cassette at [path] and serve its entries hermetically. Throws
         * [IOException] for a missing/oversized/corrupt file or an unknown format
         * version.
         */
        public fun replay(path: Path): RecordReplayRunner {
            val size = Files.size(path)
            if (size > MAX_CASSETTE_BYTES) {
                throw IOException("cassette is $size bytes, over the $MAX_CASSETTE_BYTES-byte limit")
            }
            val cassette =
                try {
                    cassetteJson.decodeFromString<CassetteFile>(Files.readString(path))
                } catch (failure: Exception) {
                    if (failure is IOException) throw failure
                    throw IOException("cassette at $path is corrupt: ${failure.message}", failure)
                }
            if (cassette.version != CASSETTE_VERSION) {
                throw IOException(
                    "cassette version ${cassette.version} is not supported (this build reads version $CASSETTE_VERSION)",
                )
            }
            val slots = LinkedHashMap<Key, ReplaySlot>()
            for (entry in cassette.entries) {
                validateOutcome(entry)
                slots.getOrPut(keyOfEntry(entry)) { ReplaySlot() }.entries.add(entry)
            }
            return RecordReplayRunner(Mode.Replay(slots))
        }
    }
}

private fun keyOf(command: Command): Key {
    val hasStdin = !command.stdinSource.isEmptyStdin
    return Key(
        program = command.program,
        args = command.commandLine.drop(1),
        cwd = command.workingDirectory?.toString(),
        hasStdin = hasStdin,
        stdinDigest = if (hasStdin) command.stdinSource.contentDigest() else null,
    )
}

private fun keyOfEntry(entry: Entry): Key = Key(entry.program, entry.args, entry.cwd, entry.hasStdin, entry.stdinDigest)

private fun entryOf(
    command: Command,
    result: ProcessResult<ByteArray>,
): Entry {
    val hasStdin = !command.stdinSource.isEmptyStdin
    return Entry(
        program = command.program,
        args = command.commandLine.drop(1),
        cwd = command.workingDirectory?.toString(),
        stdinDigest = if (hasStdin) command.stdinSource.contentDigest() else null,
        hasStdin = hasStdin,
        envNames = command.environmentOverrides.keys.sorted(),
        stdoutBase64 = Base64.getEncoder().encodeToString(result.stdout),
        stderr = result.stderr,
        exitCode = if (result.timedOut) null else result.exitCode,
        timedOut = result.timedOut,
        truncated = result.truncated,
    )
}

private fun resultOf(
    program: String,
    entry: Entry,
): ProcessResult<ByteArray> =
    ProcessResult(
        program = program,
        stdout = Base64.getDecoder().decode(entry.stdoutBase64),
        stderr = entry.stderr,
        exitCode = entry.exitCode ?: 0,
        timedOut = entry.timedOut,
        truncated = entry.truncated,
    )

/** A timed-out entry carries no exit code; an entry can't be both. */
private fun validateOutcome(entry: Entry) {
    if (entry.timedOut && entry.exitCode != null) {
        throw IOException(
            "cassette entry for `${entry.program}` is contradictory: a timed-out run has no exit code",
        )
    }
}

/** Write [json] to [path], owner-only (`0600`) on POSIX before the content lands. */
private fun writeCassette(
    path: Path,
    json: String,
) {
    val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    try {
        // Tighten perms before writing so secret-bearing content is never readable
        // at a loose umask. Windows has no POSIX perms — the directory ACL governs.
        if (Files.notExists(path)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(ownerOnly))
        } else {
            Files.setPosixFilePermissions(path, ownerOnly)
        }
    } catch (unsupported: UnsupportedOperationException) {
        // Non-POSIX filesystem (Windows): rely on the containing directory's ACL.
    }
    Files.writeString(path, json)
}
