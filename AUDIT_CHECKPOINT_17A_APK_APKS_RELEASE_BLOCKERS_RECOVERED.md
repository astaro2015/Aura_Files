# AUDIT CHECKPOINT 17A — APK/APKS RELEASE BLOCKERS RECOVERED

Date: 2026-09-14
State: completed and saved.

## Why this recovery was necessary
Stage-17 consistency review found that critical fixes described in old checkpoints 05 / 05B / 11 were not present in the actual handoff source. They are restored here explicitly rather than trusting the old reports.

## Restored / fixed

### Exported `.apks` installer callback trust
- Every PackageInstaller session gets a cryptographically random UUID nonce.
- `sessionId + nonce + trusted label/package/version + timestamp` are synchronously persisted in private SharedPreferences before `session.commit()`.
- Incoming `ACTION_INSTALL_RESULT` is rejected unless both session id and nonce match the private record.
- Trusted metadata is loaded from private storage, not from caller-controlled intent extras.
- Final callback consumes the record; `STATUS_PENDING_USER_ACTION` keeps it for the final system callback.
- Callback preferences are excluded from cloud backup and device transfer.

### APKS import / extraction / export
- Incoming APKS spooling is bounded to the 8 GiB payload ceiling plus small container overhead.
- Private-cache operations preserve a 128 MiB free-space reserve.
- Extraction preflights declared total APK size before writing a second full copy into cache.
- Import, extraction, export and PackageInstaller write loops are cooperatively cancellable.
- Export preflights total base+split size and free space before creating the bundle.
- APKS export commits only by completed temp -> final rename; no partial final-looking fallback copy.
- Export detects source APK size changes during the copy.

### Normal APK inspection/share
- `ApkInspectorActivity` uses `lifecycleScope` instead of a manually-owned CoroutineScope.
- Inspector copy/hash loops are cancellable, enforce an 8 GiB ceiling and preserve a 128 MiB cache reserve.
- Installed-app APK share writes to a unique hidden temporary file, validates copied/source size, fsyncs, then renames to the final visible share file.
- Cancellation/failure removes the temporary file instead of leaving a partial final APK.

### Archive browser lifecycle
- Extract-all and single-entry-open paths rethrow `CancellationException` instead of converting Activity destruction into an ordinary UI error.

## Verification
- `audit_harness/stage17a_apk_apks_release_blockers.py`:
  - `STAGE17A_APK_APKS_SOURCE_INVARIANTS_PASS`
  - `STAGE17A_CALLBACK_SPOOF_MODEL_PASS scenarios=4`
- Production `AuraApksBundle.kt` compiled in an isolated Kotlin stub harness against the real coroutine library: `AURA_APKS_BUNDLE_STUB_COMPILE_PASS`.
- `git diff --check`: PASS.

A full Android Gradle build is still a later stage-17 verification item.
