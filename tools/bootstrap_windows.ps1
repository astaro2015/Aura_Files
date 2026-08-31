param(
    [switch]$SkipConsent
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$AppGradlePath = Join-Path $ProjectRoot 'app\build.gradle.kts'
$AppGradleText = Get-Content -LiteralPath $AppGradlePath -Raw
if ($AppGradleText -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "Could not read versionName from $AppGradlePath"
}
$AppVersion = $Matches[1]
$OutputRoot = Join-Path $ProjectRoot 'BUILD_OUTPUT'
$PublicRoot = $env:PUBLIC
if ([string]::IsNullOrWhiteSpace($PublicRoot)) {
    $PublicRoot = Join-Path $env:SystemDrive 'Users\Public'
}

$ToolsRoot = Join-Path $PublicRoot 'AuraBuildTools'
$StageRoot = Join-Path $PublicRoot ("AuraBuild\Aura_Files_{0}" -f $AppVersion)
$CacheRoot = Join-Path $ToolsRoot 'downloads'
$JdkRoot = Join-Path $ToolsRoot 'jdk17'
$AndroidSdk = Join-Path $ToolsRoot 'android-sdk'
$GradleHome = Join-Path $ToolsRoot 'gradle-home'
$GradleDistRoot = Join-Path $ToolsRoot 'gradle-9.5.0'
$GradleZip = Join-Path $CacheRoot 'gradle-9.5.0-bin.zip'
$GradleUrl = 'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip'
$GradleSha256 = '553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'

$CmdToolsVersion = '15859902'
$CmdToolsSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$CmdToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-$($CmdToolsVersion)_latest.zip"
$NdkUrl = 'https://dl.google.com/android/repository/android-ndk-r28c-windows.zip'
$NdkSha1 = '086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d'
$NdkZip = Join-Path $CacheRoot 'android-ndk-r28c-windows.zip'
$JdkUrl = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk'
$SdkPackages = @(
    'platform-tools',
    'platforms;android-36',
    'build-tools;36.0.0'
)

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host ('=== ' + $Message + ' ===') -ForegroundColor Cyan
}

function Ensure-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Download-File([string]$Url, [string]$Destination) {
    Ensure-Directory (Split-Path -Parent $Destination)
    $Part = $Destination + '.part'
    $Curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($null -eq $Curl) {
        throw 'curl.exe was not found. Windows 10/11 normally includes it. Run Windows Update or copy curl.exe into PATH.'
    }

    Write-Host "Downloading/resuming with a finite retry limit: $Url"
    if (Test-Path -LiteralPath $Part) {
        $PartSize = (Get-Item -LiteralPath $Part).Length
        Write-Host "Existing partial file: $PartSize bytes. It will be resumed." -ForegroundColor Yellow
    }

    $FreshRetryUsed = $false
    $MaxDownloadAttempts = 12
    for ($Attempt = 1; $Attempt -le $MaxDownloadAttempts; $Attempt++) {
        Write-Host "Download attempt $Attempt of $MaxDownloadAttempts. Ctrl+C cancels."

        # There is no total transfer deadline, because the NDK archive is large.
        # A connection that stops making useful progress is terminated after three
        # minutes and resumed by the next finite attempt.
        & $Curl.Source `
            --location `
            --fail `
            --show-error `
            --continue-at - `
            --retry 30 `
            --retry-all-errors `
            --retry-delay 5 `
            --connect-timeout 120 `
            --speed-limit 1024 `
            --speed-time 180 `
            --output $Part `
            $Url
        $Code = $LASTEXITCODE

        if ($Code -eq 0 -and (Test-Path -LiteralPath $Part) -and ((Get-Item -LiteralPath $Part).Length -gt 0)) {
            Move-Item -LiteralPath $Part -Destination $Destination -Force
            return
        }

        # 33 = server rejected byte-range resume. Restart this file once from zero.
        if ($Code -eq 33 -and -not $FreshRetryUsed) {
            Write-Host 'The server rejected resume. Restarting only this file from zero once.' -ForegroundColor Yellow
            Remove-Item -LiteralPath $Part -Force -ErrorAction SilentlyContinue
            $FreshRetryUsed = $true
            Start-Sleep -Seconds 3
            continue
        }

        # 22 is generally an HTTP error such as 404/403. Repeating forever would hide a bad URL.
        if ($Code -eq 22) {
            throw "HTTP download error for: $Url`nPartial file kept at: $Part"
        }

        $Size = 0
        if (Test-Path -LiteralPath $Part) { $Size = (Get-Item -LiteralPath $Part).Length }
        if ($Attempt -lt $MaxDownloadAttempts) {
            Write-Host "curl exit $Code. Partial file kept ($Size bytes). Retrying in 10 seconds..." -ForegroundColor Yellow
            Start-Sleep -Seconds 10
        }
    }

    $Size = 0
    if (Test-Path -LiteralPath $Part) { $Size = (Get-Item -LiteralPath $Part).Length }
    throw "Download failed after $MaxDownloadAttempts attempts (last curl exit $Code): $Url`nPartial file kept at: $Part ($Size bytes). Run the builder again to resume it."
}

