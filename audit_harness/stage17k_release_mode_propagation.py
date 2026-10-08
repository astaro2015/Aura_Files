from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
bootstrap_path = ROOT / "tools/bootstrap_windows.ps1"
bootstrap_bytes = bootstrap_path.read_bytes()
bootstrap = bootstrap_bytes.decode("ascii")
release_bat = (ROOT / "BUILD_RELEASE_WINDOWS.bat").read_text(encoding="ascii")
debug_bat = (ROOT / "BUILD_ON_CLEAN_WINDOWS.bat").read_text(encoding="ascii")

assert not bootstrap_bytes.startswith(b"\xef\xbb\xbf"), "bootstrap must be BOM-free ASCII"
assert bootstrap_bytes.startswith(b"param(\r\n"), "param block must be first bytes"
assert "set \"AURA_BUILD_TYPE=Release\"" in release_bat
assert "-BuildType Release" not in release_bat
assert "set \"AURA_BUILD_TYPE=Debug\"" in debug_bat
for marker in (
    "[string]$BuildType = ''",
    "$EnvironmentBuildType = [string]$env:AURA_BUILD_TYPE",
    "Build mode mismatch:",
    "$RequestedBuildType = if",
    "$script:AuraRequestedBuildType",
    "function Ensure-AuraSigningKeystore([ValidateSet('Debug','Release')][string]$RequestedBuildType)",
    "function Build-Apk([ValidateSet('Debug','Release')][string]$RequestedBuildType)",
    "Ensure-AuraSigningKeystore -RequestedBuildType $script:AuraRequestedBuildType",
    "Build-Apk -RequestedBuildType $script:AuraRequestedBuildType",
    "$IsRelease = $RequestedBuildType -ieq 'Release'",
    "'assembleRelease'",
    "'assembleDebug'",
    "Requested build mode:",
    "Gradle tasks:",
    "Release mode routing failure: computed Gradle tasks were",
    "Release mode routing failure: assembleDebug appeared in",
    "RequestedBuildType is empty. Refusing to silently fall back to Debug.",
):
    assert marker in bootstrap, f"missing release routing marker: {marker}"
print("STAGE17K_RELEASE_MODE_PROPAGATION_PASS")
