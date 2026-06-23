# Vexra

[English](README.en.md) | 简体中文

Vexra 是一个基于 Raft 共识协议的分布式数据存储项目，目标是在多节点环境下提供强一致、容错、可恢复的数据复制能力，并通过 ADB/JDBC 层对外提供数据库访问能力。

## 核心特性

- **Raft 强一致复制**：通过 leader 选举、日志复制、提交索引和状态机应用保证已提交数据的一致性。
- **容错与恢复**：支持节点故障恢复、日志恢复、快照安装和状态机恢复流程。
- **多传输实现**：提供 gRPC 与 Netty 两套传输实现，便于不同部署场景选择。
- **状态机插件化**：通过 `SMPlugin` 扩展状态机能力，当前包含复制 Map 示例和 ADB 插件。
- **ADB/JDBC 接入**：`vexra-adb` 提供数据库状态机插件和 JDBC 访问相关能力。
- **虚拟节点支持**：支持虚拟节点、共享存储检查和最少节点部署场景。
- **外部 LDB 存储依赖**：LDB 已拆分为独立项目，Vexra 通过依赖和插件边界集成本地 KV 存储能力。

## 模块概览

| 模块 | 说明 |
| --- | --- |
| `vexra-proto` | Protobuf 协议定义和 gRPC 代码生成 |
| `vexra-common` | 公共协议对象、配置、异常、工具和序列化辅助 |
| `vexra-client` | 客户端 API、重试、有序/无序请求和管理 API |
| `vexra-server-api` | 服务端接口、Raft 配置、存储接口和状态机接口 |
| `vexra-server-sm` | 状态机基础实现、插件容器、Raft 日志和快照管理 |
| `vexra-server` | Raft 服务端核心实现 |
| `vexra-grpc` | gRPC 传输实现 |
| `vexra-netty` | Netty 传输和 DataStream 实现 |
| `vexra-rmap` | 复制 Map 状态机示例 |
| `vexra-adb` | ADB/JDBC/数据库状态机插件 |
| `vexra-metrics-api` / `vexra-metrics-default` | 指标接口和默认实现 |

## LDB 独立说明

LDB 现在作为独立项目维护。Vexra 仓库中的 LDB 相关设计文档仅保留为 ADB 集成、依赖升级和历史迁移参考；LDB 的可靠性计划、磁盘格式、API 兼容和工具命令演进应以独立 LDB 项目为准。

ADB 与 LDB 的集成边界主要包括：

- LDB 依赖版本管理。
- ADB 所需列族和插件声明。
- checkpoint/restore、repair/check、backup/restore 等能力调用。
- LDB 版本升级前的 ADB 集成验证。

## 构建

项目使用 Gradle 多模块构建，当前目标 JDK 为 8。

```powershell
.\gradlew.bat clean assemble
```

常用属性位于 [gradle.properties](gradle.properties)。

## 测试

根构建中存在测试任务配置，执行验证时请以当前 CI 或模块级任务为准。常见本地验证方式：

```powershell
.\gradlew.bat :vexra-server-sm:test
.\gradlew.bat :vexra-server:test
.\gradlew.bat :vexra-adb:test
```

LDB 自身测试应在独立 LDB 项目中执行；Vexra 侧重点验证 ADB 与外部 LDB 依赖的集成行为。

## 文档

- [项目设计文档](docs/project-design.md)
- [Project Design Document](docs/project-design.en.md)
- [开源发布合规清单](docs/open-source-compliance.md)
- [Bug 提交指南](docs/bug-reporting.md)
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)

LDB 相关设计、可靠性计划和 API 兼容说明已迁移到独立 LDB 项目维护。

## 兼容性约束

- 源码目标保持 JDK 8。
- 文档、源码注释和项目说明保持 UTF-8。
- 协议字段演进需遵循 Protobuf 兼容原则。
- ADB/LDB 依赖升级需要明确迁移、回滚和集成测试范围。

## License

Vexra 自有代码默认使用 Apache License 2.0，见 [LICENSE](LICENSE)。

仓库中包含的第三方源码、资源和依赖归属见 [NOTICE](NOTICE) 和 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。其中 `vexra-adb` 模块包含 H2 Database Engine 衍生代码和资源，相关文件保留 H2 Group 的 MPL 2.0 / EPL 1.0 双许可证声明。
