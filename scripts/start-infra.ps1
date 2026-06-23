# Person A：Docker(Redis+MQ) + Controller（最后手动确认后起）
param(
    [switch]$SkipDocker,
    [switch]$StartControllerNow
)

. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person A / 基础设施 =========="
Show-InfraSummary -Config $config

if (-not $SkipDocker) {
    Set-Location $projectRoot
    Write-Host "启动 Docker (Redis + RabbitMQ)..."
    docker compose up -d
    docker ps
}

if ($StartControllerNow) {
    Start-ModuleWindow -Title "Controller" -ProjectRoot $projectRoot `
        -ModuleName "controller" -MainClass "com.substation.controller.ControllerMain"
    Write-Host "Controller 已启动。"
} else {
    Write-Host "等待 Person B/C/D 就绪后，执行:"
    Write-Host "  .\scripts\start-infra.ps1 -SkipDocker -StartControllerNow"
    Write-Host "或:"
    Write-Host "  .\scripts\start-controller.ps1"
}