function Assert-Sha256([string]$Path, [string]$Expected) {
    $Actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path`nExpected: $Expected`nActual:   $Actual"
    }
}

function Assert-Sha1([string]$Path, [string]$Expected) {
    $Actual = (Get-FileHash -Algorithm SHA1 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-1 mismatch for $Path`nExpected: $Expected`nActual:   $Actual"
    }
}

function Install-Jdk17 {
    $JavaExe = Join-Path $JdkRoot 'bin\java.exe'
    if (Test-Path -LiteralPath $JavaExe) {
        Write-Host "Portable JDK 17 already present: $JdkRoot"
        return
    }

    Write-Step 'Downloading Eclipse Temurin JDK 17 directly (no metadata request, resumable)'
    $JdkZip = Join-Path $CacheRoot 'temurin-jdk17-windows-x64.zip'
    if (-not (Test-Path -LiteralPath $JdkZip)) {
        Download-File $JdkUrl $JdkZip
    }

    # Verify it is a readable ZIP. The direct Adoptium endpoint always returns the
    # current GA Temurin 17 x64 JDK and redirects to the actual release asset.
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $z = [IO.Compression.ZipFile]::OpenRead($JdkZip)
        $z.Dispose()
    } catch {
        Write-Host 'Cached JDK archive is incomplete/corrupt. Keeping a .part restart point is impossible after a bad completed ZIP; downloading it again.' -ForegroundColor Yellow
        Remove-Item -LiteralPath $JdkZip -Force -ErrorAction SilentlyContinue
        Download-File $JdkUrl $JdkZip
    }

    $ExtractRoot = Join-Path $ToolsRoot '_jdk_extract'
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $ExtractRoot
    Expand-Archive -LiteralPath $JdkZip -DestinationPath $ExtractRoot -Force

    $JavaCandidate = Get-ChildItem -LiteralPath $ExtractRoot -Filter 'java.exe' -File -Recurse |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if ($null -eq $JavaCandidate) {
        throw 'JDK archive extracted, but bin\java.exe was not found.'
    }
    $DetectedJdkHome = Split-Path -Parent (Split-Path -Parent $JavaCandidate.FullName)

    Remove-Item -LiteralPath $JdkRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $JdkRoot
    Get-ChildItem -LiteralPath $DetectedJdkHome -Force | ForEach-Object {
        Move-Item -LiteralPath $_.FullName -Destination $JdkRoot -Force
    }
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path -LiteralPath $JavaExe)) {
        throw 'Portable JDK 17 installation failed.'
    }
}

