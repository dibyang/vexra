# h2db Upstream Request: Database lifecycle plugin SPI

## Status

- Supported by h2db.
- `vexra-adb` should use `DatabaseLifecycleProvider` instead of URL-level `DATABASE_EVENT_LISTENER`.

## Original requested capability

- Add a first-class plugin SPI to handle database lifecycle close events for plugin-managed engines.
- Remove the need to rely on URL-level `DATABASE_EVENT_LISTENER` settings as an adapter path.

## Background

- `vexra-adb` previously used `JdbcUrlPrefixProvider` + `AdbDatabaseEventListener`.
- The listener was registered by injecting `DATABASE_EVENT_LISTENER` in rewritten JDBC URL.
- After h2db support landed, ADB should register `AdbDatabaseLifecycleProvider` through `AdbH2Plugin`.

## Expected API shape

- A `DatabaseLifecycleProvider` invoked for DB close/recycle events.
- Clear ordering and failure contract around close callbacks.
- Deterministic registration flow without requiring external URL rewriting in application SQL strings.

## Why needed

- Reduce coupling to URL-level behavior when database close is a storage-engine responsibility.
- Improve long-term compatibility when h2db evolves plugin internals.
- Align with plugin architecture goals for third-party storage engines.

## Migration path

1. Register an ADB `DatabaseLifecycleProvider` through `AdbH2Plugin`.
2. Remove `DATABASE_EVENT_LISTENER` injection from `AdbJdbcUrlPrefixProvider`.
3. Keep close/reopen regression tests around `DbStoreEngine.close(...)`.
4. Update migration docs and release notes to say the upstream gap is closed.
