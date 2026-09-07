@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo ============================================================
echo  Aura Files 1.2.4 - Google Drive OAuth registration data
echo ============================================================
echo Package name: com.aurafiles.app
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

set "ANDROID_DIR=%USERPROFILE%\.android"
set "KS=%ANDROID_DIR%\debug.keystore"
if not exist "%ANDROID_DIR%" mkdir "%ANDROID_DIR%" >nul 2>nul
if not exist "%KS%" (
  echo No debug signing key exists yet. Creating the same persistent key that Gradle uses for debug APKs...
  "%KEYTOOL%" -genkeypair -keystore "%KS%" -storepass android -alias androiddebugkey -keypass android -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -storetype JKS -validity 10000 -noprompt
  if errorlevel 1 (
    echo Failed to create %KS%
    pause
    exit /b 1
  )
)

echo Debug signing certificate used by assembleDebug:
"%KEYTOOL%" -list -v -alias androiddebugkey -keystore "%KS%" -storepass android -keypass android | findstr /I /C:"SHA1:" /C:"SHA-1:"
echo.
echo Key file: %KS%
echo This key lives OUTSIDE the Aura source folder, so clean source extractions on this
echo Windows account keep the same Google OAuth SHA-1.
echo.
echo REQUIRED IN THE SAME GOOGLE CLOUD PROJECT:
echo   1. Enable Google Drive API.
echo   2. OAuth consent/Data Access: add https://www.googleapis.com/auth/drive
echo   3. If Testing: add your Google account to Test users.
echo   4. Create OAuth Client ID type Android:
echo        Package: com.aurafiles.app
echo        SHA-1:   value printed above
echo.
echo If Aura reports UNREGISTERED_ON_API_CONSOLE, package/SHA-1 is not registered
echo for the APK currently installed on the phone, or is registered in a different Cloud project.
echo.
echo For a RELEASE APK create another Android OAuth client for the release signing SHA-1.
pause
