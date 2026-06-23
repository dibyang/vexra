# Bug Reporting Guide

[简体中文](../bug-reporting.md) | English

Thank you for helping improve Vexra. A good bug report lets maintainers reproduce the issue, locate the failing component, and assess impact quickly.

## Before Reporting

- Confirm the issue is not an unpatched security vulnerability. If it may be security-sensitive, read the [security policy](../../SECURITY.md) and use a private reporting channel.
- Try to reproduce on the relevant latest branch, latest release, or a clearly identified commit.
- Search existing issues to avoid duplicates.
- Remove keys, tokens, production accounts, private repository URLs, and sensitive data from logs, screenshots, and configuration.

## Required Information

Use the Bug report form and include:

- Vexra version, dependency coordinates, branch, or commit.
- Affected module, such as `vexra-server`, `vexra-netty`, `vexra-adb`, or a state-machine plugin.
- Usage path, such as direct client API usage, ADB/JDBC, gRPC, Netty, custom `SMPlugin`, or test environment.
- Expected behavior and actual behavior.
- Minimal reproducer, preferably a test case, command sequence, or minimal repository.
- JDK, operating system, Gradle, transport, cluster node count, LDB/storage dependency version, and key configuration.
- The smallest useful log excerpt, first exception stack, and error output.

## Concurrency, Storage, and Consistency Issues

For issues involving Raft, transactions, cache, indexes, disk storage, timeouts, or network retries, include:

- Thread count, iteration count, failure frequency, and first failure time.
- Whether transactions, cache, indexes, browser access, checkpoint/restore, repair/check, or backup/restore paths are involved.
- Cluster topology, leader changes, network interruptions, node restarts, snapshot installation, or log recovery events.
- Critical data state before and after the failure, expected commit order, and observed inconsistency.

## Reproducer Preferences

Preferred evidence, from strongest to weakest:

1. A failing test that can be added to this repository.
2. A standalone minimal repository or script.
3. A precise command sequence and configuration excerpt.
4. If the issue is intermittent, logs, frequency, and the most recent complete environment details.

## Security Boundary

Public bug issues must not include exploit details, attack payloads, credentials, production data, or unreleased system topology. If you are unsure whether an issue is security-sensitive, treat it as security-sensitive first and use a private channel or a placeholder issue without details.
