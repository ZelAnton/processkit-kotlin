# processkit-kotlin — Project Roadmap

> A **native** Kotlin/JVM implementation of the processkit model — kernel-backed,
> no-orphan child-process trees. One of the sibling implementations of the
> processkit family (Rust · Go · C# · Python · F# · **Kotlin** — see *Strategic
> position*); this one is a native JVM backend, **not** a binding. Published to
> Maven Central as `processkit`; repository `processkit-kotlin`, package
> `net.zelanton.processkit`. We port **ProcessKit-rs v1.0.1** (complete, stable) —
> the full surface, **one feature at a time, each gated by the user's
> confirmation** (see *How we port*).

## Strategic position — read first

The processkit model is "one model, many hosts". Each host is one of two kinds:

- **Independent platform backends** — each carries its *own* copy of the
  dangerous per-platform code (Windows Job Object, Linux cgroup v2, POSIX
  process groups):
  - **Rust** (`processkit`) — the reference / source of truth.
  - **Go** (`processkit-go`) — native Go (`goroutines` + `context`).
  - **C#** (`processkit-cs`) — native .NET.
  - **Kotlin** (`processkit-kotlin`) — **this document**.
- **Cheap layers over a backend, within the same runtime** — no second copy of
  the platform code, because there is no cross-runtime FFI cost:
  - **Python** (`processkit-py`) — PyO3 binding over the Rust core.
  - **F#** (`processkit-fs`) — rides the C# core inside the CLR.

**Where Kotlin lands, and why it is a native backend.** The JVM is a *new
runtime* for the family — there is no existing JVM backend for Kotlin to ride
the way F# rides C# inside the CLR. So the only two options are a native JVM
backend or a binding to the Rust core over JNI/FFM. A JVM binding to a tokio
cdylib hits exactly the C-ABI / async problems that pushed Go (cgo) and C#
(P/Invoke) to native: tokio's async surface does not cross a C ABI cleanly, and
the binding would have to **ship a per-platform native library** (`.so` /
`.dll` / `.dylib`) inside the JAR — surrendering the JVM's single-artifact
distribution. So:

> **Kotlin is the fourth independent platform backend (rs / go / cs / kt), not a
> wrapper.** The reason to build it is **reach into the JVM ecosystem** — Gradle
> / Maven plugins, Spring services, JVM-based CI / build tooling, big-data and
> backend stacks that shell out and leak children. The mitigation for a fourth
> duplicated backend is the *shared behavioural conformance suite* (see Risk
> register), not shared code.

## Why Kotlin/JVM is a strong host

Where the Python binding's hardest problem was the async bridge, Kotlin has none:

- **No async-bridge risk.** Kotlin coroutines + structured concurrency replace
  the tokio↔asyncio bridge entirely — no async/await coloring across an FFI, no
  runtime fragmentation. Every consuming verb is a `suspend fun`; line streaming
  is a `Flow<String>`; racing children is `select`/`awaitAll`; the background
  stderr drain is a coroutine. This is the Go payoff in JVM form.
- **Cancellation and timeouts are idiomatic.** Structured concurrency threads
  cancellation through the whole call tree: cancelling the enclosing
  `CoroutineScope`/`Job` cancels the awaiting verb, and the teardown hook kills
  the tree. `withTimeout` is the raised-deadline primitive; the run's own
  `timeout` is the captured-deadline one — the same captured-vs-raised split the
  crate makes.
- **Partial tree visibility without FFI.** `ProcessHandle` (Java 9+) gives
  `descendants()` / `children()` / `onExit()` (a `CompletableFuture` → `await`)
  / `destroy()` / `destroyForcibly()` — enough for the process-group fallback's
  `members()` and exit-wait, with the same "snapshot, not a containment
  guarantee" caveat as everywhere else.
- **Platform primitives reachable through FFM — and no shipped native library.**
  The **Foreign Function & Memory API** (Panama, finalized in JDK 22, the JDK 25
  LTS floor this project targets) calls `kernel32`/`libc`/raw syscalls directly.
  Job Object assignment, `posix_spawn`, cgroup writes, `clone3` — all reachable
  from pure Kotlin against the *system* libraries, so the published artifact
  stays a single platform-independent JAR. That single-JAR property is the
  decisive advantage of the native backend over a JNI binding.
- **The test seam fits the language.** A `ProcessRunner` interface is idiomatic
  JVM dependency injection; a scripted fake is trivial — none of the PyO3
  trait-binding awkwardness.
