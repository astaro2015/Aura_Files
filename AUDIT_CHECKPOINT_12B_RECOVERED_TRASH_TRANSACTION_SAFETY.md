# AUDIT CHECKPOINT 12B RECOVERED — TRASH TRANSACTION SAFETY

Date: 2026-09-13
Status: recovered from the real source tree after the historical 12B checkpoint was found not to be present in production code.

## Why recovery was necessary

The repository still contained the old copy-then-delete pattern for trash and restore. If a provider committed the delete but returned `false`/threw/lost the response, Aura deleted the completed safety copy and could therefore lose the only surviving bytes. Trash metadata also still used asynchronous `SharedPreferences.apply()`, orphan physical trash objects were hidden, and Empty Trash updated metadata only after the entire pass.

## Recovered fixes

1. `moveToTrash()` now reconciles source deletion from a strict parent identity listing.
   - confirmed deleted -> keep trash copy and commit metadata;
   - confirmed still present -> rollback the new copy;
   - ambiguous -> keep the completed trash copy, synchronously persist its original mapping, and report the unknown state without another destructive fallback.
2. `restoreFromTrash()` uses the mirror policy. An ambiguous delete of the trash source keeps the restored copy and keeps trash metadata; it never removes the possible only surviving copy.
3. Single permanent delete and `FileRepository.delete()` use centralized delete-and-probe semantics instead of trusting provider booleans.
4. Empty Trash commits metadata after every physically confirmed deletion, so a later failure does not leave already-deleted rows stale.
5. Trash metadata uses synchronous `SharedPreferences.commit()` and reports persistence failure.
6. `listTrash()` uses strict listing, removes stale metadata, and surfaces physical `.AuraTrash` objects without metadata. Such crash-orphans get a conservative restore target of the attached root because the original parent cannot be reconstructed.
7. `.AuraTrash` and its attached root are rejected when they are local filesystem symlinks. A non-directory object named `.AuraTrash` is an error, not silently replaced.
8. Normal browser `listChildren()` rejects entering a local filesystem symlink directory.
9. Fast filesystem/SAF move is reconciled after the mutation. A lost ACK never triggers blind copy+delete fallback; if destination identity cannot be proven, Aura stops conservatively.
10. Safety-copy rollback itself is reconciled; if cleanup is uncertain, the duplicate is retained rather than risking loss.

## Validation

- `git diff --check`: PASS.
- `audit_harness/stage12b_trash_recovery_model.py`: `STAGE12B_RECOVERY_MODEL_PASS scenarios=50`.
- Production source invariants: `STAGE12B_RECOVERY_SOURCE_INVARIANTS_PASS`.
- Kotlin parser-oriented smoke check on `FileRepository.kt`: no parser/conflicting-declaration diagnostics were found. Android symbols are unresolved without the Android build classpath, so this is not claimed as a full build.

## Safety policy

When a destructive provider result is ambiguous, Aura retains the completed copy/metadata and stops. A duplicate is preferable to deleting the only surviving copy.
