# AUDIT CHECKPOINT 16D — Vault restore crash safety

Дата: 2026-09-14

## Scope
Cross-feature destructive safety for restoring encrypted `.AuraVault` entries back to local/SAF storage.

## Fixed
- Restore no longer decrypts directly into the visible final filename.
- Plaintext is first written to a unique `.aura-vault-restore-*` staging object.
- Added durable `VaultRestoreJournalStore` under `noBackupFilesDir` using `AtomicFile` + `fd.sync()`.
- Journal phases: `WRITING`, `FINALIZE_PENDING`, `FINALIZED`.
- SAF restore requires a persistable write grant before external plaintext is created; grants acquired solely for the transaction are released after proven completion/recovery.
- Rename finalization reconciles lost ACK by strict parent listing and identity checks.
- Partial staging cleanup only occurs when the encrypted source is still proven present.
- Contradictory/ambiguous states preserve data and journal instead of guessing/deleting.
- Recovery never clears a journal if source/temp/final are all unproven.
- `temp + final` contradictory states preserve both copies and journal.
- Vault UI reports recovery failure instead of crashing the Compose screen.
- After ambiguous Move-to-Vault failure, FileManager clears stale analysis/index-derived state and refreshes the physical folder.

## Verification
- `audit_harness/stage16d_vault_restore_model.py`: **15/15 PASS**.
- Source invariants: PASS.
- `VaultRestoreJournalStore.kt` Kotlin stub compile: PASS.
- Modified Kotlin lexical-balance smoke: PASS.
- `git diff --check`: PASS.

## Build limitation
A full Android/Gradle build is not claimed here; release build verification is deferred to stage 17 because this container does not currently have a usable Gradle distribution/network path.
