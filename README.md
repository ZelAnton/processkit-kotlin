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

> **Status: early development.** Run-and-capture (`Command` + the capture verbs)
> works today on **Windows** and **Linux**; the rest of the surface — streaming,
> pipelines, readiness probes, supervision, resource limits — is landing
> incrementally, and macOS support is on the way. Track progress in
> [ROADMAP.md](ROADMAP.md).

## Quick start

```kotlin
import net.zelanton.processkit.Command

suspend fun main() {
    // Capture the result; a non-zero exit is data, not an exception.
    val head = Command("git", "rev-parse", "HEAD").outputString()
    println("${head.stdout.trim()}  (exit ${head.exitCode})")

    // Require success and get trimmed stdout directly.
    println(Command("git", "--version").run())
}
```

Every run is contained in its own kill-on-close tree, so a timeout or a dropped
coroutine never leaks the process — or anything it spawned. Inject a
`ProcessRunner` (e.g. `ScriptedRunner`) to unit-test code that shells out with no
subprocess.

## Requirements

**JDK 25+.** processkit binds platform APIs through the Foreign Function & Memory
API, so run with `--enable-native-access=ALL-UNNAMED`. Supported today:
**Windows** (Job Object) and **Linux** (cgroup v2 / process group); macOS is
planned.

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
