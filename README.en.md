# Vexra

English | [简体中文](README.md)

Vexra is a distributed data storage project based on the Raft consensus protocol. It aims to provide strongly consistent, fault-tolerant, and recoverable data replication across multiple nodes, with database access exposed through the ADB/JDBC layer.

## Key Features

- **Strongly consistent Raft replication**: leader election, log replication, commit indexes, and state-machine application keep committed data consistent.
- **Fault tolerance and recovery**: supports node recovery, log recovery, snapshot installation, and state-machine restore flows.
- **Multiple transports**: provides both gRPC and Netty transport implementations.
- **Pluggable state machines**: extends state-machine behavior through `SMPlugin`, including a replicated-map example and the ADB plugin.
- **ADB/JDBC access**: the adjacent independent `vexra-adb` project provides core database capabilities, while this project adds Raft cluster mode through `vexra-adb-raft`.
- **Virtual node support**: supports virtual nodes, shared-storage checks, and minimum-node deployment scenarios.
- **External LDB storage dependency**: LDB has been split into an independent project; Vexra integrates local KV storage through dependency and plugin boundaries.

## Modules

| Module | Description |
| --- | --- |
| `vexra-proto` | Protobuf definitions and gRPC code generation |
| `vexra-common` | Shared protocol objects, configuration, exceptions, utilities, and serialization helpers |
| `vexra-client` | Client APIs, retry handling, ordered/unordered requests, and admin APIs |
| `vexra-server-api` | Server interfaces, Raft configuration, storage interfaces, and state-machine interfaces |
| `vexra-server-sm` | State-machine base implementation, plugin container, Raft log, and snapshot management |
| `vexra-server` | Raft server core implementation |
| `vexra-grpc` | gRPC transport implementation |
| `vexra-netty` | Netty transport and DataStream implementation |
| `vexra-rmap` | Replicated-map state-machine example |
| `vexra-adb-raft` | Raft cluster database extension built on the independent `vexra-adb` core |
| `vexra-metrics-api` / `vexra-metrics-default` | Metrics API and default implementation |

## LDB Independence

LDB is now maintained as an independent project. LDB-related design documents kept in this repository are retained only as references for ADB integration, dependency upgrades, and migration history. LDB reliability plans, disk formats, API compatibility, and tool-command evolution should be governed by the independent LDB project.

The ADB/LDB integration boundary mainly covers:

- LDB dependency version management.
- ADB column-family and plugin declarations.
- Calls to checkpoint/restore, repair/check, and backup/restore capabilities.
- ADB integration verification before LDB dependency upgrades.

## Build

This is a Gradle multi-module project targeting JDK 8.

```powershell
.\gradlew.bat clean assemble
```

Common project properties are defined in [gradle.properties](gradle.properties).

## Testing

The root build contains test-task configuration, so validation should follow the current CI or module-level tasks. Common local commands:

```powershell
.\gradlew.bat :vexra-server-sm:test
.\gradlew.bat :vexra-server:test
.\gradlew.bat :vexra-adb-raft:test
```

LDB's own tests should run in the independent LDB project. In Vexra, testing should focus on ADB integration with the external LDB dependency.

## Documentation

- [项目设计文档](docs/project-design.md)
- [Project Design Document](docs/project-design.en.md)
- [Open source compliance checklist](docs/open-source-compliance.md)
- [Contributing guide](CONTRIBUTING.md)
- [Security policy](SECURITY.md)

LDB design, reliability plans, and API compatibility notes are now maintained in the independent LDB project.

## Compatibility Constraints

- Source compatibility remains JDK 8.
- Documentation, source comments, and project explanatory text must stay UTF-8.
- Protocol field evolution must follow Protobuf compatibility rules.
- ADB/LDB dependency upgrades require explicit migration, rollback, and integration-test scope.

## License

Vexra-owned code is licensed under the Apache License 2.0 by default. See [LICENSE](LICENSE).

Third-party source code, resources, and dependency attributions are documented in [NOTICE](NOTICE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). ADB core and its H2 Database Engine-derived resources are maintained in the adjacent independent `vexra-adb` project.
