@echo off
cd /d "%~dp0"
echo Restarting the resumable Aura Files builder...
call "%~dp0BUILD_ON_CLEAN_WINDOWS.bat"
