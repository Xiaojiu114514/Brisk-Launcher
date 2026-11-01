# Download Temurin JDK 21 (Windows x64) and unpack it into <launcher>\data\jdk\21
# Called by "Brisk Launcher Debug.cmd --install"
#
# 安全审计 M-07：以前是 Invoke-WebRequest -> Expand-Archive，全程没有校验。
# 现在从 Adoptium API 拿 SHA-256，下完先算 Get-FileHash 比对，不一致就退出。
param(
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$metaUrl = "https://api.adoptium.net/v3/assets/latest/21/hotspot" +
           "?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"

try {
    New-Item -ItemType Directory -Force -Path $Target | Out-Null
    $zip = Join-Path ([System.IO.Path]::GetTempPath()) "brisk-jdk21.zip"

    Write-Host "Resolving Java 21 package + checksum ..."
    $expectedSha = $null
    $downloadUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
    try {
        $meta = Invoke-RestMethod -UseBasicParsing -Uri $metaUrl
        $pkg = $meta[0].binary.package
        if ($pkg) {
            if ($pkg.link) { $downloadUrl = $pkg.link }
            if ($pkg.checksum) { $expectedSha = ($pkg.checksum -replace '[^0-9a-fA-F]', '').ToLower() }
        }
    } catch {
        Write-Host ("could not resolve checksum metadata: " + $_.Exception.Message)
    }

    Write-Host "Downloading Java 21 ..."
    Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $zip

    if ($expectedSha) {
        Write-Host "Verifying SHA-256 ..."
        $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
        if ($actual -ne $expectedSha) {
            Remove-Item $zip -Force -ErrorAction SilentlyContinue
            throw "checksum mismatch (expected $expectedSha, got $actual) - download aborted"
        }
    } else {
        # 拿不到官方摘要时不要静默安装：明确告诉用户这次没有校验
        Write-Host "WARNING: no official checksum available, the download was NOT verified."
    }

    Write-Host "Extracting ..."
    $temp = Join-Path $Target "_unpack"
    if (Test-Path $temp) { Remove-Item $temp -Recurse -Force }
    Expand-Archive -LiteralPath $zip -DestinationPath $temp -Force
    # zip 里通常多一层 jdk-21.x.y-<hash> 目录，把它剥掉
    $inner = Get-ChildItem $temp -Directory | Where-Object {
        Test-Path (Join-Path $_.FullName "bin\java.exe")
    } | Select-Object -First 1
    if ($null -eq $inner) {
        throw "downloaded archive does not contain bin\java.exe"
    }
    $final = Join-Path $Target "21"
    if (Test-Path $final) { Remove-Item $final -Recurse -Force }
    Move-Item $inner.FullName $final
    Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path (Join-Path $final "bin\java.exe"))) {
        throw "java.exe missing after extraction"
    }
    Write-Host "Java 21 installed: $final"
    exit 0
} catch {
    Write-Host ("install failed: " + $_.Exception.Message)
    exit 1
}
