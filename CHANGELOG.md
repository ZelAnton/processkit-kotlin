# Changelog

All notable changes to **processkit** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
