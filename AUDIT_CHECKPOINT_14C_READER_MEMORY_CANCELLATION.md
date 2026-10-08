# AUDIT CHECKPOINT 14C — reader memory / cancellation / WebView isolation

Date: 2026-09-13
Base: 14B (`cf753b0`)

## Scope

Deep pass over Chitets/Aura reader paths: DjVu, EPUB/ZIP-based books, PDF text/render modes, CBZ/CBR comics, inline reader images, WebView file-origin policy.

## Findings fixed

1. DjVu source loading allowed up to 512 MiB into Java heap. The decoder needs the complete source plus page/decode buffers, so that ceiling could OOM devices whose heap is smaller than the source allowance.
   - Added `ReaderIoPolicy.safeDjvuSourceBytes()` based on runtime heap/headroom with a 128 MiB hard ceiling.
   - Applied to both native DjVu viewer and DjVu text parser.

2. Reader `shutdownNow()` did not make multiple blocking copy/read loops cooperatively cancellable.
   - Added interruption checks to DjVu, generic book/ZIP reads, CBZ/CBR copy/extract loops, PDF reflow source reads, and inline reader image reads.
   - UI callbacks now avoid presenting fatal dialogs after Activity destruction where touched by this pass.

3. EPUB/book private cache was written directly to the final cache file.
   - Cache now writes to `.part`, checks cancellation/size, `FileDescriptor.sync()`s, and only then renames to the final cache name.
   - Partial non-empty cache cannot be reused as a complete book after interruption.

4. `ReaderActivity` enabled `setAllowFileAccessFromFileURLs(true)`.
   - Ordinary local file resource access remains enabled for EPUB images/styles.
   - JavaScript file-origin access to sibling file URLs is explicitly disabled; universal file-origin access remains disabled.

5. PDF render / comic UI stale-result handling was tightened.
   - PDF worker checks interruption around render steps and treats render OOM as a failed render rather than an uncaught worker error.
   - Comic callbacks ignore destroyed Activities; extraction/copy is interruption-aware.

## Verification

- `git diff --check`: PASS.
- Lightweight lexical brace check over all 11 touched/new Java files: `JAVA_BRACE_CHECK_PASS 11`.
- Standalone `javac` for `ReaderIoPolicy.java`: `READER_POLICY_JAVAC_PASS`.
- Search confirms no remaining `setAllowFileAccessFromFileURLs(true)` in reader package and no old 512 MiB DjVu source constant.
- Full Android Gradle build is not claimed in this environment; wrapper distribution is not locally available and external Gradle resolution is unavailable here.

## Residual / deferred

- `PdfRenderer.Page.render()` itself is platform blocking work; interruption can be observed before/after the render but cannot force-stop the native render call. Stale UI results are generation-guarded.
- Cross-feature process-kill/index reconciliation remains stage 16 work.
