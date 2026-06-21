# SQL Server 用户管理系统 — 设计文档

> 文档版本：v1.0  
> 编写日期：2026-06-18  
> 适用系统：变电站巡检仿真系统  

---

## 一、概述

将用户存储从 Redis Hash 迁移到 **SQL Server 2022 (Docker)**，并新增管理员用户管理功能：

1. Redis 用户存储 → SQL Server（用户表 + 会话仍保留在 Redis）
2. 新增注册审核机制（注册 → 待审核 → 管理员通过/拒绝）
3. 新增操作日志（记录所有用户登录后的操作）
4. 管理员用户管理界面（查看/搜索/筛选/重置密码）

---

## 二、环境配置

### 2.1 Docker 部署 SQL Server

在 WSL 中执行：

```bash
# 创建数据目录
mkdir -p /rtsy/sqlserver/data

# 启动 SQL Server 2022 容器
docker run -d --name sqlserver \
  -e "ACCEPT_EULA=Y" \
  -e "MSSQL_SA_PASSWORD=Root@1234" \
  -p 1433:1433 \
  -v /rtsy/sqlserver/data:/var/opt/mssql/data \
  mcr.microsoft.com/mssql/server:2022-latest

# 创建 root 登录用户
docker exec -it sqlserver /opt/mssql-tools/bin/sqlcmd \
  -S localhost -U sa -P 'Root@1234' \
  -Q "CREATE LOGIN root WITH PASSWORD='Root@1234'; ALTER SERVER ROLE sysadmin ADD MEMBER root;"
```

> ⚠ **注意事项**：SQL Server 密码策略要求至少8位且含大小写字母+数字+特殊字符。`1234` 不符合要求，使用 `Root@1234` 替代。JDBC 连接时使用 `root`/`Root@1234`。

### 2.2 Maven 依赖

在父 POM 的 `<dependencyManagement>` 中添加：

```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.8.1.jre11</version>
</dependency>
```

`common/pom.xml` 中引用：
```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
</dependency>
```

### 2.3 JDBC 连接信息

| 参数 | 值 |
|------|------|
| JDBC URL | `jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true` |
| 用户名 | `root` |
| 密码 | `Root@1234` |
| 驱动类 | `com.microsoft.sqlserver.jdbc.SQLServerDriver` |

---

## 三、数据库设计

### 3.1 建表 SQL

```sql
-- 创建数据库
CREATE DATABASE CarHomework;
GO
USE CarHomework;
GO

-- ==================== 用户表（含三种角色） ====================
CREATE TABLE users (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    username    NVARCHAR(50)  NOT NULL UNIQUE,
    password    NVARCHAR(200) NOT NULL,          -- BCrypt 哈希
    role        NVARCHAR(20)  NOT NULL DEFAULT 'simulator',  -- admin/simulator/analyst
    display_name NVARCHAR(50) NULL,
    status      NVARCHAR(20)  NOT NULL DEFAULT 'active',     -- active/disabled
    created_at  DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);

-- ==================== 注册申请表 ====================
CREATE TABLE registration_requests (
    id           INT IDENTITY(1,1) PRIMARY KEY,
    username     NVARCHAR(50)  NOT NULL,
    password     NVARCHAR(200) NOT NULL,          -- BCrypt 哈希
    role         NVARCHAR(20)  NOT NULL,           -- simulator/analyst（管理员不可自注册）
    display_name NVARCHAR(50) NULL,
    status       NVARCHAR(20)  NOT NULL DEFAULT 'pending',   -- pending/approved/rejected
    reviewed_by  NVARCHAR(50) NULL,               -- 审核人（管理员用户名）
    review_time  DATETIME2     NULL,
    created_at   DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);

-- ==================== 操作日志表 ====================
CREATE TABLE operation_logs (
    id          INT IDENTITY(1,1) PRIMARY KEY,
    username    NVARCHAR(50)  NOT NULL,
    action      NVARCHAR(50)  NOT NULL,           -- LOGIN / LOGOUT / REGISTER / START_TASK / RESET / etc.
    target      NVARCHAR(200) NULL,               -- 操作对象
    details     NVARCHAR(500) NULL,               -- 操作详情
    ip_address  NVARCHAR(50)  NULL,               -- 客户端 IP
    created_at  DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME()
);
```

