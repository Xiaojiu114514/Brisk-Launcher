# Brisk Launcher bootstrap (PowerShell)
#   1) find a usable Java 21
#   2) if missing, ask once and download Adoptium 21 into <launcher>\data\jdk\21
#   3) launch the app with that JDK
# Debug:
#   launch.ps1 --check     print the Java that would be used
#   launch.ps1 --install   only download + install Java
#   launch.ps1 --console   keep a console with logs
#   launch.ps1 --safe      software rendering (for old graphics drivers)
param([string]$Mode = "")

$ErrorActionPreference = "Continue"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkRoot = Join-Path $dir "data\jdk"

function Get-JavaMajor([string]$exe) {
    try {
        $out = & $exe -version 2>&1 | Out-String
        if ($out -match 'version "(?:1\.)?(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

function Find-Java21 {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe")) }
    (Get-Command java.exe -All -ErrorAction SilentlyContinue | ForEach-Object { $_.Source }) |
        ForEach-Object { if ($_) { $candidates.Add($_) } }
    $roots = @(
        "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\BellSoft",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:LOCALAPPDATA\Programs\Microsoft",
        $jdkRoot
    )
    foreach ($root in $roots) {
        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $candidates.Add((Join-Path $_.FullName "bin\java.exe"))
        }
    }
    foreach ($exe in ($candidates | Select-Object -Unique)) {
        if (-not $exe -or -not (Test-Path -LiteralPath $exe)) { continue }
        if ((Get-JavaMajor $exe) -ge 21) { return (Split-Path -Parent (Split-Path -Parent $exe)) }
    }
    return $null
}

$javaHome = Find-Java21

# JavaFX 在很舊的顯示驅動 / Windows 7 上可能起不來（D3D 管線初始化失敗）。
# --safe 改用軟體渲染，等於手動加 -Dprism.order=sw。
$safeArgs = @()
if ($Mode -match "safe") {
    $safeArgs = @("-Dprism.order=sw", "-Dprism.forceGPU=false")
}

if ($Mode -match "check") {
    if ($javaHome) { Write-Output "JAVA21=$javaHome" } else { Write-Output "JAVA21=none" }
    exit 0
}

if ($Mode -match "install") {
    & (Join-Path $dir "install-java.ps1") -Target $jdkRoot
    exit $LASTEXITCODE
}

if (-not $javaHome) {
    Add-Type -AssemblyName System.Windows.Forms
    $answer = [System.Windows.Forms.MessageBox]::Show(
        "Brisk Launcher needs Java 21 (64-bit).`n`nDownload and install it now? (~190 MB, installed into this launcher folder; your system is not modified)",
        "Brisk Launcher", [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($answer -eq [System.Windows.Forms.DialogResult]::Yes) {
        & (Join-Path $dir "install-java.ps1") -Target $jdkRoot
        $javaHome = Find-Java21
    }
    if (-not $javaHome) {
        Start-Process "https://adoptium.net/"
        exit 1
    }
}

$javaw = Join-Path $javaHome "bin\javaw.exe"
if (-not (Test-Path -LiteralPath $javaw)) { $javaw = Join-Path $javaHome "bin\java.exe" }

# console 模式：用 java.exe 前景執行，主控台（黑框）會一直留著並顯示日誌
if ($Mode -match "console") {
    Write-Host "Java  : $javaHome"
    Write-Host "Module: com.brisklauncher.mcl/com.brisklauncher.mcl.MclApplication"
    Write-Host "啟動中（關閉本視窗即結束啟動器）…"
    $javaExe = Join-Path $javaHome "bin\java.exe"
    $consoleArgs = @(
        "-Xms16m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
        "-Djavafx.cachedir=$dir\data\native\javafx",
        "-Djna.tmpdir=$dir\data\native\jna",
        "--module-path", (Join-Path $dir "lib"),
        "--module", "com.brisklauncher.mcl/com.brisklauncher.mcl.MclApplication"
    ) + $safeArgs + $args
    & $javaExe @consoleArgs
    Write-Host ""
    Write-Host ("Brisk Launcher 已結束（exit=" + $LASTEXITCODE + "）")
    Read-Host "按 Enter 關閉視窗" | Out-Null
    exit $LASTEXITCODE
}

$launcherArgs = @(
    # 跟 bin/*.bat 完全一致的記憶體上限：不設的話 JVM 會用「實體記憶體 1/4」，
    # 32GB 機器就是 8GB 上限，工作管理員看到 900MB+ 一點都不奇怪（實測就是這樣）。
    "-Xms16m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
    "-XX:ReservedCodeCacheSize=48m",
    "-Djavafx.cachedir=$env:LOCALAPPDATA\BriskLauncher\native\javafx",
    "-Djna.tmpdir=$env:LOCALAPPDATA\BriskLauncher\native\jna",
    "--module-path", (Join-Path $dir "lib"),
    "--module", "com.brisklauncher.mcl/com.brisklauncher.mcl.MclApplication"
) + $safeArgs
Start-Process -FilePath $javaw -ArgumentList $launcherArgs -WorkingDirectory $dir
exit 0
