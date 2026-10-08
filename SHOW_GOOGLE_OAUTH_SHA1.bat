@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "EXPECTED_SHA1=90:2F:F6:17:0C:61:EB:8C:DA:08:EE:60:8E:87:12:76:35:51:4B:B5"

echo ============================================================
echo  Aura Files 1.3.10 - Google Drive OAuth / signing identity
echo ============================================================
echo Package name: com.aurafiles.app
echo Expected Aura SHA-1: %EXPECTED_SHA1%
echo.

set "KEYTOOL="
where keytool >nul 2>nul
if not errorlevel 1 set "KEYTOOL=keytool"
if not defined KEYTOOL if exist "%PUBLIC%\AuraBuildTools\jdk17\bin\keytool.exe" set "KEYTOOL=%PUBLIC%\AuraBuildTools\jdk17\bin\keytool.exe"
if not defined KEYTOOL (
  echo keytool not found.
  echo Run BUILD_ON_CLEAN_WINDOWS.bat once so Aura downloads JDK 17, then run this file again.
  pause
  exit /b 1
)

set "KS=%USERPROFILE%\.android\debug.keystore"
if not exist "%KS%" (
  echo.
  echo CRITICAL: the existing Aura signing key is missing:
  echo   %KS%
  echo.
  echo Aura will NOT generate a replacement key here.
  echo Restore the ORIGINAL debug.keystore from backup. A new key would change
  echo the Android app identity and may make the existing Vault inaccessible.
  pause
  exit /b 2
)

echo Existing Aura signing certificate:
"%KEYTOOL%" -list -v -alias androiddebugkey -keystore "%KS%" -storepass android -keypass android | findstr /I /C:"SHA1:" /C:"SHA-1:"
if errorlevel 1 (
  echo Failed to read SHA-1 from %KS%
  pause
  exit /b 3
)

echo.
echo Key file: %KS%
echo Debug and release APKs from the official Aura builder intentionally use this SAME certificate.
echo Therefore the Google OAuth Android client remains:
echo   Package: com.aurafiles.app
echo   SHA-1:   %EXPECTED_SHA1%
echo.
echo IMPORTANT: back up this keystore. Do not replace it with a newly generated key.
echo.
echo REQUIRED IN THE SAME GOOGLE CLOUD PROJECT:
echo   1. Enable Google Drive API.
echo   2. OAuth consent/Data Access: add https://www.googleapis.com/auth/drive
echo   3. If Testing: add your Google account to Test users.
echo   4. Create/keep OAuth Client ID type Android with the package and SHA-1 above.
echo.
echo If Aura reports UNREGISTERED_ON_API_CONSOLE, verify this exact package + SHA-1
echo in the same Google Cloud project used by Aura.
pause
