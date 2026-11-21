# Download a JDK (Windows x64) and unpack it into <Target>\<Major>.
#   Target = <launcher>\data\jdk  ->  <launcher>\data\jdk\21\bin\java.exe
#
# Called by BriskBoot (single exe) and launch.ps1 (dev). Never downloads a JRE.
#
# Source order:
#   1) Oracle JDK 21.0.12 (NFTC, direct download) with a pinned official SHA-256
#      (archive files are immutable). 21.0.13+ moved to the OTN license and the
#      download page requires accepting it (direct URLs 404), so 21.0.12 is pinned.
#      JDK 25 is still NFTC and uses the /latest/ alias + its .sha256 sidecar.
#   2) Amazon Corretto JDK (stable latest + latest_sha256; 8 / 11 / 16 / 17 / 21 / 25).
#   3) Adoptium Temurin JDK (TUNA mirror first, GitHub fallback) with the API's SHA-256.
#
# Everything is logged to <launcher>\data\logs\boot\install-java.log so a failed
# auto-download can be diagnosed after the console window is gone.
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [int]$Major = 21
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Windows PowerShell 5.1 defaults to Ssl3/Tls on older .NET; Oracle/Adoptium need TLS 1.2.
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$Target = ([System.IO.Path]::GetFullPath($Target)).TrimEnd('\')
$dataDir = Split-Path -Parent $Target
$logDir = Join-Path $dataDir "logs\boot"
$cacheDir = Join-Path $dataDir "cache\java"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
$logFile = Join-Path $logDir "install-java.log"

function Write-Log([string]$Message) {
    $line = (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + "  " + $Message
    Write-Host $line
    try { Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8 } catch { }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLower()
}

# Download with one retry; returns $true only when a non-empty file landed.
function Save-File([string]$Url, [string]$Path) {
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
            Write-Log ("downloading " + $Url + " (attempt " + $attempt + ")")
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Path -TimeoutSec 1800
            if ((Test-Path -LiteralPath $Path) -and ((Get-Item -LiteralPath $Path).Length -gt 0)) {
                return $true
            }
            Write-Log "download produced an empty file"
        } catch {
            Write-Log ("download failed: " + $_.Exception.Message)
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

# Reads the official "<zip>.sha256" sidecar; returns $null when unavailable.
function Get-RemoteSha256([string]$Url) {
    try {
        $body = (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 30).Content
        # Windows PowerShell 5.1 gives byte[] when the server does not label it text/plain.
        if ($body -is [byte[]]) { $body = [System.Text.Encoding]::ASCII.GetString($body) }
        $body = [string]$body
        if ($body -match '([0-9a-fA-F]{64})') { return $Matches[1].ToLower() }
        Write-Log ("checksum response not understood: " + $Url)
    } catch {
        Write-Log ("checksum unavailable (" + $Url + "): " + $_.Exception.Message)
    }
    return $null
}

# Unpack, strip the single top-level directory, and move it to <Target>\<Major>.
function Expand-Jdk([string]$Zip) {
    $temp = Join-Path $Target ("_unpack_" + $Major)
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $temp | Out-Null
    Write-Log "extracting ..."
    Expand-Archive -LiteralPath $Zip -DestinationPath $temp -Force
    $inner = Get-ChildItem -LiteralPath $temp -Directory | Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName "bin\java.exe")
    } | Select-Object -First 1
    if ($null -eq $inner) { throw "downloaded archive does not contain bin\java.exe" }
    $final = Join-Path $Target ("$Major")
    if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Recurse -Force }
    Move-Item -LiteralPath $inner.FullName -Destination $final
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    return $final
}

