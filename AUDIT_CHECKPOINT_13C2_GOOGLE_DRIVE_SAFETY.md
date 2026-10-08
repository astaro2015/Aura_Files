# Aura Files audit checkpoint 13C2 — Google Drive safety

Date: 2026-09-13
Base: commit d49255c (`audit 13C1: harden Yandex async mutations`)

## Scope

Google Drive backend identity/path semantics, duplicate names, shortcuts, trashed files, parent moves, lost ACK reconciliation, auth refresh and binary transfer behaviour.

## Findings fixed

1. **Stale ID path could address a file after it moved to another parent.**
   - Drive UI paths encode stable `fileId`, but old code resolved the ID without checking its current parent.
   - A stale path could therefore rename/delete a file that had already been moved elsewhere.
   - ID resolution now verifies `trashed == false` and expected parent membership.

2. **Trashed file IDs could still look live through direct `files.get`.**
   - `FILE_FIELDS` did not request `trashed`.
   - `trashed` is now parsed and filtered consistently.

3. **Drive root alias vs canonical root ID.**
   - Root is now resolved once through `files.get("root")` and its canonical ID is cached.
   - Parent membership checks therefore compare against the actual root folder ID, not an alias string.

4. **Ambiguous/lost response after destructive mutation.**
   - mkdir/rename/move/trash now distinguish definitely-not-committed failures from failures that may have committed.
   - On potentially committed network/server/bad-response failures Aura probes current Drive state.
   - Proven committed result is accepted.
   - Proven unchanged result returns the original provider failure.
   - Unknown state fails explicitly and is not retried destructively.
   - `CancellationException` is always rethrown without reconciliation/retry.

5. **Duplicate-name semantics remain safe.**
   - Duplicate provider names remain visible as separate ID-backed items.
   - Name-based resolution refuses ambiguous matches instead of silently selecting one.

6. **Shortcuts remain non-recursive link-like objects.**
   - Shortcut target is not traversed by normal file recursion and shortcut download is rejected as a regular file.

## Verified behaviour

Fault-injection harness: `GOOGLE_13C2_HARNESS_PASS`

Covered scenarios:
- stale parent path after external move;
- trashed ID becomes absent;
- duplicate names stay distinct by ID, name lookup is rejected as ambiguous;
- shortcut surfaced as link/non-directory;
- rename committed + response lost;
- rename not committed + network failure;
- move committed + response lost;
- trash committed + response lost;
- trash not committed + network failure;
- mkdir committed + response lost;
- cancellation does not trigger mutation probe/retry.

Static checks:
- `git diff --check` PASS.
- Current environment cannot complete the Android Gradle build because wrapper dependency resolution requires external Gradle network access; no claim of full Android build is made here.

## Binary transfer / auth review

- Known-size upload streams directly; no whole-file RAM buffering.
- Unknown-size upload spools to an app-private temp file; no whole-file RAM buffering.
- Download is streamed.
- API calls retry once only for explicit UNAUTHORIZED after token invalidation/refresh.
- Resumable session PUT is intentionally sent to the session URI without adding a second bearer header; this matches Drive resumable upload semantics.

## Residual / deferred issue

Unknown-size upload performs the final temp-file -> HTTP upload inside synchronous `StorageWriteHandle.commit()`.
`TransferController.cancel()` is checked during the preceding copy loop, but cannot inject a new checkpoint while that blocking commit is already running. Therefore user cancellation may be delayed until the HTTP operation completes or reaches its configured network timeout.

This is a cancellation-latency issue, not a known data-loss path. Fixing it cleanly requires changing the generic write-handle/transfer cancellation contract (or making commit cancellable), so it is intentionally deferred to the later transfer/lifecycle/concurrency pass instead of introducing an isolated backend-only API fork here.

## Next

13C3: legacy FTP/SMB reachability and retry/reconnect safety, stale sessions, partial transfer/temp cleanup, timeout/cancellation and destructive mutation semantics.
