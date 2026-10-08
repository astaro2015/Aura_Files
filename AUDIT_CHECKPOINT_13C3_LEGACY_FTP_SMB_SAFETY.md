# Aura Files audit checkpoint 13C3 — legacy FTP / SMB safety

Date: 2026-09-13
Base: commit a7a6af1 (`audit 13C2: harden Google Drive identity and mutations`)

## Scope

Legacy FTP/FTPS and SMB routes that could bypass the newer `StorageBackend` / `BackendTransferCore`: reconnect/retry behaviour, partial transfer handling, `.aura-part-*` safety, stale sessions, destructive mutation semantics, and current UI reachability.

## FTP findings fixed

1. **Destructive commands were replayed automatically after any `IOException`.**
   - `createDirectory`, `delete`, `rename`, and the complete upload transaction previously ran inside `withReconnect`.
   - A server could commit the mutation, lose the reply/ACK, and Aura would reconnect and send the same destructive command again.
   - Mutations are now single-shot. Reconnect is used only to inspect post-failure state.

2. **Lost-ACK reconciliation added.**
   - mkdir: target directory present => committed; absent => not applied; conflicting object => ambiguous.
   - delete: path absent => committed; still present => not applied. No automatic destructive retry.
   - rename: source absent + target present => committed; source present + target absent => not applied; other combinations => ambiguous.
   - ambiguous results fail explicitly and are never replayed automatically.

3. **Upload transaction hardened.**
   - Upload writes to a unique `.aura-part-<uuid>` object and only then finalizes with rename.
   - Temp names are probed for uniqueness; Aura no longer blindly deletes a randomly named temp before upload.
   - A rejected `storeFile` cleans only the temp known to belong to the current operation.
   - A transport failure during `storeFile` probes after reconnect; if a possible temp exists it is preserved and the outcome is reported as ambiguous.
   - Before final rename Aura rechecks that the final destination did not appear concurrently.
   - Lost ACK during `temp -> final` is reconciled using temp/final presence and payload metadata.
   - Ambiguous finalize keeps the safety temp instead of deleting it.

4. **Post-commit listing is read-only/retryable.**
   - After a proven mutation, refresh may reconnect/retry because LIST is non-destructive.
   - If refresh fails, the error explicitly says the mutation completed but the listing could not be refreshed.

5. **Cancellation classification preserved.**
   - `CancellationException` is rethrown and never converted into mutation reconciliation/retry.

## SMB reachability review

- Current product flow uses legacy `SmbRepository` only for authentication/share discovery.
- After a concrete share is selected, Compose opens `BackendWorkspaceActivity` and releases the legacy SMB session.
- The old inline SMB file UI (`SmbFileRow`) had no active call site, but dead upload/create/rename/delete callbacks and ActivityResult launchers were still wired through `FtpScreen`.
- Those dead UI routes were removed so future refactors cannot accidentally expose the legacy mutation surface from the Network screen.
- `SmbConnectionDialog`, share discovery, and SFTP connection UI remain intact.
- `SmbRepository` transfer/mutation helpers are retained because the shared transfer adapter still references them internally; they are not exposed by the current Network Compose route. Existing `withMutation` already avoids automatic destructive replay on transport loss.

## Transfer / temp behaviour review

- FTP upload/download stream data; there is no whole-file RAM buffering.
- FTP download may safely retry a read after reconnect because the local destination is reopened with truncate/write mode.
- FTP upload does **not** retry a partially/ambiguously committed write. A safety temp can remain intentionally after ambiguous failure.
- Old safety temps are not mass-deleted automatically: after an ambiguous mutation, preserving a possible good copy is safer than destructive cleanup.

## Verification

Fault model:
- `FTP_13C3_FAULT_MODEL_PASS`
  - committed + lost ACK for mkdir/delete/rename/finalize;
  - definitely-not-applied outcomes;
  - ambiguous source/target combinations;
  - payload mismatch during upload finalize.

Static checks:
- `FTP_13C3_NO_DESTRUCTIVE_REPLAY_STATIC_PASS`
- `FTP_SMB_13C3_STATIC_PASS`
- `git diff --check` PASS.

Full Android Gradle build is still not claimed in this environment because the wrapper/dependencies require external Gradle network access.

## Residual / deferred issue

Apache Commons Net uses blocking socket I/O. A cancelled coroutine cannot guarantee instantaneous interruption of an already-blocked FTP data operation; cancellation latency can extend to the configured data timeout (currently 90 s). This is bounded and does not introduce an automatic destructive fallback, but a fully cancellable transport would require a larger lifecycle/socket-abort refactor.

## Next

13C4: general backend-layer pass — source/destination identity collisions, same-backend vs cross-backend MOVE, temp-object collision/cleanup policy, cancellation propagation, and user-visible distinction between safe duplicate / unknown state / true failure.