# Download + SHA-256 verify + extract one package. Throws on any problem so the
# caller can move on to the next source.
function Install-Zip([string]$Label, [string]$Url, [string]$ExpectedSha, [string]$FileName) {
    if (-not $ExpectedSha) {
        throw "no SHA-256 available for " + $Url + " (refusing to install unverified bytes)"
    }
    $zip = Join-Path $cacheDir $FileName
    if (-not (Save-File $Url $zip)) {
        throw "download failed"
    }
    $actual = Get-Sha256 $zip
    if ($actual -ne $ExpectedSha.ToLower()) {
        Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
        throw ("SHA-256 mismatch (expected " + $ExpectedSha + ", got " + $actual + ")")
    }
    Write-Log ("SHA-256 verified: " + $actual)
    $final = Expand-Jdk $zip
    $java = Join-Path $final "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) { throw "java.exe missing after extraction" }
    Write-Log ("installed " + $Label + ": " + $java)
    return $java
}

Write-Log ("install-java start: major=" + $Major + " target=" + $Target)

$installed = $null

# ---------- 1) Oracle JDK ----------
try {
    if ($Major -eq 21) {
        $oracleUrl = "https://download.oracle.com/java/21/archive/jdk-21.0.12_windows-x64_bin.zip"
        # Last NFTC JDK 21 (21.0.13+ is OTN-gated). Archive files never change, so use the
        # official sidecar value we verified once instead of spending a request on it.
        $oracleSha = "167af6612931baf82d1e55dd81444b94fcae68dffa73055946843c11cd2a37e5"
        $installed = Install-Zip "Oracle JDK 21.0.12" $oracleUrl $oracleSha "jdk-21.0.12_windows-x64_bin.zip"
    } elseif ($Major -eq 25) {
        $oracleUrl = "https://download.oracle.com/java/25/latest/jdk-25_windows-x64_bin.zip"
        $oracleSha = Get-RemoteSha256 ($oracleUrl + ".sha256")
        $installed = Install-Zip "Oracle JDK 25" $oracleUrl $oracleSha "jdk-25_windows-x64_bin.zip"
    }
} catch {
    Write-Log ("Oracle JDK failed: " + $_.Exception.Message)
}

# ---------- 2) Amazon Corretto JDK (stable latest + latest_sha256) ----------
if (-not $installed) {
    try {
        $correttoName = "amazon-corretto-$Major-x64-windows-jdk.zip"
        $correttoUrl = "https://corretto.aws/downloads/latest/$correttoName"
        $correttoSha = Get-RemoteSha256 ("https://corretto.aws/downloads/latest_sha256/$correttoName")
        if (-not $correttoSha) { throw "Corretto did not provide a SHA-256" }
        $installed = Install-Zip ("Amazon Corretto " + $Major) $correttoUrl $correttoSha $correttoName
    } catch {
        Write-Log ("Corretto JDK failed: " + $_.Exception.Message)
    }
}

# ---------- 3) Adoptium Temurin JDK (mirror first, GitHub fallback) ----------
if (-not $installed) {
    try {
        $metaUrl = "https://api.adoptium.net/v3/assets/latest/$Major/hotspot" +
                   "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
        $meta = Invoke-RestMethod -UseBasicParsing -Uri $metaUrl -TimeoutSec 30
        $pkg = @($meta)[0].binary.package
        if ($null -eq $pkg) { throw "Adoptium returned no JDK package" }
        $name = $pkg.name
        if (-not $name) { throw "Adoptium returned an empty package name" }
        $sha = ($pkg.checksum -replace '[^0-9a-fA-F]', '').ToLower()
        $urls = @(
            ("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/" + $Major + "/jdk/x64/windows/" + $name),
            $pkg.link
        )
        foreach ($url in $urls) {
            if (-not $url) { continue }
            try {
                $installed = Install-Zip ("Adoptium JDK " + $Major) $url $sha $name
                break
            } catch {
                Write-Log ("Adoptium source failed (" + $url + "): " + $_.Exception.Message)
            }
        }
    } catch {
        Write-Log ("Adoptium JDK failed: " + $_.Exception.Message)
    }
}

if (-not $installed) {
    Write-Log "install failed: no download source worked"
    exit 1
}
Write-Log ("install finished: " + $installed)
exit 0
