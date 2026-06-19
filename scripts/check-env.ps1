#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Checks this machine can build and test this Kotlin (Gradle) project before you
    initialize the template.

.DESCRIPTION
    Verifies a JDK is on PATH to launch the Gradle wrapper (Gradle 9.x needs Java
    17+ to run). The JDK 25 *compile* toolchain is downloaded automatically by the
    Gradle foojay resolver, so only a launcher JDK is required here. Prints
    "Environment ready" and exits 0 on success; if Java is missing or too old it
    prints per-OS install commands and exits 1 — install one, then re-run.

    Run it first, before scripts/init.ps1:

        pwsh ./scripts/check-env.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$problems = @()

# Minimum JDK that can launch Gradle 9.x (the wrapper's runtime floor).
$requiredJava = 17

Write-Host "==> Checking environment for Kotlin (Gradle) development" -ForegroundColor Cyan

# Required: a JDK on PATH to launch the Gradle wrapper.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $problems += "a JDK ('java' is not on PATH) — needed to launch the Gradle wrapper"
} else {
    # `java -version` prints to stderr, e.g. `openjdk version "21.0.1"` or
    # the legacy `"1.8.0_392"` form (major lives after the `1.`).
    $out = (& java -version 2>&1) -join "`n"
    if ($out -match 'version "([0-9]+)(?:\.([0-9]+))?') {
        $maj = [int]$Matches[1]
        if ($maj -eq 1 -and $Matches[2]) { $maj = [int]$Matches[2] }
        if ($maj -ge $requiredJava) {
            Write-Host "    JDK $maj found (launcher)" -ForegroundColor DarkGray
        } else {
            $problems += "a JDK >= $requiredJava to launch Gradle (found JDK $maj)"
        }
    } else {
        Write-Host "    note: could not parse the 'java -version' output; assuming it is usable." -ForegroundColor Yellow
    }
}

# Soft: the Gradle wrapper should ship with the repo; the first run downloads Gradle.
if (-not (Test-Path (Join-Path (Join-Path $PSScriptRoot '..') 'gradlew'))) {
    Write-Host "    note: ./gradlew not found next to this repo root — run from the template root." -ForegroundColor Yellow
}

if ($problems.Count -eq 0) {
    Write-Host ""
    Write-Host "Environment ready. Next: pwsh ./scripts/init.ps1 -ProjectName ..." -ForegroundColor Green
    Write-Host "(The JDK 25 compile toolchain is fetched automatically on the first ./gradlew build.)" -ForegroundColor DarkGray
    exit 0
}

Write-Host ""
Write-Host "Environment NOT ready. Missing:" -ForegroundColor Red
foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
Write-Host ""
Write-Host "Install a JDK $requiredJava+ (Temurin is a good default), then re-run this check:" -ForegroundColor Yellow
Write-Host "  Windows : winget install EclipseAdoptium.Temurin.21.JDK"
Write-Host "  macOS   : brew install temurin"
Write-Host "  Linux   : sudo apt-get install openjdk-21-jdk   # or see https://adoptium.net"
exit 1
