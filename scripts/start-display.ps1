# Person D：Display（须先确保本机 SQL Server 已运行）
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person D / Display =========="
Show-InfraSummary -Config $config

if ($config.role -ne "display") {
    Write-Warning "当前 role=$($config.role)，建议 Person D 使用 role=display（可运行 setup-config.ps1 -Role display）"
}

Start-ModuleWindow -Title "Display" -ProjectRoot $projectRoot `
    -ModuleName "display" -MainClass "com.substation.display.DisplayMain"

$httpPort = if ($config.displayHttpPort) { $config.displayHttpPort } else { 8887 }
Write-Host "Display 已启动。本机访问: http://localhost:$httpPort"
Write-Host "他人观看（需防火墙放行）: http://$($config.displayHost):$httpPort"
