# syntax=docker/dockerfile:1
#
# Linux test image for processkit-kotlin.
#
# Runs the same Gradle build/tests as the Windows host, but on Linux — the only
# place the cgroup v2 / POSIX process-group containment paths (and, later, the
# FFM `posix_spawn` / `clone3` spawn) can actually be exercised. The Windows host
# covers the Job Object path natively (`./gradlew test`); this covers the rest.
#
# JDK 25 matches the project toolchain (`jvmToolchain(25)`), so Gradle uses the
# image's JDK directly instead of downloading one via the foojay resolver.
#
# Build + run via `scripts/test-docker.{sh,ps1}` (works with Rancher Desktop's
# `docker` CLI or `nerdctl`).
FROM eclipse-temurin:25-jdk

# Tools the real-subprocess / containment tests lean on (ps for tree assertions;
# util-linux brings setsid). coreutils' kill is already present in the base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends procps util-linux \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# 1) Wrapper first — the Gradle distribution layer caches across source edits.
#    chmod guarantees the launcher is executable even when the build context came
#    from a Windows filesystem that doesn't carry the Unix execute bit.
COPY gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# 2) Build config next — the dependency layer caches unless these change.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon --quiet || true

# 3) Sources (and .editorconfig, which ktlint reads) last — only this layer
#    rebuilds on a code change.
COPY . .

# One-shot container: no Gradle daemon; plain console for clean CI-style logs.
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"

# Default to full verification (compile + test + ktlint). Override the command to
# run a subset, e.g. `docker run --rm <image> ./gradlew test`.
CMD ["./gradlew", "build", "--no-daemon", "--console=plain"]
