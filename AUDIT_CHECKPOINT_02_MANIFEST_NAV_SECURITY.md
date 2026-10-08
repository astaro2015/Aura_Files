# Audit checkpoint 02 — manifest/navigation/security surface

## Completed
- Reviewed AndroidManifest permissions, exported components, FileProvider, backup/data-transfer rules.
- `SplitPackageInstallerActivity` is the only non-launcher exported Activity; it accepts VIEW/SEND for Aura `.apks` and generic ZIP/octet-stream only when the content path matches `.apks`. Installer still validates container structure/checksums/package before PackageInstaller.
- FileProvider is non-exported and grantUriPermissions=true. Paths include external root, but no caller can enumerate it; Aura must explicitly grant each URI.
- Cloud/network credential SharedPreferences are excluded from cloud backup/device transfer.
- Full-storage tile `Все` is a real root browser path (`attachFullRoot`) and bypasses category analysis. `.AuraVault`/`.AuraTrash` are filtered from ordinary local browsing.
- SMB saved-share path launches the standard BackendWorkspaceActivity; Network page no longer needs to render SMB files.

## Important operational risk found
Vault key derivation uses `Settings.Secure.ANDROID_ID`. On Android 8+ this ID is scoped to app signing key + user + device. Therefore automatic vault recovery after reinstall is only guaranteed if Aura is signed with the SAME signing key. The Windows builder reuses `%USERPROFILE%\.android\debug.keystore` only on the same Windows profile. Losing/changing that keystore can make an existing vault unreadable after uninstall/reinstall.

This is not a code crash, but it is a high-impact data-recovery constraint and must be preserved/documented or replaced by a different device-binding design before calling vault recovery unconditional.

## Next
- I/O/memory/concurrency scan.
- File operation rollback and path-safety review.
- Cloud/network backend semantics.
