# Aura Files 1.3.6 — Universal APK signing hotfix

Runtime symptom: universal APK fusion reached signing but apksig could fail with `Failed to sign` when the export private key was backed by AndroidKeyStore.

Fix:
- Aura APK Export no longer passes an AndroidKeyStore provider-backed PrivateKey into apksig.
- A dedicated 2048-bit RSA export identity is generated with standard JCA and stored only in `Context.noBackupFilesDir/apk-export-signing` as PKCS#8 private key + X.509 certificate.
- The export identity remains completely separate from Aura Files application signing and Vault keys.
- Existing export identity files are validated by a SHA256withRSA challenge before every use; partial/corrupt identity is never silently regenerated.
- apksig errors now preserve a short cause chain in the user-visible failure message.
- The key survives normal Aura upgrades, is excluded from Android backup, and is removed on uninstall.


## Follow-up runtime fix — legacy v1 signer path

A second device-side `SignatureException: Failed to sign` remained after moving the export key out of AndroidKeyStore.
The 1.3.6 design already specified v2+v3 only because Aura Files runs on API 26+, but the implementation accidentally forced v1/JAR signing on.

Fix:
- force apksig's signing floor to API 26;
- disable v1/JAR signing;
- keep v2+v3 enabled;
- verify signatures starting at API 26 as well;
- add audit guards so v1 cannot be silently re-enabled.

This removes apksig's legacy PKCS#7/JAR signing path from the Android runtime while preserving installability on every Android version supported by Aura Files.