function Install-AndroidCommandLineTools {
    $SdkManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path -LiteralPath $SdkManager) {
        Write-Host "Android command-line tools already present: $SdkManager"
        return
    }

    Write-Step 'Downloading Android command-line tools'
    $ZipPath = Join-Path $CacheRoot "commandlinetools-win-$($CmdToolsVersion)_latest.zip"
    $NeedDownload = $true
    if (Test-Path -LiteralPath $ZipPath) {
        try {
            Assert-Sha256 $ZipPath $CmdToolsSha256
            $NeedDownload = $false
        } catch {
            Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue
        }
    }
    if ($NeedDownload) {
        Download-File $CmdToolsUrl $ZipPath
    }
    Assert-Sha256 $ZipPath $CmdToolsSha256

    $ExtractRoot = Join-Path $ToolsRoot '_cmdtools_extract'
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $ExtractRoot
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ExtractRoot -Force

    $ExtractedTools = Join-Path $ExtractRoot 'cmdline-tools'
    if (-not (Test-Path -LiteralPath (Join-Path $ExtractedTools 'bin\sdkmanager.bat'))) {
        throw 'Android command-line tools archive has an unexpected layout.'
    }

    $LatestRoot = Join-Path $AndroidSdk 'cmdline-tools\latest'
    Remove-Item -LiteralPath $LatestRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory (Split-Path -Parent $LatestRoot)
    Move-Item -LiteralPath $ExtractedTools -Destination $LatestRoot
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path -LiteralPath $SdkManager)) {
        throw 'Android command-line tools installation failed.'
    }
}

function Install-Ndk28c {
    $NdkRoot = Join-Path $AndroidSdk 'ndk\28.2.13676358'
    $NdkBuild = Join-Path $NdkRoot 'ndk-build.cmd'
    if (Test-Path -LiteralPath $NdkBuild) {
        Write-Host "Android NDK 28.2.13676358 already present: $NdkRoot"
        return
    }

    Write-Step 'Downloading Android NDK r28c (~748 MB, resumable)'
    $NeedDownload = $true
    if (Test-Path -LiteralPath $NdkZip) {
        try {
            Assert-Sha1 $NdkZip $NdkSha1
            $NeedDownload = $false
        } catch {
            Write-Host 'Cached NDK ZIP has a bad checksum. It will be downloaded again.' -ForegroundColor Yellow
            Remove-Item -LiteralPath $NdkZip -Force -ErrorAction SilentlyContinue
        }
    }
    if ($NeedDownload) { Download-File $NdkUrl $NdkZip }
    Assert-Sha1 $NdkZip $NdkSha1

    $ExtractRoot = Join-Path $ToolsRoot '_ndk_extract'
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $ExtractRoot
    Write-Host 'Extracting NDK...'
    Expand-Archive -LiteralPath $NdkZip -DestinationPath $ExtractRoot -Force
    $DetectedNdk = Get-ChildItem -LiteralPath $ExtractRoot -Filter 'ndk-build.cmd' -File -Recurse | Select-Object -First 1
    if ($null -eq $DetectedNdk) { throw 'NDK archive extracted, but ndk-build.cmd was not found.' }
    $DetectedHome = Split-Path -Parent $DetectedNdk.FullName
    Remove-Item -LiteralPath $NdkRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $NdkRoot
    Get-ChildItem -LiteralPath $DetectedHome -Force | ForEach-Object {
        Move-Item -LiteralPath $_.FullName -Destination $NdkRoot -Force
    }
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $NdkBuild)) { throw 'Android NDK installation failed.' }
}

function Install-Gradle95 {
    $GradleExe = Join-Path $GradleDistRoot 'bin\gradle.bat'
    if (Test-Path -LiteralPath $GradleExe) {
        Write-Host "Portable Gradle 9.5.0 already present: $GradleDistRoot"
        return
    }

    Write-Step 'Downloading Gradle 9.5.0 (resumable)'
    $NeedDownload = $true
    if (Test-Path -LiteralPath $GradleZip) {
        try {
            Assert-Sha256 $GradleZip $GradleSha256
            $NeedDownload = $false
        } catch {
            Write-Host 'Cached Gradle ZIP has a bad checksum. It will be downloaded again.' -ForegroundColor Yellow
            Remove-Item -LiteralPath $GradleZip -Force -ErrorAction SilentlyContinue
        }
    }
    if ($NeedDownload) {
        Download-File $GradleUrl $GradleZip
    }
    Assert-Sha256 $GradleZip $GradleSha256

    $ExtractRoot = Join-Path $ToolsRoot '_gradle_extract'
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $ExtractRoot
    Expand-Archive -LiteralPath $GradleZip -DestinationPath $ExtractRoot -Force
    $Detected = Get-ChildItem -LiteralPath $ExtractRoot -Filter 'gradle.bat' -File -Recurse |
        Where-Object { $_.FullName -match '\\bin\\gradle\.bat$' } |
        Select-Object -First 1
    if ($null -eq $Detected) { throw 'Gradle archive extracted, but bin\\gradle.bat was not found.' }
    $DetectedHome = Split-Path -Parent (Split-Path -Parent $Detected.FullName)
    Remove-Item -LiteralPath $GradleDistRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $GradleDistRoot
    Get-ChildItem -LiteralPath $DetectedHome -Force | ForEach-Object {
        Move-Item -LiteralPath $_.FullName -Destination $GradleDistRoot -Force
    }
    Remove-Item -LiteralPath $ExtractRoot -Recurse -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path -LiteralPath $GradleExe)) { throw 'Portable Gradle 9.5.0 installation failed.' }
}

