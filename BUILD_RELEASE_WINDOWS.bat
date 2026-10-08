@echo off
setlocal
cd /d "%~dp0"
title Aura Files - RELEASE Builder

echo ============================================================
echo   Aura Files - VERIFIED RELEASE APK builder
echo ============================================================
echo.
echo This mode runs testDebugUnitTest and then assembleRelease.
echo The release APK is signed with the SAME pinned Aura certificate
echo used by the existing debug APK, so Android app identity, Vault and
echo cloud-profile continuity are preserved.
echo.
echo IMPORTANT: the builder will REFUSE to continue if the signing key
echo SHA-1 is not the expected Aura certificate.
echo.

set "AURA_BUILD_TYPE=Release"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\bootstrap_windows.ps1"
set "AURA_EXIT=%ERRORLEVEL%"

echo.
if "%AURA_EXIT%"=="0" (
  echo RELEASE BUILD FINISHED SUCCESSFULLY.
  echo See BUILD_OUTPUT\Aura_Files_1.3.6-release.apk
  echo Also see UNIT_TEST_SUMMARY.txt and APK_SIGNING_IDENTITY.txt.
) else (
  echo RELEASE BUILD FAILED. Error code: %AURA_EXIT%
  echo.
  echo Error details are saved to BUILD_OUTPUT.
)
echo.
echo This window will NOT close automatically.
pause
exit /b %AURA_EXIT%
