# Roadmap for a TiDB-like Distributed Database

## Background

`vexra-adb` has completed the H2 plugin migration: SQL parsing, JDBC, Server, and tools now come from `h2db`, while ADB keeps table, index, transaction-visibility, and low-level store logic. This solves the single-node SQL integration boundary, but it is still far from a TiDB-like distributed SQL database.

A TiDB-like system needs a SQL layer, distributed execution, distributed transactions, sharded storage, Raft replication, a scheduling control plane, Online DDL, backup/restore, and operational observability. Vexra already has state machine, Raft, ADB/LDB, and plugin-based SQL integration foundations, but these need to be organized into a scalable and fault-tolerant database architecture.

## Goals

- Summarize the remaining work from the current ADB/H2 plugin database to a TiDB-like distributed database.
- Split the work into reviewable and testable milestones.
- Make 2 data nodes + lightweight witness the recommended two-data-replica HA model.
- Avoid making shared storage the default HA direction.

## Non-Goals

- This document does not promise full TiDB, MySQL, or PostgreSQL compatibility.
- This document does not define a new disk format, RPC protocol, or SQL grammar.
- This document does not require all distributed database capabilities to be implemented at once.
- This document does not support pure 2-node strong-consistency automatic failover without a witness.

## Current State

| Module | Current Capability | Gap |
| --- | --- | --- |
| `h2db` | SQL parser, JDBC, Server, plugin SPI | Does not understand Vexra shards, Raft regions, or distributed execution |
| `vexra-adb` | ADB table provider, indexes, transaction visibility, LDB/Rocks adapters | Still close to a single-database/single-node execution model |
| `vexra-ldb` | Local KV store and plugin hooks | Not a distributed region store yet |
| Vexra state machine/Raft | State-machine and consensus foundations | Needs region groups, control plane, and multi-group scheduling |

## Core Constraints

- Strongly consistent writes require quorum or equivalent fencing/lease protection.
- Automatic failover with 2 data nodes and no shared storage requires a witness or external arbiter.
- SQL execution cannot assume all data is local.
- ADB key encoding, MVCC, checkpoint, and restore must remain backward-compatible.
- h2db table/index internals remain managed migration APIs and require contract tests before upgrades.

## Target Architecture

```mermaid
flowchart TB
  Client["SQL/JDBC Client"] --> SQL["h2db SQL/JDBC/Server"]
  SQL --> Planner["Vexra Distributed Planner"]
  Planner --> Router["Range/Region Router"]
  Router --> Exec["Distributed Executor"]
  Exec --> RegionA["Region Raft Group A"]
  Exec --> RegionB["Region Raft Group B"]
  RegionA --> StoreA["ADB/LDB or Rocks Store"]
  RegionB --> StoreB["ADB/LDB or Rocks Store"]
  PD["Control Plane / PD-like Service"] --> Router
  PD --> RegionA
  PD --> RegionB
  TSO["TSO Service"] --> Planner
```

## Remaining Work

| Area | Required Capability | Notes |
| --- | --- | --- |
| SQL | Distributed planner, distributed explain, statistics | h2db plans need to map to Vexra region tasks |
| Execution | scan/filter/limit/count pushdown, later agg/join/sort | Start small instead of building full MPP immediately |
| Routing | table/index key range to region mapping | All reads and writes go through a region router |
| Storage | region split/merge, range scan, snapshot install | Evolve from one DB store to movable regions |
| Replication | one Raft group or equivalent group per region | Define leader, term, epoch, and commit index |
| Transactions | TSO, MVCC, 2PC, lock resolve, GC safe point | This is the core complexity |
| Control plane | PD-like metadata, scheduling, health, placement rules | Manage regions, nodes, leaders, TSO, and scheduling |
| Online DDL | schema version, backfill, recovery | Support long-running DDL such as add/drop index |
| Operations | metrics, tracing, slow query, admin commands | Required for production use |
| Security | users, roles, privileges, TLS, audit | Can be phased by product goal |

## Key Design Tasks

### SQL and Execution

- Define `DistributedPlan` for region tasks, pushed predicates, returned schema, and merge strategy.
- Define `RegionScanTask` with key range, projection, filter, limit, and read timestamp.
- Implement minimal pushdown first: primary-key point lookup, range scan, secondary-index range scan, count.
- Add `EXPLAIN DISTRIBUTED` or equivalent diagnostics.
- Build statistics for row count, region size, and index cardinality.

### Sharding and Replication

- Define region metadata: `regionId`, `startKey`, `endKey`, `epoch`, `replicas`, `leader`.
- Support region split/merge and route epoch updates.
- Define replica roles: data voter, witness voter, learner.
- Support snapshot install, leader transfer, membership changes, and replica repair.
- Start with leader reads, then evaluate read index and follower reads.

### Distributed Transactions

- Add a global TSO service with monotonic `startTs` and `commitTs`.
- Define MVCC write/default/lock semantics or map existing ADB structures explicitly.
- Implement 2PC: prewrite, commit, rollback, lock resolve.
- Clean locks after timeout or client disconnect.
- Define GC safe point to protect long transactions and backup.
- Start with Snapshot Isolation unless a stronger level is explicitly required.