- **Distribution is trivial.** One JAR to Maven Central via the Sonatype Central
  Portal; FFM resolves native symbols at runtime. No wheel matrix, no
  per-platform classifiers, no manylinux.

## The central technical challenge — spawn control (Phase 0)

The JVM's standard `ProcessBuilder` exposes **no spawn-control hooks at all** —
this is the de-risk question the whole project hinges on, the JVM analogue of
Go's Phase 0 but more constrained:

- **No `pre_exec` hook** → no `setpgid` / `setsid` / `setrlimit` / `uid` / `gid`
  in the child between fork and exec.
- **No `CREATE_SUSPENDED` / no primary-thread handle** → cannot reproduce the
  crate's race-free Windows `CREATE_SUSPENDED → AssignProcessToJobObject →
  ResumeThread` assignment.
- **No atomic cgroup placement** → cannot do `clone3` + `CLONE_INTO_CGROUP`.

So the native backend must **bypass `ProcessBuilder` and spawn through FFM**:

- **Windows** — `CreateProcess` with `CREATE_SUSPENDED | CREATE_NEW_PROCESS_GROUP`,
  `AssignProcessToJobObject` (a job created with
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`), then `ResumeThread` on the returned
  primary thread handle. Full control, exactly mirroring the crate.
- **Linux** — `posix_spawn` (safe in a multithreaded JVM, unlike raw `fork`)
  with `POSIX_SPAWN_SETPGROUP` / `setsid`, plus a raw `clone3` +
  `CLONE_INTO_CGROUP` path for *atomic* placement of the child into a fresh
  cgroup at spawn (file-write fallback for older kernels / non-delegated
  cgroups).
- **macOS / BSD** — `posix_spawn` with `POSIX_SPAWN_SETPGROUP` for the process
  group.
- **Handle reconstruction** — wrap the spawned pid; bridge to
  `ProcessHandle.of(pid)` for `onExit()` / `destroy()` where it helps, or
  `waitpid` via FFM, to drive the `RunningProcess` lifecycle.

**Why not just use `ProcessBuilder`?** A *fallback* exists — spawn normally and
`AssignProcessToJobObject` immediately on Windows (accepting the residual race
window the crate also documents), and wrap a small `setsid`/helper launcher on
POSIX — but it is weaker (the race window, an extra exec, cross-platform
inconsistency) and cannot do atomic cgroup placement. The FFM native spawn is
the proper path; the fallback is the de-risk safety net if FFM proves too costly
on a given target.

> **Why not raw `fork()`?** The JVM is heavily multithreaded; `fork()` without an
> immediate `exec` is unsafe (locks held by other threads are frozen in the
> child). `posix_spawn` is the only safe POSIX spawn from the JVM — which is
> *also* why there is no `pre_exec`: there is no safe child-side callback to run.
> Everything the crate does in `pre_exec` must instead be expressed as
> `posix_spawn` file actions / attributes, or done via `clone3` for the cgroup
> case.

## What does NOT carry over (shared with the Go / C# story)

