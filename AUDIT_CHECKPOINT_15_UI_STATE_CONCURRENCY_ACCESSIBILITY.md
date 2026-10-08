# Aura Files audit checkpoint 15 — UI/state/concurrency/accessibility/performance

Date: 2026-09-13
Base entering stage: `ac633a6 audit 14C: harden reader memory and cancellation`

## Scope

Deep pass over main FileManager UI/ViewModel, Trash browser, backend workspace, Vault and secondary Activities for:

- rapid taps / duplicate launches;
- lifecycle and coroutine cancellation;
- stale async UI results after navigation;
- operation visibility while switching sections;
- selection/list identity and Compose keys;
- trash grid visual consistency;
- accessibility of actionable icon buttons;
- large-list rendering patterns and obvious UI-side memory hazards.

## Confirmed defects fixed

### 1. Duplicate/destructive operations could overlap

`FileManagerViewModel.runFileOperation()` and several custom operations had no common synchronous gate. A fast second tap could launch another operation before Compose recomposed, overwrite `operationJob`, and leave two mutations running.

Fix:

- added `rejectIfFileOperationActive()` using both `operationInProgress` and `operationJob?.isActive`;
- applied to delete, favorites/vault move, analysis, paste, share preparation, trash restore and the common file-operation path;
- removed the old behavior where starting Favorites could cancel another file mutation.

### 2. Cancellation was converted to ordinary failure in UI wrappers

Several `runCatching { withContext(...) }` paths caught `CancellationException`. This could let destroyed/cancelled screens continue into stale success/failure state updates.

Fix:

- common cancellation-aware result helper in `FileManagerViewModel`;
- explicit cancellation propagation in Archive Browser, Installed Apps, Similar Photos, Vault, APK inspector and APKS installer;
- legacy FTP/SMB UI operation wrappers now distinguish cancellation from failure;
- analysis has an explicit cancellation branch which clears `analyzing/operationInProgress` and preserves the previous completed index.

### 3. Operation status disappeared after switching bottom sections

A long local operation lives in `FileManagerViewModel`, but the transfer/status overlay was only shown inside BrowserScreen. Switching to another bottom section hid all progress even though the job continued.

Fix:

- a top-level status overlay is now rendered when an operation is active outside BrowserScreen;
- BrowserScreen retains its existing overlay, so no duplicate overlay is shown.

### 4. Trash grid thumbnails did not fill the tile

The Trash grid used the regular small `FileIcon(showThumbnail=true)` inside padded content, producing the previously reported image-within-a-large-empty-rectangle layout.

Fix:

- grid tile now has a dedicated 128dp full-width preview area;
- image/video previews use `FileThumbnail(... fillMaxSize())` / `ContentScale.Crop`;
- directories/non-previewable files keep the normal icon fallback;
- selection checkbox remains overlaid in the top-right preview area.

### 5. Trash nested-folder stale-result race

`LaunchedEffect(currentFolder)` used `runCatching`, swallowing cancellation. A slow listing for folder A could complete after navigation to B and overwrite loading/error/items for the new folder.

Fix:

- cancellation is rethrown;
- requested URI is captured;
- results and errors are committed only if the requested folder is still current.

### 6. Actionable backend selection buttons lacked accessibility labels

Two actual `IconButton` controls used `contentDescription = null` in backend list/grid selection mode.

Fix:

- dynamic descriptions now identify `Выбрать <name>` / `Снять выделение <name>`;
- non-selection tile menu uses `Действия с <name>`.

## Existing protections verified

- primary local folder refresh already cancels the previous load and checks requested URI;
- secondary-pane refresh has the same protection;
- backend workspace already has independent refresh jobs plus generation counters for both panes;
- main local, trash, archive and backend lists use stable URI/path/ID-derived Compose keys;
- primary selection is cleared when folder/collection identity changes;
- large visible lists use LazyColumn/LazyVerticalGrid rather than eagerly composing every row.

## Verification

- `git diff --check`: PASS.
- delimiter/static syntax balance for all modified Kotlin files: PASS.
- stage-specific source invariant harness: `STAGE15_INVARIANTS_PASS`.
- no new TODO/FIXME/debug print statements found in the diff.

Full Android/Gradle compilation is still not claimed here: this container does not currently have the Gradle wrapper distribution cached and cannot resolve `services.gradle.org`. Final build validation remains stage 17 / clean Windows builder.

## Next

Stage 16: cross-feature destructive safety and process-kill/storage-failure reconciliation, including the known batch-rename final-state/index crash window.
