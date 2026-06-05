# h2db Upstream Request: Database lifecycle plugin SPI

## Requested capability

- Add a first-class plugin SPI to handle database lifecycle close events for plugin-managed engines.
- Remove the need to rely on URL-level `DATABASE_EVENT_LISTENER` settings as an adapter path.

## Background

- `vexra-adb` currently uses `JdbcUrlPrefixProvider` + `AdbDatabaseEventListener`.
- The listener is registered by injecting `DATABASE_EVENT_LISTENER` in rewritten JDBC URL.
- This is functional, but it is a compatibility bridge rather than a clean plugin lifecycle contract.

## Expected API shape

- A `DatabaseLifecycleProvider` (or equivalent) that is invoked for DB close/recycle events.
- Clear ordering and failure contract around close callbacks.
- Deterministic registration flow without requiring external URL rewriting in application SQL strings.

## Why needed

- Reduce coupling to URL-level behavior when database close is a storage-engine responsibility.
- Improve long-term compatibility when h2db evolves plugin internals.
- Align with plugin architecture goals for third-party storage engines.

## Requested migration path

1. Introduce SPI in h2db.
2. Publish basic contract docs.
3. Add minimal compatibility note for existing `DATABASE_EVENT_LISTENER` users.
4. Validate with one external storage plugin integration path.
