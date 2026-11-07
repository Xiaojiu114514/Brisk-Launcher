# Build the sample plugin with nothing but a JDK (no Gradle / Maven needed here).
#
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Install
#
# Requires: JAVA_HOME -> JDK 21, and the launcher must have been built once
# (target\classes from `mvn -o compile`, or build\install\brisk-launcher\lib from
#  `gradle installDist`).
param(
    [switch]$Install
)

$ErrorActionPreference = "Stop"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = (Resolve-Path (Join-Path $here "..\..")).Path

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. Point it at a JDK 21 installation."
}
$javac = Join-Path $env:JAVA_HOME "bin\javac.exe"
$jarTool = Join-Path $env:JAVA_HOME "bin\jar.exe"

$lib = Join-Path $project "build\install\brisk-launcher\lib"
$classes = Join-Path $project "target\classes"
if (-not (Test-Path $lib) -and -not (Test-Path $classes)) {
    throw "Build the launcher first: 'mvn -o compile' (target\classes) or 'gradle installDist' (build\install)."
}

# Prefer freshly compiled classes, then the API jars that ship with the launcher.
$cp = ""
if (Test-Path $classes) { $cp = $classes }
if (Test-Path $lib) {
    $cp = if ($cp) { $cp + ";" + (Join-Path $lib "*") } else { Join-Path $lib "*" }
}

$out = Join-Path $here "out"
Remove-Item -Force -Recurse $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory $out | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $here "src") | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 21 -d $out -cp $cp @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# plugin.json and i18n/ must sit at the jar root.
Copy-Item (Join-Path $here "plugin.json") $out
Copy-Item -Recurse (Join-Path $here "i18n") $out

$target = Join-Path $here "hello-plugin.jar"
& $jarTool --create --file $target -C $out .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "Built: $target"
if ($Install) {
    $plugins = Join-Path $project "data\plugins"
    New-Item -ItemType Directory -Force $plugins | Out-Null
    Copy-Item $target $plugins -Force
    Write-Host "Installed to: $plugins (click Refresh on the Tools page)"
}