### Control Plane

- Build a PD-like service for cluster membership, region metadata, TSO, and scheduling.
- Support node join/leave, health checks, leader scheduling, and hotspot detection.
- Expose system tables or admin APIs for nodes, regions, locks, transactions, and plugins.
- Make the control plane highly available, preferably on the Vexra Raft state machine.

### DDL and Operations

- Define DDL job states: pending, running, backfilling, public, rollback, failed.
- Bind SQL sessions to schema versions to handle DDL/transaction concurrency.
- Support resumable and rate-limited index backfill.
- Define backup/restore semantics for full, incremental, point-in-time restore, and region checksums.

## 2 Data Nodes + Witness Conclusion

Pure 2 data nodes without shared storage cannot safely provide strong-consistency automatic failover. The recommended model is:

- 2 data nodes keep full data replicas.
- 1 lightweight witness only participates in voting, term/epoch/lease arbitration, and does not store business data.
- Writes require quorum: `A+B`, `A+W`, or `B+W`.
- Without quorum, writes are forbidden; the system can degrade to read-only or unavailable.
- Shared-storage mode remains available but disabled by default as a compatibility/transition mode.

See `docs/two-data-node-witness-ha-design.md` for the dedicated design.

## Milestones

| Phase | Name | Deliverable | Acceptance |
| --- | --- | --- | --- |
| ADB-Cluster-01 | Region metadata and range routing | region metadata, router, system table | A primary-key range routes to regions |
| ADB-Cluster-02 | Region Raft storage | region group, leader, snapshot, membership change | Single-region failure recovery and snapshot install pass |
| ADB-Cluster-03 | 2 data nodes + witness HA | witness voter, fencing, quorum writes | One data-node failure still allows data+witness writes |
| ADB-Cluster-04 | Minimal distributed transaction loop | TSO, MVCC, 2PC, lock resolve | Cross-region commit/rollback is consistent |
| ADB-Cluster-05 | Distributed SQL execution | region scan task, filter/count pushdown | SQL can merge results across regions |
| ADB-Cluster-06 | Online DDL | schema version, index backfill | add index does not block reads/writes and can recover |
| ADB-Cluster-07 | Operations and release | metrics, admin, backup/restore, upgrade flow | rolling upgrade and disaster recovery drills pass |

## Milestone Status

| Phase | Status | Deliverables |
| --- | --- | --- |
| ADB-Cluster-01 | Done | `KeyRange`, `RegionMetadata`, and `RegionRouter` provide byte-order range metadata, point/range routing, overlap validation, and system-table rows. |
| ADB-Cluster-02 | Done | `RegionRaftGroupFactory`, `RegionRaftGroupDescriptor`, `RegionMembershipChangePlan`, and `RegionSnapshotInstallPlan` bind region metadata to the existing RaftGroup, SetConfiguration, learner/listener, witness metadata, and snapshot install planning model. |
| ADB-Cluster-03 | Done | `RegionWitnessBinding` binds region metadata to quorum write fencing, failover planning, and durable witness vote state. The dedicated witness HA model is complete through HA-01 to HA-06. |
| ADB-Cluster-04 | Done | `TimestampOracle`, `InMemoryTimestampOracle`, `TwoPhaseCommitContext`, `TxnParticipant`, `TwoPhaseCommitState`, and `TxnLock` provide monotonic TSO, 2PC state transitions, primary participant validation, commit timestamp checks, rollback constraints, and lock-expiration semantics. |
| ADB-Cluster-05 | Done | `RegionScanTask`, `DistributedPlan`, `RegionQueryResult`, and `DistributedResultMerger` describe region scan pushdown, projection/filter/limit/readTs, count-only plans, row merging, and count aggregation across regions. |
| ADB-Cluster-06 | Done | `DdlJob`, `DdlJobState`, `DdlJobStateMachine`, `SchemaVersion`, and `IndexBackfillProgress` provide Online DDL state transitions, schema-version advancement, rollback/failure paths, and resumable index backfill progress. |
| ADB-Cluster-07 | Done | `ClusterOperationsSnapshot`, `ClusterHealthStatus`, `RollingUpgradePlan`, `BackupRestoreMode`, and `BackupRestorePlan` provide operations metrics/system rows, rolling upgrade sequencing, and backup/restore planning. |

## Runtime Integration Point: ADB Region Write Gate

The next step is to connect the completed region routing and witness quorum-write constraints to the real `vexra-adb` commit path:

- `TxnManager.commit(...)` calls an optional `AdbRegionWriteGate` before allocating `commitTs` and before durable commit.
- The default gate is no-op, preserving single-node ADB/H2 plugin mode and existing `jdbc:adb:*` behavior.
- Distributed mode can install a region-aware gate that maps ADB write-set `DataKey` values through `RegionRouter`, then uses `RegionWitnessBinding` for quorum fencing.
- If the gate fails, the transaction must not enter durable commit; rollback is to remove the gate or switch back to the no-op gate.
- This integration point does not change ADB key encoding, disk format, or the existing store API, and can later be replaced by the real region Raft write path.

