# 同一 WiFi 分布式联调指南（四人四机）

> **适用场景**：四人四台电脑连**同一个 WiFi / 局域网**，不依赖 Tailscale。  
> **与 Tailscale 版的区别**：把文档里的 IP 从 `100.x.x.x` 换成各人的 **WiFi 局域网 IP**（常见 `192.168.x.x` 或 `10.x.x.x`）。  
> **配置机制完全相同**：`deploy/infra.local.json` + `scripts/start-*.ps1`。

若已配置 Tailscale，请用 **`分布式联调使用指南.md`**。两份指南的启动顺序、分工、硬约束一致。

---

## 0. 一分钟速览

```
1. 四人 ipconfig 查 WiFi IP，A 发到群里
2. 每人 setup-config.ps1（角色不同，见下文）
3. A 开防火墙 + start-infra.ps1
4. C start-planner.ps1、B start-cars.ps1、D start-display.ps1
5. A 最后 start-controller.ps1
6. D 浏览器 localhost:8887 点「开始」；其他人 http://<D的IP>:8887 观看
```

**不要用 `start_all.bat` 做四人分布式**（它会在一台机器起全套）。

---

## 1. 分工速查

| 人员 | 负责什么 | 本机要起的进程 |
|------|----------|----------------|
| **Person A** | 基础设施 + 调度 | Docker（Redis + MQ）、**Controller（必须最后起）** |
| **Person B** | 小车 | Car001、Car002、Car003（可在配置里增减） |
| **Person C** | 规划知识源 | TaskConfigurator、Navigator、TargetPlanner、StrategySupervisor |
| **Person D** | 展示 | SQL Server、Display、浏览器点「开始」 |

**全组硬约束**

- 只有 **一套** Redis + RabbitMQ（在 Person A 的 Docker 里）
- 只有 **一个** Controller、**一个** Display
- 同一辆车（如 `Car001`）不能在两台电脑各起一个进程
- **全组由 Person D 点一次「开始」**，其他人只观看、不要重复下发配置

---

## 2. 查 IP 与网络自检

### 2.1 查本机 WiFi IP

每人 PowerShell 执行：

```powershell
ipconfig
```

在 **无线局域网适配器 WLAN**（或类似名称）下找到 **IPv4 地址**，例如 `192.168.1.104`。

约定命名（示例，按实际替换）：

| 角色 | 变量 | 示例 |
|------|------|------|
| Person A | **IP_A** | `192.168.1.101` |
| Person B | IP_B | `192.168.1.102` |
| Person C | IP_C | `192.168.1.103` |
| Person D | **IP_D** | `192.168.1.104` |

Person A 把 **IP_A** 发到群里；Person D 把 **IP_D** 发到群里（供他人访问页面）。

### 2.2 互通检查（B/C/D 各执行）

```powershell
ping IP_A
Test-NetConnection IP_A -Port 6379
Test-NetConnection IP_A -Port 5672
```

`TcpTestSucceeded : True` 才算 Redis/MQ 可达。

### 2.3 路由器注意

| 现象 | 处理 |
|------|------|
| ping 不通 | 检查是否连了**访客 WiFi**、是否开启 **AP 隔离 / 客户端隔离** |
| 换 WiFi 后连不上 | DHCP 可能换 IP，重新 `ipconfig` 并更新 `infra.local.json` |
| 校园/公司网 | 部分网络禁止设备互访，需换手机热点或自建路由 |

---

## 3. 首次准备（四人相同）

### 3.1 拉代码与编译

```powershell
cd D:\car_homework
git pull
.\mvnw.cmd install -DskipTests
```

### 3.2 生成本机配置（只做一次，或 IP 变更时重做）

`setup-config.ps1` 提示输入 IP 时，**填 WiFi 局域网 IP**，不要填 `127.0.0.1`。

配置写入 `deploy/infra.local.json`（已 gitignore，不会提交到 Git）。

也可跳过脚本，直接复制 `deploy/infra.remote.example.json` 为 `infra.local.json` 后手改 IP。

---

## 4. Person A（基础设施 + Controller）

### 4.1 首次配置

```powershell
cd D:\car_homework
.\scripts\setup-config.ps1 -Role infra
```

`redisHost` / `mqHost` 为 `localhost`（Docker 在本机）。

### 4.2 防火墙（管理员 PowerShell，本机做一次）

```powershell
New-NetFirewallRule -DisplayName "Redis6379" -Direction Inbound -Protocol TCP -LocalPort 6379 -Action Allow
New-NetFirewallRule -DisplayName "RabbitMQ5672" -Direction Inbound -Protocol TCP -LocalPort 5672 -Action Allow
New-NetFirewallRule -DisplayName "RabbitMQ15672" -Direction Inbound -Protocol TCP -LocalPort 15672 -Action Allow
```

