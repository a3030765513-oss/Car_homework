# Person B：小车进程（carId 列表来自 deploy/infra.local.json 的 cars）
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person B / 小车 =========="
Show-InfraSummary -Config $config

if ($config.role -ne "car") {
    Write-Warning "当前 role=$($config.role)，建议 Person B 使用 role=car（可运行 setup-config.ps1 -Role car）"
}

$carIds = @($config.cars)
if ($carIds.Count -eq 0) {
    $carIds = @("Car001", "Car002", "Car003")
}

foreach ($carId in $carIds) {
    Start-ModuleWindow -Title $carId -ProjectRoot $projectRoot `
        -ModuleName "car" -MainClass "com.substation.car.CarMain" -ExecArgs $carId
    Start-Sleep -Milliseconds 500
}

Write-Host "$($carIds.Count) 个小车进程已分窗启动。"
