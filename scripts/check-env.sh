#!/usr/bin/env bash
#
# Checks this machine can build and test this Kotlin (Gradle) project before you
# initialize the template (POSIX counterpart of check-env.ps1 — use whichever
# matches your shell; both do the same thing).
#
# Verifies a JDK is on PATH to launch the Gradle wrapper (Gradle 9.x needs Java
# 17+ to run). The JDK 25 *compile* toolchain is downloaded automatically by the
# Gradle foojay resolver, so only a launcher JDK is required here. Exits 0 when
# ready; if Java is missing or too old it prints per-OS install commands and
# exits 1 — install one, then re-run.
#
# Usage: bash ./scripts/check-env.sh

set -euo pipefail
case "${1:-}" in -h|--help) sed -n '2,13p' "$0"; exit 0 ;; esac

script_dir="$(cd "$(dirname "$0")" && pwd)"

# Minimum JDK that can launch Gradle 9.x (the wrapper's runtime floor).
required_java=17

problems=()
echo "==> Checking environment for Kotlin (Gradle) development"

# Required: a JDK on PATH to launch the Gradle wrapper.
if ! command -v java >/dev/null 2>&1; then
  problems+=("a JDK ('java' is not on PATH) — needed to launch the Gradle wrapper")
else
  # `java -version` prints to stderr, e.g. `openjdk version "21.0.1"` or the
  # legacy `"1.8.0_392"` form (the real major lives after the `1.`). Capture the
  # whole output (no `head` pipe — that can SIGPIPE-fail the pipeline under
  # `pipefail` and abort the script), then read the major off the first line only.
  ver="$(java -version 2>&1 || true)"
  maj="$(printf '%s\n' "$ver" | sed -n '1s/.*version "\([0-9]\{1,\}\).*/\1/p')"
  if [ "$maj" = "1" ]; then
    maj="$(printf '%s\n' "$ver" | sed -n '1s/.*version "1\.\([0-9]\{1,\}\).*/\1/p')"
  fi
  if [ -z "$maj" ]; then
    echo "    note: could not parse the 'java -version' output; assuming it is usable."
  elif [ "$maj" -ge "$required_java" ]; then
    echo "    JDK $maj found (launcher)"
  else
    problems+=("a JDK >= $required_java to launch Gradle (found JDK $maj)")
  fi
fi

# Soft: the Gradle wrapper should ship with the repo; the first run downloads Gradle.
[ -f "$script_dir/../gradlew" ] || \
  echo "    note: ./gradlew not found next to this repo root — run from the template root."

if [ ${#problems[@]} -eq 0 ]; then
  echo
  echo "Environment ready. Next: bash ./scripts/init.sh --project-name ..."
  echo "(The JDK 25 compile toolchain is fetched automatically on the first ./gradlew build.)"
  exit 0
fi

echo
echo "Environment NOT ready. Missing:"
for p in "${problems[@]}"; do echo "  - $p"; done
echo
echo "Install a JDK ${required_java}+ (Temurin is a good default), then re-run this check:"
echo "  Windows : winget install EclipseAdoptium.Temurin.21.JDK"
echo "  macOS   : brew install temurin"
echo "  Linux   : sudo apt-get install openjdk-21-jdk   # or see https://adoptium.net"
exit 1