function Configure-Environment {
    $env:JAVA_HOME = $JdkRoot
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:GRADLE_USER_HOME = $GradleHome
    # Do not use JAVA_TOOL_OPTIONS here: the JVM prints a harmless 'Picked up JAVA_TOOL_OPTIONS'
    # line to stderr, and Windows PowerShell 5.1 can promote redirected native stderr to ErrorRecord.
    $env:JAVA_TOOL_OPTIONS = $null
    $env:GRADLE_OPTS = '-Dsun.net.client.defaultConnectTimeout=300000 -Dsun.net.client.defaultReadTimeout=600000 -Dorg.gradle.internal.http.connectionTimeout=300000 -Dorg.gradle.internal.http.socketTimeout=600000'
    $env:PATH = (Join-Path $JdkRoot 'bin') + ';' +
                (Join-Path $AndroidSdk 'platform-tools') + ';' +
                (Join-Path $AndroidSdk 'cmdline-tools\latest\bin') + ';' +
                $env:PATH
}

function Android-PackagesReady {
    return (
        (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar')) -and
        (Test-Path -LiteralPath (Join-Path $AndroidSdk 'build-tools\36.0.0\aapt2.exe')) -and
        (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platform-tools\adb.exe')) -and
        (Test-Path -LiteralPath (Join-Path $AndroidSdk 'ndk\28.2.13676358\ndk-build.cmd'))
    )
}

function Install-AndroidPackages {
    $SdkManager = Join-Path $AndroidSdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Android-PackagesReady) {
        Write-Host 'Required Android SDK/NDK packages are already installed.'
        return
    }

    Write-Step 'Accepting Android SDK licenses'
    $LicenseOk = $false
    for ($LicenseAttempt = 1; $LicenseAttempt -le 20; $LicenseAttempt++) {
        1..300 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$AndroidSdk" '--licenses'
        if ($LASTEXITCODE -eq 0) { $LicenseOk = $true; break }
        Write-Host "sdkmanager --licenses network/error attempt $LicenseAttempt of 20. Retrying in 10 seconds..." -ForegroundColor Yellow
        Start-Sleep -Seconds 10
    }
    if (-not $LicenseOk) {
        throw 'sdkmanager --licenses could not complete after 20 attempts. Run this BAT again; installed/downloaded data is retained.'
    }

    Write-Step 'Installing Android SDK 36, Build Tools 36.0.0 and NDK 28.2'
    for ($Attempt = 1; $Attempt -le 30; $Attempt++) {
        Write-Host "sdkmanager attempt $Attempt of 30..."
        & $SdkManager "--sdk_root=$AndroidSdk" $SdkPackages
        $SdkCode = $LASTEXITCODE
        if ($SdkCode -eq 0 -and (Android-PackagesReady)) {
            return
        }
        Write-Host "sdkmanager did not finish (exit $SdkCode). Waiting and retrying; already downloaded components are kept." -ForegroundColor Yellow
        Start-Sleep -Seconds ([Math]::Min(30, 5 * $Attempt))
    }

    throw 'Android SDK installation did not finish after 30 attempts. Run BUILD_ON_CLEAN_WINDOWS.bat again; sdkmanager will reuse already installed components.'
}

function Stage-Project {
    Write-Step 'Staging project into an ASCII-only build path'
    $SourceFull = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
    $StageFull = [IO.Path]::GetFullPath($StageRoot).TrimEnd('\')

    if ($SourceFull.Equals($StageFull, [StringComparison]::OrdinalIgnoreCase)) {
        Write-Host "Project is already in staging path: $StageRoot"
        return
    }

    Remove-Item -LiteralPath $StageRoot -Recurse -Force -ErrorAction SilentlyContinue
    Ensure-Directory $StageRoot

    $ExcludedDirs = @(
        (Join-Path $ProjectRoot '.git'),
        (Join-Path $ProjectRoot '.gradle'),
        (Join-Path $ProjectRoot 'build'),
        (Join-Path $ProjectRoot 'app\build'),
        (Join-Path $ProjectRoot 'BUILD_OUTPUT')
    )

    $RoboArgs = @(
        $ProjectRoot,
        $StageRoot,
        '/MIR', '/R:2', '/W:1', '/NFL', '/NDL', '/NJH', '/NJS', '/NP',
        '/XF', 'local.properties',
        '/XD'
    ) + $ExcludedDirs

    & robocopy.exe @RoboArgs | Out-Null
    $RoboCode = $LASTEXITCODE
    if ($RoboCode -gt 7) {
        throw "robocopy failed with exit code $RoboCode"
    }
}

function Write-LocalProperties {
    $EscapedSdk = $AndroidSdk.Replace('\', '\\').Replace(':', '\:')
    $LocalProperties = Join-Path $StageRoot 'local.properties'
    Set-Content -LiteralPath $LocalProperties -Encoding ASCII -Value "sdk.dir=$EscapedSdk"
}

function Build-Apk {
    Write-Step ("Building Aura Files {0} debug APK" -f $AppVersion)
    Ensure-Directory $OutputRoot
    $LogPath = Join-Path $OutputRoot 'build.log'
    # Start each build with a fresh log so diagnostics from an older source tree do not
    # trigger retry/compile-error detection in the current run.
    Set-Content -LiteralPath $LogPath -Encoding Unicode -Value ''
    $GradleExe = Join-Path $GradleDistRoot 'bin\gradle.bat'
    if (-not (Test-Path -LiteralPath $GradleExe)) { throw "Gradle executable missing: $GradleExe" }

    Push-Location $StageRoot
    try {
        $BuildExit = 1
        for ($Attempt = 1; $Attempt -le 12; $Attempt++) {
            Write-Host "Gradle build attempt $Attempt of 12..."
            # Windows PowerShell 5.1 wraps native stderr lines as red ErrorRecord objects.
            # Merge Gradle stderr into stdout inside cmd.exe instead; PowerShell then receives
            # ordinary text while the real Gradle exit code remains the failure criterion.
            $GradleCommand = ('call "{0}" --no-daemon --stacktrace --console=plain clean assembleDebug 2>&1' -f $GradleExe)
            & $env:ComSpec /d /s /c $GradleCommand | Tee-Object -FilePath $LogPath -Append
            $BuildExit = $LASTEXITCODE
            if ($BuildExit -eq 0) { break }

            $RecentBuildLog = (Get-Content -LiteralPath $LogPath -Tail 220 -ErrorAction SilentlyContinue) -join "`n"
            $CompileFailure = $RecentBuildLog -match '(?i)(compileDebugKotlin FAILED|compileDebugJavaWithJavac FAILED|mergeDebugJavaResource FAILED|Compilation error|Unresolved reference|Argument type mismatch|Conflicting overloads|More than one file was found|files found with path)'
            if ($CompileFailure) {
                Write-Host 'Gradle reached source compilation and found a code error. Retrying cannot fix source code, so stopping immediately.' -ForegroundColor Red
                break
            }

            Write-Host "Gradle failed with exit $BuildExit. Retrying in case a dependency download was interrupted." -ForegroundColor Yellow
            Start-Sleep -Seconds ([Math]::Min(30, 5 * $Attempt))
        }
    } finally {
        Pop-Location
    }

    if ($BuildExit -ne 0) {
        throw "Gradle build failed with exit code $BuildExit. See $LogPath"
    }

    $BuiltApk = Join-Path $StageRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $BuiltApk)) {
        throw "Gradle reported success, but APK was not found: $BuiltApk"
    }

    $FinalApk = Join-Path $OutputRoot ("Aura_Files_{0}-debug.apk" -f $AppVersion)
    Copy-Item -LiteralPath $BuiltApk -Destination $FinalApk -Force
    $ApkInfo = Get-Item -LiteralPath $FinalApk
    $ApkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FinalApk).Hash.ToLowerInvariant()

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host 'BUILD SUCCESSFUL' -ForegroundColor Green
    Write-Host "APK:    $FinalApk" -ForegroundColor Green
    Write-Host "Size:   $($ApkInfo.Length) bytes" -ForegroundColor Green
    Write-Host "SHA256: $ApkHash" -ForegroundColor Green
    Write-Host '============================================================' -ForegroundColor Green

    try {
        Start-Process explorer.exe -ArgumentList "/select,`"$FinalApk`""
    } catch {
        # Explorer opening is optional; build success must not depend on it.
    }
}

try {
    Write-Host ("Aura Files {0} clean-Windows builder" -f $AppVersion)
    Write-Host "Source: $ProjectRoot"
    Write-Host "Portable tools: $ToolsRoot"
    Write-Host "ASCII build copy: $StageRoot"
    Write-Host ''
    Write-Host 'The first run needs Internet access and several GB of free disk space.'
    Write-Host 'It downloads Eclipse Temurin JDK 17 and Google Android SDK/NDK components.'

    if (-not [Environment]::Is64BitOperatingSystem) {
        throw 'A 64-bit Windows installation is required.'
    }

    if (-not $SkipConsent) {
        Write-Host ''
        Write-Host 'Android SDK components are subject to the Android SDK License Agreement.' -ForegroundColor Yellow
        $Answer = Read-Host 'Download the tools, accept SDK licenses and continue? [Y/N]'
        if ($Answer -notmatch '^(?i:y|yes)$') {
            Write-Host 'Cancelled by user.'
            exit 2
        }
    }

    Ensure-Directory $ToolsRoot
    Ensure-Directory $CacheRoot
    Ensure-Directory $AndroidSdk
    Ensure-Directory $GradleHome
    Ensure-Directory $OutputRoot

    $SetupLog = Join-Path $OutputRoot 'setup.log'
    try { Start-Transcript -LiteralPath $SetupLog -Append -Force | Out-Null } catch {}

    Install-Jdk17
    Configure-Environment

    Write-Step 'JDK check'
    & (Join-Path $JdkRoot 'bin\java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'java -version failed.' }

    Install-AndroidCommandLineTools
    Configure-Environment
    Install-Ndk28c
    Install-AndroidPackages
    Install-Gradle95
    Stage-Project
    Write-LocalProperties
    Build-Apk
    try { Stop-Transcript | Out-Null } catch {}
    exit 0
} catch {
    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Red
    Write-Host 'BUILD/SETUP FAILED' -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host '============================================================' -ForegroundColor Red
    Write-Host $_.ScriptStackTrace
    try {
        Ensure-Directory $OutputRoot
        $FailurePath = Join-Path $OutputRoot 'LAST_ERROR.txt'
        $FailureLines = @('Aura Files setup/build failed.', '', 'ERROR:', $_.Exception.Message, '', 'STACK:', $_.ScriptStackTrace)
        $BuildLogForFailure = Join-Path $OutputRoot 'build.log'
        if (Test-Path -LiteralPath $BuildLogForFailure) {
            $FailureLines += @('', 'LAST 120 LINES OF BUILD.LOG:')
            $FailureLines += Get-Content -LiteralPath $BuildLogForFailure -Tail 120
        }
        $FailureLines | Set-Content -LiteralPath $FailurePath -Encoding UTF8
        Write-Host "Error was also saved to: $FailurePath" -ForegroundColor Yellow
    } catch {}
    try { Stop-Transcript | Out-Null } catch {}
    exit 1
}
