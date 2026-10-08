# Audit checkpoint 11 — memory / lifecycle / cancellation / cache robustness

Date: 2026-09-12
Target: Aura Files 1.3.4 deep-audit working tree.

## Reviewed
- Raw Thread/Executor usage and lifecycle shutdown.
- `runCatching` around suspend work and lifecycle cancellation.
- Whole-file buffering / large private-cache staging paths.
- APK/APKS export/import loops and cache-space behavior.
- Archive browser background extraction/listing cancellation.

## Findings fixed
1. MEDIUM LIFECYCLE: `ApkInspectorActivity` used its own CoroutineScope and `runCatching` around suspend work. Cancellation on Activity destruction could be swallowed and UI failure handling could still run against a destroyed screen.
   - Switched to `lifecycleScope`.
   - Cancellation is rethrown explicitly.

2. MEDIUM/HIGH STORAGE/RESOURCE: APK inspection copied arbitrary input to private cache with no size ceiling or free-space reserve, and the copy/hash loops had no cooperative cancellation.
   - Added 8 GiB safety ceiling and 128 MiB cache reserve.
   - Copy and hash loops now call `ensureActive()`.

3. MEDIUM RESOURCE: APKS import/export was bounded for size/space but the long ZIP copy/extract/export loops themselves were not cooperatively cancellable.
   - `prepareForInstall`, `prepareFromFile`, `exportInstalledPackage`, and APK-part writer are now suspend/cancellable and checkpoint cancellation inside long loops.

4. MEDIUM STORAGE/DATA-INTEGRITY: sharing a normal installed APK used direct `copyTo(target, overwrite=true)` with no free-space preflight and could leave a partial final-looking APK after failure/cancellation.
   - Added 128 MiB reserve preflight.
   - Copy goes to a temporary file, verifies initial/final source size and copied byte count, then commits by rename/copy fallback.
   - Long copy is cancellable.

5. MEDIUM LIFECYCLE: Installed Apps split/APK share flows swallowed coroutine cancellation in `runCatching`.
   - Cancellation now propagates rather than turning into a toast/error result.

6. LOW/MEDIUM LIFECYCLE: Archive Browser list/open/extract paths swallowed lifecycle cancellation and could attempt UI error reporting after screen disposal.
   - Cancellation now propagates in list, single-entry open, and extract-all paths.

## Static verification
- `git diff --check`: PASS.
- Kotlin parser checks on all modified files: no parser/illegal-escape errors.
- No new whole-file AES/APK buffer introduced; long operations remain streaming.

## Remaining to audit
- Deep local filesystem safety: symlink/junction loops, recursive stack depth, trash/undo/rename rollback, stale SAF handles.
- Backend/network/cloud semantic consistency and two-panel behavior.
- Media/readers / external Intent handling.
- Cross-version regression + packaging/build-layer audit.
