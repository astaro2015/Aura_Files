# Audit checkpoint 10 — resumed audit / cumulative fixes preserved

Date: 2026-09-12
Target: Aura Files 1.3.4 SHORT LABELS, continued multi-stage audit.

## Continuity
The active source tree was reconstructed from the exact 1.3.4 SHORT_LABELS source ZIP and the already-recorded checkpoint fixes were reapplied before continuing deeper review. A local Git repository is now used for every subsequent checkpoint; `.git` will never be shipped in the release archive.

## Reapplied and rechecked cumulative fixes from checkpoints 03–09
- AVF2 counts actual streamed plaintext bytes and aborts before source deletion if a known positive source size changed/truncated.
- Vault sharing is lifecycle-bound coroutine work; cancellation is not converted to a UI error.
- APKS import is bounded, keeps a 128 MiB cache reserve, and preflights extraction space.
- APKS export preflights total APK size/cache space and verifies source APKs do not change mid-export.
- PackageInstaller callbacks are bound to a persisted per-session random nonce; externally spoofed callback intents are rejected before any supplied confirmation Intent can be launched.
- PackageInstaller callback state is excluded from Android backup/device-transfer.
- Same-backend fast MOVE rethrows coroutine cancellation instead of falling back to copy+delete.
- Partial move-to-vault failures explicitly report `X из Y`, so successful prior moves are visible to the user.

## Additional hardening added while reconstructing
- APKS export now verifies bytes read against the initial APK file length, preventing a changing source APK from producing inconsistent metadata/hash output.
- PackageInstaller preparation and commit coroutines explicitly preserve cancellation semantics.

## Save point
Local Git checkpoint created after this file. Continue from this commit if the next audit pass is interrupted.
