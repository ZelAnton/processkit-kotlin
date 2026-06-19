#!/usr/bin/env bash
#
# Run the build + tests inside a Linux container (JDK 25) — the cross-platform
# half of the test matrix. The Windows host runs `./gradlew test` natively (Job
# Object path); this exercises the Linux cgroup v2 / POSIX process-group paths.
#
# Works with Rancher Desktop's `docker` CLI (default) or `nerdctl`
# (set `DOCKER=nerdctl`). No bind mounts — the image COPYs the source, so there
# are no Windows<->Linux path or build-artifact clashes.
#
# Usage:
#   scripts/test-docker.sh                                  # ./gradlew build
#   scripts/test-docker.sh test                             # tests only
#   scripts/test-docker.sh test --tests "net.zelanton.processkit.*"
#
# Later, the kill-on-close / cgroup tests may need extra runtime privileges; pass
# them through DOCKER_RUN_ARGS, e.g. DOCKER_RUN_ARGS="--privileged --cgroupns=host".
set -euo pipefail
cd "$(dirname "$0")/.."

ENGINE="${DOCKER:-docker}"
IMAGE="${IMAGE:-processkit-kotlin-test}"

if [ "$#" -eq 0 ]; then
    set -- build
fi

echo "==> Building test image '$IMAGE' with $ENGINE"
"$ENGINE" build -t "$IMAGE" .

echo "==> Running: ./gradlew $* (in $IMAGE)"
# --init: a PID-1 reaper (tini) so orphaned grandchildren in the process-tree
# tests are reaped, not left as zombies (which would read as still-alive).
# shellcheck disable=SC2086  # DOCKER_RUN_ARGS is an intentional word-split list.
exec "$ENGINE" run --rm --init ${DOCKER_RUN_ARGS:-} "$IMAGE" \
    ./gradlew "$@" --no-daemon --console=plain
