# Security Policy

## Supported versions

Security fixes are applied to the latest released version of **processkit**.
Older versions are not maintained — upgrade to the latest release to receive
fixes.

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report privately through GitHub's
[private vulnerability reporting](https://github.com/ZelAnton/processkit-kotlin/security/advisories/new)
(repository **Security → Advisories → Report a vulnerability**). If that is
unavailable, contact the maintainer listed on the
[ZelAnton](https://github.com/ZelAnton) profile.

Please include:

- a description of the vulnerability and its impact;
- steps to reproduce (a minimal proof of concept is ideal);
- affected version(s).

You can expect an initial acknowledgement within a few days. Once a fix is
ready, a patched release is published to Maven Central and the advisory is disclosed.

## Automated scanning

This repository runs [GitHub CodeQL](.github/workflows/codeql.yml)
(`security-and-quality` suite, `java-kotlin` pack) on every pull request, every
push to `main`, and weekly. [Dependabot](.github/dependabot.yml) keeps Gradle
dependencies and Actions current, and the
[dependency-submission workflow](.github/workflows/dependency-submission.yml)
reports the resolved Gradle dependency graph so security alerts also cover
transitive dependencies.
