# Person B（lyq）答辩技术总结

> **姓名**：lyq  
> **Git 分支**：`lyq_car`  
> **负责模块**：car 小车知识源 + 用户系统后端（登录/注册/管理）  
> **答辩日期**：2026-06

---

## 一、我的分工与职责

按照团队《人员分工》和《人员代码阅读指南》，我负责两大子系统：

| 子系统 | 模块 | 语言/技术 | 说明 |
|--------|------|-----------|------|
| **小车执行端** | `car` | Java 17 | 小车怎么走：五态状态机 + 原子移动 + 分布式锁 |
| **用户系统后端** | `common/auth`、`common/sql`、`common/admin` | Java + SQL Server | 账号怎么存、怎么登录注册、管理员怎么审核 |

**一句话概括**：小车怎么动 + 账号怎么登录注册存数据库。

---

## 二、子系统一：car 小车模块

### 2.1 它在系统里的位置

```
Controller (节拍调度)
    │  TICK_MOVE  (RabbitMQ: Car_Car001)
    ▼
┌─────────────────────────┐
│    Car 进程 (独立 JVM)   │
│                         │
│  CarMain   ←── 入口     │
│  CarAgent  ←── 收消息   │
│  MoveExecutor ←── 移动  │
└──────────┬──────────────┘
           │  MOVED / ROUTE_DONE / BLOCKED
           ▼
    Controller (ControllerCmd)
           │
           ├── Redis: mapView (点亮3×3)
           ├── Redis: Car001:Position (更新位置)
           ├── Redis: Car001:Steps (步数+1)
           ├── Redis: mapHeat (热力图计数)
           └── Redis: Car001:History (轨迹记录)
```

### 2.2 三个核心类

| 文件 | 行数 | 职责 |
|------|------|------|
| `car/src/main/java/com/substation/car/CarMain.java` | ~167 | 进程入口：解析 carId CLI 参数、连接 Redis/MQ、自注册、JVM shutdown hook |
| `car/src/main/java/com/substation/car/CarAgent.java` | ~77 | 消息分发器：收到 `TICK_MOVE`→调 MoveExecutor；收到 `BLOCKED_TIMEOUT`→只确认状态不写黑板 |
| `car/src/main/java/com/substation/car/MoveExecutor.java` | ~217 | **移动核心**：12 步原子操作，含分布式锁 + 3×3 点亮 + History + mapHeat |

#### CarMain 启动流程

```
1. 解析 CLI 参数: java -jar car.jar Car001 [redisPort]
2. 创建 BlackboardClient → 读取 TaskConfig 地图尺寸
3. selfRegister: 若黑板无本车数据 → 随机空地初始化
4. 连接 RabbitMQ → 声明 Car_{carId} 队列
5. 创建 CarAgent → 订阅消息
6. 注册 shutdown hook (cleanup)
7. 主线程保持存活 (while mb.isConnected)
```

**动态添加小车**：启动新进程 `CarMain Car006` 即自动注册到黑板。Controller 通过 `discoverCarIds()` 下一 tick 发现新车。

#### CarAgent 消息处理

```
handleMessage(json):
  ├── TICK_MOVE      → moveExecutor.executeMove(tick)
  ├── BLOCKED_TIMEOUT → 日志记录 + 只读确认状态（不写黑板）
  ├── 非法 JSON       → WARN 日志丢弃
  └── 未知类型        → WARN 日志丢弃
```

> 关键设计：`BLOCKED_TIMEOUT` 到达时 Controller 已完成全部黑板清理，Car 只读不写。

#### MoveExecutor 12 步原子移动

这是整个模块最核心的逻辑：

```
executeMove(tick):
  ┌─ 1. 分布式锁 lock:{carId} (SET NX PX 5000) ─ 获取失败→跳过本拍
  ├─ 2. 状态检查 == READY ─ 非READY→解锁返回
  ├─ 3. 心跳：写 Status=MOVING (防Controller误判崩溃)
  ├─ 4. peekNextRouteStep ─ 查看下一步不移除
  ├─ 5. 障碍物检测 ─ 有障碍→清路线/目标→BLOCKED→发BLOCKED消息
  ├─ 6. popNextRouteStep ─ 消费该步
  ├─ 7. 清除旧位置 mapBlock 标记
  ├─ 8. 写新位置 CarID:Position (HSET x y)
  ├─ 9. 新位置 mapBlock 标记占用
  ├─10. 3×3 点亮 mapView + HINCRBY mapHeat 热力图
  ├─11. INCR CarID:Steps + RPUSH CarID:History {x,y,tick}
  ├─12. 路径判定：
  │     ├─ RouteList 空→clearTarget→IDLE→ROUTE_DONE
  │     └─ RouteList 非空→READY→MOVED
  └─13. Lua 脚本原子释放锁
```

