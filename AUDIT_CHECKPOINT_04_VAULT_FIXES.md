# Audit checkpoint 04 — Vault integrity/lifecycle fixes applied

## Changes
- AVF2 encryption now counts the actual plaintext bytes streamed from the source.
- If the source had a known positive size and the encrypted byte count differs, the move aborts before commit/source deletion. Temporary vault output is removed by existing rollback logic.
- Vault share preparation no longer uses a raw `Thread`; it is lifecycle-bound via `lifecycleScope` and performs decrypt/cache work on `Dispatchers.IO`.
- Coroutine cancellation is explicitly rethrown rather than converted into a UI error.

## Why this matters
This closes a data-loss edge case where a changing/broken ContentProvider could return early EOF without throwing, resulting in an authenticated but incomplete vault file whose original could previously be deleted.

## Remaining documented constraint
Same-phone recovery after uninstall/reinstall depends on stable `ANDROID_ID`, which in turn depends on using the same app signing key/user/device. This must be documented prominently; it is not fixable by pretending ANDROID_ID is hardware-global.
