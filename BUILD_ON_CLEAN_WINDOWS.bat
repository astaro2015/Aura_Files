@echo off
setlocal
cd /d "%~dp0"
title Aura Files - Windows Builder

echo ============================================================
echo   Aura Files - NO-TIMEOUT clean Windows APK builder
echo ============================================================
echo.
echo No Android Studio is required.
echo Large downloads have no short total timeout and are resumable. If Internet drops, run this
echo file again: already downloaded data will be reused/resumed.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\bootstrap_windows.ps1"
set "AURA_EXIT=%ERRORLEVEL%"

echo.
if "%AURA_EXIT%"=="0" (
  echo BUILD FINISHED SUCCESSFULLY.
  echo See BUILD_OUTPUT in this folder.
) else (
  echo BUILD FAILED OR DOWNLOAD WAS INTERRUPTED. Error code: %AURA_EXIT%
  echo.
  echo Just run BUILD_ON_CLEAN_WINDOWS.bat again to resume downloads.
  echo Error details are saved to:
  echo   BUILD_OUTPUT\LAST_ERROR.txt
  echo   BUILD_OUTPUT\setup.log
  echo   BUILD_OUTPUT\build.log
)
echo.
echo This window will NOT close automatically.
pause
exit /b %AURA_EXIT%
