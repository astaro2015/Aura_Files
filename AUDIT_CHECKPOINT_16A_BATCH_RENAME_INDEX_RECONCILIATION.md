# Audit checkpoint 16A — batch rename / index crash reconciliation

State: completed and saved.

Focus:
- crash after batch-rename filesystem commit but before Room/index URI reconciliation;
- durable handoff between the rename transaction and cache/index layer;
- recovery after process death without re-running destructive renames.

Findings and fixes:
1. Batch rename could reach a verified FINAL state, delete its durable rename journal, and then the process could die before `StorageIndexer.replaceEntries`. Files were safe, but Room/analysis/recent state could retain stale old URIs indefinitely.
2. Added `batch-rename-index-reconcile-v1.json` in `noBackupFilesDir` as a durable post-commit marker. It is fsync'd before the rename journal is cleared.
3. On startup, either a recovered rename or a pending reconcile marker discards stale analysis/index state. The marker is cleared only after that stale index has been safely updated/discarded.
4. Normal batch rename clears the marker only after incremental `replaceEntries` + analysis refresh succeeds. If incremental reconciliation itself fails, Aura discards the stale index/cache and only then acknowledges the marker; a successful filesystem rename is not misreported as a rollback.
5. Legacy URI-favorites journal state is persisted synchronously before the rename journal is removed. Current encrypted Vault does not use this legacy URI-favorites set, so this is compatibility hardening rather than a Vault fix.

Validation:
- `stage16_batch_index_crash_model.py`: `STAGE16_BATCH_INDEX_CRASH_PASS scenarios=7`.
- source invariants: `STAGE16_BATCH_INDEX_SOURCE_PASS`.
- `git diff --check`: PASS.

Next: Stage 16B local TransferEngine destructive safety — MOVE/SKIP, atomic REPLACE, lost provider ACK, strict recursive listing, source disappearance, recursion/cycle/link guards.
