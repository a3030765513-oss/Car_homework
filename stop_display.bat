@echo off
chcp 65001 >nul 2>&1
echo Stopping Display on ports 8887 / 8888...

set "KILLED=0"
for /f "tokens=5" %%P in ('netstat -ano ^| findstr ":8887" ^| findstr "LISTENING"') do (
  taskkill /PID %%P /F >nul 2>&1
  if not errorlevel 1 (
    echo   Killed PID %%P
    set "KILLED=1"
  )
)

if "%KILLED%"=="0" (
  echo   No Display process found on port 8887.
) else (
  echo   Display stopped.
)

pause

