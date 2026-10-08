from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
p = ROOT / "tools/bootstrap_windows.ps1"
b = p.read_bytes()
assert b.startswith(b"param(\r\n"), "PowerShell param block is not first"
assert b"\xef\xbb\xbf" not in b[:16], "BOM found before param block"
text = b.decode("ascii")
assert "[string]$BuildType = ''" in text
assert "[switch]$SkipConsent" in text
assert text.index("[string]$BuildType = ''") < text.index("[switch]$SkipConsent")
assert "$env:AURA_BUILD_TYPE" in text
print("STAGE17L_POWERSHELL_ENTRYPOINT_INTEGRITY_PASS")
