# 开源发布合规清单

本文档用于 Vexra 发布前自检。

## 必备文件

- [x] `LICENSE`
- [x] `NOTICE`
- [x] `THIRD-PARTY-NOTICES.md`
- [x] `CONTRIBUTING.md`
- [x] `SECURITY.md`
- [x] `CODE_OF_CONDUCT.md`
- [x] `signing.properties.example`

## 发布前检查

- 确认 `signing.properties` 未被 Git 跟踪，真实凭据未出现在提交历史、日志、截图或 issue 中。
- 确认新增第三方源码、二进制或资源已登记到 `THIRD-PARTY-NOTICES.md`。
- 确认 Maven POM 中的许可证信息与 artifact 内容一致。
- 执行 `.\gradlew.bat clean assemble`，或记录未执行原因。
- 执行可用的 `check` 或模块级测试；如测试依赖外部环境，应记录未执行原因。
- 生成或更新依赖许可证报告 / SBOM，并复核传递依赖许可证。
- 检查源码包、二进制包、`sources.jar`、`javadoc.jar` 是否携带必要的 `LICENSE`、`NOTICE` 和第三方归属信息。

## H2 / ADB 边界

ADB-H2-10 之后，`vexra-adb` 不再在模块源码和资源中分发旧 `org.adb.*` H2 衍生副本。发布时必须明确：

- Vexra 自有代码默认 Apache-2.0。
- H2 Database Engine 能力通过 `h2db` 依赖引入，许可证和归属信息应由依赖清单、SBOM 和发布说明体现。
- ADB 差异化实现保留在 `net.xdob.vexra.adb.*`，包括表、索引、事务可见性、锁、行编码和底层 store 适配。
- `jdbc:adb:*` 是 h2db Driver 插件化 URL provider 暴露的兼容前缀，不再代表独立 `org.adb.Driver` 发行物。

## 后续建议

- 引入 Gradle 依赖许可证报告插件或 CycloneDX SBOM 插件。
- 在 CI 中固定执行 `assemble`、`check` 和许可证清单校验。
- 对发布流程增加凭据泄露扫描。
