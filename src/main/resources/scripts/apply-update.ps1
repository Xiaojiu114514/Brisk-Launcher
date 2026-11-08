# Brisk Launcher - automatic overwrite update helper.
#
# Windows does not let a running launcher replace its own exe/jar files, so the
# real work happens here, in a short-lived PowerShell process started right
# before the launcher exits:
#
#   1. wait until the launcher process (our pid) is really gone
#   2. wait until the launcher files are no longer locked
#   3. back up everything that is about to be replaced (data/ is never touched)
#   4. copy the staged files over the installation directory
#   5. mirror lib/ and app/ so stale jars never show up twice on the module path
#   6. on any failure: restore the backup, write FAILED, restart the old version
#   7. on success: write OK + installed version, delete the staging folder,
#      prune old backups and restart the launcher
#
# Keep this file ASCII-only: it is written out and parsed by Windows PowerShell
# 5.1, whose default code page is not UTF-8 on every machine.

param(
  [int]$LauncherPid = 0,
  [string]$Source = "",
  [string]$Target = "",
  [string]$Backup = "",
  [string]$RestartKind = "",
  [string]$RestartFile = "",
  [string]$Version = "",
  [string]$Log = "",
  [string]$StatusFile = "",
  [string]$InstalledFile = ""
)

$ErrorActionPreference = "Stop"
$script:applied = $false

function Write-Log([string]$text) {
  if ([string]::IsNullOrWhiteSpace($Log)) { return }
  $line = ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $text)
  try { Add-Content -LiteralPath $Log -Value $line -Encoding UTF8 } catch { }
}

function Write-Status([string]$text) {
  if ([string]::IsNullOrWhiteSpace($StatusFile)) { return }
  try { Set-Content -LiteralPath $StatusFile -Value $text -Encoding UTF8 } catch { }
}

function Invoke-RoboCopy {
  param([string]$From, [string]$To, [string[]]$Extra)
  $arguments = @($From, $To, "/E", "/IS", "/IT", "/R:2", "/W:1", "/NFL", "/NDL", "/NP", "/NJH", "/NJS")
  if ($Extra) { $arguments += $Extra }
  & robocopy.exe @arguments | Out-Null
  $code = $LASTEXITCODE
  # robocopy: 0..7 = success (incl. "extra files" / "copied"), 8+ = real failure
  if ($code -ge 8) {
    throw ("robocopy failed (code {0}): {1} -> {2}" -f $code, $From, $To)
  }
}

function Invoke-Mirror {
  param([string]$From, [string]$To)
  if (-not (Test-Path -LiteralPath $From)) { return }
  $arguments = @($From, $To, "/MIR", "/R:2", "/W:1", "/NFL", "/NDL", "/NP", "/NJH", "/NJS")
  & robocopy.exe @arguments | Out-Null
  if ($LASTEXITCODE -ge 8) {
    throw ("robocopy mirror failed (code {0}): {1} -> {2}" -f $LASTEXITCODE, $From, $To)
  }
}

function Restart-Launcher {
  if ([string]::IsNullOrWhiteSpace($RestartFile)) {
    Write-Log "no restart command configured"
    return
  }
  try {
    Write-Log ("restart: " + $RestartKind + " " + $RestartFile)
    # Build the argument line here on purpose: passing a pre-quoted argument
    # string through "powershell -File" is split at the first space by the
    # PowerShell 5.1 binder (tested: the path was truncated).
    if ($RestartKind -eq "ps1") {
      $argline = '-NoProfile -ExecutionPolicy Bypass -File "' + $RestartFile + '"'
      Start-Process -FilePath "powershell.exe" -ArgumentList $argline -WorkingDirectory $Target | Out-Null
    } elseif ($RestartKind -eq "vbs") {
      Start-Process -FilePath "wscript.exe" -ArgumentList ('"' + $RestartFile + '"') -WorkingDirectory $Target | Out-Null
    } else {
      Start-Process -FilePath $RestartFile -WorkingDirectory $Target | Out-Null
    }
  } catch {
    Write-Log ("restart failed: " + $_.Exception.Message)
  }
}

function Test-Unlocked {
  foreach ($name in @("lib", "app", "runtime")) {
    $dir = Join-Path $Target $name
    if (-not (Test-Path -LiteralPath $dir)) { continue }
    $file = Get-ChildItem -LiteralPath $dir -File -Recurse -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($file -eq $null) { continue }
    try {
      $stream = [System.IO.File]::Open($file.FullName,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite)
      $stream.Close()
    } catch {
      return $false
    }
  }
  return $true
}