### 3.2 三种角色区别（通过 role 字段区分）

| 角色 | role 值 | 注册方式 | 用户管理可见 |
|------|---------|---------|-------------|
| 管理员 | `admin` | 仅系统预设 | 不可见（自己看不到自己） |
| 仿真员 | `simulator` | 注册+审核 | ✅ 管理员可见 |
| 统计分析员 | `analyst` | 注册+审核 | ✅ 管理员可见 |

> 三种角色存于同一张 `users` 表，用 `role` 字段区分，便于统一查询和管理。

### 3.3 预设管理员账号

系统启动时通过 SQL 保证管理员存在（幂等）：

```sql
IF NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin')
  INSERT INTO users (username, password, role, display_name)
  VALUES ('admin', '$2a$10$...bcrypt_hash...', 'admin', '管理员');
```

预设账号：

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| `admin` | `admin123` | admin | 管理员 |
| `simulator1` | `sim123` | simulator | 仿真员 |
| `analyst1` | `ana123` | analyst | 统计分析员 |

---

## 四、注册审核流程

```
普通用户
  │
  │ POST /api/auth/register
  ▼
┌──────────────────────────────────────┐
│  检查 registration_requests 表中      │
│  是否有同名的 pending 记录             │
│  ┌─────────────────────────────────┐ │
│  │ 有 pending → 返回"该账号审核中"   │ │
│  │ 没有 → INSERT 待审核记录          │ │
│  └─────────────────────────────────┘ │
└──────────────────────────────────────┘
  │
  │ 管理员登录 → 用户管理界面
  ▼
┌──────────────────────────────────────┐
│  查看 registration_requests          │
│  状态=pending 的申请列表              │
│                                      │
│  [通过] → 移到 users 表              │
│  [拒绝] → 更新状态为 rejected         │
└──────────────────────────────────────┘
```

---

## 五、管理员用户管理 API

**Base Path**: `/api/admin/users`

所有接口需要 admin 角色：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users` | 查询用户列表（支持搜索/筛选） |
| GET | `/api/admin/users/{username}` | 查看单个用户详情 |
| POST | `/api/admin/users/{username}/reset-password` | 重置密码为 123456 |
| GET | `/api/admin/registrations` | 查看注册申请列表（只显示 simulator/analyst） |
| POST | `/api/admin/registrations/{id}/approve` | 通过注册申请 |
| POST | `/api/admin/registrations/{id}/reject` | 拒绝注册申请 |

### 接口详情

**GET /api/admin/users**

查询参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| search | string | 否 | 搜索用户名或显示名称 |
| role | string | 否 | 筛选角色 simulator / analyst |
| page | int | 否 | 页码，默认1 |
| size | int | 否 | 每页条数，默认20 |

响应：
```json
{
  "success": true,
  "data": [
    {
      "username": "simulator1",
      "role": "simulator",
      "displayName": "仿真员1",
      "status": "active",
      "createdAt": "2026-06-15T10:30:00"
    }
  ],
  "total": 5,
  "page": 1,
  "size": 20
}
```

**POST /api/admin/users/{username}/reset-password**

响应：
```json
{
  "success": true,
  "message": "密码已重置为 123456"
}
```

**GET /api/admin/registrations**

响应：
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "username": "newuser",
      "role": "simulator",
      "displayName": "新仿真员",
      "status": "pending",
      "createdAt": "2026-06-18T08:00:00"
    }
  ],
  "total": 2
}
```

**POST /api/admin/registrations/{id}/approve**

响应：
```json
{
  "success": true,
  "message": "已通过注册申请，用户 newuser 已激活"
}
```

**POST /api/admin/registrations/{id}/reject**

响应：
```json
{
  "success": true,
  "message": "已拒绝注册申请"
}
```

---

## 六、操作日志 API

