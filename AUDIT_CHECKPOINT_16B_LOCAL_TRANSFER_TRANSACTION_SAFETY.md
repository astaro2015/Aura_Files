# AUDIT CHECKPOINT 16B — LOCAL TRANSFER TRANSACTION SAFETY

Date: 2026-09-13
Base: 16A + recovered 12A
Scope: `TransferEngine.kt` local COPY/MOVE/DELETE/TRASH/RESTORE paths.

## Fixed

- COPY/MOVE folders cannot target themselves or descendants; the destination URI is checked during the actual traversal as well as preflight, closing a race where the destination is moved under the source while copying.
- Destination filesystem symlinks are rejected for local destructive/copy operations.
- Directory traversal uses recovered `DocumentTreeSafety` depth/cycle/symlink guards and strict child listing.
- COPY writes files to unique `.aura-part-*` objects and directories to `.aura-dir-*`; the final visible name is committed only after the temporary object is complete.
- REPLACE is staged as `old -> .aura-backup-*`, `temp -> final`, then backup cleanup. Rename/delete results are reconciled from strict parent listings after provider exceptions/false returns.
- Ambiguous REPLACE preserves safety temp/backup instead of deleting evidence/data.
- SKIP during MOVE no longer deletes the source; source deletion runs only after a destination copy actually committed.
- Fast same-provider MOVE is reconciled by document identity, not merely by name. Ambiguous state never falls back to copy+delete.
- Source fingerprints are captured before destructive work. Filesystem sources include `BasicFileAttributes.fileKey()` where available; SAF sources use stable identity plus name/type/size/mtime. If the original path is replaced mid-operation, Aura refuses to delete the replacement.
- DELETE and post-copy MOVE deletion reconcile provider lost-ACK outcomes by parent identity probing.
- Temporary cleanup deletes only the exact Aura-created object; a concurrently reused `.aura-*` name is never deleted by name alone.
- Service-name allocation is bounded.
- Cancellation is rethrown and never converted to destructive fallback.

## Validation

- `git diff --check`: PASS.
- Transaction/fault model: `audit_harness/stage16b_local_transfer_model.py` -> `STAGE16B_LOCAL_TRANSFER_MODEL_PASS scenarios=87`.
- Production-source invariants -> `STAGE16B_SOURCE_INVARIANTS_PASS`.
- `TransferEngine.kt` compiled in an isolated Kotlin stub harness with the production transfer models/controller/progress/conflict code and bundled coroutines -> `TRANSFER_ENGINE_KOTLIN_STUB_COMPILE_PASS`.
- Full Gradle Android build is still unavailable in this container because the required Gradle distribution is not cached and `services.gradle.org` is unreachable; the stub compile is not claimed as a full Android build.

## Residual for 16C

A process kill in the middle of the multi-step local REPLACE can leave the data safe but staged under `.aura-backup-*` / `.aura-part-*` with the final visible name temporarily absent. Durable restart recovery for this transaction remains a separate 16C item and must be solved before the cross-feature destructive-safety stage is closed.
