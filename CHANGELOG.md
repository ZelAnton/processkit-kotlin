# Changelog

All notable changes to **processkit** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
-

### Fixed
-

[Unreleased]: https://github.com/ZelAnton/processkit-kotlin/commits/main
