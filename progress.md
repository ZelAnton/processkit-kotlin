# progress.md — session handoff / recovery notes

> **Why this file exists:** this repo is being copied to another machine and the
> current Claude Code session will be lost. This is a self-contained snapshot so
> work can resume there. The agent's `~/.claude` auto-memory and the sibling
> `../.hq` control layer do **not** travel with the repo, so everything needed is
> folded in below. Read [AGENTS.md](AGENTS.md) and [ROADMAP.md](ROADMAP.md) first;
> this file is the *current state* on top of them.

_Last updated: 2026-06-20._

---

## 1. What this project is

A faithful port of the Rust crate **ProcessKit-rs v1.0.1**
(`../_Rust/ProcessKit-rs`, also at `d:\GitHub\Personal\_Rust\ProcessKit-rs`) →
**processkit-kotlin** (native Kotlin/JVM, JDK 25, coroutines + FFM/Panama, no JNI).

**Prime directive:** *faithful to the crate's observable behaviour, idiomatic to
Kotlin — never a blind translation.* Match the crate's guarantees; write the best
Kotlin for them (coroutines, `Flow`, sealed types, `AutoCloseable`/`use`, SLF4J).
Resolve design forks toward a popular, broadly-useful, stable **1.0** API balanced
with structural durability.

Develop **directly on `main`** (no feature branches), jj-colocated repo.

---

## 2. The autonomous per-step loop (MANDATED — follow for every step)

The user authorised fully autonomous execution of the ROADMAP. For **each** step:

1. **Plan** in detail; resolve every fork yourself toward the durable-1.0 choice.
2. **Execute** the step.
3. **review-changes loop**: review the diff, fix all serious problems; repeat until
   a pass finds none — **minimum 2 passes**. (Delegate passes to subagents with
   distinct lenses — correctness/FFM, concurrency, API/fidelity — then fix
   findings yourself. For FFM/Windows-native code, verify offsets/ABI against
   Microsoft Learn docs.)
4. **Push**, wait for CI, fix failures, repeat until green.
5. (For larger milestones) **review-whole-solution loop**: same ≥2-pass rule.
6. Move to the next step.

**Gate:** the user drives step-by-step with "continue with <step>". Stop at each
step boundary and report; don't start the next feature unprompted.

The user sometimes says (in Russian) *"Проведи ревью изменений, исправь все
потенциальные проблемы. Повторяй пока в последнем прогоне будут найходиться
серьёзные проблемы. Минимум 2 прогона."* = run the review-changes loop again
(≥2 passes, fix all serious problems). These deeper second-round reviews have
repeatedly found real bugs the first round missed — take them seriously.

---

## 3. Toolchain, verification, and version control (CRITICAL for recovery)

- **Build:** Gradle (`./gradlew`), Kotlin 2.x, `jvmToolchain(25)`, `explicitApi()`
  strict, `allWarningsAsErrors`. ktlint (`ktlint_official`).
- **Verify locally before EVERY push — both platforms:**
  - Windows native: `./gradlew ktlintCheck test`
  - Linux: `bash scripts/test-docker.sh test` (Docker / Rancher Desktop)
  - Auto-format nits with `./gradlew ktlintFormat`.
- **CI:** GitHub Actions matrix (ubuntu/windows/macos). After push:
  `gh run list --branch main --limit 2 --json databaseId,headSha,status,workflowName`
  then `gh run watch <CI-run-id> --exit-status`.
- **Version control = jujutsu (`jj`), NOT raw git** (colocated; raw git writes
  desync the jj working copy). Per-step publish sequence:
  ```
  jj describe -m "feat: step X — ..."        # describe BEFORE pushing (jj refuses no-desc)
  jj bookmark move main --to '@'             # NOTE: quote '@' in PowerShell (bare @ is a parse error)
  jj git push -b main
  jj log -r 'main@origin' --no-graph -T 'commit_id.short() ++ " | " ++ description.first_line() ++ "\n"'
  ```
  **Always verify `main@origin` actually advanced** (piping push output can mask
  failures). After a push, jj makes a fresh empty `@` on top of the pushed commit.
