# CLAUDE.md

Guidance for Claude Code in this repository. The canonical, detailed reference is
[AGENTS.md](AGENTS.md) — read it before any work.

**The two rules that matter most here:**

1. **Port ProcessKit-rs v1.0.1 one feature at a time, in the order set by
   [ROADMAP.md](ROADMAP.md) — and do NOT start the next feature until the user
   explicitly confirms the current one works.** Stop at each gate and ask.
2. **Faithful to behaviour, idiomatic to Kotlin — never a blind translation.**
   Match the crate's guarantees and observable semantics; write the best Kotlin
   code for them (coroutines, `Flow`, sealed types, `AutoCloseable`/`use`, DI,
   SLF4J). The design bar: reliable, readable, fully testable, extensible — see
   the **Design principles** in AGENTS.md.

Development happens **directly on `main`** (no feature branches). Everything
else — toolchain, code style, coroutine/FFM conventions, the full version-control
workflow — is in [AGENTS.md](AGENTS.md). The cross-project `.hq` protocol is in
the local-only `CLAUDE.local.md`.