## Runtime Integration Point: ADB Region Read Routing

After the write gate, the read path needs a pluggable region-routing entry point before it can evolve into mixed local/remote execution:

- `TxnManager.getVisible(...)`, `entryIterator(...)`, and `indexScanIterator(...)` call an optional `AdbRegionReadRouter` before local store reads.
- The default read router is no-op, preserving the current single-node read path and H2 table/index behavior.
- A region-aware read router only maps point reads, primary-table range scans, and index range scans to regions, and can notify diagnostics/observability components.
- This phase does not change scan cursors, the store API, or result merging; later work can replace this entry point with `RegionScanTask` and a remote executor.
- If the read router fails, the read operation fails; rollback is to remove the router or switch back to the no-op router.

## Remaining Implementation Phases

At the current state, the public models for ADB-Cluster-01 through ADB-Cluster-07 are complete. The real `vexra-adb` write path also has a region write gate, and the real read path has a region read router. The remaining work is no longer model definition; it is wiring those models into runnable distributed execution, replication, transactions, and operations.

ADB-Runtime-01 through ADB-Runtime-11 in the current roadmap are complete. There are 0 remaining implementation phases in this roadmap.

| Order | Phase | Goal | Main Deliverables | Acceptance |
| --- | --- | --- | --- | --- |
| - | - | - | - | - |

The next highest-priority work is no longer feature completion inside phases 1-11. It is wiring these runtime boundaries to real multi-node deployment, real Raft/RPC, certificates/privileges, and long-running stress tests.

### ADB-Runtime-03 Implementation Scope

`ADB-Runtime-03` has completed the local `RegionScanTask` adapter:

- The input is a `Transaction2` and a single `RegionScanTask`; the output is a `RegionQueryResult`.
- The adapter uses `RegionScanTask.keyRange` to scan ADB version keys by range and deduplicates by logical `DataKey`.
- Primary-table row scans use `DefaultVisibleRowResolver`; index scans first use `DefaultVisibleIndexResolver` to validate visible index entries, then look up the primary row.
- This phase returns minimal diagnostic fields: `row_id`, `payload`, and `key_hex`; index scans also return `index_id` and `index_hex`.
- This phase does not implement remote RPC, SQL planner rewrites, store API changes, or disk-format changes.
- The implementation is `AdbLocalRegionScanExecutor`, covered by `AdbLocalRegionScanExecutorTest`.

### ADB-Runtime-04 Implementation Scope

`ADB-Runtime-04` has completed the replaceable boundary for a remote region scan executor:

- Define a region scan request object carrying `RegionScanTask`, transaction ID, read timestamp, count-only flag, and timeout.
- Define an asynchronous scan client interface shared by real RPC, in-process fakes, and the local bridge.
- The distributed executor dispatches multiple region scan requests concurrently and uses `DistributedResultMerger` to merge rows or counts.
- Remote failures, timeouts, and interruption must map to `SQLException`, with regionId included in diagnostic messages.
- This phase does not implement the real network protocol and does not change the public `RegionScanTask` / `RegionQueryResult` models.
- The implementation includes `AdbRegionScanRequest`, `AdbRegionScanClient`, `AdbLocalRegionScanClient`, and `AdbDistributedRegionScanExecutor`, covered by `AdbDistributedRegionScanExecutorTest`.

### ADB-Runtime-05 Implementation Scope

`ADB-Runtime-05` has connected the ADB commit path to a replaceable region commit client:

- After the write gate passes and `commitTs` is allocated, `TxnManager.commit(...)` can call a region commit coordinator instead of directly calling local `DbStore.commitAsync`.
- The coordinator routes the current transaction write set to regions. The first runtime step only allows single-region commits; cross-region commit is deferred to ADB-Runtime-07 2PC.
- The coordinator validates region leader, epoch, and routing results. If validation fails, the transaction must not remain in `COMMITTING`.
- The commit client abstracts real region Raft apply. This phase provides a local bridge client reusing existing `DbStore.commitAsync`; later work can replace it with a real Raft/RPC client.
- This phase does not change the ADB intent/version disk format or the public `DbStore` interface.
- The implementation includes `AdbRegionCommitRequest`, `AdbRegionCommitClient`, `AdbLocalRegionCommitClient`, and `AdbRegionCommitCoordinator`, covered by `AdbRegionCommitCoordinatorTest`.

### ADB-Runtime-06 Implementation Scope

`ADB-Runtime-06` has wired control-plane region metadata snapshots and global TSO into ADB runtime:

- Define an ADB control-plane client that provides region route snapshots and global timestamp allocation.
- Define a session/runtime context that installs the route snapshot into `TxnManager` read router, write commit coordinator, and later extensibility points.
- `TxnManager` supports an optional external timestamp provider. When enabled, `startTs` and `commitTs` come from the control-plane TSO; when disabled, existing single-node counters remain unchanged.
- Route snapshots carry an epoch. A session can refresh explicitly, and new transactions use the refreshed region router.
- This phase does not implement a standalone PD process, does not change JDBC URL semantics, and does not enable distributed mode by default.
- The implementation includes `AdbControlPlaneClient`, `AdbControlPlaneSnapshot`, `InMemoryAdbControlPlaneClient`, `AdbControlPlaneTimestampProvider`, `AdbTimestampProvider`, and `AdbRuntimeSessionContext`, covered by `AdbRuntimeSessionContextTest`.