- **Review fixes that land on already-pushed steps** are committed as a separate
  `fix: review of <step> — ...` commit on top of `main` (precedent: commits
  `499b62c`, `972f79fe`, `e2f92004`).

### ⚠️ Test-harness foot-gun (learned the hard way — a guard now enforces it)
A Kotlin expression-body `@Test` whose **last expression returns non-`Unit`**
(e.g. `= runBlocking { … assertFailsWith { } }` or `… assertNotNull(x)`) compiles
to a **non-void** method, which **JUnit Jupiter silently never runs** (green build,
zero coverage). 18 tests were silently dead before this was caught.
- **Fix pattern:** give such tests `: Unit =` (e.g. `fun x(): Unit = runBlocking { … }`),
  or a block body, or end in a `Unit`-returning assertion.
- `runTest { }` is unaffected (`TestResult = Unit` on JVM); only `runBlocking`
  expression bodies are at risk.
- **`src/test/.../TestHygieneTest.kt`** is a reflection guard that **fails the
  build** on any non-void `@Test`. Keep it. If it fires, fix the flagged method.

---

## 4. Status — what's DONE (all on `main@origin`, CI-green)

All **13 main ROADMAP steps are ported, reviewed (≥2 passes each), and CI-green**:
1 contained run+capture · 2 ProcessGroup · 3 streaming/output (3a–3f complete) ·
4 Pipelines · 5 Supervision · 6 readiness probes · 7 retry · 8 CliClient+doubles ·
9 process-control · 10 stats (group) · 11 limits · 12 SLF4J observability ·
13 record/replay cassettes.

Deferred sub-steps completed since: **8b** (streaming `ProcessRunner` seam) ·
**3d/3e/3f** (live output handlers / interactive stdin / output-buffer policy +
`outputEvents`) · **8c** (scripted-stream timing `Reply.pending`/`withLineDelay`
+ env removal `Command.envRemove`/`CliClient.defaultEnvRemove`) · **9b** (Windows
suspend/resume via thread-enum + kernel-authoritative `members`) · **9b deep
review round 2** (un-skipped the 18 silently-dead tests + `TestHygieneTest` guard;
idempotent closed-group ops) · **10b** (per-run `RunProfile` +
`RunningProcess.{cpuTime,peakMemoryBytes,profile}`).

Most recent feature step fully through the loop: **`9a47f9c6` — step 10b**.
(Verify the actual `main@origin` head with `jj log -r main@origin`.)

---

## 5. Status — step 11b part 1 (Windows cpuQuota): pushed, REVIEW LOOP PENDING

**Step 11b — part 1: Windows `cpuQuota` (CPU rate control).** Implemented and
**committed + pushed to `main@origin`** as part of this migration handoff; **green
on Windows + Docker locally**. STILL PENDING on the new machine: the formal
**review-changes loop (≥2 passes)** and confirming the **CI matrix** is green (see
"To finish" below). It was pushed mid-step (without the usual review loop) only so
the work survives the machine move — treat the review as not-yet-done.

Files touched: `ResourceLimits.kt`, `internal/Containment.kt`, `LimitsTest.kt`.
What was done:
- `ResourceLimits.cpuQuota` is now **enforced on Windows** (was fail-fast). Removed
  the `WindowsJobContainment` cpuQuota fail-fast `init` block; KDoc updated.
- `Win32.createContainedJob(memoryMax, maxProcesses, cpuQuota)` gained a `cpuQuota`
  param; sets a Job Object **hard CPU cap** via a 2nd `SetInformationJobObject`
  with `JobObjectCpuRateControlInformation` (class 15), `ControlFlags =
  ENABLE|HARD_CAP`, `CpuRate` at struct offset 4 (8-byte struct).
