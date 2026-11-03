param(
    [Parameter(Mandatory=$true)][string]$NdkPath,
    [Parameter(Mandatory=$true)][string]$BashPath,
    [switch]$SkipCompiler
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath($PSScriptRoot)
$cache = 'D:\Data\.temp\AndroidToolKit-native'
New-Item -Force -ItemType Directory $cache | Out-Null
function Fetch([string]$Url, [string]$Target, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Target)) { Invoke-WebRequest $Url -OutFile $Target }
    if ((Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash -ne $Sha256) { throw "Hash mismatch: $Target" }
}
foreach ($source in @(@('openocd','openocd-0.12.0'),@('libusb','libusb-1.0.27'))) {
    & robocopy (Join-Path $workspace "feature/flash/third_party/$($source[0])") (Join-Path $cache $source[1]) /E /NFL /NDL /NJH /NJS /NP
    if ($LASTEXITCODE -ge 8) { throw 'Native source staging failed' }
}
function MsysPath([string]$Value) { $value = [IO.Path]::GetFullPath($Value).Replace('\','/'); '/' + $value.Substring(0,1).ToLower() + $value.Substring(2) }
function BashQuote([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }
$env:MSYSTEM='MSYS'
$arguments = @('bash', (MsysPath "$workspace/feature/flash/native/build-openocd.sh"), (MsysPath $cache), (MsysPath $NdkPath), (MsysPath $workspace))
& $BashPath -lc (($arguments | ForEach-Object { BashQuote $_ }) -join ' ')
if ($LASTEXITCODE -ne 0) { throw 'OpenOCD build failed' }
& node "$workspace/feature/flash/native/generate-targets.mjs" "$workspace/feature/flash/src/main/assets/openocd" "$workspace/feature/flash/src/main/assets/targets.tsv"
if ($LASTEXITCODE -ne 0) { throw 'Target catalog generation failed' }
if (-not $SkipCompiler) {
    $packages = Get-Content "$workspace/feature/flash/native/compiler-packages.json" -Raw | ConvertFrom-Json
    New-Item -Force -ItemType Directory "$cache/compiler" | Out-Null
    foreach ($package in $packages) {
        $deb = "$cache/compiler/$($package.name).deb"
        Fetch "https://packages.termux.dev/apt/termux-main/$($package.file)" $deb $package.sha256
        $directory = "$cache/compiler/$($package.name)"
        New-Item -Force -ItemType Directory $directory | Out-Null
        & tar -xf $deb -C $directory
        if ($LASTEXITCODE -ne 0) { throw 'Compiler package extraction failed' }
        Get-ChildItem $directory -Filter 'data.tar.*' | ForEach-Object { & tar -xf $_.FullName -C $directory }
    }
    $gcc = "$cache/arm-gcc.tar.gz"
    Fetch 'https://github.com/xpack-dev-tools/arm-none-eabi-gcc-xpack/releases/download/v15.2.1-1.1/xpack-arm-none-eabi-gcc-15.2.1-1.1-linux-x64.tar.gz' $gcc 'DA6A49AD4003944B823C6C93702A8787C922AB34BD7E918EC0EAF6933A9B1FF6'
    New-Item -Force -ItemType Directory "$cache/gcc" | Out-Null
    & tar -xf $gcc -C "$cache/gcc"
    if ($LASTEXITCODE -ne 0) { throw 'Multilib extraction failed' }
    & node "$workspace/feature/flash/native/package-compiler.mjs" $cache $workspace
    if ($LASTEXITCODE -ne 0) { throw 'Compiler packaging failed' }
}
Write-Output 'Flash tools rebuilt. Run Build.ps1 to package the APK.'