**3×3 点亮算法**：

```java
for (int dr = -1; dr <= 1; dr++)
    for (int dc = -1; dc <= 1; dc++) {
        int r = center.y + dr, c = center.x + dc;
        if (r >= 0 && r < mapHeight && c >= 0 && c < mapWidth) {
            bb.setMapViewBit(r, c, true);   // 探索点亮
            bb.incrementMapHeat(r, c);       // 热力图计数
        }
    }
```

### 2.3 五态状态机（Car 只写自己管辖的变迁）

```
Car 写入：
  自注册        → IDLE
  READY→MOVING  (收到TICK_MOVE)
  MOVING→READY  (移动成功，路径非空)
  MOVING→IDLE   (路径走完)
  MOVING→BLOCKED(下一步障碍)

Car 永不写 WAITING_ROUTE（那是 Controller 写的）
```

### 2.4 消息接口

| 收 | 发送方 | 队列 |
|----|--------|------|
| `TICK_MOVE` | Controller | `Car_{carId}` |
| `BLOCKED_TIMEOUT` | Controller | `Car_{carId}` |

| 发 | 接收方 | 队列 | data |
|----|--------|------|------|
| `MOVED` | Controller | ControllerCmd | `{newPosition, routeRemaining}` |
| `ROUTE_DONE` | Controller | ControllerCmd | `{finalPosition}` |
| `BLOCKED` | Controller | ControllerCmd | `{blockedPosition, blockedTick}` |

### 2.5 Redis Key 写入

| Key | 写入场景 | 操作 |
|-----|----------|------|
| `{carId}:Position` | 自注册 / 每步移动 | HSET x y |
| `{carId}:Status` | 状态变迁 | SET |
| `{carId}:Steps` | 每步移动 | INCR |
| `{carId}:BlockedTick` | 检测到障碍 | SET |
| `{carId}:History` | 每步移动 | RPUSH {x,y,tick} |
| `mapView` | 3×3 点亮 | SETBIT |
| `mapBlock` | 注册占格 + 移动去旧放新 | SETBIT |
| `mapHeat` | 3×3 点亮 | HINCRBY {row},{col} 1 |

### 2.6 分布式锁

```java
DistributedLock lock = new DistributedLock(jedisPool, carId);
if (lock.tryLock()) {             // SET lock:Car001 value NX PX 5000
    try { doMove(tick); }
    finally { lock.unlock(); }   // Lua: if get == value then del
}
```

- 超时 5 秒，失败跳过本拍不阻塞
- Lua 脚本验证 ownership 再释放，避免误删

### 2.7 测试覆盖

| 测试文件 | 测试数 | 覆盖内容 |
|----------|--------|----------|
| `car/src/test/java/com/substation/car/MoveExecutorTest.java` | 19 | 正常移动、障碍物、非READY跳过、空路径、3×3点亮含边界、History记录、热力图、分布式锁互斥 |
| `car/src/test/java/com/substation/car/CarAgentTest.java` | 6 | TICK_MOVE分发、路由完成、BLOCKED_TIMEOUT处理、非法JSON/未知消息不崩溃 |
| `car/src/test/java/com/substation/car/CarMainTest.java` | 4 | CLI参数解析、Car编号自动补全 |

**运行方式**：
```bash
mvn test -pl common,car -am
```

---

## 三、子系统二：用户系统后端

### 3.1 整体架构

```
浏览器 (Person D 前端)
    │  POST /api/auth/login
    │  POST /api/auth/register
    │  GET  /api/auth/me
    │  GET  /api/admin/*
    ▼
Display (HttpFileServer 路由转发)
    │  /api/auth/  → AuthApiHandler  (我负责)
    │  /api/admin/ → AdminApiHandler (我负责)
    ▼
┌────────────────────────────┐
│  AuthApiHandler            │ ← 我负责
│  ├── handleLogin()         │
│  ├── handleRegister()      │
│  ├── handleLogout()        │
│  ├── handleMe()            │
│  └── handleChangePassword()│
├────────────────────────────┤
│  AdminApiHandler            │ ← 我负责
│  ├── 审核注册申请           │
│  ├── 用户列表查询           │
│  └── 重置密码              │
├────────────────────────────┤
│  持久层 (common/sql)       │
│  ├── SqlUserStore           │ ← BCrypt 认证
│  ├── RegistrationStore      │ ← 注册申请待审核
│  └── OperationLogStore      │ ← 操作日志
├────────────────────────────┤
│  SQL Server                 │
│  ├── users                 │
│  ├── registration_requests │
│  └── operation_logs        │
└────────────────────────────┘
```

### 3.2 核心类与代码定位