- New top-level `internal fun cpuHardCapRate(cores, cpus)`: per-core quota →
  `1..=10000` rate (`round((cores/cpus)*10000)`, clamp 1..10000). Extracted to
  top-level so it's hermetically testable (the Windows-only `Win32` object can't
  init off-Windows).
- `LimitsTest`: removed the obsolete "cpuQuota fails fast" test; added the
  process-group-rejects-cpuQuota case, hermetic `cpuHardCapRate` conversion +
  saturate/floor tests, and a Windows "accepts a cpu quota" test.

**Rust references:** `../_Rust/ProcessKit-rs/src/sys/windows.rs` —
`cpu_hard_cap_rate` (line ~641) and the `JOBOBJECT_CPU_RATE_CONTROL_INFORMATION`
usage (line ~131-151).

### To finish 11b-part-1 on the new machine:
1. `./gradlew ktlintFormat` then `./gradlew ktlintCheck test` (Windows) — the
   `Windows accepts a cpu quota` + `Windows enforces a max-process cap` tests run
   here; confirm `TestHygieneTest` passes (all new tests are `: Unit =`).
2. `bash scripts/test-docker.sh test` (Linux) — confirms no regression (cpuQuota
   on the process-group backend still correctly fails fast).
3. Run the **review-changes loop (≥2 passes)** — lenses: FFM/ABI correctness of the
   CPU-rate struct (verify `JOBOBJECT_CPU_RATE_CONTROL_INFORMATION` layout = 8 bytes,
   `CpuRate`@4, class 15, flags ENABLE=0x1/HARD_CAP=0x4 against Microsoft Learn);
   and the `cpuHardCapRate` math/fidelity vs the crate. Fix findings.
4. `jj describe -m "feat: step 11b (part 1) — Windows cpuQuota via Job Object CPU rate control"`,
   move `main`, push, watch CI.

---

## 6. Status — what's NEXT

### 11b — part 2: Linux cgroup-v2 backend (NOT STARTED)
The harder, **CI-unverifiable** half. A cgroup-v2 backend (memory.max / pids.max /
cpu.max; join via `cgroup.procs`; kill via `cgroup.kill`; fallback to the
process-group backend when no writable/delegated cgroup) so Linux limits stop
failing fast. Rust ref: `../_Rust/ProcessKit-rs/src/sys/linux.rs` (the `Cgroup`
backend + `Job` enum).
- **Testability caveat (important):** cgroup-v2 needs a writable cgroup at the real
  root — **not available in Docker CI (no delegation) nor on the Windows dev box**.
  Plan agreed with the user: hermetic unit tests for the pure logic (cpu.max
  formatting, path/quota derivation), a Docker-testable **fallback-to-process-group**
  path, and a **self-skipping integration test** that runs only on a real cgroup
  root. The cgroup *enforcement* path itself ships validated mainly by the Rust
  reference — flag this clearly when pushing.
- **JVM constraint:** `ProcessBuilder` has no pre-exec hook (Rust joins
  `cgroup.procs` pre-exec after fork). Options: spawn-then-write-pid-to-`cgroup.procs`
  (small race window, like the Windows assign-after-start), or an `sh -c 'echo $$ >
  .../cgroup.procs && exec "$@"'` launcher prefix (joins before exec — mirrors how
  the pgroup backend prefixes `setsid`). Decide and document.

### Then, remaining toward 1.0 (order by need):
- **Deferred step-3f 1.0-fidelity items** (surfaced when reviewing 3f against the
  crate; the user deferred them — get a decision before 1.0):
  1. `OutputEvent` carries a bare `String`; the crate wraps lines in an extensible
     `OutputLine` (so timestamp/index can be added post-1.0 without breaking).
  2. `ProcessException.OutputTooLarge` carries only `program`; the crate also has
     `lineLimit`/`byteLimit`/`totalLines`/`totalBytes`.
  3. `outputBytes` line-caps stdout under a policy (normalises newlines); the crate
     keeps `outputBytes` stdout **raw** (the line policy applies only to line-pumped
     capture).
  4. The streamed path (`stdoutLines`/`outputEvents`) doesn't enforce the buffer
     policy; the crate does.