**Base Path**: `/api/admin/logs`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/admin/logs` | 查询操作日志（支持筛选） | admin |

**GET /api/admin/logs**

查询参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 否 | 按用户名筛选 |
| action | string | 否 | 按操作类型筛选 |
| page | int | 否 | 页码 |
| size | int | 否 | 每页条数 |

**日志记录的操作类型**：
- `LOGIN` — 登录
- `LOGOUT` — 登出
- `REGISTER` — 注册
- `START_TASK` — 开始仿真
- `RESET` — 重置系统
- `ADD_CAR` — 添加小车
- `CHANGE_CONFIG` — 修改配置
- `CHANGE_PASSWORD` — 修改密码

---

## 七、代码结构

### 7.1 新增文件

```
common/src/main/java/com/substation/common/
├── sql/
│   ├── DatabaseManager.java          ← SQL Server 连接池管理
│   ├── SqlUserStore.java             ← SQL 用户存储（替代 Redis UserStore）
│   ├── RegistrationStore.java        ← 注册申请管理
│   ├── OperationLogStore.java        ← 操作日志管理
│   └── model/
│       ├── UserRecord.java           ← 用户表行 record
│       ├── RegistrationRecord.java   ← 注册申请表行 record
│       └── OperationLogRecord.java   ← 操作日志表行 record
├── admin/
│   ├── AdminApiHandler.java          ← 管理员 API（用户管理/注册审核）
│   └── model/
│       └── UserQueryResult.java      ← 分页查询结果 record

display/src/main/resources/web/
├── user-management.html              ← 管理员用户管理界面
└── js/
    └── user-management.js            ← 用户管理前端逻辑
```

### 7.2 修改文件

| 文件 | 修改内容 |
|------|---------|
| `pom.xml` | 添加 mssql-jdbc 依赖 |
| `common/pom.xml` | 引用 mssql-jdbc |
| `common/auth/UserStore.java` | 改为委托 SqlUserStore + 保留 BCrypt 逻辑 |
| `common/auth/AuthApiHandler.java` | register 改为写注册申请表 + 写操作日志 |
| `display/HttpFileServer.java` | 注册 `/api/admin/` 路由 |
| `display/DisplayMain.java` | 初始化 DatabaseManager + AdminApiHandler |
| `display/.../dashboard.html` | 管理员增加"用户管理"入口 |
| `display/.../login.html` | 注册时增加密码确认 |
| `display/.../js/auth.js` | 注册后提示"审核中" |

### 7.3 保留不变的系统

- `SessionManager`（Redis）— Token 会话管理仍用 Redis（TTL 优势）
- `BlackboardClient`（Redis）— 黑板数据仍用 Redis
- 所有仿真相关模块 — 不变
- MQ 消息通信 — 不变

---

## 八、前后端对比（Redis 旧方案 vs SQL Server 新方案）

| 项目 | 旧（Redis） | 新（SQL Server / Redis 混合） |
|------|-----------|---------------------------|
| 用户存储 | Redis HSET `auth:users` | SQL Server `users` 表 |
| 注册流程 | 直接写入 Redis | 写入 `registration_requests` 表，管理员审核 |
| 会话管理 | Redis `auth:session:{token}` | 不变（Redis 更合适） |
| 操作日志 | 无 | SQL Server `operation_logs` 表 |
| 用户查询 | Redis HGETALL | SQL SELECT + LIKE 搜索筛选 |
| 密码存储 | BCrypt（不变） | BCrypt（不变） |

---

## 九、实施计划

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| 1 | Docker 部署 SQL Server + 建库建表 | P0 |
| 2 | 实现 DatabaseManager（连接池） | P0 |
| 3 | 实现 SqlUserStore（替代 Redis UserStore） | P0 |
| 4 | 实现 RegistrationStore（注册申请管理） | P0 |
| 5 | 实现 OperationLogStore（操作日志） | P0 |
| 6 | 修改 AuthApiHandler（注册→审核流程） | P0 |
| 7 | 实现 AdminApiHandler（用户管理 API） | P0 |
| 8 | 编写 user-management.html + js | P0 |
| 9 | 编写测试 + 修复问题 | P1 |
| 10 | 编写操作说明文档 | P1 |
