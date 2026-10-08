# Audit checkpoint 05 — SPLIT/APKS security and storage hardening

## Confirmed baseline strengths
- Aura `.apks` validates a private manifest, part count, paths, declared sizes and SHA-256.
- APK package/version/signatures are checked early where Android can parse split APKs; PackageInstaller remains authoritative.
- Archive traversal names are rejected.
- Installation writes all parts to one PackageInstaller session.

## Findings fixed
1. HIGH SECURITY: exported `SplitPackageInstallerActivity` accepted internal-looking `ACTION_INSTALL_RESULT` from any explicit external Intent. An untrusted app could spoof install results and, most importantly, inject `Intent.EXTRA_INTENT` into a fake `STATUS_PENDING_USER_ACTION`, causing Aura to launch an attacker-supplied Intent as if it were a trusted PackageInstaller callback.
   Fix: every real PackageInstaller session now gets a cryptographically unguessable UUID nonce. Session id + nonce + trusted label/package/version are persisted in Aura-private SharedPreferences *before* commit. Incoming result intents are rejected unless session+nonce match the private record. Metadata is loaded from the private record, not trusted from external extras. Final status consumes the record; pending-user-action keeps it for the final callback.

2. MEDIUM/HIGH STORAGE DoS: incoming `.apks` was copied completely to private cache before any maximum-size validation. A giant/hostile content stream could fill device storage before ZIP validation.
   Fix: incoming copy is bounded (8 GiB payload ceiling + small container overhead) and maintains 128 MiB free-space reserve while spooling.

3. HIGH STORAGE ROBUSTNESS: valid `.apks` preparation keeps the original cached bundle while extracting another full copy of every APK part. Previously no free-space preflight existed before extraction.
   Fix: after manifest validation and before extraction, require enough current usable space for declared extracted APK bytes plus 128 MiB reserve.

4. ROBUSTNESS: incoming preparation now rethrows coroutine CancellationException instead of turning cancellation into an ordinary UI error.