### 4.3 每次联调

**第一步：起 Docker**

```powershell
cd D:\car_homework
.\scripts\start-infra.ps1
```

确认 `docker ps` 中有 redis、rabbitmq。

**第二步：把 IP_A 发到群里**，等 B/C/D 回复「已就绪」。

**第三步：最后起 Controller**

```powershell
.\scripts\start-controller.ps1
```

成功日志：`[Controller] 控制器已启动，等待任务配置...`

### 4.4 不要做的事

- 不要在 B/C/D 就绪之前起 Controller
- 不要用 `start_all.bat` 代替分布式脚本（除非本机单机联调）

---

## 5. Person B（小车）

### 5.1 首次配置

将 `192.168.1.101` 换成 Person A 的 **IP_A**：

```powershell
cd D:\car_homework
.\scripts\setup-config.ps1 -Role car -InfraHost 192.168.1.101
```

交互模式下，提示输入 IP 时填 **IP_A** 即可（脚本文案可能写 Tailscale，填局域网 IP 同样有效）。

### 5.2 每次联调

```powershell
.\scripts\start-cars.ps1
```

默认按配置启动 Car001～Car003。群里回复：**「小车已就绪」**。

### 5.3 自检

Car 窗口日志应出现连向 **IP_A:6379**，而不是 `localhost`。  
本机**不要**自己 `docker compose` 起 Redis。

### 5.4 调整车数

编辑 `deploy/infra.local.json` 的 `cars` 数组后重新运行 `start-cars.ps1`。

---

## 6. Person C（规划模块）

### 6.1 首次配置

```powershell
cd D:\car_homework
.\scripts\setup-config.ps1 -Role planner -InfraHost 192.168.1.101
```

### 6.2 每次联调

```powershell
.\scripts\start-planner.ps1
```

会打开 4 个窗口。群里回复：**「规划模块已就绪」**。

### 6.3 扩展 Navigator（可选）

```powershell
.\scripts\start-navigator.ps1 -Count 2
```

该机器也需正确的 `infra.local.json`（`redisHost` / `mqHost` 指向 IP_A）。

---

## 7. Person D（Display + 操作浏览器）

### 7.1 首次配置

```powershell
cd D:\car_homework
.\scripts\setup-config.ps1 -Role display -InfraHost 192.168.1.101 -DisplayHost 192.168.1.104
```

- `-InfraHost`：Person A 的 **IP_A**
- `-DisplayHost`：本机 WiFi IP **IP_D**（供他人浏览器访问）

### 7.2 防火墙（管理员 PowerShell，本机做一次）

```powershell
New-NetFirewallRule -DisplayName "Display8887" -Direction Inbound -Protocol TCP -LocalPort 8887 -Action Allow
New-NetFirewallRule -DisplayName "DisplayWS8888" -Direction Inbound -Protocol TCP -LocalPort 8888 -Action Allow
```

### 7.3 每次联调

**先确保 SQL Server 已启动**（登录依赖它）。

```powershell
cd D:\car_homework
.\scripts\start-display.ps1
```

群里回复：**「Display 已就绪」**。

Person A 起好 Controller 后，D 在本机浏览器打开：

```text
http://localhost:8887
```

登录 → 仿真页 → **点「开始」**。

### 7.4 让别人同步观看

| 谁 | 地址 |
|----|------|
| Person D（操作） | `http://localhost:8887` |
| A / B / C / 其他人 | `http://IP_D:8887` |

Unity 3D 视图在分布式下依赖 `unity/ws-redirect.js` 将 WebSocket 指向 D 的主机名，一般无需额外配置。

### 7.5 Display 启动失败

若出现 `ClassNotFoundException: DisplayMain`，说明 Maven 在根项目执行了 `exec:java`。请：

1. `git pull` 获取最新的 `scripts/_common.ps1` 修复
2. 或手动执行：

```cmd
cd /d D:\car_homework
.\mvnw.cmd -pl display -am compile -q
.\mvnw.cmd -pl display exec:java -Dexec.mainClass=com.substation.display.DisplayMain
```

---

## 8. 全组启动顺序

```
Person A   setup-config(-Role infra) → start-infra.ps1（Docker）
Person C   setup-config(-Role planner) → start-planner.ps1
Person B   setup-config(-Role car)     → start-cars.ps1      ← 可与 C 并行
Person D   setup-config(-Role display) → start-display.ps1
           → 群里确认 B/C/D 已就绪
Person A   start-controller.ps1                              ← 必须最后
Person D   浏览器点「开始」
其他人     http://IP_D:8887 观看
```

