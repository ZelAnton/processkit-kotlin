# Changelog

All notable changes to **processkit** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
