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
- 确认 `vexra-adb` 中 H2 衍生文件保留原始版权头和许可证声明。
- 确认新增第三方源码或资源已登记到 `THIRD-PARTY-NOTICES.md`。
- 确认 Maven POM 中的许可证信息与 artifact 内容一致。
- 执行 `.\gradlew.bat clean assemble`。
- 执行可用的 `check` 或模块级测试；如测试依赖外部环境，应记录未执行原因。
- 生成或更新依赖许可证报告/SBOM，并复核传递依赖许可证。
- 检查源码包、二进制包、`sources.jar`、`javadoc.jar` 是否携带必要的 `LICENSE`、`NOTICE` 和第三方归属信息。

## H2/ADB 边界

`vexra-adb` 携带了 H2 Database Engine 衍生代码和资源。发布时必须明确：

- Vexra 自有代码默认 Apache-2.0。
- H2 衍生内容保持文件头声明的 MPL 2.0 / EPL 1.0 双许可证。
- 对 H2 衍生内容的修改应可由提交历史、文件注释或发布说明追踪。

## 后续建议

- 引入 Gradle 依赖许可证报告插件或 CycloneDX SBOM 插件。
- 在 CI 中固定执行 `assemble`、`check` 和许可证清单校验。
- 对发布流程增加凭据泄露扫描。