- **Tracked tech debt:** `CliClient.fillDefaults` compares env keys
  case-sensitively, but the crate folds case on Windows (`env_key_eq`) — a
  case-differing per-command env may not win there. Pre-existing (since step 8).
  Cross-backend Windows semantic → coordinate before fixing.
- **macOS** `posix_spawn` containment backend (currently macOS throws
  "not implemented"; many real-subprocess tests self-skip on macOS via
  `assumeTrue(Os.current == WINDOWS || LINUX)`).
- **Pipeline** doesn't honor `stdoutEncoding`/handlers/`outputBufferPolicy` (LOW).
- **failure-storm guard**, **`uncheckedInPipe`**.
- **Phase 5 — 1.0 hardening:** re-enable Binary Compatibility Validator + Kover
  when Java-25 tooling catches up; JVM shutdown-hook backstop; Dokka API docs;
  cookbook/README → **1.0 release**.

---

## 7. Recovery checklist on the new machine

1. Confirm toolchain: JDK 25, Gradle wrapper, Docker (Rancher Desktop) for Linux
   tests, `gh` authenticated for CI watching, `jj` installed (colocated repo).
2. `jj st` / `jj log` — the in-flight 11b-part-1 changes should be present (they
   were committed + pushed as part of this handoff; if only the working copy was
   copied, they're uncommitted — see §5). Verify against `main@origin`.
3. Re-establish context: read this file, `AGENTS.md`, `ROADMAP.md`, and the Rust
   crate at `../_Rust/ProcessKit-rs` (path may differ on the new machine — adjust).
4. **NOT travelling with the repo** (recreate / re-point as needed):
   - The agent `~/.claude` auto-memory (port-progress, the loop protocol, jj-push
     lessons) — all folded into this file.
   - The `../.hq` cross-project control layer (mail/threads/tasks) and the
     git-ignored `CLAUDE.local.md` — local-only; the `.hq` protocol governs
     cross-repo coordination (this port is the 4th backend of a shared conformance
     suite). If `.hq` isn't copied, cross-repo coordination is paused; the port
     itself continues fine.
5. Continue the per-step loop (§2): finish 11b-part-1 verification/review/push if
   not already green on CI, then 11b-part-2 (cgroup), then §6.

---

## 8. Quick architecture map

- `Command` — fluent builder + suspend verbs (`run`/`outputString`/`outputBytes`/
  `exitCode`/`probe`/`parse`/`firstLine`/`start`); each run in its own
  kill-on-close container.
- `ProcessRunner` seam — `execute()` (bulk) + `start()` (streaming). Real backend
  `JobRunner`; test doubles `ScriptedRunner`/`Reply`, `RecordingRunner`,
  `RecordReplayRunner` (cassettes).
- `RunningProcess` — live handle: `stdoutLines`/`outputEvents`/`waitFor*`/`finish`/
  `takeStdin`/`cpuTime`/`peakMemoryBytes`/`profile`; `AutoCloseable` (`use{}` reaps).
- `ProcessGroup` — shared kill-on-close container + `ProcessRunner`; `signal`/
  `suspend`/`resume`/`members`/`adopt`/`stats`/`sampleStats`; `ResourceLimits`.
- `internal/Containment.kt` — the OS backends: `WindowsJobContainment` (FFM Job
  Object: `Win32` object — kernel32 bindings, `captureCallState` for GetLastError),
  `PosixGroupContainment` (`setsid` + `Libc` kill/sysconf). `Os.current` dispatch.
- `internal/ProcessMetrics.kt` — per-process CPU/memory (`/proc` on Linux,
  `GetProcessTimes`/`K32GetProcessMemoryInfo` on Windows).
- Secret-safety discipline: **never** log/serialize argv or env values — only
  program names, exit codes, counts, env *names*.
</content>
