@echo off
setlocal
chcp 65001 >nul 2>&1
set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
cd /d "%~dp0."

if "%SKIP_BUILD%"=="1" goto run

set "MODULE="
set "NEXT_IS_MODULE="
for %%A in (%*) do (
  if /i "%%~A"=="-pl" (
    set "NEXT_IS_MODULE=1"
  ) else if defined NEXT_IS_MODULE (
    set "MODULE=%%~A"
    set "NEXT_IS_MODULE="
  )
)

if defined MODULE (
  echo [build] Installing %MODULE% and dependencies...
  call mvnw.cmd install -pl %MODULE% -am -DskipTests -q
) else (
  echo [build] Installing all modules...
  call mvnw.cmd install -DskipTests -q
)
if errorlevel 1 (
  echo BUILD FAILED - check errors above.
  pause
  exit /b 1
)

:run
call mvnw.cmd exec:java %*