- **No RAII / `Drop`.** The idiom is `AutoCloseable` + Kotlin `use { }` (the
  JVM's `using`/`IDisposable`). The no-orphan guarantee degrades to "holds as
  long as you didn't forget the `use`/`close()`". `Cleaner` and finalizers are
  even less reliable than Python's `__del__` and **must not** be used for
  teardown. The closest thing to `Drop` on the abrupt-exit path is a
  `Runtime.addShutdownHook` — which fires on normal exit and `SIGTERM`, but
  **not** on `SIGKILL` / `halt`.
- **Same platform asymmetry, documented not hidden.** Windows
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` is kernel-enforced and survives a hard
  kill of the JVM parent (as long as the job handle's FFM lifetime is held open
  for the process's life). Linux cgroup / process-group teardown needs an active
  kill dispatched from the parent — the `use`/`close()`/cancel path, or the
  shutdown hook — best-effort only, weaker than the Rust `Drop` path.
- **JVM-specific traps.**
  - **`PDEATHSIG` is unreliable** — it is thread-scoped, and the JVM multiplexes
    coroutines/threads, so it can fire spuriously when the creating thread exits.
    Containment must rest on the Job Object / cgroup, **never** on `PDEATHSIG`
    (same trap Go documents).
  - **The JVM may already be inside a Job Object** (some CI agents, containers).
    Windows 8+ nested jobs make assignment possible, but breakaway /
    `JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK` behaviour must be handled, not
    assumed.
  - **FFM is a restricted operation.** JDK integrity-by-default means native
    access needs `--enable-native-access` (a warning otherwise on JDK 24+); the
    library documents the flag and fails honestly where the platform refuses.

## Target API (illustrative, idiomatic Kotlin — fluent builder + coroutines)

```kotlin
import net.zelanton.processkit.Command
import net.zelanton.processkit.ProcessGroup
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    // Run-and-capture; a non-zero exit is data, not an exception.
    val res = Command("git", "rev-parse", "HEAD").outputString()
    println("${res.stdout.trim()}  exit=${res.exitCode}")

    // Require success and get trimmed stdout directly.
    val version = Command("kotlinc", "-version").run()

    // Kill-on-close container for a whole tree (AutoCloseable + use).
    ProcessGroup().use { group ->
        val server = group.start(Command("my-server"))
        server.waitForPort(InetSocketAddress("127.0.0.1", 8080), 10.seconds)
        // ... use the server ...
    } // group close reaps the whole tree, grandchildren included

    // Stream stdout line by line as a Flow.
    Command("git", "log", "--oneline", "-n", "50").start().use { run ->
        run.stdoutLines().collect { line -> println("commit: $line") }
        val finished = run.finish()
        if (finished.outcome != Outcome.Exited(0)) {
            System.err.println("git ended ${finished.outcome}: ${finished.stderr}")
        }
    }
}
```

**Run vocabulary** — one verb, one meaning, at every layer, mirroring the crate
(`run` / `runUnit` / `outputString` / `outputBytes` / `exitCode` / `probe` /
`parse` / `firstLine` / `start`). Verbs are `suspend`; `start()` returns a live
`RunningProcess`.

**Cancellation** is structured-concurrency-native: cancelling the coroutine that
awaits a verb tears down the process tree (in a `NonCancellable` finally) and
the verb completes by propagating cancellation. An explicit `cancelOn(job)` /
client-level default mirrors the crate's `cancel_on` for cross-scope cancel.

## Architecture decisions

- **Pure JVM via FFM/Panama, no JNI, no shipped native library.** All platform
  access through the Foreign Function & Memory API against system libraries —
  the single-JAR distribution win. `--enable-native-access` documented.
- **JDK floor: 25 LTS.** Stable FFM (finalized JDK 22) plus long-term support;
  already the `kotlin-repo-template` default (Kotlin 2.4, Gradle 9, foojay
  toolchain provisioning).
- **Coroutines first.** Every blocking verb is `suspend`; streaming is `Flow`;
  blocking FFM `waitpid`/IO runs on `Dispatchers.IO`; cancellation and timeouts
  flow through structured concurrency onto the tree.
- **Containment backends mirror the crate.** Windows Job Object, Linux cgroup v2
  (with a POSIX process-group fallback), POSIX process group on macOS/BSD; the
  active `Mechanism` is observable and never a silent downgrade.
- **Honest results.** A non-zero exit is data in `ProcessResult`, not an
  exception, until `ensureSuccess()`; a timeout is captured (`timedOut`); a
  cancellation is always an error. Errors are a sealed hierarchy.
- **Faithful to behaviour, idiomatic to Kotlin.** We port v1.0.1's *guarantees
  and observable semantics*, not its code shape — re-deriving the best Kotlin
  design at each step (see *How we port*). The bar: reliable, readable, fully
  testable, extensible.
- **Dependency injection & observability are first-class.** The `ProcessRunner`
  interface is the test/extension seam (swap the real spawner for fakes),
  **constructor-injected** — friendly to Spring / Koin / manual DI, no globals or
  singletons. An optional **SLF4J** logger (the JVM standard logging facade;
  no-op without a binding, **never** logs argv or env) gives structured lifecycle
  logging without a compile-time feature. `InputStream` / `OutputStream` interop
  lets stdlib sources/sinks plug into stdin / stdout / tee; line streaming is a
  `Flow<String>`. Errors are a sealed `ProcessException` hierarchy (exhaustive
  `when`).
- **Rust feature flags → runtime / DI gating, not build flags.** Gradle has no
  additive compile-time feature system worth emulating; the only genuine split is
  **platform** (FFM bindings per OS, selected at runtime). Each optional Rust
  feature becomes *always-compiled* API, gated at **runtime** (throw
  `ProcessException.Unsupported` where a platform can't honour it) or wired via
  **DI** (the logger) — one honest surface, no combinatorial build matrix. A
  heavy optional dependency (e.g. `kotlinx.serialization` for record/replay) may
  live in a thin companion module; default to one artifact until a consumer needs
  the split.
- **Build & quality gates from the template.** Gradle version catalog;
  `explicitApi()` strict; `allWarningsAsErrors`; binary-compatibility-validator
  (`api/*.api` baselines diffed in CI — the parity-with-`cargo-public-api`
  move); ktlint; Kover coverage; Maven Central publishing via the vanniktech
  plugin.

## Naming & publishing

Brand-continuous with the family — discoverability beats an independent brand
for a sibling implementation.

- **Maven Central artifact:** `processkit` (same name as the crate). **Group:**
  `net.zelanton` (domain-verifiable via the owned `zelanton.net`) —
  `io.github.zelanton` is the GitHub-namespace fallback. **Package:**
  `net.zelanton.processkit`. *(Group is an Open decision — confirm before first
  publish.)*
- **Repository:** `processkit-kotlin`, beside `ProcessKit-rs`, `processkit-py`,
  `processkit-go`, `processkit-cs`, `processkit-fs` — the suffix family reads as
  "same model, many hosts".
- **No native artifacts.** Single platform-independent JAR; FFM binds system
  libraries at runtime.

---

## How we port — method & cadence

Source of truth: **ProcessKit-rs v1.0.1** (complete, stable). We port the whole
surface, but **incrementally and deliberately**:

- **One feature at a time.** Implement, test, and document a single feature to
  completion before opening the next. The default-on **core** («ядро») comes
  first; every later feature builds on a green base.
- **Confirmation gate (hard rule).** A feature is "done" only when the **user
  explicitly confirms** it works in this project ("успешно реализована"). Do
  **not** start the next feature until that confirmation lands. The agent stops
  at each gate and waits. This cadence is also recorded in the repo's
  `AGENTS.md`.
- **Faithful to behaviour, idiomatic to Kotlin — never a blind translation.**
  Match the crate's *guarantees and observable semantics*, then re-derive the
  best Kotlin design for them. Rust and Kotlin differ deeply (ownership/`Drop` vs
  GC + `AutoCloseable`/`use`, `async`/await vs coroutines, traits vs interfaces,
  enums + `Result` vs sealed classes + exceptions, compile-time `cfg` features vs
  modules/DI); the best Kotlin shape often diverges from the Rust one.
- **Think like the user.** Design for how people *run, observe, test, maintain,
  and debug* this — the `ProcessRunner` DI seam, optional SLF4J logging,
  structured-concurrency cancellation, sealed-exception `when`, `InputStream` /
  `OutputStream` interop, KDoc examples, actionable error messages. The full
  treatment lives in `AGENTS.md`; the per-feature mapping below applies it.
- **Feature flags dissolve** into always-compiled API gated at runtime
  (`ProcessException.Unsupported`) or via DI (the logger) — see *Architecture
  decisions*.

## Phase 0 — De-risk spikes  *(prerequisite, blocking)*

> ✅ **Done (2026-06-19).** An internal `Containment` seam proves spawn +
> whole-tree kill-on-close on **Windows** (Job Object via FFM `kernel32`) and
> **Linux** (process group via a `setsid` launcher + FFM `kill`); a containment
> test reaps a child→grandchild tree on both, run natively on Windows and in
> Docker on Linux. Race-free `CREATE_SUSPENDED` / `posix_spawn` / cgroup and
> macOS land in step 1 (the decision-gate fallback was not needed — FFM works).

The risky unknowns are about *spawning*, not an async bridge. Throwaway
experiments (folded into `sys/` or deleted after the phase).

- **Windows race-free job assignment.** `CreateProcess` with `CREATE_SUSPENDED`
  via FFM, `AssignProcessToJobObject`, `ResumeThread`. Prove a grandchild
  spawned in the assignment window is still contained and reaped on job-handle
  close.
- **Linux atomic cgroup placement.** `clone3` + `CLONE_INTO_CGROUP` lands a child
  in a fresh delegated cgroup at spawn; the whole subtree dies on teardown;
  verify the file-write fallback for older kernels / non-delegated cgroups.
- **POSIX process group.** `posix_spawn` + `POSIX_SPAWN_SETPGROUP`; `killpg`
  reaps the tree. Confirm `posix_spawn` is the *only* safe spawn from the JVM (no
  `fork`).
- **Confirm `PDEATHSIG` is NOT load-bearing.** Demonstrate the spurious-fire
  hazard and that containment holds without it.

**Exit criteria:** child → grandchild spawned, the enclosing coroutine cancelled
(or the group closed), grandchild proven dead on Windows and Linux — via FFM
native spawn, no reliance on `PDEATHSIG`. *Decision gate: if FFM native spawn
proves disproportionately costly on a target, fall back to assign-after-spawn +
a `setsid` helper for that target and document the residual window.*

## Port sequence — each step is a confirmation gate

Walk these top-to-bottom, **one at a time**, each gated by the user's "done".
Effort in parentheses. The **lean-core / 0.1** boundary is marked.

**— Core (Rust's always-on base; the default «ядро») —**

1. **Contained run & capture** *(M)* — `Command` fluent builder; `run` /
   `runUnit` / `outputString` / `outputBytes` / `exitCode` / `probe` verbs (all
   `suspend`); `ProcessResult` / `Outcome` / sealed `ProcessException`
   (`Timeout`, `Cancelled`, `Unsupported`, `NotFound`, … + a rich `Exit`); the
   **per-run private kill-on-close Job** on all three mechanisms (the guarantee,
   unconditional); the `ProcessRunner` interface seam introduced here (plus a
   minimal `ScriptedRunner`) so everything above it is hermetically testable from
   day one; the captured-vs-raised `timeout` and structured-concurrency
   cancellation woven in; `Mechanism` observable. Builds on the Phase 0 FFM spawn.
   *Gate:* capture a command into a typed result; non-zero exit is data, not an
   exception; coroutine cancel kills the tree; orphan-leak test green on every
   mechanism.
   ✅ **Done (2026-06-20)** on Windows + Linux (`Command`, the capture verbs,
   `ProcessResult`, open `ProcessException`, `ProcessRunner`/`JobRunner`/
   `ScriptedRunner`, captured timeout, structured-concurrency cancellation).
   `Outcome` and the macOS backend are deferred to where they are first needed.

2. **ProcessGroup container** *(M)* — explicit shared kill-on-close group:
   `ProcessGroup() : AutoCloseable` / `close()` / `start()`; graceful
   `shutdown()` (TERM → grace → KILL on Unix; atomic Job kill on Windows);
   `waitAny` / `waitAll`; a concurrency-capped `outputAll` / `outputAllBytes`
   batch.
   *Gate:* several children in one group; `use { }` / `close()` reaps the whole
   tree (grandchildren included); graceful shutdown verified on Unix.
   ✅ **Done (2026-06-20)** on Windows + Linux (`ProcessGroup` as a
   `ProcessRunner` + `AutoCloseable`, `mechanism`, `close()`, graceful
   `shutdown()`, and concurrency-capped `outputAll` / `outputAllBytes`).
   `start()` / `waitAny` / `waitAll` moved to step 3, where they ride the live
   `RunningProcess` handle.

3. **Streaming & interactive I/O** *(M)* — `RunningProcess`: `stdoutLines():
   Flow<String>`, background stderr drain, interactive stdin, `onStdoutLine` /
   `onStderrLine` handlers, bounded buffer policy + overflow mode, encoding
   override, merged `outputEvents()` / tee to an `OutputStream`.
   *Gate:* stream a long child line-by-line; cancel the coroutine mid-stream →
   tree reaped, `Flow` completes, follow-up `finish()` reports `Cancelled`.
   ✅ **Core done (2026-06-20)** on Windows + Linux: `RunningProcess`
   (`start()` / `stdoutLines(): Flow` / background stderr / `finish()` / a
   timeout watchdog / `close()`), `Stdin` sources (`none`/string/bytes/file),
   and `waitFor` / `waitAny` / `waitAll`. **Remaining (3d, follow-on):**
   interactive stdin writer (`keepStdinOpen`), `onStdoutLine`/`onStderrLine`
   handlers, buffer policy + overflow, encoding override, merged
   `outputEvents()` / tee.
   **← lean-core / 0.1 boundary**

**— Demand-ordered core (still flag-less in Rust; order by real need) —**

4. **Pipelines** *(M)* — shell-free `a | b | c` (in-process relay), pipefail
   attribution, whole-chain timeout, one shared group, `uncheckedInPipe`.
5. **Supervision** *(L)* — `Supervisor`: restart policies, backoff + jitter,
   failure-storm guard, stop conditions, `SupervisionOutcome`. High value for the
   service / infra niche. Testable via a virtual clock (`kotlinx-coroutines-test`).
6. **Readiness probes** *(S–M)* — `waitForLine` / `waitForPort` / `waitFor`;
   `NotReady` distinct from `Timeout`; a probe does not kill the child.
7. **Retry** *(S)* — `Command.retry`: replay one run to success — classifier +
   fixed backoff between tries (distinct from Supervision's keep-alive, which owns
   the jittered exponential backoff). `RetryWhen` ships ready-made classifiers.
8. **CliClient + test doubles** *(M)* — **done** for the bulk seam: `CliClient`
   typed-wrapper core (defaults, `command`/`commandIn`, run verbs + `parse`,
   accepting an arg list or a `Command`); `RecordingRunner` + `Invocation` (input
   assertions, redacted `toString`). Hand-written, idiomatic Kotlin DI — **no**
   MockK-generated runner shipped (the crate's `mock` feature, intentionally
   dropped; consumers add MockK themselves for expectation-style mocking).
   **8b done:** `start()` on the `ProcessRunner` seam (default throws
   `Unsupported`); `ScriptedRunner.start` streams a scripted `RunningProcess`
   (via a fake `ScriptedProcess`) through the real machinery; `onSequence`
   (fail-then-succeed); `firstLine` on `ProcessRunner`/`Command`/`CliClient`.
   *Deferred to 8c:* scripted-stream timing refinements (line-delay pacing,
   pending-until-cancel) and `Command.envRemove` / `CliClient.defaultEnvRemove`
   (single-var env removal — its own small env-model change).

**— Rust feature flags, mapped to Kotlin (each always-compiled, runtime/DI-gated) —**

9. **process-control** *(M)* — **done** (`Signal` + `ProcessGroup.{signal,
   suspend, resume, members, adopt}`, throwing `ProcessException.Unsupported`
   where a platform can't deliver). Unix: any signal, `SIGSTOP`/`SIGCONT`
   suspend/resume, members = group leaders + adopted. Windows: `signal(Kill)`,
   `adopt` (Job assignment), members = spawned roots + live descendants.
   *Deferred to 9b:* Windows `suspend`/`resume` (per-thread enumeration) and
   kernel-authoritative Windows `members` (`QueryInformationJobObject`).
10. **stats** *(M)* — group stats **done (10a)**: `ProcessGroupStats` +
    `ProcessGroup.stats()` (Job Object accounting on Windows; live-group count on
    the process-group backend, CPU/memory `null`) + `sampleStats(): Flow`.
    *Deferred to 10b:* per-run `RunProfile` / `RunningProcess.{cpuTime,
    peakMemoryBytes, profile}` (per-process `/proc` parsing + `GetProcessTimes` /
    memory FFM).
11. **limits** *(M)* — **done (11a)**: `ResourceLimits(memoryMax / maxProcesses /
    cpuQuota)` via `ProcessGroup(limits)`; the Windows Job Object enforces
    `memoryMax` + `maxProcesses`; an unenforceable cap (process-group backend, or
    `cpuQuota`) fails fast with `ProcessException.ResourceLimit` — never a silently
    unbounded group. *Deferred to 11b:* the Linux cgroup v2 backend (only usable
    at the real cgroup root — not in containers / under systemd, so untestable in
    Docker CI) and Windows `cpuQuota` via CPU rate control.
12. **observability — replaces `tracing`** *(S)* — **done**: the **SLF4J** logger
    `net.zelanton.processkit` (no-op without a binding) logs run start/finish,
    timeout, retries, supervisor restarts, and group signal/suspend/resume/shutdown
    at DEBUG. **Never logs argv or env** (program names + exit codes only). The
    JVM's standard structured-logging seam — a Logback / log4j2 / OpenTelemetry
    backend rides the same SLF4J binding.
13. **record/replay — replaces `record`** *(M)* — **done**: `RecordReplayRunner`
    JSON cassettes over the `ProcessRunner` seam (`kotlinx.serialization`); record
    once, replay hermetically; secret-handling discipline mirrored (`0600` on
    POSIX, env values never persisted, strict `ProcessException.CassetteMiss`).
    Covers the capturing/run verbs; streaming `start` recording is out of scope
    (pairs with the deferred 8b streaming seam).

(The crate's `mock` feature has no Kotlin counterpart — folded into step 8's
hand-written doubles.)

## Cross-cutting, applied at every step

- **Docs as we go:** KDoc + Dokka API docs with runnable examples; grow the
  cookbook ("I want to … → snippet") feature by feature, not in a final pass.
- **Tests at two layers:** hermetic (the `ProcessRunner` seam + `ScriptedRunner`,
  every OS) for logic; real-subprocess + kill-on-close / leak tests (JUnit tag /
  opt-in) for the OS contract. Cover the streaming and supervisor paths.
- **Honesty matrix:** keep the platform-caveat table current as each feature
  lands (implemented / honestly-partial / `Unsupported`, never silently skipped).
- **ABI discipline:** `explicitApi()` + the binary-compatibility-validator
  baseline (`api/`) updated each step; warnings-as-errors; ktlint clean; a
  CHANGELOG `[Unreleased]` entry per user-visible change.

## Phase 5 — Hardening & 1.0  *(after the sequence)*

- Full platform-caveat matrix (mirror the crate's honesty: full CPU/memory on
  Windows + Linux cgroup, counts only on pgroup backends, etc.).
- Leak / stress tests: parent `SIGKILL`, the shutdown-hook path, `Ctrl-C` /
  SIGINT, on every mechanism.
- **Conformance-suite participation** — run the shared cross-language behavioural
  spec (rs / go / cs / kt) in CI; this is what keeps a fourth backend honest.
- Performance sanity (syscall-bound; confirm FFM and the coroutine pumps add no
  silly overhead).
- API-stability commitment + semver; first Maven Central release. **1.0 maps to
  processkit 1.0.1 parity.**

---

## Risk register

- **FFM native spawn complexity (highest).** Reconstructing `CREATE_SUSPENDED →
  assign → resume` and `clone3 + CLONE_INTO_CGROUP` from FFM is the core risk.
  Mitigation: Phase 0 spike; per-target fallback to assign-after-spawn + a
  `setsid` helper with a documented residual window.
- **A fourth independent platform backend (rs / cs / go / kt).** The strategic
  cost. Mitigation: the **shared cross-language behavioural conformance suite** —
  a spec plus black-box tests keeping the backends aligned (py / fs inherit from
  their cores). Cross-repo coordination via `.hq`.
- **No RAII → discipline-dependent teardown.** Mitigation: `use { }` +
  structured-concurrency cancellation + a `Runtime` shutdown hook; lean on
  Windows `KILL_ON_JOB_CLOSE`; document Linux best-effort honestly.
- **`fork()` is unsafe in the JVM.** Mitigation: `posix_spawn` only; `clone3` via
  a raw syscall, carefully, solely for atomic cgroup placement.
- **`PDEATHSIG` spurious fire.** Mitigation: never rely on it; Phase 0 proves
  containment without it.
- **FFM restricted-access / integrity-by-default churn.** Mitigation: pin the
  JDK 25 LTS floor; document `--enable-native-access`; fail honestly where
  refused.
- **JVM already inside a Job Object (Windows nested jobs / breakaway).**
  Mitigation: handle nesting and breakaway explicitly; assert in Phase 0.
- **cgroup delegation requirements** (root / container / `Delegate=yes`), kernel
  version. Mitigation: file-write fallback; `ResourceLimit` instead of a
  silently-unbounded group — same posture as the crate.

*Risk explicitly eliminated vs the Python plan: there is no async-bridge risk —
coroutines are native (the Go advantage, in JVM form).*

## Non-goals (deliberate scope cuts)

- **Not a binding** to the Rust crate (no JNI cdylib) — a native reimplementation
  by design; that is the whole point of this square.
- **Not Kotlin Multiplatform / Kotlin/Native for v1** — JVM-only; revisit KMP
  later if there is demand (it trades FFM for cinterop and duplicates platform
  code per target).
- Not a general `ProcessBuilder` convenience wrapper — cede that to the stdlib.
- No reliance on `PDEATHSIG`.
- No cgroup v1, no Windows pre-10.
- No JDK below the FFM-stable floor.
- No shipped expectation-mock runner — `ScriptedRunner` / `RecordingRunner` are
  the seam.

## Open decisions

1. **Maven group** — `net.zelanton` (domain-verified) vs `io.github.zelanton`
   (GitHub namespace). Confirm before first publish.
2. **Cancellation model** — rely on `kotlinx.coroutines.CancellationException`
   plus teardown-on-cancel (idiomatic), expose an explicit `cancelOn(job)` token
   mirroring `cancel_on`, or both. Leaning: both — coroutine cancellation is the
   default; the token covers cross-scope cancel and client-level defaults.
3. **FFM-only vs a thin JNI helper** for the hardest spawn paths. Leaning
   FFM-only to preserve the single-JAR distribution win; JNI only if a target's
   FFM spawn proves intractable.
4. **`record` module split** — keep `kotlinx.serialization` in the main artifact
   vs a thin `processkit-record` companion module. Leaning: one artifact until a
   consumer needs the split.
5. **Repository / artifact suffix** — keep `processkit-kotlin` (existing) vs
   `-kt` for tighter family symmetry. Leaning: keep `processkit-kotlin`.
6. **Conformance-suite participation** — how `kt` runs the shared behavioural
   spec in CI (depends on the family's suite-ownership decision).

### Resolved

- **API style** — fluent builder (`Command(...).args(...).verb()`) with `suspend`
  verbs, `Flow` streaming, structured-concurrency cancellation, sealed
  result/error types, `AutoCloseable` + `use { }` groups. (Confirmed
  2026-06-19.)
- **Platform target** — Kotlin/JVM only for v1; KMP deferred. (Confirmed
  2026-06-19.)
- **Approach** — native JVM backend, not a binding to the Rust core. (Confirmed
  2026-06-19.)
- **Logging** — SLF4J facade, injected/optional, no-op without a binding, never
  argv/env (the `tracing`-feature replacement).

---

## Appendix — Rust → Kotlin module mapping (parity tracker)

The crate is ~19.5k lines across these modules; the table is the porting
checklist and the parity surface the conformance suite asserts against. "Step"
references the numbered *Port sequence* above (0 = Phase 0).

| Rust (`src/…`) | Concern | Planned Kotlin (`net.zelanton.processkit…`) | Step |
|---|---|---|---|
| `command.rs` | `Command` builder + verbs + `retry` | `Command` (fluent, `suspend` verbs) | 1 / 7 |
| `runner.rs` | `ProcessRunner` seam, `JobRunner` | `ProcessRunner` interface, `JobRunner` | 1 / 8 |
| `result.rs` | `ProcessResult`, `Outcome` | `ProcessResult`, sealed `Outcome` | 1 |
| `error.rs` | `Error` enum | sealed `ProcessException` hierarchy | 1 |
| `group.rs` | `ProcessGroup`, options | `ProcessGroup : AutoCloseable`, options | 2 |
| `mechanism.rs` | `Mechanism` | `Mechanism` enum | 1 |
| `sys/{windows,linux,unix,pgroup,mod}.rs` | platform `Job` + spawn | `sys/` FFM bindings (Job Object / cgroup / pgroup) | 0 / 1 |
| `sys/graceful.rs` | SIGTERM→SIGKILL teardown | graceful `shutdown()` tier | 2 |
| `stdin.rs` | `Stdin` sources, `ProcessStdin` | `Stdin` sources, writer (`InputStream` interop) | 3 |
| `pump.rs`, `buffer.rs` | line pump, buffer policy | coroutine pump + buffer policy | 3 |
| `running/{mod,stream}.rs` | `RunningProcess`, streaming | `RunningProcess`, `Flow` | 3 |
| `running/probes.rs` | readiness probes | `waitForLine` / `waitForPort` / `waitFor` | 6 |
| `batch.rs` | `wait_all` / `output_all` | `waitAll` / `outputAll` (concurrency cap) | 2 |
| `pipeline.rs` | shell-free pipelines | `Pipeline` (`pipe`) | 4 |
| `supervisor.rs` | restart/backoff supervision | `Supervisor` | 5 |
| `client.rs` | `CliClient` + macro | `CliClient` helper | 8 |
| `doubles.rs` | `ScriptedRunner`/`RecordingRunner` | test doubles | 1 (intro) / 8 |
| `signal.rs` | `Signal`, suspend/resume | `Signal`, suspend/resume | 9 |
| `stats.rs` | stats, sampler, profile | `stats()`, `sampleStats`, `profile` | 10 |
| `limits.rs` | resource caps | `ResourceLimits` | 11 |
| (`tracing` feature) | lifecycle events | SLF4J lifecycle logging | 12 |
| `cassette.rs` | record/replay | `RecordReplayRunner` | 13 |

> Authored 2026-06-19. This roadmap mirrors the family conventions (cf. the
> `processkit-go` / `processkit-py` roadmaps in `.hq/projects/`). The Rust crate
> stays the reference; significant behaviour is mirrored, not invented. The
> porting cadence and design principles are enforced via the repo's `AGENTS.md`.
