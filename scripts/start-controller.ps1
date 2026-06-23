# Person A：仅启动 Controller（须最后执行）
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Controller =========="
Show-InfraSummary -Config $config

Start-ModuleWindow -Title "Controller" -ProjectRoot $projectRoot `
    -ModuleName "controller" -MainClass "com.substation.controller.ControllerMain"
