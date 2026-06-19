# AGENTS.md

Conventions and guardrails for AI coding agents working in this repository. This
is the canonical, detailed reference; [CLAUDE.md](CLAUDE.md) is a shorter pointer
to it.

> **Scope & tracking.** `AGENTS.md` and `CLAUDE.md` are **committed** project
> instructions — they travel with the repo, alongside [ROADMAP.md](ROADMAP.md).
> Per-contributor agent config stays **local** (git-ignored, never pushed):
> `.claude/` (tool permissions) and `CLAUDE.local.md` (the cross-project `.hq`
> protocol). Cross-project state also lives in `../.hq/projects/processkit-kotlin/`.

## Project

`processkit-kotlin` is a **native Kotlin/JVM** implementation of the *processkit*
model: kernel-backed, **no-orphan** child-process trees — every process you start,
and everything *it* spawns, lives in a kill-on-close container (a Windows **Job
Object**, a Linux **cgroup v2** with a process-group fallback, or a POSIX
**process group**) so no descendant outlives your program. Beyond spawning:
run-and-capture, `Flow` line streaming, interactive stdin, shell-free pipelines,
readiness probes, timeouts & structured-concurrency cancellation, supervision,
resource limits, and a mockable `ProcessRunner` seam for subprocess-free tests.

It is the **fourth independent platform backend** of the processkit family
(Rust · Go · C# · Python · F# · Kotlin) — a native JVM backend built on the
**Foreign Function & Memory API** (Panama) and **Kotlin coroutines**, *not* a
binding to the Rust core. The Rust crate **`ProcessKit-rs` v1.0.1** is the
reference / source of truth (`../_Rust/ProcessKit-rs/`); this port mirrors its
**behaviour**, not its code. Public surface: `Command` (fluent builder, `suspend`
verbs), `ProcessGroup : AutoCloseable`, `RunningProcess` (`Flow` streaming),
`Supervisor`, `Pipeline`, and the `ProcessRunner` DI seam. Package
`net.zelanton.processkit`; published to Maven Central as `processkit`.

> The repo is **pre-scaffolding**: it currently holds only `README.md` +
> `ROADMAP.md`. Port-sequence step 1 scaffolds from `kotlin-repo-template`
> (Gradle, version catalog, CI, ktlint, Maven Central wiring). Until then the
> Gradle commands below are aspirational.

## How we port — the cadence (read before any feature work)

Read [ROADMAP.md](ROADMAP.md) **"How we port"** and **"Port sequence"** first.
The non-negotiables:

- **Source of truth: ProcessKit-rs v1.0.1** (complete, stable). We port the
  whole surface — but **incrementally, one feature at a time**, in the
  ROADMAP's order. The default-on **core** («ядро») first; every later feature
  builds on a green base.
- **Confirmation gate (hard rule).** A feature is "done" only when it is
  implemented, tested, documented, lint/ABI/warnings-clean — **and the user has
  explicitly confirmed** it works in this project ("успешно реализована"). **Do
  not start the next feature until that confirmation lands.** Stop at the gate
  and ask. Working two features at once, or advancing the sequence unprompted, is
  a process violation.
- **Faithful to behaviour, idiomatic to Kotlin — never a blind translation.**
  Match the crate's *guarantees and observable semantics*; then write the best
  Kotlin code for them. Rust and Kotlin differ deeply (ownership/`Drop` vs
  GC + `AutoCloseable`/`use`; `async`/await vs coroutines; traits vs interfaces;
  `enum` + `Result` vs sealed classes + exceptions; `cfg` features vs
  modules/DI). When the idiomatic Kotlin shape diverges from the Rust shape,
  **prefer the Kotlin shape** and note why. Read the corresponding Rust
  module(s) (see the ROADMAP mapping table) before porting, to capture the
  semantics and the platform fine print — not to transliterate.
- **Per-feature definition of done:** idiomatic implementation · hermetic unit
  tests through the `ProcessRunner` seam · real-subprocess / containment
  integration tests for the OS contract · KDoc (+ cookbook entry) · `CHANGELOG`
  `[Unreleased]` bullet · `explicitApi`/ABI baseline + ktlint + warnings clean ·
  **user confirmation**.

## Design principles (the bar: reliable, readable, testable, extensible)

Apply these at every step; they are *why* the port is worth more than a
translation.

- **Reliability first.** The kill-on-close tree guarantee is **unconditional** —
  it holds in every configuration, never gated by an optional feature. Results
  are **honest**: a non-zero exit is *data* (`ProcessResult`), a timeout is
  *captured* (`timedOut`), a cancellation is *always* an error; a platform that
  can't honour a capability says so (`ProcessException.Unsupported`) — **never a
  silent downgrade**. Native handle / `Arena` lifetimes are explicit and tied to
  the owning `AutoCloseable`.
- **Readable & small surface.** `explicitApi()` strict — every public
  declaration states visibility and return type. Keep the public surface
  intentional; mark internals `internal`/`private`. Immutable by default (`val`,
  read-only collections). KDoc the **why** (a wire contract, a platform
  workaround, a trade-off), not the obvious what. Match the family's verb
  vocabulary (`run`/`outputString`/`exitCode`/`probe`/…).
- **Fully testable — the seam is first-class.** Everything routes through the
  `ProcessRunner` interface so logic is testable **without a subprocess** via
  `ScriptedRunner`/`RecordingRunner`. No global state, no singletons, no
  hidden `object` that can't be substituted. Time-dependent logic (backoff,
  storms, timeouts) uses the coroutine virtual clock (`kotlinx-coroutines-test`).
  Two test layers: hermetic everywhere; real-subprocess/leak tests tagged for
  the OS contract.
