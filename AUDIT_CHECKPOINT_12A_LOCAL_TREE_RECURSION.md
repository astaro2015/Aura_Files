# Audit checkpoint 12A — local tree recursion / cycles / self-copy

State: completed and fixed.

Focus:
- recursive local/SAF traversal;
- filesystem symlinks in Full Storage mode;
- cycles/aliases from document providers;
- pathological depth / StackOverflow risk;
- copying/moving a folder into itself or a descendant;
- copying an object into its own parent with REPLACE semantics;
- ZIP creation following a filesystem link outside the selected tree.

Findings and fixes:
1. TransferEngine local COPY/MOVE had no engine-level self/descendant guard. UI guarded the common two-pane path, but another caller/stale state could still reach the engine. A directory copied into its descendant could recursively copy the newly-created tree. Fixed with canonical/direct path preflight plus destination-URI detection during source measurement.
2. MOVE to the same parent could collide with the source object itself. REPLACE could therefore target the source. Fixed: same-parent MOVE is rejected; COPY to the same parent is forced to a safe unique name instead of ever replacing the source.
3. Local recursive measurement/copy/skip had unbounded recursion. Added depth ceiling (256), directory visit keys and cycle detection. Local filesystem directory symlinks are refused for recursive transfer.
4. FolderSizeCalculator was recursive without cycle/depth protection. Converted to iterative traversal with visited-directory protection and symlink non-traversal.
5. StorageIndexer was recursive without cycle/depth protection. Converted to an iterative stack, with visited-directory protection, symlink non-traversal, depth ceiling, and overflow-checked byte/file counters.
6. DirectoryComparator now refuses to recurse into StorageItem.isLink and enforces cycle/depth protection.
7. Legacy FileRepository.analyze now uses bounded iterative traversal and does not follow local symlinks.
8. FileRepository.copy gets a self/descendant preflight; copyDocument rejects local symlinks and detects repeated/cyclic directories.
9. ZIP creation no longer follows local filesystem symlinks, and recursive ZIP construction has depth/cycle protection.

Validation:
- `git diff --check`: PASS.
- Kotlin parser smoke check for all six modified/new Kotlin files: PASS (no parser/illegal-escape errors).
- Full Android/Gradle compilation is deferred to final stage / Windows builder because this environment cannot resolve services.gradle.org.

Next checkpoint: trash/restore/delete transactional safety and stale SAF/provider states.