### ADB-Runtime-07 Implementation Scope

`ADB-Runtime-07` has extended cross-region writes from single-region commit to minimal 2PC orchestration:

- `AdbRegionCommitClient` now has three phases: `prewriteAsync`, `commitAsync`, and `rollbackAsync`. A real Raft/RPC client will implement region-local lock writes, commits, and rollbacks at this boundary.
- `AdbRegionCommitCoordinator` now routes and groups the write set by region. Single-region transactions keep the existing fast path; cross-region transactions choose the region containing the first written key as the primary participant.
- Cross-region transactions prewrite every participant first. After all prewrites succeed, they commit primary first and then secondary participants. A prewrite failure rolls back every participant that has already prewritten.
- If the primary has already committed and a secondary commit fails, the coordinator must not pretend the transaction fully rolled back. It surfaces the failure to the caller; later lock resolve/background cleanup must finish or repair the secondary.
- This phase completed coordinator-level 2PC orchestration, primary participant validation, failure rollback, and fault-injection tests. Real MVCC lock columns, background lock resolve workers, timeout cleanup, and idempotent recovery remain follow-up increments.
- The implementation touches `AdbRegionCommitClient`, `AdbRegionCommitRequest`, `AdbLocalRegionCommitClient`, and `AdbRegionCommitCoordinator`, covered by `AdbRegionCommitCoordinatorTest`.

### ADB-Runtime-08 Implementation Scope

`ADB-Runtime-08` has wired region split/merge and snapshot install into the ADB runtime boundary:

- Defined a control-plane interface that can publish route snapshots, so split/merge can advance route epochs without depending on the in-memory implementation type.
- Provided a minimal region topology manager: generate left/right child regions from a parent region and split key, then publish the new region metadata snapshot. Merge is limited to adjacent-region metadata merge first and does not move data files.
- Provided an ADB region snapshot installer bridge: accept `RegionSnapshotInstallPlan`, validate the target replica, and call `DbStore.restore(...)` to install the snapshot directory.
- This phase reuses the existing `DbStore.checkpoint(...)` / `restore(...)` capability, does not change the LDB/RocksDB disk format, and does not implement real Raft snapshot chunk transfer.
- Validation covers route epoch advancement and correct post-split routing, plus data readability after installing a checkpoint snapshot.
- The implementation touches `AdbRouteSnapshotPublisher`, `AdbRegionTopologyManager`, `AdbRegionSnapshotInstaller`, and `InMemoryAdbControlPlaneClient`, covered by `AdbRegionTopologyManagerTest`.

### ADB-Runtime-09 Implementation Scope

`ADB-Runtime-09` has converted h2db/ADB local scan intent into a distributed execution plan:

- Provided an ADB distributed plan adapter that converts table ID, rowId range, projections, filters, limit, and read timestamp for a table row scan into a region-split `DistributedPlan`.
- The adapter uses the current route snapshot's `RegionRouter` to compute intersections between the scan range and region ranges, so every `RegionScanTask` scans only the key range owned by that region.
- Provided `EXPLAIN DISTRIBUTED`-style plan text including regionId, key range, limit, read timestamp, and count-only flag. This starts as an internal diagnostic API and does not change h2db SQL syntax.
- This phase reuses `AdbDistributedRegionScanExecutor` and `AdbLocalRegionScanClient` to validate basic pushdown execution. Real h2db optimizer rules, statistics-based cost selection, and SQL syntax extensions remain follow-up increments.
- The implementation touches `AdbDistributedPlanAdapter`, covered by `AdbDistributedPlanAdapterTest`.

### ADB-Runtime-10 Implementation Scope

`ADB-Runtime-10` has wired the Online DDL public model into the ADB runtime:

- Provided an ADB Online DDL runtime controller that reuses `DdlJobStateMachine`, `SchemaVersion`, and `IndexBackfillProgress` to manage the ADD_INDEX job lifecycle.
- During RUNNING, the controller marks the target index as `BUILDING`; during PUBLIC, it marks the index as `READY`. Schema version advancement protects sessions from reading inconsistent metadata.
- Backfill exposes a recoverable progress-advance API that records lastCompletedKey and completedRows, allowing a rebuilt controller to resume from an existing job.
- This phase does not implement the real index KV backfill scanner and does not change h2db DDL syntax. Real backfill workers and failure compensation remain follow-up increments.
- The implementation touches `AdbOnlineDdlRuntimeController`, covered by `AdbOnlineDdlRuntimeControllerTest`.

### ADB-Runtime-11 Implementation Scope

`ADB-Runtime-11` has wired the production operations and security loop to the minimal verifiable ADB runtime boundary:

