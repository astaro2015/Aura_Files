# Audit checkpoint 08 — Network/cloud credentials and transport review

## Confirmed
- Google Drive and Yandex API endpoints are HTTPS; returned upload/download/session URLs are checked for HTTPS in relevant paths.
- FTPS backend enables endpoint/hostname checking and protects the data channel with PROT P.
- SFTP requires an explicitly trusted host-key fingerprint; a blank fingerprint is not silently accepted. First-observed fingerprints surface through the trust flow.
- SMB explicit credentials do not silently fall back to guest on a bad password.
- Saved SMB/FTP/SFTP secrets and SFTP private keys/passphrases use AES-GCM keys from AndroidKeyStore via CredentialStore; profile metadata stores only secret IDs.
- OAuth tokens use a dedicated encrypted token store; cloud/token/network credential preferences are excluded from Android backup/device transfer.

## Changes
- Removed a duplicate `.put("tls", profile.tls)` serialization call in NetworkProfileRepository (harmless but misleading maintenance defect).
- Added private APKS PackageInstaller callback state (`split_install_callbacks.xml`) to backup/device-transfer exclusions. It should never be migrated to another device/process lifetime as if its PackageInstaller session still existed.

## Known/expected constraints
- Plain FTP is inherently unencrypted if the user chooses FTP rather than FTPS; this is protocol behavior, not Aura encryption failure.
- SFTP private-key authentication currently stages the key briefly in the app/JVM temp area because SSHJ loads from a file path; normal completion overwrites/deletes it. A hard process crash at exactly that point can leave a private app-cache temp file until cache cleanup. No cross-app access on normal Android sandboxing.
