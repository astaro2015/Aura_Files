# Audit checkpoint 07 — Archive safety / extraction review

## Reviewed
- Path traversal filtering (`..`, absolute paths, drive-like paths) via ArchiveSafety.
- TAR symbolic links/hardlinks/special entries are skipped during sequential extraction.
- ZIP Unix symlinks are skipped.
- Entry-count and extracted-byte ceilings exist.
- 7z/XZ memory limits exist.
- Temporary copies of non-file archives have compressed-size and cache-space guards.
- A failed whole-archive extraction recursively removes the newly-created destination root.
- A failed individual file extraction deletes the incomplete output.

## Findings / classification
- No direct Zip Slip/path traversal escape found in the reviewed extraction paths.
- The configured 64 GiB single-entry / 128 GiB total ceilings are security ceilings, not promises that a phone has that much storage. For direct extraction to an arbitrary SAF destination, generic free-space preflight is not reliable across all DocumentProviders. Write failures should still unwind and delete the newly-created extraction root.
- Temporary 7z processing has an explicit usable-space reserve before spooling to Aura cache.
- No code change made in this stage; current behavior is acceptable, with destination-provider ENOSPC remaining an expected runtime error rather than a hidden corruption path.
