@echo off
chcp 65001 >nul
title 变电站巡检仿真系统
echo ========================================
echo   变电站巡检仿真系统 - 一键启动
echo ========================================
echo.

:: 检查 Java
java -version 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 未找到 Java，请安装 JDK 17+
    pause
    exit /b 1
)

:: 检查 Redis
echo [检查] Redis (6379)...
redis-cli ping >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN]  Redis 未响应，请先启动 Redis 服务
)

:: 检查 RabbitMQ
echo [检查] RabbitMQ (5672)...
curl -s http://localhost:15672 >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN]  RabbitMQ 未响应，请先启动 RabbitMQ 服务
)

echo.

:: 编译全部模块
echo [1/2] 编译全部模块...
call mvnw.cmd compile -q
if %errorlevel% neq 0 (
    echo [ERROR] 编译失败
    pause
    exit /b 1
)
echo       编译成功

:: 打包 launcher（含全部依赖的胖 JAR）
echo [2/2] 打包 Launcher...
call mvnw.cmd package -pl launcher -am -q -DskipTests
if %errorlevel% neq 0 (
    echo [ERROR] 打包失败
    pause
    exit /b 1
)
echo       打包成功

echo.
echo ========================================
echo   启动全部模块...
echo   按 Ctrl+C 停止所有模块
echo ========================================
echo.

java -jar launcher\target\launcher-1.0-SNAPSHOT.jar

pause
