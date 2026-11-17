param(
    [Parameter(Mandatory = $true)]
    [string]$Server,

    [Parameter(Mandatory = $true)]
    [string]$User,

    [string]$RemotePath = "/var/www/wendajia-dashboard-src"
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ArchivePath = Join-Path $env:TEMP "wendajia-dashboard.zip"

if (Test-Path $ArchivePath) {
    Remove-Item -LiteralPath $ArchivePath -Force
}

Compress-Archive -Path (Join-Path $ProjectRoot "*") -DestinationPath $ArchivePath -Force
scp $ArchivePath "$User@${Server}:$RemotePath.zip"

Write-Host ""
Write-Host "上传完成。"
Write-Host "服务器上执行下面两步即可："
Write-Host "1. unzip -o $RemotePath.zip -d $RemotePath"
Write-Host "2. sudo bash $RemotePath/deploy/install-static-site.sh '_'"