| 功能 | 文件 | 说明 |
|------|------|------|
| **登录认证** | `common/.../auth/AuthApiHandler.java` | POST `/api/auth/login`：查 `users` 表，BCrypt 校验，生成 Redis session token |
| **注册申请** | `common/.../auth/AuthApiHandler.java` | POST `/api/auth/register`：写 `registration_requests` 表，管理员审核后才进 `users` |
| **会话管理** | `common/.../auth/SessionManager.java` | Token 生成/SHA-256 哈希，Redis `auth:session:*` 存储，支持挤出旧 session |
| **鉴权过滤** | `common/.../auth/AuthFilter.java` | HTTP 请求拦截：从 Authorization header 提取 token，校验后注入上下文 |
| **用户存储** | `common/.../sql/SqlUserStore.java` | 用户 CRUD：认证查询、密码 BCrypt 更新、列表分页搜索 |
| **注册审核** | `common/.../sql/RegistrationStore.java` | `registration_requests` 表读写，管理员审批 |
| **操作日志** | `common/.../sql/OperationLogStore.java` | 登录/注册/审核写入 `operation_logs` |
| **数据库管理** | `common/.../sql/DatabaseManager.java` | 连接池、建表幂等 SQL、默认管理员插入 |
| **管理员接口** | `common/.../admin/AdminApiHandler.java` | `/api/admin/*`：审核申请、用户列表、重置密码 |

### 3.3 登录流程详解

```
1. 前端 POST /api/auth/login {username, password}
2. Display → HttpFileServer → AuthApiHandler.handleLogin()
3. SqlUserStore.authenticate(username, password):
   a. SELECT password FROM users WHERE username=? AND status='active'
   b. BCrypt.checkpw(password, hash)
4. 成功 → SessionManager.createSession() → Redis auth:session:{token}
5. 返回 {token, username, role, displayName}
6. OperationLogStore 记录 "LOGIN"
```

### 3.4 注册审核流程（安全设计）

```
1. 用户提交注册 → registration_requests (status='pending')
2. 管理员在 user-management.html 看到待审核列表
3. 管理员点"通过" → AdminApiHandler:
   a. 从 registration_requests 取出密码哈希和角色
   b. INSERT INTO users
   c. UPDATE registration_requests status='approved'
4. 用户可以用注册账号登录了
```

### 3.5 安全机制

| 机制 | 实现 |
|------|------|
| 密码加密 | BCrypt 哈希（salt + 10 轮），不存明文 |
| 会话管理 | Redis token + SHA-256，挤出旧 session |
| SQL 注入防护 | PreparedStatement 参数化查询 |
| 管理员鉴权 | AuthFilter 验证 token + role='admin' 检查 |
| 密码强度 | 客户端 + 服务端双重 >=6 位校验 |

### 3.6 数据库表结构

| 表 | 字段 | 用途 |
|----|------|------|
| `users` | id, username, password(bcrypt), role, display_name, status, created_at | 用户主表 |
| `registration_requests` | id, username, password(bcrypt), role, display_name, status, reviewed_by, review_time | 注册审核队列 |
| `operation_logs` | id, username, action, target, details, ip_address, created_at | 操作审计 |

### 3.7 SQL Server 连接配置

```java
// DatabaseManager.java 第 23-25 行
public static final String DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true";
public static final String DB_USER = "sa";
public static final String DB_PASSWORD = "Root@1234";
```

---

## 四、我的模块如何与其他人协作

### 4.1 Car ↔ Controller (Person A)

```
Controller                        Car (我)
  │                                 │
  ├── TICK_MOVE ──────────────────→│
  │                                 │ executeMove() 12步原子操作
  │←── MOVED ──────────────────────┤
  │←── ROUTE_DONE ─────────────────┤
  │←── BLOCKED ────────────────────┤
  │                                 │
  ├── BLOCKED_TIMEOUT ────────────→│ (Car只确认，不写黑板)
```

我通过 MQ 收 `TICK_MOVE`、发 `MOVED/ROUTE_DONE/BLOCKED`。状态写入遵守 `CarStatus` 约定：我写 IDLE/READY/MOVING/BLOCKED，A 写 WAITING_ROUTE。

### 4.2 Car ↔ Navigator (Person C) — 间接

- C 的 Navigator 写 `{carId}:RouteList`（LPUSH）
- 我的 MoveExecutor 读 `{carId}:RouteList`（peek=LINDEX -1，pop=RPOP）
- 二者不直接通信，通过 Redis 黑板交换路径数据

### 4.3 Car ↔ Display (Person D) — 间接

- D 的 Display 读取我写的 `{carId}:Position`、`{carId}:Status`、`{carId}:Steps`、`{carId}:History`、`mapHeat`
- 我不直接向 D 发消息

### 4.4 用户系统 ↔ Display (Person D)

- D 做登录页 (`login.html`、`auth.js`)、用户管理页 (`user-management.html`)
- 我做后端 API (`AuthApiHandler`、`AdminApiHandler`)、数据库
- 接口契约：改 API 路径或 JSON 字段必须通知 D

