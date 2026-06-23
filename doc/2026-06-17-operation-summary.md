# 2026-06-17 全部操作详细说明

> 分支：`lyq_car` | 操作人：刘倚岐  
> 远程仓库：https://github.com/a3030765513-oss/Car_homework.git  
> 累计推送：11 次 commit（今日）

---

## 〇、阅读指引

本文档按功能模块组织，每个模块包含：
- **涉及的文件**及完整路径
- **修改了什么**（新功能 / Bug 修复）
- **代码关键点**及设计思路
- **数据库和配置**相关说明

---

## 一、SQL Server 数据库配置

### 1.1 Docker 部署

```bash
docker run -d --name sqlserver \
  -e "ACCEPT_EULA=Y" \
  -e "MSSQL_SA_PASSWORD=Root@1234" \
  -p 1433:1433 \
  mcr.microsoft.com/mssql/server:2022-latest
```

### 1.2 数据库和表结构

```
数据库：CarHomework
JDBC 连接：jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true
用户名/密码：sa / Root@1234
```

三张表（`DatabaseManager.java` 启动时自动建表）：

```sql
users (
  id INT IDENTITY(1,1) PRIMARY KEY,
  username NVARCHAR(50) NOT NULL UNIQUE,
  password NVARCHAR(200) NOT NULL,   -- BCrypt 哈希
  role NVARCHAR(20),                  -- admin | simulator | analyst
  status NVARCHAR(20) DEFAULT 'active',
  created_at DATETIME2 DEFAULT SYSUTCDATETIME()
)

registration_requests (
  id INT IDENTITY(1,1) PRIMARY KEY,
  username NVARCHAR(50) NOT NULL,
  password NVARCHAR(200) NOT NULL,
  role NVARCHAR(20),                  -- simulator | analyst (admin 不可自注册)
  status NVARCHAR(20) DEFAULT 'pending',  -- pending | approved | rejected
  reviewed_by NVARCHAR(50) NULL,
  review_time DATETIME2 NULL,
  created_at DATETIME2 DEFAULT SYSUTCDATETIME()
)

operation_logs (
  id INT IDENTITY(1,1) PRIMARY KEY,
  username NVARCHAR(50) NOT NULL,
  action NVARCHAR(50) NOT NULL,       -- LOGIN/START_TASK/RESET/ADD_CAR 等
  target NVARCHAR(200) NULL,
  details NVARCHAR(500) NULL,
  ip_address NVARCHAR(50) NULL,
  created_at DATETIME2 DEFAULT SYSUTCDATETIME()
)
```

### 1.3 Maven 依赖

`pom.xml`（父）和 `common/pom.xml` 均添加：

```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.8.1.jre11</version>
</dependency>
<dependency>
    <groupId>org.mindrot</groupId>
    <artifactId>jbcrypt</artifactId>
    <version>0.4</version>
</dependency>
```

---

## 二、新增模块：SQL 层

### 2.1 DatabaseManager — 数据库连接管理

**文件**：`common/src/main/java/com/substation/common/sql/DatabaseManager.java`

**功能**：
- JDBC 连接管理（`DriverManager.getConnection()`）
- 启动时自动建表（`initDatabase()`，使用 `IF NOT EXISTS` 保证幂等）
- 自动创建管理员预设账号 admin/admin123（BCrypt 哈希密码）

**关键常量**：
```java
public static final String DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true";
public static final String DB_USER = "sa";
public static final String DB_PASSWORD = "Root@1234";
```

### 2.2 SqlUserStore — 用户数据存储

**文件**：`common/src/main/java/com/substation/common/sql/SqlUserStore.java`

**替代对象**：原 Redis `UserStore`（`auth/UserStore.java` 保留但不再使用）

**功能**：
- `authenticate(username, password)` → 验证登录，支持 BCrypt 密码比对
- `changePassword(username, oldPwd, newPwd)` → 修改密码
- `getUserInfo(username)` → 获取用户信息（不含密码）
- `queryUsers(search, role, page, size)` → 用户列表查询（支持模糊搜索和角色筛选，分页）
- `countUsers(search, role)` → 查询总数
- `resetPassword(username)` → 管理员重置密码为 123456（BCrypt 哈希后写入）

### 2.3 RegistrationStore — 注册申请管理

**文件**：`common/src/main/java/com/substation/common/sql/RegistrationStore.java`

**功能**：
- `hasPendingRequest(username)` → 检查是否有重名的待审核申请
- `insertRequest(username, passwordHash, role, displayName)` → 插入注册申请
- `queryRequests(status, page, size)` → 查询申请列表
- `approve(id, reviewedBy)` → 通过申请（从 registration_requests 移到 users 表）
- `reject(id, reviewedBy)` → 拒绝申请（更新 status）
- `countRequests(status)` → 查询总数

**审核流程**：前端点"通过" → `POST /api/admin/registrations/{id}/approve` → `AdminApiHandler` → `RegistrationStore.approve()` → 三步操作：SELECT 待审核记录 → INSERT 到 users 表 → UPDATE 状态为 approved

### 2.4 OperationLogStore — 操作日志管理

