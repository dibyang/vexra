# 第三方许可证与归属说明

本文档记录 Vexra 仓库中直接携带的第三方源码、资源和主要构建依赖。它用于开源发布审查，不替代各第三方项目自身许可证文本。

## 项目许可证边界

- Vexra 自有代码默认使用 Apache License 2.0，见 [LICENSE](LICENSE)。
- 文件头、资源头或目录说明中标注其他许可证的内容，以对应标注为准。
- `vexra-adb` 中的 `org.adb` 包和相关 `org.adb` 资源包含 H2 Database Engine 衍生内容，保留 H2 Group 版权声明和原始许可证声明。

## 仓库内携带的第三方源码和资源

| 范围 | 来源 | 许可证 | 说明 |
| --- | --- | --- | --- |
| `vexra-adb/src/main/java/org/adb/**` | H2 Database Engine 衍生代码 | MPL 2.0 / EPL 1.0 双许可证 | 文件头保留 H2 Group 版权与许可证声明。包名已调整为 `org.adb`，如有本项目修改，应在提交历史或文件注释中保留修改记录。 |
| `vexra-adb/src/main/resources/org.adb/**` | H2 Database Engine 衍生资源 | MPL 2.0 / EPL 1.0 双许可证 | 包含帮助文档、国际化资源、图片和 Web 控制台资源等。 |
| `vexra-adb/src/main/java/org/adb/server/web/res/**` | H2 Web 控制台资源衍生内容 | MPL 2.0 / EPL 1.0 双许可证 | 部分 JSP、JS、CSS、图片资源保留 H2 Group 声明。 |

H2 Database Engine 许可证说明可参考：

- https://h2database.com/html/license.html
- https://www.mozilla.org/MPL/2.0/
- https://www.eclipse.org/legal/epl-v10.html

## 主要外部依赖清单

以下依赖来自 Gradle 构建脚本。版本以 `gradle.properties` 和各模块 `build.gradle` 为准；发布前建议使用依赖许可证插件或 SBOM 工具重新生成机器可读清单。

| 依赖 | 主要用途 | 常见许可证 |
| --- | --- | --- |
| `org.slf4j:slf4j-api` | 日志 API | MIT |
| `ch.qos.logback:logback-core`, `logback-classic` | 日志实现/测试或运行辅助 | EPL 1.0 / LGPL 2.1 |
| `com.google.protobuf:protobuf-java`, `protobuf-java-util` | Protobuf Java 运行库 | BSD-3-Clause |
| `io.grpc:*` | gRPC 通信 | Apache-2.0 |
| `io.netty:*` | Netty 通信 | Apache-2.0 |
| `com.google.guava:guava` | Java 工具库 | Apache-2.0 |
| `io.dropwizard.metrics:*` | 指标采集 | Apache-2.0 |
| `org.junit.jupiter:*` | 测试框架 | EPL 2.0 |
| `jline:jline` | 控制台交互 | BSD-3-Clause |
| `org.osgi:*` | OSGi 接口 | Apache-2.0 |
| `org.locationtech.jts:jts-core` | 几何/空间数据支持 | EPL 2.0 / BSD-3-Clause |
| `javax.servlet:javax.servlet-api` | Servlet API | CDDL 1.1 / GPLv2 with Classpath Exception |
| `jakarta.servlet:jakarta.servlet-api` | Jakarta Servlet API | EPL 2.0 / GPLv2 with Classpath Exception |
| `org.apache.lucene:*` | 搜索/解析 compileOnly 依赖 | Apache-2.0 |
| `org.rocksdb:rocksdbjni` | RocksDB compileOnly 依赖 | Apache-2.0 / GPLv2 dual license, depending on distribution terms |
| `org.lz4:lz4-java` | LZ4 压缩 | Apache-2.0 |
| `net.xdob.vexra:vexra-ldb` | 外部 LDB 存储依赖 | 以独立 LDB 项目声明为准 |
| `net.researchgate:gradle-release` | Gradle 发布插件 | Apache-2.0 |
| `com.netflix.nebula:gradle-ospackage-plugin` | Gradle 打包插件 | Apache-2.0 |

## 发布检查要求

发布源码包、二进制包或 Maven artifact 前，应确认：

- 根目录包含 `LICENSE`、`NOTICE` 和本文件。
- 源码包保留所有第三方文件头和资源版权声明。
- `vexra-adb` 的 POM 能表达 H2 衍生内容的 MPL 2.0 / EPL 1.0 许可证边界。
- 生成的 `sources.jar` 和 `javadoc.jar` 不丢失必要的版权或归属信息。
- 通过依赖许可证报告或 SBOM 复核实际传递依赖。
- 发布凭据只来自本机安全存储、环境变量或用户级 Gradle 配置，不提交到仓库。
