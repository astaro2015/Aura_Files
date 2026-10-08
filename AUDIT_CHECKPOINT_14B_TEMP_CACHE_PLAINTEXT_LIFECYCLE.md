# Audit checkpoint 14B — temporary cache / Vault plaintext lifecycle

Date: 2026-09-13
Base: after checkpoint 14A.

## Scope
Temporary plaintext/cached copies used for Vault open/share, archive item preview, and remote backend open.

## Findings fixed
### Vault plaintext timestamp bug
`AuraVault.preparePlainFile()` previously copied the *original* file modification timestamp onto the decrypted cache file. Cleanup uses the cache file `lastModified()` as its staging age. Therefore an old photo/document could be considered older than the 2-hour plaintext TTL immediately and deleted by the next Vault refresh while a viewer still needed it.

Fix: staging files now retain a staging-time timestamp. The original modification timestamp remains only in encrypted metadata / `FileEntry` and is not reused for cache expiry.

### Vault free-space safety
Before decrypting a full Vault item to private cache, Aura now reserves 256 MiB and preflights the known plaintext size. A large video can no longer intentionally consume the filesystem down to zero free bytes before failure.

### Archive preview accumulation / disk exhaustion
`cache/archive-preview` previously had no expiry cleanup. Opened archive members could accumulate indefinitely. Single-item extraction also trusted only a large logical size ceiling and could consume nearly all free cache space.

Fixes:
- stale archive preview files are swept after 2 hours;
- a 256 MiB free-space reserve is enforced before preview extraction;
- known ZIP/TAR/7z/RAR member sizes are preflighted against available cache space;
- copy loops re-check remaining space while writing, so unknown/misreported sizes stop safely and the partial preview is removed by existing rollback.
- normal user-requested extraction to a selected destination is intentionally *not* subjected to the app-cache free-space guard.

### Remote backend open partial files / cancellation
`BackendWorkspaceViewModel.openOrPreview()` previously streamed directly to the final `backend-open` file via `InputStream.copyTo()`. Cancellation/network failure could leave a partial object in cache, and cancellation was not observed between chunks.

Fixes:
- stream to hidden `.part` first;
- check coroutine cancellation between chunks;
- maintain a 256 MiB free-space reserve and preflight a known remote size;
- rename `.part` to the final cache filename only after a complete copy;
- delete partial/final candidates on any failure;
- stale backend-open cache cleanup remains age based so a recent file granted to an external viewer is not eagerly deleted.

## FileProvider/grant review
- FileProvider is `exported=false` and `grantUriPermissions=true`.
- Vault/archive/backend cache directories are individually exposed through cache paths; access still requires an explicit URI grant.
- Existing ACTION_VIEW/ACTION_SEND paths use `FLAG_GRANT_READ_URI_PERMISSION`; generic multi-share also supplies ClipData.

## Verification
- `git diff --check`: PASS.
- Kotlin parser-oriented checks on all three modified files: no syntax diagnostics found (unresolved Android/Compose symbols are expected without the Android build classpath).
- Static invariants: `STAGE14B_STATIC_PASS`.
- Full Gradle build remains unavailable in this container because the Gradle 9.5.0 distribution cannot be resolved from `services.gradle.org`.

## Next
14C: reader/viewer cancellation and memory limits, especially whole-file DjVu, executor shutdown behavior, archive viewer cancellation, and WebView file-origin isolation.
