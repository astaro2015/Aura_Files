# AUDIT CHECKPOINT 16C — LOCAL REPLACE CRASH RECOVERY

Date: 2026-09-13
Base: 16B + recovered 12B

## Problem

16B made normal/lost-ACK local REPLACE transactional, but a process/power kill between `old -> .aura-backup-*` and `.aura-part-* -> final` could leave the bytes safe while the visible final name was absent. There was no durable restart state.

## Fix

1. Added `LocalReplaceJournalStore` under `context.noBackupFilesDir`.
2. Every REPLACE gets a unique per-transaction journal; multiple independent operations do not share one mutable record.
3. Journal writes use `AtomicFile`, explicit flush and `fd.sync()`.
4. Journal validates version, UUID-safe file name, parent URI and Aura-owned service-name prefixes before recovery.
5. Durable phases are persisted around destructive mutations:
   - `PREPARED`
   - `BACKUP_PENDING`
   - `BACKUP_DONE`
   - `FINAL_PENDING`
   - `FINAL_DONE`
   - `CLEANUP_PENDING`
   - `ROLLBACK_PENDING`
6. The complete journal exists before the first `old -> backup` rename.
7. A process-wide `LOCAL_REPLACE_TRANSACTION_LOCK` serializes recovery against active local REPLACE finalization even when multiple `TransferEngine` instances exist.
8. `TransferEngine.execute()` attempts recovery before any new local operation, so a pending journal cannot be bypassed by starting another transfer.
9. `FileManagerViewModel` also runs recovery during startup and clears stale analysis/index state when filesystem names were reconciled.

## Recovery policy

- visible final only -> transaction is complete; clear stale journal;
- final missing + backup present -> restore old backup to the final name; retain a complete new temp if one exists;
- final + backup, temp gone, durable `FINAL_DONE/CLEANUP_PENDING` -> new final was proven; safely finish backup cleanup;
- final + backup, temp gone, but final commit phase was not durably proven -> keep the old backup instead of guessing/deleting it;
- old final + new temp, no backup -> no destructive mutation can be proven; keep both and close the journal;
- contradictory/ambiguous combinations -> stop, delete nothing and retain the journal.

Thus restart recovery prefers a safe duplicate/service artifact over losing either complete version.

## Validation

- `audit_harness/stage16c_local_replace_crash_model.py`: `STAGE16C_LOCAL_REPLACE_CRASH_MODEL_PASS scenarios=17`.
- Production source invariants: `STAGE16C_SOURCE_INVARIANTS_PASS`.
- Current production `TransferEngine.kt` compiled in the existing Kotlin stub harness: `TRANSFER_ENGINE_STUB_COMPILE_PASS`.
- Actual `LocalReplaceJournalStore.kt` separately compiled against minimal `AtomicFile`/`JSONObject` API stubs: `LOCAL_REPLACE_STORE_STUB_COMPILE_PASS`.
- Parser-oriented smoke check on the modified ViewModel/store: `STAGE16C_PARSER_SMOKE_PASS`.
- `git diff --check`: PASS.

Full Android Gradle build remains deferred to stage 17 because this container cannot obtain the required Gradle distribution from `services.gradle.org`.