- Provided an ADB runtime operations bridge that emits `ClusterOperationsSnapshot`, system table rows, and metrics from the control-plane route snapshot.
- Provided a backup/restore drill bridge that reuses `BackupRestorePlan` and `DbStore.checkpoint(...)` / `restore(...)` to run local full backup/restore drills.
- Provided distributed runtime options. Distributed mode is disabled by default; explicitly enabling it requires TLS and least-privilege flags to be enabled together, preventing test settings from silently becoming production settings.
- This phase does not implement real multi-node deployment scripts, certificate issuance, a privilege system, or a rolling-upgrade executor. Those are production release-engineering work, but the runtime facade can host later integrations.
- The implementation touches `AdbDistributedRuntimeOptions` and `AdbRuntimeOperationsBridge`, covered by `AdbRuntimeOperationsBridgeTest`.

## Remaining Production Work After This Roadmap

The current phases 1-11 have landed the key runtime boundaries required by a TiDB-like distributed database, with code and tests. Production readiness still requires follow-up engineering and verification:

- Replace region scan/commit clients with real Raft/RPC clients and validate them with multi-process, multi-node smoke tests.
- Add real MVCC lock columns, lock resolve workers, idempotent recovery, and GC safe points to the storage format.
- Wire h2db optimizer rules, `EXPLAIN DISTRIBUTED` SQL syntax, statistics, and cost selection into the real SQL path.
- Wire Online DDL backfill workers to real index KV backfill, failure compensation, and long-running task scheduling.
- Complete certificate issuance, the privilege system, rolling-upgrade executor, backup media integration, and long-running stress tests.

## Post-Runtime Production Phases

After the current phases 1-11, production work continues through the following phases. As of 2026-06-13, by phase acceptance status, there are 6 production phases in total: 0 completed, 2 in progress, and 4 not started. Therefore, if the question is "how many phases still need to reach acceptance", 6 phases remain. If only not-started phases are counted, 4 phases remain. `ADB-Prod-01` and `ADB-Prod-02` have completed several sub-deliverables, but they have not yet met phase acceptance.

The plan continues to track these 6 production phases: first finish the OS-level multi-process multi-node smoke work in `ADB-Prod-01` and the cluster-level lock/GC loop in `ADB-Prod-02`, then move through `ADB-Prod-03` to `ADB-Prod-06`. Each completed phase still requires a local commit.

| Counting Scope | Count | Notes |
| --- | --- | --- |
| Completed production phases | 0 | No production phase has reached full acceptance yet. |
| In-progress production phases | 2 | `ADB-Prod-01` has completed real RaftServer/GRPC JUnit smoke, but still lacks OS-level multi-process multi-node smoke. `ADB-Prod-02` has progressed durable locks, batch resolve, secondary roll-forward, the background lock resolve worker, the primary-status lookup boundary, the primary-status read path, the control-plane-routed primary-status reader, the RClient registry refresher, the committed-version GC cleaner, the background committed-version GC worker, and the cluster-level GC sharding boundary, but still lacks real deployment-level connection factory integration, a region-scoped cleaner, global safe-point advancement, and acceptance loops for long transactions and partial commits. |
| Not-started production phases | 4 | `ADB-Prod-03` through `ADB-Prod-06` have not started. |
| Production phases still to finish | 6 | Includes the in-progress `ADB-Prod-01`, `ADB-Prod-02`, plus 4 not-started phases. |

| Order | Phase | Status | Goal | Main Deliverables | Acceptance |
| --- | --- | --- | --- | --- | --- |
| 1 | ADB-Prod-01 | In progress | Region Raft/RPC client integration | commit/scan transports, request/response models, timeout and error mapping | The 2PC coordinator can use a replaceable RPC client, with failure and timeout tests passing |
| 2 | ADB-Prod-02 | In progress | Real MVCC lock resolve and GC | lock columns, primary/secondary resolve, safe point, committed-version GC cleaner, background committed-version GC worker | Partial commit, lock expiration, long-transaction GC protection, and cluster-level background cleanup tests pass |
| 3 | ADB-Prod-03 | Not started | Real SQL path integration | h2db optimizer adapter, `EXPLAIN DISTRIBUTED` SQL, statistics | JDBC SQL can produce and execute distributed plans |
| 4 | ADB-Prod-04 | Not started | Online DDL backfill worker | index KV backfill, resumable progress, failure compensation | add index can recover and eventually become READY |
| 5 | ADB-Prod-05 | Not started | Multi-node deployment and security | startup scripts, TLS/privileges, system tables, rolling upgrade | Multi-process smoke, backup/restore drill, and rolling-upgrade drill pass |
| 6 | ADB-Prod-06 | Not started | Long-running and fault injection | network partition, leader transfer, disk faults, stress report | Long-running and fault-injection reports meet release criteria |

### ADB-Prod-01 Current Progress

`ADB-Prod-01` has completed the first real integration boundary for the region commit RPC client and the region scan RPC transport:

