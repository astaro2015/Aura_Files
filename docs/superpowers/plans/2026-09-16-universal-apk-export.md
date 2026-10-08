> **1.3.8 runtime correction:** the original plan below records the first implementation path. The final on-device architecture no longer uses Android Keystore/private keys with host AOSP `com.android.tools.build:apksig`. Export identity is a software RSA-2048 key in `noBackupFilesDir`, and APK signing/verification uses `MuntashirAkon/apksig-android 4.4.0` with API 26 floor, v1=false, v2=true, v3=true, v4=false. See `AURA_1.3.8_ANDROID_APKSIG_RUNTIME_FIX_CHECKPOINT.md`.

# Universal APK Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make split applications share as one verified, Aura-signed APK while preserving `.apks` as an explicit fallback.

**Architecture:** `UniversalApkExporter` orchestrates a focused ARSCLib merge engine and Android Keystore/apksig signer. `InstalledAppsActivity` only chooses export mode and shares the resulting file; split-specific policy/naming is kept in a pure Kotlin helper for unit testing.

**Tech Stack:** Kotlin/Java 17, Android Keystore, REAndroid ARSCLib 1.4.0, Android apksig 9.3.0, Compose, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-16-universal-apk-export-design.md`

## Global Constraints

- Keep existing `.apks` export/install as fallback.
- Never sign exported third-party APKs with Aura Files' own application signing key.
- Inputs are PackageManager-owned `sourceDir/splitSourceDirs` only.
- Output package/version must match the installed source before share.
- Cancellation/failure cleans partial unsigned/signed files.
- Keep the 8 GiB ceiling and cache reserve.
- APKEditor-derived merge logic must retain Apache-2.0 attribution.

---

### Task 1: Export policy and naming

**Files:**
- Create: `app/src/main/java/com/aurafiles/app/tools/UniversalApkExportPolicy.kt`
- Test: `app/src/test/java/com/aurafiles/app/tools/UniversalApkExportPolicyTest.kt`

**Interfaces:**
- Produces: `UniversalApkExportPolicy.outputFileName(label, packageName, versionName, versionCode): String`
- Produces: `UniversalApkExportPolicy.shareLabel(isSplit, exporting): String`

- [ ] Write failing tests for safe `.apk` naming and split primary action wording.
- [ ] Run focused unit test and confirm RED.
- [ ] Implement minimum pure helper.
- [ ] Run focused unit test and confirm GREEN.
- [ ] Commit.

### Task 2: Merge engine

**Files:**
- Create: `app/src/main/java/com/aurafiles/app/tools/UniversalApkMerger.java`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Create: `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Produces: `UniversalApkMerger.merge(List<File> apkFiles, File output): void`

- [ ] Add a source-invariant test that requires ARSCLib dependency, manifest sanitization, stale signature removal, and no APKEditor CLI dependency; confirm RED.
- [ ] Implement the APKEditor-1.4.9-derived merge core with ARSCLib 1.4.0 and attribution.
- [ ] Run invariant test and confirm GREEN.
- [ ] Commit.

### Task 3: Dedicated export signer

**Files:**
- Create: `app/src/main/java/com/aurafiles/app/tools/ApkExportSigner.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

**Interfaces:**
- Produces: `ApkExportSigner.sign(unsignedApk: File, signedApk: File): String` (certificate SHA-256)
- Produces: `ApkExportSigner.verify(apk: File): Unit`

- [ ] Extend source-invariant test to require dedicated Android Keystore alias, apksig v2/v3, verifier call, and prohibit Aura release keystore/env variables; confirm RED.
- [ ] Implement RSA-2048 Android Keystore signer and apksig verification.
- [ ] Run invariant test and confirm GREEN.
- [ ] Commit.

### Task 4: Export orchestration

**Files:**
- Create: `app/src/main/java/com/aurafiles/app/tools/UniversalApkExporter.kt`

**Interfaces:**
- Consumes: `UniversalApkMerger.merge`, `ApkExportSigner.sign/verify`
- Produces: `suspend fun export(context, InstalledPackageSource): ExportedApk`

- [ ] Extend invariant/model test for cache bounds, cleanup and package/version verification; confirm RED.
- [ ] Implement merge -> sign -> verify -> atomic publish with cancellation cleanup.
- [ ] Run invariant/model test and confirm GREEN.
- [ ] Commit.

### Task 5: Installed-app UI

**Files:**
- Modify: `app/src/main/java/com/aurafiles/app/ui/InstalledAppsActivity.kt`

**Interfaces:**
- Split primary action calls `UniversalApkExporter.export` and shares MIME `application/vnd.android.package-archive`.
- Split fallback action keeps `AuraApksBundle.exportInstalledPackage`.

- [ ] Extend source-invariant test to require `Поделиться APK` primary and explicit `APKS` fallback; confirm RED.
- [ ] Wire exporter, warning dialog/toast text, progress state and fallback button.
- [ ] Run invariant and policy tests; confirm GREEN.
- [ ] Commit.

### Task 6: Release verification and package

**Files:**
- Create: `audit_harness/stage18_universal_apk_export.py`
- Modify: `SOURCE_SHA256SUMS.txt`
- Modify: release notes/checkpoint documentation as present in repository.

- [ ] Run stage18 harness plus all existing audit harnesses.
- [ ] Run production Kotlin harnesses available in this environment.
- [ ] Recompute source manifest and verify exact file set.
- [ ] Build new source ZIP and independently extract/hash-verify it.
- [ ] Commit final checkpoint.
