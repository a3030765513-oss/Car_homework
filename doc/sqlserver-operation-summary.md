# SQL Server 迁移 + 用户管理系统 — 操作说明文档

> 操作时间：2026-06-17  
> 分支：`lyq_car`  
> 操作人：刘倚岐

---

## 一、操作概述

1. **拉取 `wsh_test` 分支**，完整合并其前端更新（dashboard.html、particles.js、浅色主题等）
2. **用户存储从 Redis 迁移到 SQL Server**（Docker 部署，三张表：users / registration_requests / operation_logs）
3. **注册流程改造**：直接注册 → 提交审核 → 管理员通过/拒绝
4. **管理员用户管理界面**：查看用户列表 + 审核注册 + 重置密码 + 搜索筛选
5. 合并 wsh_test 前端（浅色UI、角色仪表盘、粒子动画、修改密码弹窗）
6. 全部 9 模块编译通过

---

## 二、环境配置

### 2.1 SQL Server（Docker）

```bash
# 已创建的容器
docker ps
# 3103eaa65a4d   mcr.microsoft.com/mssql/server:2022-latest   sqlserver

# JDBC 连接信息
JDBC URL：jdbc:sqlserver://localhost:1433;databaseName=CarHomework;encrypt=false;trustServerCertificate=true
用户名：sa
密码：Root@1234
```

> ⚠ **重要说明**：SQL Server 密码策略要求密码长度≥8位且包含大小写字母+数字+特殊字符。原计划 `root`/`1234` 不符合要求，使用 `sa`/`Root@1234` 作为 JDBC 连接。此信息已写入 `DatabaseManager.java` 常量。

### 2.2 数据库表结构

```
数据库：CarHomework
├── users                        ← 用户账号表（三种角色统一存储）
│   ├── username (UNIQUE)
│   ├── password (BCrypt hash)
│   ├── role (admin / simulator / analyst)
│   ├── status (active / disabled)
│   └── display_name, created_at
├── registration_requests        ← 注册申请表
│   ├── username, password, role, display_name
│   ├── status (pending / approved / rejected)
│   └── reviewed_by, review_time
└── operation_logs               ← 操作日志表
    ├── username, action, target, details
    └── ip_address, created_at
```

### 2.3 预设账号

| 用户名 | 密码 | 角色 | 创建方式 |
|--------|------|------|---------|
| `admin` | `admin123` | 管理员 | DatabaseManager.initDatabase() 启动时自动创建 |

> `simulator1`/`analyst1` 需通过注册流程创建：访问 login.html → 注册 → admin 在用户管理界面审核通过。

---

## 三、新增文件清单

### 3.1 common 模块 — 数据库层

```
common/src/main/java/com/substation/common/sql/
├── model/
│   ├── UserRecord.java              ← 用户表行 record
│   ├── RegistrationRecord.java      ← 注册申请表行 record
│   └── OperationLogRecord.java      ← 操作日志表行 record
├── DatabaseManager.java             ← JDBC 连接管理 + 自动建表 + 预设admin
├── SqlUserStore.java                ← SQL 用户存储（替代 Redis UserStore）
├── RegistrationStore.java           ← 注册申请管理
└── OperationLogStore.java           ← 操作日志管理
```

### 3.2 common 模块 — 管理员 API

```
common/src/main/java/com/substation/common/admin/
└── AdminApiHandler.java             ← 8个管理员 API
```

### 3.3 display 模块 — 前端（wsh_test 合入后）

```
display/src/main/resources/web/
├── login.html                     ← 登录+注册（浅色主题+粒子背景）
├── dashboard.html                  ← 角色仪表盘（管理员含用户管理入口）
├── index.html                      ← 仿真控制主界面（浅色风格）
├── analysis.html                   ← 统计分析页面
├── user-management.html            ← 管理员用户管理（已适配 wsh_test Auth API）
├── css/style.css                   ← 全局样式（浅色主题）
└── js/
    ├── auth.js                     ← 认证模块（window.Auth API, key=auth_token）
    ├── app.js                      ← 仿真主逻辑
    ├── analysis.js                 ← 分析页逻辑
    └── particles.js                ← 粒子动画
```

### 3.4 doc 设计文档

```
doc/
├── sqlserver-user-management-design.md  ← SQL Server 设计文档(v1.0)
└── sqlserver-operation-summary.md       ← 本文件(v1.0)
```

---

## 四、修改的现有文件

| 文件 | 修改内容 |
|------|---------|
| `pom.xml`（根） | 添加 `mssql-jdbc:12.8.1.jre11` 依赖 |
| `common/pom.xml` | 引用 `mssql-jdbc` |
| `common/auth/AuthApiHandler.java` | 新增 SqlUserStore 模式构造器；register 改为审核流程（pending检查 + 写 registration_requests）；login/me/change-password 支持 SQL/Redis 双模式 |
| `display/HttpFileServer.java` | 注册 `/api/admin/` 路由；构造函数注入 AdminApiHandler |
| `display/DisplayMain.java` | 初始化 DatabaseManager/SqlUserStore/RegistrationStore/OperationLogStore/AdminApiHandler；改用 SQL 模式启动 |