- `AdbRpcRegionCommitClient` maps 2PC prewrite/commit/rollback phases to a replaceable `AdbRegionCommitTransport` and consistently handles failed responses, transport exceptions, and client-side timeouts.
- `AdbRaftRegionCommitTransport` now uses the existing `RClient` / `RaftRClient` write path: `PREWRITE` maps to the ADB proto `Prewrite`, `COMMIT` maps to `Commit`, and `ROLLBACK` maps to `Rollback`.
- `AdbSMPlugin` now handles `Rollback` write requests, so `RaftStore.rollbackAsync(...)` no longer becomes a no-op at the state machine.
- `AdbRaftRegionScanClient` now reads region key ranges through the existing `RClient` / `ReadRequest.RegionScan` path, covering pagination, count-only results, and failed-response mapping to `SQLException`; raw `Scan` remains as a low-level KV capability.
- `Prewrite` / `PrewriteMutation` proto support is now in place. The PREWRITE phase sends real prewrite requests, and `AdbSMPlugin` persists each mutation as existing ADB uncommitted `VersionKey` intents and `TxnRefKey` references.
- `RegionScan` / `RegionScanResult` proto support is now in place. `AdbRegionScanReader` performs minimal MVCC visibility merging in the region state machine, and `AdbRaftRegionScanClient` now sends the dedicated region scan request.
- `LocalRClient` now supports `Prewrite`, `Commit`, `Rollback`, `RegionScan`, and async methods. `AdbRegionRpcSmokeTest` covers the commit/scan RClient protocol loop.
- `AdbRealRaftRegionRpcSmokeTest` now starts 3 real `RaftServer` + GRPC nodes and uses `RaftRClient` to cover the multi-node Raft/RPC protocol path for prewrite, commit, and region scan.
- Multi-process multi-node Raft/RPC smoke tests are still follow-up work inside `ADB-Prod-01`.

This `ADB-Prod-01` prewrite increment uses this scope:

- Add a backward-compatible `Prewrite` oneof branch to the ADB proto, carrying txnId, startTs, primary lock metadata, TTL, and the mutation list for the current region.
- Make `AdbRaftRegionCommitTransport` send `Prewrite` for the PREWRITE phase instead of the previous empty batch.
- When the state machine receives `Prewrite`, reuse the existing ADB intent/ref disk semantics: write uncommitted `VersionKey` entries and `TxnRefKey` references, while `Commit` / `Rollback` continue to use the current `DbStore.commitAsync` / `rollbackAsync` paths.
- This increment only delivers real prewrite requests and durable intent writes. Lock timeout resolution, primary/secondary resolve, GC safe points, and background cleanup remain part of `ADB-Prod-02`.

This `ADB-Prod-01` region scan proto pushdown uses this scope:

- Add `RegionScan` / `RegionScanResult` to the ADB proto so the region state machine receives the read timestamp, limit, count-only flag, and key range directly.
- Perform minimal MVCC visibility merging inside the region state machine and return visible row payloads/counts instead of exposing raw version KVs to the client.
- Make `AdbRaftRegionScanClient` send the dedicated `RegionScan` request while keeping raw `Scan` as a low-level KV capability and rollback path.
- This increment does not introduce full filter/projection proto support. Complex SQL pushdown and cost-based selection remain part of `ADB-Prod-03`.

This `ADB-Prod-01` smoke baseline uses this scope:

- Extend `LocalRClient` to support `Prewrite`, `Commit`, `Rollback`, `RegionScan`, and async methods so single-process smoke tests use the same ADB proto as Raft/RPC.
- Add an ADB region RPC smoke test covering prewrite and commit through `AdbRpcRegionCommitClient` + `AdbRaftRegionCommitTransport`, followed by visible-row reads through `AdbRaftRegionScanClient`.
- This smoke baseline only proves the RClient protocol loop. Real multi-process multi-node startup, leader discovery, port allocation, log-directory isolation, and process cleanup still need follow-up scripted verification.

This `ADB-Prod-01` real RaftServer/GRPC smoke baseline uses this scope:

- Start 3 real `RaftServer` instances inside JUnit, each with an isolated GRPC port, storage directory, cache directory, and `AdbStateMachine`.
- Send ADB proto through the real GRPC Raft client path via `RaftRClient`, covering prewrite, commit, and region scan.
- This baseline verifies the multi-node Raft/RPC protocol chain and ADB state-machine integration. It is not the same as OS-level multi-process deployment acceptance; process launch scripts, log-directory isolation, port cleanup, and failed-process cleanup remain the final `ADB-Prod-01` follow-up.

### ADB-Prod-02 Current Progress

The first `ADB-Prod-02` increment has added runtime entry points for real lock resolve and GC safe points:

- `AdbTxnLock` adds an ADB-specific lock record. In addition to the key, primary key, startTs, region, and TTL carried by common `TxnLock`, it includes the txnId required for rollback.
- `AdbLockResolver` adds a lock resolver that initially resolves expired locks by calling the existing `DbStore.rollbackAsync(txnId)` path, cleaning durable intents and `TxnRefKey` entries.
- `AdbGcSafePointManager` adds a GC safe point manager that advances safe points monotonically and blocks advancement when active long transactions would make the new safe point unsafe for snapshot reads.
- `AdbLockResolverTest` and `AdbGcSafePointManagerTest` cover expired-lock rollback, unexpired-lock wait, monotonic safe-point advancement, long-transaction protection, and collectable-version checks.

