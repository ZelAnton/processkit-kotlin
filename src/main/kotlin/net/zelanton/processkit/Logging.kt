package net.zelanton.processkit

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The library's single SLF4J logger (named `net.zelanton.processkit`). Lifecycle
 * events log at DEBUG; configure that logger to see them.
 *
 * **Secret-safe:** messages name the program and report exit codes / counts /
 * durations, but never log argv **arguments** or environment **values** — a token
 * passed as `--token=…` or `API_KEY=…` must never reach the logs.
 */
internal val log: Logger = LoggerFactory.getLogger("net.zelanton.processkit")
