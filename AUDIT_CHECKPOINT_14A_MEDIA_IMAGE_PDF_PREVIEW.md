# Audit checkpoint 14A — image/PDF preview hardening

Date: 2026-09-13
Base: 1.3.4 audit tree after recovered 12C and completed 13C.

## Scope
First media/preview pass: image thumbnails, embedded image preview, Vault thumbnails, PDF preview failure handling.

## Findings fixed
- Grid/embedded image decoding sampled large sources safely, but unlike the fullscreen ImageViewer it ignored EXIF rotation/flip. A camera JPEG/HEIF could therefore be sideways in the file grid/preview and correct only after opening fullscreen.
- Vault thumbnail generation had the same EXIF mismatch, so newly encrypted image thumbnails could permanently store the wrong orientation.
- Embedded image/PDF preview collapsed load failure to `null`, which was also the loading sentinel. Corrupt/unsupported files therefore displayed an endless spinner instead of a failure state.

## Changes
- `FilePreview.decodeSampledImage()` applies AndroidX `ExifInterface` rotation and horizontal flip after bounded sampled decode and before final edge scaling.
- EXIF transform is fail-soft: unsupported metadata, provider errors, `OutOfMemoryError`, or bitmap transform failures keep the safely sampled decoded bitmap rather than crashing.
- `AuraVault.createThumbnail()` applies the same EXIF orientation before scaling/compressing newly-created encrypted thumbnails; bitmap ownership/recycling remains explicit.
- `BitmapPreview` and `PdfPreview` now retain `Result` state so loading and failure are distinct; corrupt/unsupported input shows a user-visible error rather than an infinite progress indicator.

## Existing protections confirmed
- Normal image thumbnails use bounds-first sampled decode; source dimensions/pixels and decoded pixels are capped.
- Video thumbnails use `MediaMetadataRetriever.getScaledFrameAtTime()` on API 27+ and release the retriever in `finally`.
- PDF rendering caps scale and rendered pixels and scopes descriptor/renderer/page with `use`.
- Fullscreen ImageViewer already applied EXIF rotation/flip and has its own decode ceilings.
- Thumbnail LRU caches are byte-bounded (ordinary 12 MiB; Vault 8 MiB); deliberately no eager recycle-on-eviction because Compose may still hold/render an evicted bitmap.

## Verification
- `git diff --check`: PASS.
- Static invariants script: `STAGE14A_STATIC_PASS`.
- Source-level ownership review of transformed/original bitmaps: PASS.
- Full Android Gradle compile is still unavailable in this container because the wrapper distribution is not present and `services.gradle.org` cannot be resolved. This checkpoint does not claim a full Android build.

## Next
14B: temporary plaintext/cache lifecycle — Vault `vault-open`, archive preview cache, external viewer/share grants and cleanup/space limits.
