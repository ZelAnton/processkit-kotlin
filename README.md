# processkit-kotlin

Native Kotlin/JVM child-process management built on Kotlin coroutines:
whole-tree **kill-on-close** (no orphaned subprocesses) via Windows Job Objects,
Linux cgroup v2 (with a POSIX process-group fallback), and POSIX process groups
on macOS/BSD — plus run-and-capture, line streaming (`Flow`), shell-free
pipelines, readiness probes, timeouts & structured-concurrency cancellation, and
supervision.

A **native** sibling in the [`processkit`](https://github.com/ZelAnton/ProcessKit-rs)
family (Rust · Go · C# · Python · F# · Kotlin) — its own JVM backend, not a
binding. The Rust crate is the reference; this port mirrors its behaviour.

> **Status:** planning. See [ROADMAP.md](ROADMAP.md) for the design, the
> spawn-control de-risk plan, and the gated, feature-by-feature port sequence.

## Building & testing

`./gradlew build` on any host (JDK 25 toolchain, provisioned automatically). The
containment backend is platform-specific, so the suite runs on **two** hosts:
natively on Windows/JVM (the Job Object path) and in a Linux container for the
cgroup v2 / POSIX process-group paths —

```sh
bash scripts/test-docker.sh     # or: pwsh ./scripts/test-docker.ps1
```

No Linux machine needed (works with Docker / Rancher Desktop). CI runs the full
Linux/Windows/macOS matrix. See [CONTRIBUTING.md](CONTRIBUTING.md).
