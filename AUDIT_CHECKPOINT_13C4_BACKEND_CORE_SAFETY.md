# Aura Files audit checkpoint 13C4 — backend core safety

Date: 2026-09-13
Base: commit 640c3d3 (`audit 13C3: harden legacy FTP and close SMB UI route`)

## Scope

Protocol-neutral `BackendTransferCore`: source/destination collisions, same-backend vs cross-backend move behaviour, temporary-object collisions, non-replace finalization, committed-backup cleanup, cancellation checkpoints, and user-visible distinction between true failure and unknown remote state.

## Findings fixed

1. **Blind retry of committed backup delete.**
   - After a successful Replace transaction, cleanup of `.aura-backup-*` retried delete up to three times after any failure.
   - A lost ACK could mean the first delete already committed; replay was unnecessary and could target a replacement object created under the same path.
   - Cleanup now sends delete once, then probes:
     - backup absent => delete was committed;
     - backup present => keep it and warn;
     - probe unavailable => keep state untouched and warn that state is unknown.

2. **Non-Replace `temp -> final` did not reconcile lost ACK.**
   - File and directory copy paths previously called `rename` directly.
   - If rename committed but the response was lost, copy failed even though final data existed; outer cleanup could then act on the old temp path.
   - Non-Replace finalization now probes source temp and final target exactly like the hardened Replace path.
   - Proven committed rename becomes success + warning; ambiguous rename preserves the completed temp.

3. **Destination could appear during a long copy.**
   - Conflict policy is resolved before byte transfer. Another actor can create the final name before finalization.
   - Aura now rechecks the final path immediately before non-Replace finalize.
   - If a target appeared, Aura does not overwrite it and preserves the completed temp copy.

4. **Random service-object names were not collision-probed.**
   - `.aura-part-*`, `.aura-dir-*`, and `.aura-backup-*` now use a shared allocator that probes `stat()` before use and retries with a fresh UUID.
   - The allocator is bounded to 32 attempts.

5. **Unknown DELETE state was not explicit.**
   - Top-level backend DELETE already probed after an error, but if that probe itself failed the original provider error was rethrown.
   - The user now gets an explicit message that delete state is unknown and that Aura did not replay the destructive operation.

6. **Cancellation checkpoint before final commit.**
   - File and directory copy now perform a controller checkpoint after data has been copied and before final rename/swap.
   - This prevents a queued user cancel from being ignored unnecessarily at the finalization boundary.

## Existing safety confirmed

- Same-backend MOVE into the same parent is rejected.
- Directory copy/move into itself or a descendant is rejected.
- Same-backend fast MOVE reconciles lost ACK and never falls through to copy+delete on ambiguous state.
- Cross-backend MOVE never deletes the completed destination because source deletion is uncertain; source is left in place and surfaced as a warning.
- Link-like objects are not recursively copied as normal directories.
- Recursion depth is bounded at 256.
- `CancellationException` is rethrown through mutation/finalization paths.
- Transfer warnings are surfaced by `BackendWorkspaceViewModel` for normal copy/move/delete and sync flows.

## Executed verification

Compiled JVM harness used the **actual production `BackendTransferCore.kt`** with an in-memory fake `StorageBackend`.

Result:

`BACKEND_13C4_KOTLIN_HARNESS_PASS`

Scenarios executed:
1. non-Replace final rename committed + ACK lost => final exists, operation succeeds with warning;
2. backup delete committed + ACK lost => recognized as committed, exactly one delete call;
3. backup delete not applied => backup retained, warning emitted, exactly one delete call;
4. destination appears between copy and finalization => external target preserved and completed temp retained;
5. DELETE fails and post-failure stat is unavailable => explicit unknown-state error, no replay;
6. first random `.aura-part-*` candidate collides => allocator selects a fresh temp without overwrite.

Additional checks:
- `BACKEND_13C4_FAULT_MODEL_PASS`
- `BACKEND_13C4_STATIC_PASS`
- `git diff --check` PASS.

Full Android Gradle build is still not claimed in this environment because wrapper/dependency resolution requires external Gradle access.

## Residual / architectural limit

Two separately configured backend IDs can theoretically point at the same underlying remote storage. The current `StorageBackend` contract has no stable cross-instance storage identity/fingerprint, so a same-storage alias can look like a cross-backend transfer. The depth/link guards still bound damage, but perfect alias detection requires a future backend identity capability rather than guessing from title/host strings.

## Next

Recover the missing 12C durable batch-rename crash journal exactly as called out by the handoff: persist PREPARE/FINALIZE state before destructive steps, recover after restart, and fault-test swaps/cycles of 2–7 files with a crash after every individual rename step.