This `ADB-Prod-02` durable lock record increment uses this scope:

- Add `PrewriteLock` to the ADB proto so PREWRITE requests explicitly carry txnId, lock key, primary key, startTs, regionId, and TTL.
- Add `TxnKeyType.LOCK` records in the TXN CF. The key is txnId + LOCK + cfId + logical key, and the value is the encoded `AdbTxnLock`, providing a scan entry point for later primary/secondary resolve and background workers.
- Make `AdbPrewriteApplicator` write lock records in the same write batch as durable intents and `TxnRefKey` entries, preserving prewrite atomicity.
- Make `LdbStore` and `RocksStore` delete lock records for the same txnId during commit/rollback, preventing finished transactions from leaving stale locks that a resolver could misread.
- This increment still does not start a background lock-resolve/GC worker and does not delete historical committed versions. Primary/secondary resolve and the GC worker remain follow-up work inside `ADB-Prod-02`.

This `ADB-Prod-02` lock scanning and batch resolve increment uses this scope:

- Add a store-agnostic TXN CF lock scanner that scans `TxnKeyType.LOCK` records and decodes them into `AdbTxnLock`, so background workers, diagnostic tools, and manual recovery commands can share the same entry point.
- Extend `AdbLockResolver` with a batch scanning entry point in addition to single-lock resolve. It checks TTL expiration and reuses `DbStore.rollbackAsync(txnId)` to clean the durable intent, `TxnRefKey`, and lock record.
- This increment still handles only the expired-lock rollback path. Secondary roll-forward when the primary lock has committed, cross-region primary lookups, and periodic background scheduling remain follow-up work.

This `ADB-Prod-02` primary-committed secondary roll-forward increment uses this scope:

- When `AdbLockResolver` handles an expired lock, it first checks whether the primary key has a committed version for the same txnId. If the primary has committed, it reuses `DbStore.commitAsync(txnId, primaryCommitTs, emptyMetas)` to roll forward remaining secondary intents in the current store.
- The batch resolve result now includes a roll-forward count, so background workers and manual recovery commands can distinguish rollback from roll-forward effects.
- This increment only handles cases where the primary committed version is visible from the resolver's current store. Cross-region primary lookups, primary-state caching, and periodic scheduling remain follow-up work.

This `ADB-Prod-02` background lock resolve worker increment uses this scope:

- Add a startable and closeable `AdbLockResolveWorker` that periodically calls `AdbLockResolver.resolveExpiredLocks(...)`, while keeping `resolveOnce()` as the test, diagnostic, and manual recovery entry point.
- The worker records the latest batch result and latest failure, so runtime operations bridges or later admin/system tables can expose the state.
- This increment only schedules expired locks resolvable from the current store. Cross-region primary lookups, GC deletion of historical versions, and cluster-level worker sharding remain follow-up work.

This `ADB-Prod-02` primary-status lookup boundary increment uses this scope:

- Add a pluggable `AdbPrimaryLockStatusReader`, and make `AdbLockResolver` query primary commit state through this interface.
- The default implementation still reads committed versions from the current store, preserving existing single-node and same-region behavior. Later cross-region/RPC lookup can replace this interface implementation.
- This increment does not reuse the current `RegionScan` visible-row result as primary status, because the current region scan proto does not return source txnId/commitTs. A dedicated primary-status RPC or extended fields remain follow-up work.

This `ADB-Prod-02` primary-status read path increment uses this scope:

- Add `PrimaryLockStatusRequest` / `PrimaryLockStatusResult` to the ADB read proto so the primary region can return committed/unknown and commitTs by txnId and primary logical key.
- `AdbSMPlugin` and `LocalRClient` share the same `AdbPrimaryLockStatusProto` adapter. The server-side decision still uses `LocalAdbPrimaryLockStatusReader`, so visible-row region scan semantics are not mixed with primary-status semantics.
- Add `AdbRaftPrimaryLockStatusReader`, mapping the resolver's `AdbPrimaryLockStatusReader` interface to the existing `RClient` read path. Later work only needs the control plane to select the `RClient` for the primary region.
- This increment does not implement control-plane routing, leader discovery, primary-status caching, or retry policy. Those remain part of the cluster-level primary lookup closure.

This `ADB-Prod-02` control-plane-routed primary-status reader increment uses this scope:

- Add `AdbRClientRegistry`, mapping replica/leader ids to `RClient` instances. The registry does not own client lifecycles; deployment code remains responsible for creating and closing real connections.
- Add `AdbRoutedPrimaryLockStatusReader`, which routes the primary logical key through the current `AdbControlPlaneSnapshot` / `RegionRouter`, reads the region leaderId, selects the matching `RClient` from the registry, and reuses `AdbRaftPrimaryLockStatusReader` to issue the primary-status read.
- If the primary key cannot be routed, the region has no leader, or the leader client is not registered, the reader returns `SQLException` so the resolver does not treat an uncertain state as unknown and roll back a secondary.
- This increment still does not implement deployment-level auto-registration, leader-change subscriptions, primary-status caching, retry/backoff, or old-leader forwarding. Those remain in `ADB-Prod-01` multi-process deployment and the `ADB-Prod-02` acceptance loop.

