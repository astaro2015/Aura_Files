# Audit checkpoint 17L — Windows PowerShell entrypoint binding

BUILD_FIX7 fixes a Windows PowerShell 5.1 release-entry failure found by the real builder.

Root cause:
- `tools/bootstrap_windows.ps1` began with two UTF-8 BOM byte sequences.
- after the encoding marker was consumed, a second invisible `U+FEFF` remained before `param(...)`;
- top-level parameter binding therefore malfunctioned: `Release` was treated as a value for `[switch]$SkipConsent`, while `$BuildType` remained empty.

Fix:
- `bootstrap_windows.ps1` is now pure ASCII and BOM-free; `param(` is literally the first bytes;
- official BAT entrypoints set `AURA_BUILD_TYPE=Debug|Release` and no longer pass build mode through `powershell.exe -File` arguments;
- manual `-BuildType` is still supported, and if both sources exist they must agree;
- resolved mode is still explicitly passed into signing/build functions; Release still refuses any route to `assembleDebug`.

Regression guards: stage17K + stage17L.