- **Extensible & evolvable.** Design types for additive evolution under SemVer:
  sealed hierarchies for results/errors (callers `when`-exhaustively), interfaces
  for seams, data classes for values. Don't paint the API into a corner; prefer
  adding an overload/option to breaking a signature.
- **Dependency injection is the default.** The `ProcessRunner` is
  **constructor-injected** — friendly to Spring / Koin / manual wiring; a
  sensible `JobRunner` default, always overridable. No service locator, no
  `static`/`object` spawner. A `CliClient` takes its runner the same way, so a
  typed CLI wrapper is hermetically testable.
- **Standard logging via SLF4J.** Lifecycle observability (spawn/exit,
  timeout/cancel, teardown, retries, storms) goes through the **SLF4J** facade —
  the JVM-standard logging seam, the replacement for the crate's `tracing`
  feature. Optional and **no-op without a binding**; consumers wire
  Logback/log4j2/OpenTelemetry. **Never log argv or environment values** (they
  carry secrets) — program name, pid, mechanism, durations only.
- **Debuggability.** Exceptions carry actionable context (program, quoted
  command line, cwd, the PATH that was searched on not-found); error types are
  sealed and specific; coroutines get meaningful `CoroutineName`s; value types
  get readable `toString()`. A failure should explain itself without a debugger.
- **Think like the user.** The audience: agent/LLM frameworks, CI/build tooling,
  test runners, service supervisors — people who shell out and must not leak
  children, must cancel cleanly, and must test easily. Interop with the JVM
  stdlib (`InputStream`/`OutputStream` for stdin/tee, `Flow` for lines,
  `kotlin.time.Duration` for deadlines, `ProcessHandle` where it helps). Optimise
  ergonomics and docs for *running, observing, testing, maintaining, debugging*.

## Coroutines & concurrency conventions

- Every consuming verb is a `suspend fun`; live streaming is `Flow<String>`;
  racing is `select`/`awaitAll`.
- Blocking native work (FFM `waitpid`, pipe IO) runs on `Dispatchers.IO`; never
  block a dispatcher thread in shared code.
