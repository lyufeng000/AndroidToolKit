param(
    [string[]]$Tasks = @(':app:assembleDebug'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$ApkPath = (Join-Path $PSScriptRoot 'AndroidToolKit-debug.apk')
)

$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$temporaryRoot = [IO.Path]::GetFullPath('D:\Data\.temp')
$stagingRoot = Join-Path $temporaryRoot ('AndroidToolKit-source-' + [Guid]::NewGuid().ToString('N'))
$cacheRoot = Join-Path $temporaryRoot 'AndroidToolKit-gradle'
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
& robocopy $sourceRoot $stagingRoot /E /XD .gradle .git build .idea /XF '*.apk' /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw "Source staging failed: $LASTEXITCODE" }

$previousJavaHome = $env:JAVA_HOME
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$env:TEMP = $temporaryRoot
$env:TMP = $temporaryRoot
Push-Location -LiteralPath $stagingRoot
try {
    & (Join-Path $stagingRoot 'gradlew.bat') @Tasks --project-cache-dir $cacheRoot --console=plain
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}
if ($buildExitCode -eq 0 -and @($Tasks | Where-Object { $_ -match '(^|:)assembleDebug$' }).Count -gt 0) {
    $builtApk = Join-Path $temporaryRoot 'AndroidToolKit-build\app\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $builtApk -PathType Leaf)) { throw 'Debug APK was not generated.' }
    Copy-Item -LiteralPath $builtApk -Destination $ApkPath -Force
    Write-Output "APK: $ApkPath"
}
exit $buildExitCode
