# Security Policy

[简体中文](../../SECURITY.md) | English

## Supported Versions

This project is still in an early stage. Security fixes target the main branch and the latest release by default. Maintainers decide whether to backport fixes to older versions based on impact.

## Vulnerability Reporting

Do not disclose details of unpatched vulnerabilities in public issues. Prefer GitHub Private Vulnerability Reporting. If private reporting is not enabled, create a public placeholder issue without details, or contact the maintainers to establish a private channel before sharing the full report.

A useful report should include:

- Affected module, version, dependency coordinates, or commit.
- Reproduction steps or a minimal reproducer.
- Impact assessment, such as data exposure, arbitrary file access, exploitable deserialization, privilege bypass, remotely triggerable denial of service, remote execution, or consistency violation.
- Affected deployment conditions, such as network exposure, authentication state, cluster size, transport, ADB/JDBC path, or state-machine plugin path.
- Known mitigations.

## Content That Does Not Belong in Public Issues

Use a private security channel for:

- Unauthorized reads or writes, data exposure, or privilege escalation.
- Arbitrary file access, arbitrary code execution, or command execution.
- Remotely and reliably triggerable denial of service, resource exhaustion, or consistency violation.
- Exposure of keys, tokens, production configuration, private repository URLs, or supply-chain credentials.
- Reports that require exploit details, proof-of-vulnerability code, or attack payloads.

## Response Process

After receiving a report, maintainers should:

- Confirm validity and impact scope.
- Provide a fix plan or temporary mitigation guidance.
- Publish a security advisory and upgrade guidance after the fix is released.

## Credential and Release Security

- Do not commit `signing.properties`, GPG private keys, repository tokens, OSS/Maven publishing credentials, or production configuration.
- Keep release credentials in user-level Gradle configuration, environment variables, or a dedicated secret-management system.
- Rotate credentials immediately if they appear in logs, screenshots, chat records, or repository history.
