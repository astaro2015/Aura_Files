# Aura Files audit checkpoint 12C-RECOVERED — durable batch rename journal

Date: 2026-09-13
Base: commit d2aa1c1 (`audit 13C: close network and cloud pass`)

## Why this recovery was required

The handoff explicitly warned that the previously reported 12C fix was not present in the actual repository. `FileRepository.batchRename()` still used in-memory two-phase temp names plus best-effort rollback, so a process kill between rename steps permanently lost transaction state.

This checkpoint restores 12C in the real tree rather than trusting the earlier status message.

## Implemented

### Durable state machine

Added `BatchRenameJournalEngine.kt`, a production Kotlin state machine with phases:

- `PREPARE`
- `FINALIZE`
- `ROLLBACK_STAGE`
- `ROLLBACK_RESTORE`

The journal stores:

- operation UUID;
- current phase;
- exact next step;
- original URI and parent URI/key;
- original, temporary, target and rollback names;
- original forward phase/step when rollback begins;
- legacy favorite URI snapshot for compatibility.

The engine synchronously persists both the pre-step and post-step state around every destructive rename. If the provider commits the rename but the process dies before the post-step write, restart recovery probes actual source/target names and resumes without blindly replaying the mutation.

### Crash-safe persistence

`FileRepository` stores the journal using Android `AtomicFile` under `context.noBackupFilesDir`.

- complete plan is persisted and fsync'd before the first rename;
- state is fsync'd before and after each destructive rename step;
- journal is not eligible for Android cloud backup/device transfer because it lives in `noBackupFilesDir`;
- journal is deleted only after the requested final state or the rollback final state has been proven from storage;
- malformed/corrupt journal data is rejected instead of being used for destructive recovery.

### Rename safety

- service names use unique `.aura-rename-<uuid>` and `.aura-rollback-<uuid>` names;
- service names are collision-probed and validated against original/target names;
- duplicate selected original names in one parent are rejected because name-based crash recovery would be ambiguous;
- duplicate/ambiguous provider entries discovered during recovery stop the transaction instead of guessing;
- rename exceptions/false results are reconciled by probing source/target state;
- committed + lost ACK is accepted without replay;
- source still present + target absent is treated as not committed;
- any other state is treated as ambiguous and the journal is preserved;
- `CancellationException` is rethrown by the engine and is never converted into a destructive retry/fallback;
- fatal `Throwable`s are no longer swallowed by a generic `runCatching` around the mutation.

### Rollback of swaps/cycles

A partially finalized cycle can have one selected file occupying another selected file's original name. Rollback therefore has its own two phases:

1. move every selected object to its unique rollback name;
2. only after all original names are free, restore every original name.

`rollbackSourcePhase` + `rollbackSourceStep` makes this deterministic even if the process dies immediately after a rename and before its post-step journal write.

### Restart recovery / UI cache

`FileManagerViewModel` checks for a pending journal on startup on `Dispatchers.IO`.

After recovery it clears analysis/category caches and clears the current root index so stale URIs from the killed process are not kept in normal UI state. A new batch rename also synchronously recovers any older journal first, protected by a process-wide lock.

## Executed verification

### Production Kotlin engine harness

The harness compiles and executes the **actual** production `BatchRenameJournalEngine.kt`.

Result:

`BATCH_RENAME_12C_PRODUCTION_ENGINE_PASS forward=54 rollback=54 lostAck=6 cancellation=1 boundaries=12 total=127`

Covered:

- cycles/swaps of 2..7 files;
- process death after every individual PREPARE/FINALIZE rename, specifically after the storage mutation but before the next durable journal write;
- process death after every individual rollback-stage / rollback-restore rename;
- PREPARE -> FINALIZE and ROLLBACK_STAGE -> ROLLBACK_RESTORE boundary recovery;
- provider commits rename but reports failure/lost ACK;
- cancellation propagation.

### Independent second model

A separate Python state-machine simulation intentionally does not reuse the production engine logic.

Result:

`BATCH_RENAME_12C_CRASH_MATRIX_PASS main=66 rollback=54 lost_ack_steps=6 boundaries_mixed=14 total=140`

This covers the same 2..7 cycle family from an independent implementation, including mixed/no-op names and rollback boundary states.

### Static/source checks

`BATCH_RENAME_12C_STATIC_PASS`

Verified:

- old best-effort `rollbackBatchRename()` path removed;
- journal uses `noBackupFilesDir` + `AtomicFile` + `fd.sync()`;
- full plan is durable before the production engine starts mutation;
- pre/post step persistence exists in the production engine;
- rollback source phase/step is retained;
- cancellation is rethrown;
- provider outcome is re-probed after failed/uncertain rename;
- journal structure is validated before recovery;
- startup recovery and index invalidation hooks exist;
- `git diff --check` PASS;
- Kotlin parser pass on changed `FileRepository.kt` found no syntax diagnostics.

## Build limitation

A full Android Gradle compilation still cannot be claimed in this container. The wrapper has no cached Gradle 9.5.0 distribution and attempts to reach `https://services.gradle.org/distributions/gradle-9.5.0-bin.zip`; DNS/network access fails with `UnknownHostException: services.gradle.org` even when invoked with `--offline`.

The production standalone journal engine itself **does compile with kotlinc** and is what the executable Kotlin harness tests.

## Residual for later stage 16

There is still a narrow **cross-layer metadata** crash window after `FileRepository` has proven the final filesystem state and deleted its journal but before `FileManagerViewModel` finishes updating the Room/index metadata. Files are not lost, but the index can be stale until rebuilt. This belongs to the planned stage 16 (`index/database after partial operation` / exact process-kill behaviour) and must be tested there rather than hidden as part of this checkpoint.

## Next

Stage 14 — media / preview / open / readers / external intents, including bitmap/video OOM, corrupt/huge media, EXIF orientation, PDF/text/book/archive viewers, FileProvider permissions, Vault plaintext cache lifecycle, cancellation and temp cleanup.