**文件**：`common/src/main/java/com/substation/common/sql/OperationLogStore.java`

**功能**：
- `log(username, action, target, details)` → 写入操作日志
- `log(username, action, target, details, ipAddress)` → 写入操作日志（带 IP）
- `queryLogs(username, action, page, size)` → 查询日志（支持按用户名和操作类型筛选）
- `countLogs(username, action)` → 查询总数

**时间修复**：SQL Server 使用 UTC 时间存储（SYSUTCDATETIME），查询时通过 `Timestamp.toInstant().atZone(UTC).withZoneSameInstant(Asia/Shanghai)` 转换为北京时间（UTC+8）格式化为 `yyyy-MM-dd HH:mm:ss`。

### 2.5 数据模型（model 记录类）

| 文件 | 路径 | 说明 |
|------|------|------|
| `UserRecord.java` | `common/src/main/java/com/substation/common/sql/model/` | 用户表行：username, role, displayName, status, createdAt |
| `RegistrationRecord.java` | 同上 | 注册申请表行：id, username, role, displayName, status, reviewedBy, reviewTime, createdAt |
| `OperationLogRecord.java` | 同上 | 操作日志表行：id, username, action, target, details, createdAt |

---

## 三、新增模块：管理员 API

### 3.1 AdminApiHandler

**文件**：`common/src/main/java/com/substation/common/admin/AdminApiHandler.java`

