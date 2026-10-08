# Audit checkpoint 17K — release mode propagation / BUILD_FIX6

External Windows evidence showed that `BUILD_RELEASE_WINDOWS.bat` correctly invoked:

`bootstrap_windows.ps1 -BuildType Release`

but inside the PowerShell functions the inherited `$BuildType` value was empty. The builder therefore silently computed `assembleDebug`, producing `Aura_Files_1.3.5-debug.apk` even though the release entry point had been used.

## Fix

- The requested mode is normalized once at script startup into `$script:AuraRequestedBuildType`.
- `Ensure-AuraSigningKeystore` receives `-RequestedBuildType` explicitly.
- `Build-Apk` receives `-RequestedBuildType` explicitly.
- Release mode fails closed if the computed task list lacks `assembleRelease` or contains `assembleDebug`.
- The bootstrap prints both the requested mode and exact Gradle task list before build execution.
- An empty mode is now an immediate error instead of a silent Debug fallback.

No application Kotlin/Java behavior was changed by this fix.

Regression harness: `audit_harness/stage17k_release_mode_propagation.py`.


## BUILD_FIX7 follow-up

The later Windows run exposed the deeper root cause behind the empty mode: `bootstrap_windows.ps1` had two UTF-8 BOM byte sequences before `param(...)`. BUILD_FIX7 removes BOMs entirely (the script is ASCII) and routes official BAT build mode through `AURA_BUILD_TYPE`, while preserving explicit function parameters and fail-closed Release routing. See `AUDIT_CHECKPOINT_17L_POWERSHELL_ENTRYPOINT_BINDING.md`.
