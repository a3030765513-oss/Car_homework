@echo off
chcp 65001 >nul 2>&1
set PROJ=%~dp0
cd /d "%PROJ%"

echo ============================================
echo   CarHomework - Start All Modules
echo   Path: %PROJ%
echo ============================================
echo.

echo [0/9] Waiting for Redis and RabbitMQ...
call :wait_port 6379
call :wait_http http://localhost:15672/api/overview
echo       Infrastructure ready.
echo.

echo [1/9] TaskConfigurator...
start "TaskConfigurator" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl task-configurator "-Dexec.mainClass=com.substation.taskconfigurator.TaskConfiguratorMain"
ping -n 8 127.0.0.1 >nul

echo [2/9] Navigator...
start "Navigator" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl navigator "-Dexec.mainClass=com.substation.navigator.NavigatorMain"

echo [3/9] TargetPlanner...
start "TargetPlanner" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl target-planner "-Dexec.mainClass=com.substation.targetplanner.TargetPlannerMain"
ping -n 5 127.0.0.1 >nul

echo [4/9] StrategySupervisor...
start "StrategySupervisor" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl strategy-supervisor "-Dexec.mainClass=com.substation.strategysupervisor.StrategySupervisorMain"
ping -n 5 127.0.0.1 >nul

echo [5/9] Car001...
start "Car001" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car001"

echo [6/9] Car002...
start "Car002" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car002"

echo [7/9] Car003...
start "Car003" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car003"
ping -n 5 127.0.0.1 >nul

echo [8/9] Display...
start "Display" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl display "-Dexec.mainClass=com.substation.display.DisplayMain"
ping -n 8 127.0.0.1 >nul

echo [9/9] Controller (last)...
start "Controller" /d "%PROJ%" cmd /k .\mvnw.cmd exec:java -pl controller "-Dexec.mainClass=com.substation.controller.ControllerMain"

echo.
echo All 9 modules started.
echo Open http://localhost:8887
echo.
echo Check: TaskConfigCmd queue should have exactly 1 consumer.
echo       http://localhost:15672  guest/guest
echo.
pause
goto :eof

:wait_port
powershell -NoProfile -Command "$p=%1; $ok=$false; for($i=0;$i -lt 30;$i++){ if((Test-NetConnection -ComputerName localhost -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded){ $ok=$true; break }; Start-Sleep 1 }; if(-not $ok){ Write-Host 'ERROR: port' $p 'not ready'; exit 1 }"
if errorlevel 1 exit /b 1
goto :eof

:wait_http
powershell -NoProfile -Command "$u='%1'; $ok=$false; for($i=0;$i -lt 30;$i++){ try { $r=Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 2 -Headers @{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest')))}; if($r.StatusCode -eq 200){ $ok=$true; break } } catch {}; Start-Sleep 1 }; if(-not $ok){ Write-Host 'ERROR: RabbitMQ not ready at' $u; exit 1 }"
if errorlevel 1 exit /b 1
goto :eof
