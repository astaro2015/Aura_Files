param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$DexdumpPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

$required = @(
    'Landroidx/media3/ui/AspectRatioFrameLayout;',
    'Landroidx/media3/ui/SubtitleView;'
)
$found = @{}
$tempDex = [System.IO.Path]::GetTempFileName()
$apk = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
try {
    foreach ($entry in $apk.Entries | Where-Object { $_.Name -match '^classes\d*\.dex$' }) {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tempDex, $true)
        $currentClass = ''
        $constructor = $false
        & $DexdumpPath -e $tempDex | ForEach-Object {
            if ($_ -match "Class descriptor\s*:\s*'([^']+)'") {
                $currentClass = $Matches[1]
                $constructor = $false
            } elseif ($currentClass -in $required -and $_ -match "name\s*:\s*'<init>'") {
                $constructor = $true
            } elseif ($constructor -and $_ -match "type\s*:\s*'\(Landroid/content/Context;Landroid/util/AttributeSet;\)V'") {
                $found[$currentClass] = $true
                $constructor = $false
            }
        }
        if ($LASTEXITCODE -ne 0) { throw "dexdump failed for $($entry.Name)" }
    }
} finally {
    $apk.Dispose()
    Remove-Item -LiteralPath $tempDex -Force
}

$missing = @($required | Where-Object { -not $found.ContainsKey($_) })
if ($missing.Count -gt 0) {
    throw "Media3 XML constructor missing from release APK: $($missing -join ', ')"
}
Write-Output 'Media3 XML constructors present in release APK.'