**8 个 API 接口**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users` | 用户列表（search/role/page/size 参数） |
| GET | `/api/admin/users/{username}` | 用户详情 |
| POST | `/api/admin/users/{username}/reset-password` | 重置密码为 123456 |
| GET | `/api/admin/registrations` | 注册申请列表（status/page/size 参数） |
| POST | `/api/admin/registrations/{id}/approve` | 通过注册申请 |
| POST | `/api/admin/registrations/{id}/reject` | 拒绝注册申请 |
| GET | `/api/admin/logs` | 操作日志列表（username/action/page/size 参数） |

**路径解析修复（Bug fix）**：
- 路径 `/api/admin/registrations/1/approve` split `/` 后：`[0]="" [1]="api" [2]="admin" [3]="registrations" [4]="1" [5]="approve"`
- 此前错误使用索引 3 提取 ID（取到 `"registrations"`），修正为索引 4

---

## 四、修改模块：认证系统

### 4.1 AuthApiHandler — 认证 API

**文件**：`common/src/main/java/com/substation/common/auth/AuthApiHandler.java`

**修改内容**：
- 移除 Redis UserStore 依赖，改用 `SqlUserStore` + `RegistrationStore` + `OperationLogStore`
- `handleRegister()`：注册不再直接写入 users 表，改为写入 `registration_requests` 表（status=pending），重复注册提示"该账号正在审核中"
- `handleLogin()`：调用 `SqlUserStore.authenticate()`，登录成功后写操作日志（`LOGIN`）
- `handleChangePassword()`：改密成功后写操作日志（`CHANGE_PASSWORD`）
- 所有关键操作均通过 `OperationLogStore.log()` 记录

### 4.2 SessionManager — 会话管理

**文件**：`common/src/main/java/com/substation/common/auth/SessionManager.java`

**未修改**：仍使用 Redis 管理 Token（TTL 30min），因为 Redis 的 TTL 机制更适合会话场景。Token 生成使用 `SecureRandom` + Hex 编码。

### 4.3 AuthFilter — 鉴权过滤器

**文件**：`common/src/main/java/com/substation/common/auth/AuthFilter.java`

**修改内容**（wsh_test 版本）：白名单增加 `/index.html`、`/dashboard.html`、`/analysis.html`、`/api/auth/`，所有页面和认证接口免鉴权。

---

## 五、修改模块：HTTP 服务

### 5.1 HttpFileServer

**文件**：`display/src/main/java/com/substation/display/HttpFileServer.java`

**修改内容**：
- 构造函数增加 `AdminApiHandler` 参数
- 新增 `/api/admin/` 路由（需登录鉴权）
- 白名单（免登录路径）：`/login.html`、`/index.html`、`/dashboard.html`、`/analysis.html`、`/css/`、`/js/`、`/api/auth/login`、`/api/auth/register`、`/favicon.ico`、`/`
- 根路径 `/` 映射到 `/login.html`

### 5.2 DisplayMain

**文件**：`display/src/main/java/com/substation/display/DisplayMain.java`

**修改内容**：
- 初始化 `DatabaseManager` → `SqlUserStore` → `RegistrationStore` → `OperationLogStore`
- 创建 `AuthApiHandler`（SQL 模式构造函数）
- 创建 `AdminApiHandler`
- 注入 `OperationLogStore` 到 `WebSocketBridge`（通过 `setOperationLogStore()`）

### 5.3 WebSocketBridge — 仿真操作日志

**文件**：`display/src/main/java/com/substation/display/WebSocketBridge.java`

**新增功能**：仿真操作日志记录

**修改内容**：
- 新增 `operationLogStore` 字段和 `setOperationLogStore()` 注入方法
- `onMessage()` 在以下消息类型时自动写日志：

| 前端消息类型 | 日志 action | 说明 |
|-------------|------------|------|
| `SET_CONFIG` | `START_TASK` | 开始仿真任务 |
| `RESET` | `RESET` | 重置系统 |
| `TOGGLE_PAUSE` | `TOGGLE_PAUSE` | 暂停/继续 |
| `ADD_CAR` | `ADD_CAR` | 动态添加小车 |
| `TOGGLE_OBSTACLE` | `TOGGLE_OBSTACLE` | 动态障碍物 |

所有仿真操作的 username 统一记录为 `"system"`，管理员可在日志中搜索 username=system 查看。

---

## 六、前端页面（wsh_test 合入 + lyq_car 新增）

### 6.1 wsh_test 合入的页面

| 文件 | 说明 |
|------|------|
| `login.html` | 登录 + 注册双模式，浅色主题，粒子背景 |
| `dashboard.html` | 角色仪表盘（按角色显示不同入口按钮） |
| `index.html` | 仿真控制主界面，Canvas 地图，三列布局 |
| `analysis.html` | 统计分析页面（框架） |
| `css/style.css` | 全局浅色主题 |
| `js/auth.js` | 认证模块（`window.Auth` API，`auth_token` key） |
| `js/app.js` | 仿真主逻辑 |
| `js/analysis.js` | 分析页逻辑 |
| `js/particles.js` | 120 粒子背景动画 |
| `dashboard.html` | 管理员增加紫色「👥 用户管理」入口按钮 |

### 6.2 lyq_car 新增的页面

**文件**：`display/src/main/resources/web/user-management.html`

**功能**：
- **双 Tab 设计**：用户列表 + 注册审核
- **用户列表 Tab**：
  - 用户名蓝色链接（点击查看操作历史）
  - 搜索用户名/显示名称
  - 角色下拉筛选（仿真员/统计分析员）
  - 分页（每页 20 条）
  - 每行按钮：「日志」查看操作历史、「重置密码」弹窗
- **注册审核 Tab**：
  - 状态筛选（待审核/已通过/已拒绝）
  - 待审核数量红色角标
  - 通过/拒绝按钮
- **操作日志弹窗**：700px 宽模态框，显示：操作类型（中文化）、操作对象、详情、时间（北京时间）
- **粒子背景**：同其他页面一致的 `particles.js` 效果
- **顶栏**：`← 返回` + `退出` 按钮

### 6.3 注册消息修复

**文件**：`login.html`（wsh_test 版）

**修复内容**：第 126 行 `alert('注册成功，请登录')` → `alert(d.message || '操作成功')`，现在显示后端返回的实际消息 `"注册申请已提交，请等待管理员审核"`。

---

## 七、数据库与 Redis 分工

| 存储 | 用途 | 表/Key |
|------|------|--------|
| SQL Server | 用户账号 | `users` |
| SQL Server | 注册申请 | `registration_requests` |
| SQL Server | 操作日志 | `operation_logs` |
| Redis | 会话 Token | `auth:session:{token}`（TTL 30min） |
| Redis | 黑板数据 | `mapView` / `mapBlock` / `Car*:Position` 等 |

---

## 八、今日 Git 提交记录

```
b263903 docs: 同步 — 操作日志查看功能
20ec6c9 feat(admin): 用户列表点击用户名查看操作历史
86a1886 docs: 同步更新 — 审核API修复 + 用户管理退出按钮
5b5d08d fix(admin): 修复注册审核路径索引bug + 用户管理界面增加退出按钮
d1b2b7f fix: 三修复 — 注册消息/审核API/用户管理界面风格
50f07b8 docs: 同步更新操作说明文档 — wsh_test合并+用户管理修复
a3b9f6f fix(auth+admin): 用户管理页适配 wsh_test Auth API + 注册消息修正
e0d605e merge: 合并 wsh_test 完整前端 + SQL Server 后端，解决全部冲突
28a7ca2 fix(display): 根路径 / 加入白名单
d5ab113 docs: SQL Server 迁移 + 用户管理系统操作说明文档
c27e15b feat(auth+admin): SQL Server 用户存储 + 注册审核 + 操作日志 + 管理员用户管理界面
```

---

## 九、如何启动和测试

```bash
# 1. 确认中间件运行
docker ps  # 应看到 redis, rabbitmq, sqlserver 三个容器

# 2. 编译
.\mvnw.cmd install -DskipTests

# 3. 启动
.\start_all.bat

# 4. 浏览器
http://localhost:8887/        → 登录页（admin/admin123）
http://localhost:8887/dashboard.html → 仪表盘 → 用户管理
```

**测试注册审核完整流程**：
1. 登录页 → 注册 simulator1/sim123 → 提示"注册申请已提交"
2. 登录 admin → 仪表盘 → 用户管理 → 注册审核 Tab
3. 通过申请 → 用户列表 Tab 看到 simulator1
4. 点击用户名 → 查看日志（包含 LOGIN、REGISTER、APPROVE_REGISTRATION）
5. 进入仿真控制 → 开始仿真 → 再去用户列表点 system → 看到仿真操作日志
