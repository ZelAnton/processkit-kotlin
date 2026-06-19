#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run the build + tests inside a Linux container (JDK 25).

.DESCRIPTION
    The cross-platform half of the test matrix. The Windows host runs
    `./gradlew test` natively (Job Object path); this exercises the Linux
    cgroup v2 / POSIX process-group paths. POSIX counterpart: scripts/test-docker.sh.

    Works with Rancher Desktop's `docker` CLI (default) or `nerdctl`
    ($env:DOCKER = 'nerdctl'). No bind mounts — the image COPYs the source, so
    there are no Windows<->Linux path or build-artifact clashes.

.EXAMPLE
    pwsh ./scripts/test-docker.ps1
    pwsh ./scripts/test-docker.ps1 test
    pwsh ./scripts/test-docker.ps1 test --tests "net.zelanton.processkit.*"

.NOTES
    Later, the kill-on-close / cgroup tests may need extra runtime privileges;
    set $env:DOCKER_RUN_ARGS (e.g. '--privileged --cgroupns=host').
#>
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$GradleArgs)

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$engine = if ($env:DOCKER) { $env:DOCKER } else { 'docker' }
$image = if ($env:IMAGE) { $env:IMAGE } else { 'processkit-kotlin-test' }
if (-not $GradleArgs -or $GradleArgs.Count -eq 0) {
    $GradleArgs = @('build')
}
$runArgs = if ($env:DOCKER_RUN_ARGS) { $env:DOCKER_RUN_ARGS.Split(' ', [StringSplitOptions]::RemoveEmptyEntries) } else { @() }

Write-Host "==> Building test image '$image' with $engine" -ForegroundColor Cyan
& $engine build -t $image $repoRoot
if ($LASTEXITCODE -ne 0) { throw "image build failed (exit $LASTEXITCODE)" }

Write-Host "==> Running: ./gradlew $($GradleArgs -join ' ') (in $image)" -ForegroundColor Cyan
# --init: a PID-1 reaper (tini) so orphaned grandchildren in the process-tree
# tests are reaped, not left as zombies (which would read as still-alive).
& $engine run --rm --init @runArgs $image ./gradlew @GradleArgs --no-daemon --console=plain
exit $LASTEXITCODE
