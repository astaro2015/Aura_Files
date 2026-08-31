@echo off
setlocal
set "CACHE=%PUBLIC%\AuraBuildTools\downloads"
if not exist "%CACHE%" mkdir "%CACHE%"
start "" explorer.exe "%CACHE%"
start "" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
start "" "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
start "" "https://dl.google.com/android/repository/android-ndk-r28c-windows.zip"
start "" "https://services.gradle.org/distributions/gradle-9.5.0-bin.zip"
echo.
echo Browser tabs were opened together with the Aura download cache folder.
echo Read MANUAL_DOWNLOAD_FALLBACK_RU.txt for the required file names.
pause
