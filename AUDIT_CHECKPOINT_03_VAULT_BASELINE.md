# Audit checkpoint 03 — Vault deep audit (baseline findings)

Baseline: Aura Files 1.3.4 SHORT LABELS.

## Confirmed strengths
- AVF2 payload encryption is chunked (1 MiB) with independent AES-GCM IV/tag per record.
- Final authenticated zero-length record detects truncation.
- Chunk index and plaintext length are authenticated as AAD.
- Metadata and thumbnails are encrypted; temporary plaintext lives only in app-private cache.
- AVF1 remains readable for backwards compatibility.
- `.AuraVault` is filtered from normal browser/index and from local backend / FTP / SFTP paths.
- Move-to-vault logic prefers duplicate over data loss when source-delete result is ambiguous.

## Findings requiring action
1. HIGH DATA-INTEGRITY: `encryptEntry()` trusts `entry.size` for metadata but does not verify that the streamed source actually produced that many bytes before the encrypted copy is committed and the source may be deleted. A provider/source that changes or returns premature EOF without throwing can produce an authenticated but incomplete AVF2 container. Later decrypt validates the declared size and can reject it, after the source is gone.
   Planned fix: count plaintext bytes during AVF2 encryption and, whenever declared source size > 0, require exact equality before commit/delete. Add structural validation of the freshly written container before source deletion.

2. MEDIUM ROBUSTNESS: Vault sharing uses a raw `Thread`, not lifecycle-bound coroutine work. Activity destruction can leave work running and callbacks targeting a dead UI.
   Planned fix: use `lifecycleScope` + `Dispatchers.IO` and lifecycle-safe UI result handling.

3. HIGH OPERATIONAL/RECOVERY CONSTRAINT: vault key is derived from `ANDROID_ID`. On Android 8+ this value is scoped by app signing key + Android user + device. Reinstall recovery therefore depends on reinstalling an Aura APK signed with the same signing key. Losing/changing the debug/release keystore can make the vault unreadable after uninstall/reinstall even on the same physical phone.
   Action: document prominently in app/package; do not claim unconditional same-phone recovery. Consider later migration/recovery design if release signing changes are possible.

4. LEGACY COMPATIBILITY RISK: AVF1 decrypt uses one GCM operation for the entire payload. Very large legacy AVF1 files may still have provider/JCE memory pressure. New writes are AVF2 and avoid this. Keep as known compatibility risk unless a low-risk streaming legacy implementation is introduced.
