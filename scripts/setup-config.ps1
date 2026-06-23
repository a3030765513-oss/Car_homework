# 首次配置：从模板生成 deploy/infra.local.json（IP 只需输入一次，写入文件后永久生效）
param(
    [switch]$Remote,
    [string]$InfraHost = "",
    [ValidateSet("infra", "planner", "car", "display")]
    [string]$Role = "",
    [string]$DisplayHost = ""
)

. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$localPath = Get-DeployConfigPath -ProjectRoot $projectRoot

$isRemoteRole = $Role -in @("planner", "car", "display")
if ($isRemoteRole) {
    $Remote = $true
}

if ($Role -eq "infra") {
    $Remote = $false
}

if ($Remote -and $InfraHost -eq "" -and (Test-Path $localPath)) {
    $existing = Get-Content $localPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($existing.redisHost -and $existing.redisHost -ne "localhost") {
        Write-Host "检测到已有配置 redisHost=$($existing.redisHost)"
        $reuse = Read-Host "直接回车沿用，或输入新的 Person A IP"
        if ($reuse -ne "") {
            $InfraHost = $reuse.Trim()
        } else {
            $InfraHost = $existing.redisHost
        }
    }
}

if ($Remote -and $InfraHost -eq "") {
    $InfraHost = (Read-Host "请输入 Person A 的 Tailscale IP（只需配置一次，会写入 deploy/infra.local.json）").Trim()
}

if ($Remote -and $InfraHost -eq "") {
    Write-Error "未提供基础设施 IP，无法生成远程配置"
    exit 1
}

if ($Role -eq "display" -and $DisplayHost -eq "") {
    $detectedIp = ""
    try {
        $detectedIp = (tailscale ip -4 2>$null | Select-Object -First 1).Trim()
    } catch { }
    if ($detectedIp -ne "") {
        Write-Host "检测到本机 Tailscale IP: $detectedIp"
        $displayInput = Read-Host "作为 displayHost 直接回车确认，或输入其他 IP"
        $DisplayHost = if ($displayInput -ne "") { $displayInput.Trim() } else { $detectedIp }
    } else {
        $DisplayHost = (Read-Host "请输入本机 Tailscale IP（供他人浏览器访问 Display）").Trim()
    }
}

$templateName = if ($Remote) { "infra.remote.example.json" } else { "infra.example.json" }
$templatePath = Join-Path $projectRoot "deploy\$templateName"

if (-not (Test-Path $templatePath)) {
    Write-Error "缺少模板: $templatePath"
    exit 1
}

Copy-Item $templatePath $localPath -Force
$config = Get-Content $localPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($Remote) {
    $config.redisHost = $InfraHost
    $config.mqHost = $InfraHost
}

if ($Role -ne "") {
    $config.role = $Role
}

if ($DisplayHost -ne "") {
    $config.displayHost = $DisplayHost
}

$config | ConvertTo-Json -Depth 4 | Set-Content $localPath -Encoding UTF8

Write-Host ""
Write-Host "已写入: $localPath"
Show-InfraSummary -Config $config
Write-Host "之后每次联调只需运行 start-*.ps1，模块会自动读此文件，不用再输入 IP。"