---

## 五、管理员 API 接口

**Base Path**: `/api/admin`

| 方法 | 路径 | 说明 | 从何处进入 |
|------|------|------|----------|
| GET | `/api/admin/users` | 用户列表（搜索/角色筛选/分页） | user-management.html → 用户列表 Tab |
| GET | `/api/admin/users/{username}` | 用户详情 | - |
| POST | `/api/admin/users/{username}/reset-password` | 重置密码为 123456 | 用户列表 → 重置密码按钮 |
| GET | `/api/admin/registrations` | 注册申请列表 | user-management.html → 注册审核 Tab |
| POST | `/api/admin/registrations/{id}/approve` | 通过注册申请 | 注册审核 → 通过按钮 |
| POST | `/api/admin/registrations/{id}/reject` | 拒绝注册申请 | 注册审核 → 拒绝按钮 |
| GET | `/api/admin/logs` | 操作日志列表 | user-management.html → 用户列表点击用户名/日志按钮 |

---

## 六、注册流程变更

| 对比项 | 旧流程（Redis） | 新流程（SQL Server） |
|--------|----------------|---------------------|
| 注册 | 立即写入 users 表 | 写入 registration_requests 表（status=pending） |
| 审核 | 无 | 管理员在用户管理界面通过/拒绝 |
| 重复注册 | 返回"用户名已存在" | 返回"该账号正在审核中" |
| 操作记录 | 无 | 操作日志表 (LOGIN/REGISTER/APPROVE/REJECT/RESET_PASSWORD 等) |

---

## 七、操作日志类型

| action 值 | 触发时机 |
|----------|---------|
| LOGIN | 用户登录成功 |
| REGISTER | 用户提交注册申请 |
| REGISTER_DUPLICATE | 重复提交审核中的注册 |
| APPROVE_REGISTRATION | 管理员通过注册申请 |
| REJECT_REGISTRATION | 管理员拒绝注册申请 |
| RESET_PASSWORD | 管理员重置用户密码 |
| CHANGE_PASSWORD | 用户自行修改密码 |

---

## 八、前端界面说明

### 8.1 用户管理界面 (user-management.html)

访问路径：`http://localhost:8887/user-management.html`

**双 Tab 设计**：

| Tab | 功能 |
|-----|------|
| 用户列表 | 查看全部非admin用户信息、搜索、筛选、分页、点击用户名查看操作历史、日志按钮、重置密码按钮 |
| 注册审核 | 查看待审核/已通过/已拒绝的注册申请、通过/拒绝待审核申请、显示审核人和审核时间 |

**权限要求**：仅 admin 角色可访问（前端校验 + 后端 API 需 Token 鉴权）

**操作说明**：
1. 登录 → dashboard.html → 管理员点击紫色「👥 用户管理」按钮（已添加）
2. 用户列表 Tab：查看、搜索、筛选、重置密码
3. 注册审核 Tab：查看申请列表，点击"通过"或"拒绝"

### 8.2 注册页面流程

1. 用户访问 login.html → 点击"没有账号？立即注册"
2. 填写信息 → 提交 → 显示"注册申请已提交，等待管理员审核"
3. 管理员登录 → user-management.html → 注册审核 Tab → 通过/拒绝
4. 通过后：用户可用注册的账号密码登录

---

## 九、如何验证

```bash
# 1. 确认 SQL Server 运行中
docker ps | grep sqlserver

# 2. 确认表已创建
docker exec sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Root@1234' -C \
  -Q "USE CarHomework; SELECT name FROM sys.tables;"

# 3. 确认 admin 预设账号
docker exec sqlserver /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P 'Root@1234' -C \
  -Q "USE CarHomework; SELECT username, role FROM users;"

# 4. 编译
.\mvnw.cmd compile -DskipTests

# 5. 启动 Display 模块
.\mvnw.cmd exec:java -pl display -Dexec.mainClass=com.substation.display.DisplayMain

# 6. 浏览器访问
#    登录页: http://localhost:8887/login.html
#    仪表盘: http://localhost:8887/dashboard.html  
#    用户管理: http://localhost:8887/user-management.html
```

---

## 十、已完成项 / 后续待办

| 事项 | 优先级 | 说明 |
|------|--------|------|
| 合并 wsh_test 前端更新 | ✅ | dashboard/particles/浅色主题已合入 |
| 注册审核功能修复 | ✅ | AdminApiHandler extractId/extractName 路径索引 3→4 |
| 用户管理界面退出按钮 | ✅ | header 增加"退出"按钮 |
| 用户管理界面风格统一 | ✅ | ← 返回 + 退出，与 index/analysis 一致 |
| 注册消息修正 | ✅ | alert 显示后端 d.message |
| HttpFileServerTest / DisplayMain 测试更新 | P1 | 测试需要适配新构造函数参数 |
| 编写 SQL 模式集成测试 | P2 | SqlUserStoreTest / RegistrationStoreTest 等 |
| 操作日志前端查看页面 | ✅ | 用户列表点用户名/日志按钮即可查看操作历史 |
| admin 审批日志记录 | ✅ | approve/reject 均写入 operation_logs |