---

## 9. 配置文件说明（`deploy/infra.local.json`）

| 字段 | 含义 | Person A | Person B/C/D |
|------|------|----------|--------------|
| `redisHost` | Redis 地址 | `localhost` | **IP_A**（WiFi IP） |
| `mqHost` | RabbitMQ 地址 | `localhost` | **IP_A** |
| `redisPort` / `mqPort` | 端口 | 6379 / 5672 | 一般不改 |
| `role` | 角色 | `infra` | `car` / `planner` / `display` |
| `displayHost` | Display 机器 IP | `localhost` | **IP_D**（D 的 WiFi IP） |
| `displayHttpPort` / `displayWsPort` | 页面 / WS | 8887 / 8888 | 一般不改 |
| `cars` | 小车列表 | 可忽略 | **Person B** 按此启动 |

**B 机示例**（IP_A=`192.168.1.101`，IP_D=`192.168.1.104`）：

```json
{
  "redisHost": "192.168.1.101",
  "redisPort": 6379,
  "mqHost": "192.168.1.101",
  "mqPort": 5672,
  "role": "car",
  "displayHost": "192.168.1.104",
  "displayHttpPort": 8887,
  "displayWsPort": 8888,
  "cars": ["Car001", "Car002", "Car003"]
}
```

命令行 `--redis-host` 等可覆盖配置文件（高级用法）。

---

## 10. 脚本一览

| 脚本 | 谁用 |
|------|------|
| `scripts/setup-config.ps1` | 四人首次各跑一次（参数不同） |
| `scripts/start-infra.ps1` | Person A |
| `scripts/start-controller.ps1` | Person A（最后） |
| `scripts/start-planner.ps1` | Person C |
| `scripts/start-cars.ps1` | Person B |
| `scripts/start-display.ps1` | Person D |
| `scripts/start-navigator.ps1` | 任意（扩算力） |

`setup-config.ps1` 常用参数：

```text
-Role infra|planner|car|display
-InfraHost <IP_A>          # B/C/D 填 A 的 WiFi IP
-DisplayHost <IP_D>        # display 角色填 D 的 WiFi IP
```

---

## 11. 联调检查（30 秒）

| 检查项 | 命令 / 现象 |
|--------|-------------|
| 能 ping 通 A | `ping IP_A` |
| 远程 Redis 通 | `Test-NetConnection IP_A -Port 6379` |
| A 的 Docker | `docker ps` 有 redis、rabbitmq |
| B 的车 | 3 个 Car 窗口，日志为 `IP_A:6379` |
| C 的模块 | 4 个窗口无报错 |
| D 的页面 | `http://localhost:8887` 能打开 |
| Controller 唯一 | 仅 A 一台，且最后起 |
| 仿真跑起来 | D 点开始后小车动、探索率涨 |
| 他人能看 | `http://IP_D:8887` 可打开 |

---

## 12. 常见问题

| 现象 | 处理 |
|------|------|
| 缺少 `infra.local.json` | 运行 `setup-config.ps1` 或复制 `infra.remote.example.json` |
| ping 不通 | 同一 WiFi、关 AP 隔离、勿用访客网络 |
| 连不上 Redis | 检查 IP_A、A 防火墙、A 的 Docker |
| Controller 秒退 | 全组只保留 A 上一个 Controller |
| 车不动 | A 最后起 Controller；B 车已起且连 IP_A |
| 登录失败 | D 本机 SQL Server 未启动 |
| Display ClassNotFound | 见 §7.5；确保 `-pl display` |
| 3D 视图连不上 WS | D 防火墙放行 8888；浏览器强刷 |
| 联调后单机跑不起来 | `setup-config -Role infra` + `docker compose up -d` + `start_all.bat` |

---

## 13. 联调结束后恢复单机

在需要单机全套的机器上：

```powershell
cd D:\car_homework
.\scripts\setup-config.ps1 -Role infra
docker compose up -d
.\start_all.bat
```

---

## 14. 相关文档

| 文件 | 说明 |
|------|------|
| `分布式联调使用指南.md` | Tailscale 版（逻辑相同，IP 为 `100.x.x.x`） |
| `四台分布式启动流程.md` | 更细的防火墙、附录命令 |
| `分布式部署指南.md` | 原理、多 Navigator、Launcher |
| `多人同步观看网页教程.md` | 多浏览器观看 Display |
| `deploy/infra.remote.example.json` | 远程联调配置模板 |

---

*文档版本：2026-06-23*
