# Changelog

All notable changes to **processkit** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Windows process-control parity: `ProcessGroup.suspend()` / `resume()` now work on
  Windows (no more `Unsupported`) by suspending/resuming every thread of every
  member process (a system-wide thread snapshot, filtered to the Job Object's pids),
  and `ProcessGroup.members()` is now kernel-authoritative on Windows
  (`QueryInformationJobObject(JobObjectBasicProcessIdList)`) rather than an estimate
  from the spawned roots' live descendants.
- Environment removal: `Command.envRemove(name)` drops a variable the child would
  otherwise inherit, and `CliClient.defaultEnvRemove(name)` makes it a client-wide
  default; a per-command `env` / `envRemove` for the same key wins. Env overrides
  are now an ordered set-or-remove list (the last op for a key wins).
- Scripted-stream timing on `Reply`: `Reply.pending()` parks a run until it is
  cancelled — a `Command.timeout` surfaces it as timed-out, and on `start` the
  watchdog / `close` reaps it — the hermetic mirror of a hung long-runner for
  cancellation/timeout tests; `Reply.withLineDelay(d)` paces a scripted `start`
  stream line by line so a streaming test observes incremental delivery (the bulk
  verbs ignore it).
- Output buffer policy: `Command.outputBuffer(OutputBufferPolicy)` caps how much
  captured output the bulk verbs retain — `bounded(n)` (ring buffer, drop oldest),
  `failLoud(n)` (error past the ceiling), `withMaxBytes` / `withOverflow`
  (`DROP_OLDEST` / `DROP_NEWEST` / `ERROR`). A dropped capture sets the new
  `ProcessResult.truncated`; `run`/`parse` reject a truncated capture, and the
  fail-loud ceiling raises the new `ProcessException.OutputTooLarge`. The pipe is
  always drained, so the child never blocks.
- `RunningProcess.outputEvents(): Flow<OutputEvent>` — stdout and stderr merged
  into one stream, each line tagged `OutputEvent.Stdout`/`Stderr` in arrival order.
- Interactive stdin: `Command.keepStdinOpen()` + `RunningProcess.takeStdin()` hand
  back a `ProcessStdin` writer (`write` / `writeLine` / `flush` / `close`) so a
  streamed child can be fed incrementally. Only affects `start()`; the bulk verbs
  close stdin as before, and `finish` / `waitFor` close an untaken kept-open stdin
  so a stdin-reading child can still exit.
- Live output observation on `Command`: `onStdoutLine` / `onStderrLine` (a
  fault-isolated callback per decoded line as the capture pump reads it),
  `stdoutTee` / `stderrTee` (mirror each line to an `Appendable` — `System.out`, a
  file `Writer`, …), and `stdoutEncoding` / `stderrEncoding` (decode non-UTF-8
  output). Handlers/tees fire on the bulk verbs (and replay over a `ScriptedRunner`
  reply, so progress code tests hermetically); a sink that throws is disabled for
  the rest of the run without affecting capture.
- Streaming `ProcessRunner` seam: `start(command): RunningProcess` is now part of
  the seam (default throws `ProcessException.Unsupported`). `ScriptedRunner.start`
  hands back a scripted handle whose canned output streams through the same
  `stdoutLines` / `waitForLine` / `finish` machinery as a real child, so streaming
  code tests hermetically; `ScriptedRunner.onSequence` serves fail-then-succeed
  reply sequences; `RecordingRunner` records streamed runs too. New `firstLine`
  verb (on `ProcessRunner`, `Command`, and `CliClient`) — stream stdout and return
  the first matching line (`null` if none), reaping the run.
- Record/replay cassettes: `RecordReplayRunner.record(path)` captures real
  command→result pairs to a human-diffable JSON cassette; `RecordReplayRunner.replay(path)`
  serves them hermetically (a miss is `ProcessException.CassetteMiss`, never a
  surprise subprocess). Matches on program + args + working dir + stdin digest;
  duplicates replay in order then repeat the last. Environment **values** are never
  written (sorted names only); the file is owner-only (`0600`) on POSIX.
- Observability via SLF4J: the library logs lifecycle events (run start/finish,
  retry attempts, supervisor restarts, group signal/suspend/resume/shutdown) at
  DEBUG under the `net.zelanton.processkit` logger. Messages are secret-safe —
  program names and exit codes only, never argv arguments or environment values.
  SLF4J is an `implementation` dependency; with no binding it is a silent no-op.
- Resource limits: `ProcessGroup(ResourceLimits(...))` caps the whole tree's
  committed memory (`memoryMax`) and live process count (`maxProcesses`), enforced
  by the Windows Job Object. A cap that can't be enforced — any limit on the
  process-group backend, or `cpuQuota` (not yet implemented) — fails fast at
  construction with the new `ProcessException.ResourceLimit`, never silently
  leaving the tree unbounded.
- Group stats: `ProcessGroup.stats()` returns a `ProcessGroupStats` snapshot
  (active process count and, under a Job Object, total CPU time and peak memory;
  `null` on the process-group backend), and `sampleStats(every)` is a `Flow` of
  snapshots (first immediately, then per interval, ending when the group can no
  longer report).
- Process-control on `ProcessGroup`: `signal(Signal)` (broadcast a `Signal` to the
  whole tree — any signal on Unix; only `Signal.Kill` on Windows, else
  `ProcessException.Unsupported`), `suspend()` / `resume()` (freeze/thaw the tree
  via `SIGSTOP`/`SIGCONT` on Unix; Windows deferred), `members()` (a snapshot of
  the group's pids), and `adopt(process)` (bring an externally-started process
  under the group). New `Signal` type and `ProcessException.Unsupported`.
- `CliClient` — a reusable core for typed CLI wrappers (`git`/`jj`/`gh`/…): owns
  the program, a `ProcessRunner`, and client-wide defaults (`defaultTimeout` /
  `defaultEnv`); builds preconfigured commands (`command` / `commandIn`); and
  offers every run verb plus `parse`, each accepting an arg list or a ready-made
  `Command`. Compose it into a typed facade — no codegen needed.
- `RecordingRunner` + `Invocation` — a test double that records every command
  (program, args, working dir, env, stdin presence) before delegating, with
  `calls` / `onlyCall()` / `hasFlag` for input assertions; `toString` redacts
  argv and env values.
- `parse` — a verb on `Command`, `ProcessRunner`, and `CliClient` that requires a
  zero exit and feeds the untrimmed stdout to a transform (a throwing transform
  propagates; the run honors `retry`, the transform is not retried).
- `Command.retry(maxAttempts, backoff, retryIf)` — replay one run to success: the
  success-checking verbs (`run` / `runUnit` / `exitCode` / `probe`) re-execute a
  fresh process while a classifier accepts the `ProcessException`, sleeping a fixed
  `backoff` between tries; the capturing verbs and a cancelled run never retry.
  `RetryWhen` offers ready-made classifiers (`timedOut`, `transient`, `exitCode(…)`).
- Readiness probes on `RunningProcess`: `waitForLine` (until a stdout line
  matches), `waitForPort` (until a TCP port accepts), and `waitUntil` (a custom
  check) — each throwing the new `ProcessException.NotReady` (distinct from
  `Timeout`) on the deadline, without killing the child.
- `Command(program, args: List<String>)` constructor and a
  `RunningProcess.waitForPort(host, port, timeout)` overload for the common
  call shapes.
- `outputAll` / `outputAllBytes` now default `concurrency` to `DEFAULT_CONCURRENCY`
  (the available-processor count), so the batch APIs work without picking a number.
- `Supervisor` — keeps a command alive: restart policies (`ALWAYS` / `ON_CRASH` /
  `NEVER`), bounded restarts, exponential backoff with jitter, a `stopWhen`
  condition, and a pluggable runner; returns a `SupervisionOutcome` (`StopReason`).
- `Pipeline` (`Command.pipe(...)`) — shell-free `a | b | c`: stages connected
  stdout→stdin in-process (no shell, no quoting/injection surface), all in one
  shared kill-on-close container, with pipefail attribution and a chain `timeout`.
- `RunningProcess.waitFor()` plus `waitAny(...)` / `waitAll(...)` — wait for a
  process to exit (returning its exit code), race several for the first to exit,
  or join several; the handles stay usable afterwards.
- `Stdin` + `Command.stdin(...)` — feed a command's standard input from a string,
  bytes, or a file. The default closes stdin (EOF) so a stdin-reading child can't
  block forever.
- `RunningProcess` (via `Command.start()` / `ProcessGroup.start()`) — a live
  handle for streaming: `stdoutLines(): Flow<String>`, background stderr capture,
  a `timeout` watchdog that bounds the stream, and `finish(): Finished`. It is
  `AutoCloseable`; `use { }` reaps the tree on a dropped or cancelled run.
- `Finished` — the outcome of a streamed run (`exitCode`, `stderr`, `timedOut`,
  `isSuccess`).
- `ProcessGroup` — a shared kill-on-close container (and a `ProcessRunner`): run
  several children through it and `close()` reaps the whole tree, grandchildren
  included; `shutdown()` adds a graceful SIGTERM→grace→SIGKILL tier on Unix;
  `mechanism` reports the active containment primitive.
- `outputAll` / `outputAllBytes` — run a batch of commands with a concurrency cap,
  collecting every outcome in order (a failure never short-circuits the batch).
- `Command` — a fluent builder (program, args, `workingDir`, `env`/`clearEnv`,
  `timeout`) with `suspend` verbs: `run` / `runUnit` / `outputString` /
  `outputBytes` / `exitCode` / `probe`. Each run is contained in its own
  kill-on-close tree; a timeout is captured, cancellation kills the tree.
- `ProcessResult` — the captured outcome (`stdout`, `stderr`, `exitCode`,
  `timedOut`, `isSuccess`, `ensureSuccess()`); a non-zero exit is data, not an error.
- `ProcessException` — typed failures (`Exit`, `Timeout`, `NotFound`, `Spawn`).
- `ProcessRunner` seam with `JobRunner` (the real backend) and `ScriptedRunner` /
  `Reply` (a hermetic test double) — code that shells out is unit-testable
  without a subprocess.
- `Mechanism` — the kernel containment primitive reported for a process tree
  (`JOB_OBJECT` / `CGROUP_V2` / `PROCESS_GROUP` / `NONE`).

### Changed
- `RunningProcess.waitForLine` now keeps draining stdout in the background after
  the match (and after a timed-out wait), so a chatty child can't block on a full
  pipe; a timed-out probe no longer pins an I/O thread on the blocking read.

### Fixed
- Windows: a child that started but failed Job-Object assignment is now killed
  instead of being orphaned outside the container.
- `RunningProcess.finish()` is now idempotent (repeat calls return the same
  `Finished` instead of surfacing a spurious `CancellationException`), waits for
  the stdout drain to complete, and `close()` is now idempotent (no double
  `CloseHandle` on Windows).

[Unreleased]: https://github.com/ZelAnton/processkit-kotlin/commits/main