- **Structured concurrency only** — no `GlobalScope`. Cancellation is
  cooperative: cancelling the awaiting coroutine tears down the tree, with the
  kill performed in a `withContext(NonCancellable)` `finally` so teardown still
  runs. Propagate `CancellationException` (don't swallow it); surface a run's
  abandonment as the typed `Cancelled` per ROADMAP Open decision #2.
- The captured-vs-raised timeout split: the run's own `timeout` is *captured*
  in the result; an external `withTimeout` *raises*. Don't conflate them.

## FFM / native conventions

- **FFM/Panama only, no JNI, no shipped native library** — bind system libraries
  (`kernel32`, `libc`, raw syscalls) so the artifact stays a single
  platform-independent JAR. Document `--enable-native-access`.
- Tie every native resource to an `Arena`/`AutoCloseable` whose lifetime matches
  the owning handle; no leaked segments or descriptors.
- **`posix_spawn` only** — never raw `fork()` from the multithreaded JVM.
- **Never rely on `PDEATHSIG`** (thread-scoped; fires spuriously). Containment
  rests on the Job Object / cgroup / process group.
- Select the platform backend at runtime; the active `Mechanism` is observable.
  A capability a platform lacks throws `Unsupported`, never silently skips.

## Build, test, lint

Use the Gradle **wrapper** (`./gradlew`, `gradlew.bat`) — never a system
`gradle`.

| Task | Command |
|---|---|
| Full build (compile + test + lint) | `./gradlew build` |
| Tests only | `./gradlew test` |
| One test | `./gradlew test --tests "net.zelanton.processkit.CommandTest"` |
| Lint | `./gradlew ktlintCheck` |
| Auto-format | `./gradlew ktlintFormat` |
| Coverage (Kover) ¹ | `./gradlew koverHtmlReport` |
| Public-ABI snapshot ¹ | `./gradlew apiDump` (commit `api/`) |
| Publish locally | `./gradlew publishToMavenLocal` |

¹ **Opt-in, off by default.** Kover and the Binary Compatibility Validator are
commented out in `build.gradle.kts`; their tasks (`koverHtmlReport`, `apiDump`,
`apiCheck`) exist only once you uncomment the plugin alias. Enable the BCV in
step 1 — run `apiDump` once to seed `api/`, after which `build` also runs
`apiCheck`.

The build is **warnings-as-errors** (`allWarningsAsErrors = true`) and runs
ktlint as part of `check`/`build`. Fix a warning/lint violation rather than
suppressing it; reserve `@Suppress` for cases with a written justification.

## Toolchain

- **Kotlin 2.4+**, **Gradle 9** (wrapper), **JDK 25** toolchain
  (`jvmToolchain(25)`) — chosen for **stable FFM** (finalized JDK 22) plus LTS.
  The foojay resolver downloads a matching JDK when one isn't installed, so a
  fresh checkout builds with no manual JDK setup.
- Do **not** lower the JDK floor below the FFM-stable line — FFM is load-bearing
  here. (The generic template allows lowering to 17/21; this project does not.)
- Opt-in tooling from the catalog: **Kover** coverage and the **Binary
  Compatibility Validator** (`api/`) that pairs with `explicitApi()`.

## Code style

- **4-space indent**, spaces not tabs, LF, UTF-8, final newline — `.editorconfig`
  + ktlint (`kotlin.code.style=official`).
- `explicitApi()` strict; small public surface; internals `internal`/`private`.
- Prefer immutable data, expression bodies where they read clearly, top-level /
  `object` functions over needless classes.

### Exception handling

- No one-line `try` / `catch` / `finally` — each keyword owns a braced block on
  its own lines.
- Every empty `catch` carries a comment naming the expected exception and why
  doing nothing is correct here. A bare empty block is unacceptable.

## Dependencies and versions

- **All versions in `gradle/libs.versions.toml`** (the version catalog);
  reference as `libs.<alias>` / `libs.plugins.<alias>`, never hard-coded inline.
  Not an allow-list — add what's needed; give a non-obvious dependency a "why"
  comment. Keep the runtime dependency set **minimal** (the FFM single-JAR
  property is a selling point): coroutines + SLF4J-api are the core; add
  `kotlinx.serialization` only with record/replay (step 13), MockK/`kotlin.test`
  test-only.
- JUnit aligned via `junit-bom`; `kotlin("test")` on the JUnit Platform.

## Tests

- Tests under `src/test/kotlin/<package path>` mirroring main. JUnit test classes
  with `@Test`; backtick-quoted names encouraged (`` `non-zero exit is data` ``).
- Assert **behaviour, not implementation**. Hermetic via the seam by default;
  tag real-subprocess / kill-on-close / leak tests so CI can opt in per OS.

## Changelog

`CHANGELOG.md` is the single source of truth for release notes (once it exists).
Every user-visible change ships its entry in the same change set, under
`## [Unreleased]` (`### Added/Changed/Fixed/Removed/Deprecated`), written for a
consumer. Never edit versioned sections — the release workflow owns those. Empty
`[Unreleased]` is auto-filled from commit subjects by git-cliff (`cliff.toml`);
manual entries win.

## Security scanning

GitHub **CodeQL** supports Kotlin (`java-kotlin`); a `codeql.yml` ships with the
template — treat new alerts like build warnings. Keep Dependabot and the
dependency-submission workflow so alerts cover transitive deps. (Native/FFM code
is outside CodeQL's reach — cover it with the leak/stress tests instead.)

## Version control workflow

Colocated git + [jujutsu (`jj`)](https://jj-vcs.github.io/jj/). Drive everything
through `jj` (git writes can desync the jj working copy; if unavoidable, follow
with `jj git import`).

**Development happens directly on `main`.** This repo does **not** use
feature-branch-per-PR — keep the `main` bookmark advanced to the finished work and
push it straight to `origin/main`. (Solo library; no review gate to satisfy.) The
generic "never `main` by default" reminder some tooling injects is overridden
here: `main` *is* the working branch.

**Evaluate each new prompt before editing** — classify the scope:

| Signal in prompt | Category | Action |
|---|---|---|
| Same topic, refinement, follow-up | **Continuation** | Just work; jj folds edits into the current change. |
| Same change, goal refined/expanded | **Scope shift** | `jj describe -m "<refined summary>"`. Don't start a new change. |
| Orthogonal topic, "теперь сделай X" | **New work** | Finished → `jj new -m "..."` (descendant); in progress → `jj new @- -m "..."` (sibling). |

Signals like "теперь" / "now" / "next" / "also" usually mean **new work** or
**scope shift**; imperative follow-ups ("исправь это", "продолжи") mean
**continuation**. When in doubt, ask.

- **Describe early**, fold small follow-ups, re-`describe` on scope shift.
- **Integrate onto `main`:** when work is ready, advance the bookmark —
  `jj bookmark move main --to @` — so `main` stays at the head of local history.
  Don't create per-feature bookmarks.
- **Sync only on the user's explicit `pull`/`push`/`sync`:** `jj git fetch`;
  rebase if upstream advanced (`jj rebase -d main@origin`); then
  `jj bookmark move main --to @` and `jj git push -b main`. **Never push without
  an explicit signal.**
- **Undo via jj:** `jj undo`, `jj abandon <rev>`, `jj restore`, `jj op log` +
  `jj op restore <op-id>`.

## Cross-project protocol (`.hq`)

This repo participates in a personal cross-repo control layer (`../.hq/`); its
project space is `../.hq/projects/processkit-kotlin/`. Because this port makes
Kotlin a **fourth independent backend**, the family's **shared conformance
suite** is coordinated there — don't decide cross-repo matters unilaterally;
treat incoming requests as proposals to weigh, not commands. The full protocol
(threads, tasks, human liaison) lives in the local-only `CLAUDE.local.md` and in
`../.hq/README.md`.
