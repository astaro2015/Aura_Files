# Universal APK Export Design

## Goal

For installed split applications, make the primary sharing action produce one installable `.apk` instead of an `.apks` transport bundle. Keep the existing Aura `.apks` export/install path as a fallback.

## User flow

- Non-split installed app: `Поделиться APK` keeps copying the installed APK unchanged.
- Split installed app: primary action becomes `Поделиться APK`; Aura fuses `base.apk` plus the splits installed on this device into one APK, signs the fused APK with a stable per-Aura-install export key, verifies it, then opens Android Share.
- Split installed app: secondary `APKS` action remains available as a fallback when fusion is incompatible or when the recipient needs the exact original split set.
- While fusion/signing is running, the row shows progress state `Готовим APK…` and disables duplicate exports.
- On fusion failure Aura reports the concrete reason and leaves the `.APKS` fallback available; it does not silently substitute an APKS file.

## Compatibility semantics

The fused APK is device-derived, not a mathematically universal package across all architectures/densities/languages. It contains exactly the base and installed splits available on the source device. The UI calls it a single APK (`Единый APK` internally) and does not promise support for ABIs/resources absent from the source device.

Google Play Asset Delivery / protected or unusual split layouts may still fail after fusion. Aura treats that as a supported failure mode and keeps `.apks` as fallback.

## Merge engine

Use the merge approach from REAndroid/APKEditor 1.4.9, adapted into Aura and attributed under Apache-2.0. Do not embed APKEditor's CLI, smali library, or JCommand.

Use `io.github.reandroid:ARSCLib:1.4.0` for `ApkBundle`, resource-table merge, binary manifest editing, APK writing, and alignment. Merge the installed source files directly; do not duplicate every split into a staging directory before merge.

After ARSCLib module merge:
- remove split-only manifest attributes (`requiredSplitTypes`, `splitTypes`, `isSplitRequired`),
- remove Play split/stamp metadata and its resource-table entry where applicable,
- remove stale signature material,
- refresh resource table and manifest,
- preserve `extractNativeLibs` behavior,
- write an unsigned fused APK.

## Signing

Use Android runtime port `com.github.MuntashirAkon:apksig-android:4.4.0` to sign the fused APK. The upstream AOSP `com.android.tools.build:apksig` artifact is host-oriented and must not be embedded as Aura's on-device signer. Aura minSdk is 26: v1/JAR signing is disabled, v2 + v3 are enabled, and v4 is disabled because it creates a separate `.idsig` sidecar rather than contributing to the single exported APK.

Generate a dedicated RSA-2048 software keypair for the export identity and persist its PKCS#8 private key plus X.509 certificate under `noBackupFilesDir/apk-export-signing`. This identity is deliberately separate from Aura Files' own application signing certificate and is excluded from backup/restore. It remains stable across ordinary Aura upgrades, but is removed when Aura is uninstalled; APKs exported after reinstall may therefore have a different signer and cannot update APKs exported by the previous installation. Do not use an AndroidKeyStore-backed private key here: the export signer must receive a normal JCA RSA private key that the on-device apksig port can consume directly.

Never use Aura's application signing key for exported third-party APKs.

## Verification

Before sharing:
- verify the new signature with `ApkVerifier`,
- parse the output with Android `PackageManager`,
- require the package name and versionCode to match the installed source,
- require non-empty file and bounded size,
- clean temporary unsigned output on success/failure/cancellation.

## Resource and safety limits

Reuse the existing 8 GiB APK/APKS ceiling. Require cache free-space reserve before writing the merged and signed outputs. Work under Aura cache only; use unique temp names; never follow arbitrary user-supplied paths or symlinks. Inputs come only from `ApplicationInfo.sourceDir/splitSourceDirs` returned by PackageManager.

## UI

For split rows:
- primary: `Поделиться APK…`,
- secondary: compact `APKS` fallback button,
- status while busy: `Готовим APK…`.

For normal rows:
- primary remains `Поделиться APK…`.

Show a one-time/in-context warning after successful split fusion before share: the APK is re-signed by Aura, so it is intended for clean install or for updates of APKs previously exported by the same Aura installation; it cannot update the Play/original-signed installation.

## Licensing

Add `THIRD_PARTY_NOTICES.md` entry for REAndroid/APKEditor merge logic and ARSCLib, both Apache-2.0, plus `MuntashirAkon/apksig-android` / AOSP apksig attribution (Apache-2.0).

## Release gate

- Pure unit tests for export naming/policy.
- Source invariant harness for dependencies, no use of Aura app signing key, primary APK action + APKS fallback, merge sanitization, apksig verification.
- Existing crash/release harnesses remain green.
- Windows clean build must run unit tests and release R8 build before publication.
