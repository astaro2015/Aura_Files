# Audit checkpoint 12A-RECOVERED — local tree recursion / cycles / links

State: recovered into the actual source tree and saved.

Reason for recovery:
- the handoff/checkpoint stated that 12A protections existed, but the Stage-16 audit found that several of those code changes were absent from the current repository;
- this checkpoint records the real restoration rather than trusting the historical note.

Restored/hardened:
1. Added shared `DocumentTreeSafety` with depth ceiling 256, stable local/SAF identity keys, filesystem symlink refusal and directory visited/cycle guards.
2. Added `FastDocumentListing.listStrict()` for recursive/safety-sensitive operations. A provider failure/null child query is not converted to an empty directory.
3. `FileRepository` recursive analysis is bounded/iterative, skips filesystem symlink traversal and uses strict listing.
4. Recursive `FileRepository.copyDocument` has source/destination preflight, self/descendant protection, cycle/depth protection and strict listing.
5. ZIP creation preflights destination vs selected trees and does not recurse through filesystem symlinks/cycles; recursive ZIP traversal is bounded and strict.
6. `StorageIndexer` recursive scan is iterative/bounded, cycle guarded, does not follow filesystem symlinks and fails closed on unavailable SAF trees.
7. `FolderSizeCalculator` is iterative/bounded, cycle guarded and does not follow filesystem symlinks.
8. `DirectoryComparator` uses bounded iterative traversal, refuses recursive descent into `StorageItem.isLink`, and detects normalized-path cycles.

Validation:
- `STAGE12A_RECOVERY_STATIC_PASS`.
- `git diff --check`: PASS.
- Full Android Gradle build remains deferred to Stage 17 because this container cannot resolve/download the Gradle distribution.

TransferEngine is intentionally excluded from this recovery commit because Stage 16B is applying stronger transactional MOVE/REPLACE/lost-ACK safeguards there and will validate them separately.