---

## 五、我的关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 分布式锁超时 | SET NX PX 5000ms | 防死锁，获取失败跳过本拍不阻塞 |
| 状态写入权限 | Car 只写 IDLE/READY/MOVING/BLOCKED | 不写 WAITING_ROUTE，避免和 Controller 状态冲突 |
| 移动心跳 | 移动前写 MOVING | 防 Controller 误判 Car 崩溃而强制复位 |
| 障碍检测 | pop 前先 peek + isBlocked | 保证"先检查，再消费"，避免误走障碍格 |
| 密码存储 | BCrypt + 10 rounds salt | 防彩虹表，符合 OWASP 建议 |
| 注册审核 | 两阶段：pending → approved | 防垃圾注册，管理员手动审核 |
| Session 存储 | Redis 内存 + TTL | 重启不丢 + 支持挤出旧 session |
| 复用连接池 | `bb.getJedisPool()` | 消除 CarMain 和 BlackboardClient 各自创建 JedisPool 的浪费 |

---

## 六、代码文件清单

### Car 模块（3 个源文件 + 3 个测试文件）

| 文件 | 说明 |
|------|------|
| `car/src/main/java/com/substation/car/CarMain.java` | 进程入口，CLI 参数，自注册 |
| `car/src/main/java/com/substation/car/CarAgent.java` | 消息分发，TICK_MOVE/BLOCKED_TIMEOUT |
| `car/src/main/java/com/substation/car/MoveExecutor.java` | 12 步原子移动，分布式锁，3×3点亮 |
| `car/src/test/java/com/substation/car/MoveExecutorTest.java` | 移动器 19 个测试 |
| `car/src/test/java/com/substation/car/CarAgentTest.java` | 消息分发 6 个测试 |
| `car/src/test/java/com/substation/car/CarMainTest.java` | 入口逻辑 4 个测试 |

### 认证模块（10 个文件）

| 文件 | 说明 |
|------|------|
| `common/.../auth/AuthApiHandler.java` | 登录/注册/改密/注销 API |
| `common/.../auth/SessionManager.java` | Token 会话管理 |
| `common/.../auth/AuthFilter.java` | HTTP 鉴权过滤 |
| `common/.../auth/AuthResponses.java` | 统一错误响应 |
| `common/.../auth/UserStore.java` | 用户存储抽象接口 |
| `common/.../auth/model/LoginRequest.java` | 登录请求 record |
| `common/.../auth/model/LoginResponse.java` | 登录响应 record |
| `common/.../auth/model/UserInfo.java` | 用户信息 record |
| `common/.../auth/model/SessionInfo.java` | 会话信息 record |
| `common/.../auth/model/SessionValidation.java` | 校验结果 record |

### SQL 持久化模块（7 个文件）

| 文件 | 说明 |
|------|------|
| `common/.../sql/DatabaseManager.java` | 连接池管理 + 建表 + 默认管理员 |
| `common/.../sql/SqlUserStore.java` | 用户表 CRUD |
| `common/.../sql/RegistrationStore.java` | 注册申请管理 |
| `common/.../sql/OperationLogStore.java` | 操作日志 |
| `common/.../sql/model/UserRecord.java` | 用户记录 record |
| `common/.../sql/model/RegistrationRecord.java` | 注册记录 record |
| `common/.../sql/model/OperationLogRecord.java` | 日志记录 record |

### 管理员接口（1 个文件）

| 文件 | 说明 |
|------|------|
| `common/.../admin/AdminApiHandler.java` | 审核用户、用户列表、重置密码 |

---

## 七、测试结果

```
Car 模块:   29/29 ✅
认证/持久化层: 随 common 模块集成测试覆盖
总计贡献: 20+ 源文件, 29+ 测试用例
```

---

## 八、我参与的环境配置问题

在本地开发过程中，我解决的关键环境问题：

1. **WSL Redis 端口冲突**：WSL 内旧的 redis-server 持久占用 6379，导致 Docker 无法绑定 → 停用 systemd redis-server，改由 Docker 独占
2. **SQL Server 密码适配**：RTSY docker-compose 用 `Root@1234`，代码默认 `Hospital#2024` → 修改 `DatabaseManager.DB_PASSWORD` 对齐
3. **SQL Server 数据库初始化**：容器重启后数据库丢失 → 编写 `tools/init_db.sql` 一键重建
4. **BCrypt 2b/2a 前缀兼容**：Python bcrypt 生成 `SHA-256 → 改用 Java jbcrypt 生成 `SHA-256` 哈希写入数据库
5. **start_all.bat IPv6 卡死**：PowerShell `Test-NetConnection` 默认走 `::1` → 改用 `TcpClient.Connect('127.0.0.1')` 替代