try {
  Write-Log "--- update start ---"
  Write-Log ("pid={0} version={1}" -f $LauncherPid, $Version)
  Write-Log ("source={0}" -f $Source)
  Write-Log ("target={0}" -f $Target)
  if (-not (Test-Path -LiteralPath $Target)) { throw "target directory missing" }
  if (-not (Test-Path -LiteralPath $Source)) { throw "staging directory missing" }

  # 1) wait for the launcher to exit (the pid is passed by the launcher itself)
  if ($LauncherPid -gt 0) {
    try {
      $process = Get-Process -Id $LauncherPid -ErrorAction SilentlyContinue
      if ($process -ne $null) {
        $process.WaitForExit(180000) | Out-Null
        Write-Log "launcher process exited"
      } else {
        Write-Log "launcher process already gone"
      }
    } catch {
      Write-Log ("wait for launcher failed: " + $_.Exception.Message)
    }
  }

  # 2) give Windows a moment to release file handles (antivirus / delayed close)
  $waited = 0
  while (-not (Test-Unlocked)) {
    if ($waited -ge 60000) {
      Write-Log "warning: files still look locked after 60s, trying anyway"
      break
    }
    Start-Sleep -Milliseconds 500
    $waited += 500
  }
  Write-Log ("lock wait: " + $waited + "ms")

  # 3) back up the current launcher files only.
  #    Never back up player data or regenerable content:
  #      data/       launcher settings, accounts, themes, logs
  #      minecraft/  the built-in game directory (huge, and updates never touch it)
  #      runtime/    jars extracted by the exe; recreated on the next launch
  if (Test-Path -LiteralPath $Backup) {
    Remove-Item -LiteralPath $Backup -Recurse -Force -ErrorAction SilentlyContinue
  }
  New-Item -ItemType Directory -Force -Path $Backup | Out-Null
  Invoke-RoboCopy -From $Target -To $Backup -Extra @("/XD", "data", "minecraft", "runtime", "logs", "instances")
  Write-Log "backup done (data/minecraft/runtime excluded)"

  # 4) copy the staged files over the installation
  Invoke-RoboCopy -From $Source -To $Target -Extra @("/XD", "data", "minecraft", "runtime")
  # lib/app are launcher-managed: purge stale jars so the module path never
  # contains two versions of the same module.
  Invoke-Mirror -From (Join-Path $Source "lib") -To (Join-Path $Target "lib")
  Invoke-Mirror -From (Join-Path $Source "app") -To (Join-Path $Target "app")
  Write-Log "copy done"

  # 5) sanity check: the target must still look like a launcher
  $hasLib = Test-Path -LiteralPath (Join-Path $Target "lib")
  $hasApp = Test-Path -LiteralPath (Join-Path $Target "app")
  $hasEntry = (Test-Path -LiteralPath (Join-Path $Target "launch.ps1")) -or
              (Test-Path -LiteralPath (Join-Path $Target "Brisk Launcher.exe")) -or
              (Test-Path -LiteralPath (Join-Path $Target "bin\brisk-launcher.bat"))
  # single exe build: BriskLauncher.exe + runtime\ (the exe unpacks its jars there)
  $hasSingle = (Test-Path -LiteralPath (Join-Path $Target "BriskLauncher.exe")) -and
               (Test-Path -LiteralPath (Join-Path $Target "runtime"))
  if (-not (($hasEntry -and ($hasLib -or $hasApp)) -or ($hasSingle))) {
    throw "target does not look like a launcher after copy"
  }

  $script:applied = $true
  if (-not [string]::IsNullOrWhiteSpace($InstalledFile)) {
    try { Set-Content -LiteralPath $InstalledFile -Value $Version -Encoding UTF8 } catch { }
  }
  Write-Status ("OK|" + $Version + "|" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
  Write-Log "RESULT=OK"
} catch {
  $message = $_.Exception.Message
  Write-Log ("RESULT=FAILED " + $message)
  try {
    if (Test-Path -LiteralPath $Backup) {
      Invoke-RoboCopy -From $Backup -To $Target -Extra @("/XD", "data")
      Invoke-Mirror -From (Join-Path $Backup "lib") -To (Join-Path $Target "lib")
      Invoke-Mirror -From (Join-Path $Backup "app") -To (Join-Path $Target "app")
      Write-Log "rollback done"
    }
  } catch {
    Write-Log ("rollback failed: " + $_.Exception.Message)
  }
  Write-Status ("FAILED|" + $Version + "|" + $message)
}

# 6) restart: the new version on success, the old one after a rollback
Restart-Launcher

# 7) clean up after a successful update
if ($script:applied) {
  try { Remove-Item -LiteralPath $Source -Recurse -Force -ErrorAction SilentlyContinue } catch { }
  try {
    $parent = Split-Path -Parent $Backup
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
      Get-ChildItem -LiteralPath $parent -Directory -Filter "backup-*" -ErrorAction SilentlyContinue |
        Sort-Object CreationTime -Descending |
        Select-Object -Skip 2 |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
    }
  } catch { }
}

exit 0
