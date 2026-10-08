# Audit checkpoint 09 — UI/state/collection behavior

## Reviewed
- Home `Все` tile bypasses analysis and opens the real full-storage browser path.
- `Книги` remains a short label only; classification is unchanged.
- Image catalog uses Room paging (400/page) and category-specific count/query, so Camera volume no longer hides Screenshots.
- Non-image category collection loading is deliberately bounded to 5000 entries. When the full category count is larger, UI explicitly displays `Показано N из M файлов в категории`; this is a known scalability limit, not silent data disappearance. `Все` remains available for unrestricted browsing.
- Folder refresh jobs are request-URI guarded and ignore cancellation failures.
- ExoPlayer preview has DisposableEffect cleanup; image/PDF decoding has pixel/dimension bounds and OOM fallbacks.

## Finding fixed
MEDIUM UX/STATE: multi-file move to encrypted `Избранное` is sequential and intentionally non-transactional. If files 1..N moved successfully and a later file failed, the prior UI showed only the final error, making it easy to think nothing had moved even though earlier source files had disappeared into the vault.

Fix: failure state now reports `В Избранное перемещено X из Y` plus the failing error whenever a partial move occurred. Vault/index state is still refreshed from actual results.

## Known limit
General non-image category pages still stop at 5000 and show an explicit truncation message. A future generalized paged collection UI would remove this limit without loading tens of thousands of FileEntry objects into Compose at once.
