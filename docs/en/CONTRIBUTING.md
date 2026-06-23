# Contributing Guide

[简体中文](../../CONTRIBUTING.md) | English

Thank you for your interest in Vexra. Before contributing, please read this guide, the [bug reporting guide](bug-reporting.md), and [THIRD-PARTY-NOTICES.md](../../THIRD-PARTY-NOTICES.md).

## Development Environment

- JDK 8.
- Use the Gradle Wrapper included in this repository.
- Keep documentation, source comments, and project explanatory text in UTF-8.
- Chinese is the default language for project explanations and comments. Public API documentation may include English when needed.

## Feedback and Communication

- For ordinary bugs, use the GitHub Bug report form and provide reproducible details according to the [bug reporting guide](bug-reporting.md).
- For security vulnerabilities, follow the [security policy](SECURITY.md) and do not disclose exploit details in public issues.
- For feature requests, use the Feature request template and describe the use case, expected behavior, and compatibility impact.

## Local Validation

Common commands:

```powershell
.\gradlew.bat clean assemble
.\gradlew.bat check
```

If some tests require external services, the independent LDB project, or a special local environment, explain which checks were not run and why in the pull request description.

## Submission Requirements

- Keep changes focused and avoid unrelated formatting churn.
- For changes involving interfaces, protocols, disk formats, database structures, state-machine flows, migration, or compatibility behavior, add design notes or change notes first.
- Bug fixes should include regression tests when practical. If a test cannot be added yet, explain the reproducer, validation command, and remaining risk.
- When adding third-party source code, resources, or generated artifacts, update license, source, modification notes, and NOTICE or third-party inventory files.
- Do not commit keys, tokens, account passwords, signing files, production configuration, or private repository URLs.
- Keep Java code compatible with JDK 8.

## Pull Request Description

A pull request should include:

- Change summary.
- Impact scope and compatibility notes.
- Test or validation commands.
- Whether the change affects third-party licenses, NOTICE files, release configuration, or security.

## License

Unless a file header or directory notice states otherwise, contributions are licensed to this project under Apache License 2.0. If a contribution includes third-party content, the contributor must confirm that the license allows redistribution and preserves required notices.