This `ADB-Prod-02` RClient registry refresher increment uses this scope:

- Add `AdbRClientFactory` and `AdbRClientRegistryRefresher`. Deployment code supplies a `replicaId -> RClient` factory, and the refresher reads the current region leaders from `AdbControlPlaneSnapshot` and registers them into `AdbRClientRegistry`.
- The refresher only manages leader ids it registered itself: new leaders call the factory to create or fetch clients, still-present leaders are retained, and old leaders no longer present in the current snapshot are removed from the registry.
- The refresher does not own client lifecycles and does not close old clients. Real connection pools, authentication, TLS, address discovery, and retry policy remain deployment responsibilities.
- This increment only connects control-plane snapshots to the primary-status reader registration loop. It does not implement multi-process startup scripts or real address service discovery.

This `ADB-Prod-02` committed-version GC increment uses this scope:

- Add a conservative committed-version GC cleaner that scans committed versions in the DEFAULT CF and deletes old historical versions before the GC safe point.
- The cleaner must keep the latest committed version for every logical key, even when that version is older than the safe point, so current visible data is not removed.
- This increment does not delete intents, lock records, or cross-region historical versions, and it does not add long-running scheduling yet. Cluster-level GC workers and region sharding remain follow-up work.

This `ADB-Prod-02` background committed-version GC worker increment uses this scope:

- Add a startable and closeable `AdbCommittedVersionGcWorker` that periodically calls `AdbCommittedVersionGcCleaner.cleanOnce(...)`, while keeping `cleanOnce()` as the test, diagnostic, and manual recovery entry point.
- The worker records the latest successful GC result and latest failure, so later runtime operations bridges, admin APIs, or system tables can expose the state.
- This increment only schedules committed-version GC for the current store. It does not handle cross-region sharding, global safe-point advancement, leader election, or worker leases. Cluster-level GC worker scheduling remains follow-up work.

This `ADB-Prod-02` cluster-level GC sharding increment uses this scope:

- Add `AdbClusterCommittedVersionGcScheduler`, which reads the current `AdbControlPlaneSnapshot`, creates one GC request per region, and passes `regionId`, `regionEpoch`, `leaderId`, `routeEpoch`, `KeyRange`, `safePoint`, `limit`, and `timeoutMillis` to an async `AdbRegionCommittedVersionGcClient`.
- The scheduler only handles sharded dispatch, no-leader skips, timeout/failure mapping, and result aggregation. Real transport, worker leases, leader fencing, connection pools, and authentication remain responsibilities of deployment code or later RPC clients.
- This increment does not change the local scan semantics of `AdbCommittedVersionGcCleaner` and does not yet implement true region-key-range-limited historical-version deletion. A region-scoped cleaner, global safe-point advancement, and multi-worker leases remain follow-up acceptance items.

## Rollback Strategy

- Every phase must keep the single-node ADB/H2 plugin mode as a rollback target.
- Do not change old data format before region metadata is versioned and migration tooling exists.
- If witness mode fails, roll back to `single` or explicit `shared-storage` mode.
- Gate distributed transactions with a feature flag before general rollout.

## Test Plan

- Unit tests: key encoding, region routing, TSO, 2PC state machine, DDL job state machine.
- Integration tests: single region, multi region, cross-node scan, leader transfer, snapshot install.
- Concurrency tests: transaction conflicts, lock cleanup, region split with concurrent reads/writes.
- Fault injection: node crash, network partition, witness loss, disk error, duplicate commit.
- Compatibility tests: old `jdbc:adb:*`, old data directory, h2db minor upgrades.
- Long-running tests: split/merge, GC, checkpoint, backup/restore loops.

## Risks

| Risk | Severity | Description | Mitigation |
| --- | --- | --- | --- |
| Distributed transaction complexity | P0 | 2PC, lock resolve, or GC bugs can break consistency | Start with minimal SI and add fault injection/model tests |
| Pure 2-node misconfiguration | P0 | Automatic failover without witness can split brain | Forbid automatic writes in this configuration |
| h2db planner mismatch | P1 | Local plan cannot represent region pushdown | Add a Vexra `DistributedPlan` layer |
| Region metadata corruption | P0 | Wrong routing can read/write wrong shard | Version, validate, replicate, and provide recovery tooling |
| Operational tooling lag | P1 | Production use is unsafe without observability and recovery | Build metrics/admin/check tools in each phase |

## Open Questions

- Should the control plane be a standalone process or reuse existing Vexra server roles?
- Should TSO live in the control plane or in a state machine plugin?
- Should the first transaction phase support only single-table transactions or cross-table transactions?
- The h2db plan to distributed plan boundary needs prototype validation.
- Does `vexra-ldb` need new plugin contracts for region snapshot, range split, and learner flows?
