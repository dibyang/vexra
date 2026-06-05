# ADB-H2 插件化迁移发布前清单

## 发布结论

`vexra-adb` 的 H2 插件化迁移主线已完成，可以进入发布前验收阶段。当前版本不再分发旧 `org.adb.*` H2 分叉代码，SQL parser、JDBC、Server 和工具链路回归 `h2db` 依赖；ADB 只保留表、索引、事务可见性和底层 store 等自有能力。

## 已完成项

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| h2db 依赖 | 已完成 | `vexra-adb` 使用 `net.xdob.h2db:h2db:${h2db_version}` |
| H2 插件注册 | 已完成 | `AdbH2Plugin` 通过 `META-INF/services/org.h2.api.H2Plugin` 自动发现 |
| JDBC URL 兼容 | 已完成 | `jdbc:adb:*` 由 `AdbJdbcUrlPrefixProvider` 映射到 `jdbc:h2:*` |
| 表 provider | 已完成 | `AdbTableProvider` 创建 ADB 自有表实现 |
| 事务事件 | 已完成 | `AdbTransactionEventProvider` 承接 commit / rollback 边界 |
| 数据库生命周期 | 已完成 | `AdbDatabaseLifecycleProvider` 承接 close 事件并释放 `DbStoreEngine` |
| 旧 H2 分叉代码 | 已完成 | 旧 `org.adb.*` 源码与资源已移除 |
| 合规边界 | 已完成 | `docs/open-source-compliance.md` 已说明 h2db 依赖边界 |

## 已执行验证

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :vexra-adb:compileJava :vexra-adb:test --tests net.xdob.vexra.adb.h2plugin.AdbJdbcUrlPrefixProviderTest --tests net.xdob.vexra.adb.h2plugin.AdbDatabaseLifecycleProviderTest` | 通过 |
| `.\gradlew.bat :vexra-adb:test` | 通过 |

## 发布前仍建议执行

| 验收项 | 目的 |
| --- | --- |
| 全仓测试 | 确认 ADB 迁移没有影响其他模块集成 |
| DBServer TCP smoke test | 确认 `org.h2.tools.Server` 替换后启动/停止行为稳定 |
| close/reopen 长时间循环 | 确认 `DatabaseLifecycleProvider` 持续释放底层 LDB / RocksDB 资源 |
| checkpoint / snapshot / restore 回归 | 确认 ADB 与 LDB 存储恢复链路不受迁移影响 |
| 发布包依赖清单检查 | 确认发布物不再包含旧 `org.adb.*` H2 衍生代码 |

## 兼容性说明

- 老的 `jdbc:adb:*` URL 可以继续使用。
- 调用方应加载 `org.h2.Driver`，或依赖 `DriverManager` / ServiceLoader 自动发现。
- 旧的 `org.adb.Driver` 不再作为发行入口。
- 旧的 `DATABASE_EVENT_LISTENER` URL 注入桥接已删除，数据库关闭事件改由 h2db 插件 SPI 承接。

## 回滚点

| 场景 | 回滚方式 |
| --- | --- |
| h2db 插件装载失败 | 回退到迁移前 `vexra-adb` 发行版本 |
| `jdbc:adb:*` 映射异常 | 临时要求调用方使用等价 `jdbc:h2:*;DEFAULT_TABLE_ENGINE=adb_table` |
| close/reopen 资源释放异常 | 回退 `AdbDatabaseLifecycleProvider` 相关提交并恢复上一版桥接实现 |
| ADB 表/索引行为回归 | 回退对应 ADB-H2 迁移提交，优先定位表 provider 或索引实现 |

## 发布说明要点

- `vexra-adb` 已切换为 h2db 插件化发行模型。
- `jdbc:adb:*` 是兼容 URL 前缀，不再代表独立 JDBC Driver。
- ADB 自有存储、事务和索引能力仍由 `net.xdob.vexra.adb.*` 提供。
- h2db 生命周期 SPI 已接入，关闭数据库时会通过插件释放 ADB 底层 store。
