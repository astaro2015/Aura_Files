# AUDIT CHECKPOINT 16 — CROSS-FEATURE DESTRUCTIVE SAFETY COMPLETE

Date: 2026-09-14
State: completed and saved.

Stage 16 was split into four independently saved subpasses:

- **16A — batch rename / index reconciliation** (`4a75e0f`): durable post-filesystem marker closes the crash window between a committed batch rename and Room/analysis/index reconciliation.
- **16B — local transfer transactions** (`a08b390`): staged COPY/MOVE/REPLACE, strict identity reconciliation, self/descendant and link guards, safe SKIP, source replacement protection, cancellation safety.
- **16C — local REPLACE crash recovery** (`834c4db`): durable `AtomicFile` journal under `noBackupFilesDir`, fsync phases, startup recovery, conservative duplicate-preserving policy for ambiguous states.
- **16D — Vault restore crash safety** (`e7fe0a1`): staging plaintext, durable restore journal, persisted SAF access before external plaintext, lost-ACK reconciliation, safe restart recovery.

During stage 16 two earlier checkpoints were proven missing from the actual handoff source and were recovered as explicit commits instead of being silently trusted:

- **12A RECOVERY** (`9485c0b`) — recursion/depth/cycle/symlink/self-descendant guards.
- **12B RECOVERY** (`9f6548d`) — trash/restore lost-ACK safety, synchronous metadata/orphan handling, symlink protection, partial Empty Trash reconciliation.

Key safety invariant after stage 16:

> When the result of a destructive mutation is ambiguous, Aura preserves the possible source/destination/backup/temp and durable recovery evidence rather than guessing and deleting a possible last copy.

Validation is documented in each 16A–16D checkpoint. Full Android Gradle build remains a stage-17 release verification item.
