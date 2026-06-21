# 用户登录认证系统 — 设计文档

> 文档版本：v1.1（已全部实现）  
> 编写日期：2026-06-12  
> 最后更新：2026-06-15  
> 适用系统：变电站巡检仿真系统  

---

## 一、需求概述

为现有的变电站巡检仿真系统增加用户登录认证功能，实现：

1. **登录认证** — 用户通过用户名+密码登录，未登录禁止进入系统
2. **角色分级** — 三种角色：仿真员（simulator）、管理员（admin）、统计分析员（analyst）
3. **会话管理** — 登录后保持会话，超时自动退出
4. **前端集成** — 登录页面独立，认证通过后跳转到主系统

### 1.1 三角色权限矩阵

| 功能 | 仿真员 | 管理员 | 统计分析员 |
|------|--------|--------|------------|
| 查看仿真地图 / 车辆状态 | ✅ | ✅ | ✅ |
| 开始 / 暂停 / 重置任务 | ✅ | ✅ | ❌ |
| 修改任务配置参数 | ✅ | ✅ | ❌ |
| 添加小车 | ✅ | ✅ | ❌ |
| 动态调整障碍物 | ✅ | ✅ | ❌ |
| 进入统计分析界面 | ❌ | ✅ | ✅ |
| 导出统计数据 | ❌ | ✅ | ❌ |
| 修改密码 | ✅ | ✅ | ✅ |

**职责说明**：
- **仿真员（simulator）**：执行仿真任务，控制仿真过程
- **管理员（admin）**：拥有全部权限，可操作仿真 + 查看分析 + 导出数据
- **统计分析员（analyst）**：只读仿真进程，专注数据查看和统计分析

---

## 二、技术方案

### 2.1 技术选型

与现有项目技术栈保持一致：

| 组件 | 方案 | 理由 |
|------|------|------|
| 用户存储 | Redis Hash | 复用现有 Redis，无需新增数据库 |
| 密码加密 | BCrypt | 业界标准，Java 无需额外依赖（Spring Security 内嵌也可用，或使用独立的 jBCrypt） |
| 会话管理 | Redis Key + TTL | `auth:session:{token}` → 用户信息 JSON，TTL=30min |
| 前端鉴权 | 登录页 + Cookie/Storage Token | 纯 JS，无需框架 |
| 后端鉴权 | HttpServer Filter 中间件 | 复用 JDK HttpServer，在请求入口校验 |
| CSRF 防护 | Token + SameSite Cookie | 基础安全 |

### 2.2 为什么不引入数据库

- 项目当前部署简单（Docker Compose 仅 Redis + RabbitMQ）
- 系统用户量极小（团队内部使用，估计 ≤ 10 人）
- Redis 自带持久化（RDB/AOF）可以保存用户数据
- 避免项目增加 MySQL/PostgreSQL 依赖，保持轻量

### 2.3 为什么不使用 JWT

- JWT 无状态特性在此场景优势不大（已有 Redis）
- Token 撤回（踢人下线）在纯 JWT 下很难实现，需要额外黑名单
- 基于 Redis 的 Session Token 可以直接 DEL 强制下线

---

## 三、数据设计

### 3.1 Redis 数据结构

```
# ═════ 用户账号存储 ═════
# Key:   auth:users
# Type:  Hash
# Value: field=用户名, value=JSON用户信息

HSET auth:users "admin" '{"passwordHash":"$2a$10$...","role":"admin","displayName":"管理员","createdAt":1718000000}'
HSET auth:users "zhangsan" '{"passwordHash":"$2a$10$...","role":"user","displayName":"张三","createdAt":1718000100}'

# ═════ 会话 Token ═════
# Key:   auth:session:{token}
# Type:  String (JSON)
# TTL:   1800s (30分钟无操作自动过期)
# Value: {"username":"admin","role":"admin","loginAt":1718000500,"lastAccess":1718001000}

SET auth:session:a1b2c3d4... '{"username":"admin","role":"admin","loginAt":1718000500,"lastAccess":1718001000}' EX 1800
```

### 3.2 用户信息 JSON 结构

```java
public record UserAccount(
    String username,       // 登录名
    String passwordHash,   // BCrypt 哈希
    String role,           // "admin" | "user"
    String displayName,    // 显示名称（如"管理员"、"张三"）
    long createdAt         // 创建时间戳 epoch 秒
) {}

public record SessionInfo(
    String username,       // 登录名
    String role,           // 角色
    long loginAt,          // 登录时间
    long lastAccess        // 最后访问时间
) {}
```

### 3.3 预设账号

系统首次启动时自动创建：

| 用户名 | 密码 | 角色 | 说明 |
|--------|------|------|------|
| `admin` | `admin123` | admin | 默认管理员 |
| `simulator1` | `sim123` | simulator | 默认仿真员 |
| `analyst1` | `ana123` | analyst | 默认统计分析员 |

---

## 四、认证流程

### 4.1 登录流程

```
┌─────────────┐      ┌──────────────┐      ┌──────────┐
│   浏览器     │      │  Display 模块  │      │  Redis   │
│ (login.html)│      │ (HttpServer)  │      │          │
└──────┬──────┘      └──────┬───────┘      └────┬─────┘
       │                    │                   │
       │ POST /api/auth/login│                   │
       │ {username,password} │                   │
       │───────────────────>│                   │
       │                    │ HGET auth:users   │
       │                    │ {username}         │
       │                    │──────────────────>│
       │                    │<──────────────────│
       │                    │                   │
       │                    │ BCrypt.verify()   │
       │                    │ 密码匹配?          │
       │                    │                   │
       │                    │ 成功 → 生成Token   │
       │                    │ SET session:{t}   │
       │                    │ EX 1800           │
       │                    │──────────────────>│
       │                    │<─────── OK ───────│
       │                    │                   │
       │  200 {token,role}  │                   │
       │<───────────────────│                   │
       │                    │                   │
       │ localStorage.set   │                   │
       │ ("token", token)   │                   │
       │                    │                   │
       │ 跳转 index.html    │                   │
```

### 4.2 鉴权流程（每次请求）

```
浏览器请求 (带 Authorization: Bearer {token})
       │
       ▼
┌──────────────────┐
│ AuthFilter 中间件 │
└──────────────────┘
       │
       ├─ 无 Token → 返回 401 → 前端跳回 login.html
       │
       ├─ 有 Token → GET auth:session:{token}
       │              │
       │              ├─ Redis 返回 nil → Token 过期/无效 → 401
       │              │
       │              └─ Redis 返回 session 数据
       │                   │
       │                   ├─ 角色检查: 接口需要 admin 但是 user? → 403
       │                   │
       │                   └─ 通过 → EXPIRE 续期 1800 → 放行
```

### 4.3 注册流程

```
浏览器 → POST /api/auth/register (免登录)
       → UserStore.register(username,password,role,displayName)
       → 校验用户名不重复、密码≥6位、角色限simulator/analyst
       → BCrypt哈希密码 → HSET auth:users
       → 成功 → 自动跳转登录表单
```

### 4.4 登出流程

```
浏览器 → POST /api/auth/logout (带 Token)
       → DEL auth:session:{token}
       → 前端清除 localStorage
       → 跳转 login.html
```

---

## 五、后端接口设计

### 5.1 AuthFilter 中间件

```java
// 在 HttpFileServer 中增加鉴权过滤
// 对 /api/ 路径之外的请求全部鉴权，/api/auth/ 路径除外
// 对 /login.html, /css/, /js/ 放行（登录页资源）
```

**放行白名单**：
- `GET /login.html`
- `GET /css/*`
- `GET /js/*`（登录页 + 认证 JS）
- `POST /api/auth/login`
- `POST /api/auth/register`
- 静态资源（favicon, image）

**鉴权规则**：
- 从 `Authorization` header 提取 `Bearer {token}`
- 没有 token：返回 `401` + JSON `{"error":"请先登录"}`
- token 无效/过期：返回 `401` + JSON `{"error":"会话已过期，请重新登录"}`
- 角色不满足：返回 `403` + JSON `{"error":"权限不足"}`

### 5.2 AuthApiHandler（新增类）

路径前缀：`/api/auth/`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/auth/register` | 注册新用户 | 否 |
| POST | `/api/auth/login` | 登录，返回 token | 否 |
| POST | `/api/auth/logout` | 登出 | 是 |
| GET | `/api/auth/me` | 获取当前用户信息 | 是 |
| POST | `/api/auth/change-password` | 修改密码 | 是 |

**POST /api/auth/login**

请求：
```json
{
  "username": "admin",
  "password": "admin123"
}
```

成功响应 (200)：
```json
{
  "success": true,
  "token": "a1b2c3d4e5f6...",
  "username": "admin",
  "role": "admin",
  "displayName": "管理员"
}
```

**POST /api/auth/register**

请求：
```json
{
  "username": "newuser",
  "password": "pass123",
  "role": "simulator",
  "displayName": "新仿真员"
}
```

成功响应 (200)：
```json
{
  "success": true,
  "message": "注册成功，请登录"
}
```

失败响应 (400)：
```json
{
  "success": false,
  "error": "用户名已存在"
}
```
> 注：管理员角色(admin)只能由系统预设创建，注册时仅可选 simulator 或 analyst。

---

失败响应 (401)：
```json
{
  "success": false,
  "error": "用户名或密码错误"
}
```

**POST /api/auth/logout**

请求头：`Authorization: Bearer {token}`

响应 (200)：
```json
{
  "success": true,
  "message": "已退出登录"
}
```

**GET /api/auth/me**

请求头：`Authorization: Bearer {token}`

响应 (200)：
```json
{
  "success": true,
  "username": "admin",
  "role": "admin",
  "displayName": "管理员"
}
```

**POST /api/auth/change-password**

请求头：`Authorization: Bearer {token}`

请求体：
```json
{
  "oldPassword": "admin123",
  "newPassword": "newPass456"
}
```

响应 (200)：
```json
{
  "success": true,
  "message": "密码修改成功"
}
```

---

## 六、前端页面设计

### 6.1 登录页面 (login.html)

布局：居中卡片式 + 暗色工业风

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│                  ⚡ 变电站巡检仿真系统                  │
│                                                     │
│           ┌───────────────────────────┐              │
│           │                            │              │
│           │   👤 用户名                 │              │
│           │   ┌─────────────────────┐ │              │
│           │   │                     │ │              │
│           │   └─────────────────────┘ │              │
│           │                            │              │
│           │   🔒 密码                   │              │
│           │   ┌─────────────────────┐ │              │
│           │   │                     │ │              │
│           │   └─────────────────────┘ │              │
│           │                            │              │
│           │   ┌─────────────────────┐ │              │
│           │   │      登  录         │ │              │
│           │   └─────────────────────┘ │              │
│           │                            │              │
│           │   ⚠ 错误提示区域            │              │
│           │                            │              │
│           └───────────────────────────┘              │
│                                                     │
│                  v1.0 · 团队内部系统                  │
└─────────────────────────────────────────────────────┘
```

**状态说明**：
- 默认状态：输入框为空，登录按钮可用
- 提交中：按钮禁用，显示加载动画
- 错误状态：红色边框提示，错误信息显示
- 成功状态：跳转到 index.html

### 6.2 主界面修改 (index.html)

在现有 header 右侧增加用户信息区（替换 / 补充原有全局信息区）：

```
┌──────────────────────────────────────────────────────────┐
│ ⚡ 变电站巡检仿真系统  │ 👤 管理员(admin) │ [统计分析] [退出] │
└──────────────────────────────────────────────────────────┘
```

- `👤 管理员(admin)` — 不可点击，仅显示当前登录用户
- `[统计分析]` — 点击跳转 analysis.html（所有角色可见）
- `[退出]` — 点击登出，返回 login.html

**权限控制前端实现**：
```js
// 从 /api/auth/me 获取当前用户信息
const user = await fetch('/api/auth/me').then(r => r.json());

if (user.role !== 'admin') {
    // 隐藏操作按钮
    document.getElementById('btn-start').style.display = 'none';
    document.getElementById('btn-pause').style.display = 'none';
    document.getElementById('btn-reset').style.display = 'none';
    document.getElementById('btn-addcar').style.display = 'none';
    document.getElementById('cfg-obstacleRatio').disabled = true;
    // etc.
}
```

### 6.3 前端 JS 架构

```
web/
├── login.html          ← 新增：登录页面
├── index.html          ← 修改：增加用户信息+权限控制
├── analysis.html       ← 新增：统计分析页面（框架）
├── css/
│   └── style.css       ← 修改：增加登录页/权限相关样式
└── js/
    ├── app.js          ← 修改：启动时检查登录状态
    ├── auth.js         ← 新增：认证相关 JS（登录/登出/token管理）
    └── analysis.js     ← 新增：统计分析页面 JS（空壳）
```

---

## 七、安全考虑

| 事项 | 方案 |
|------|------|
| 密码存储 | BCrypt 哈希，永不明文存储 |
| 传输安全 | Token 通过 Header 传输，不放在 URL |
| 暴力破解防护 | 登录失败 5 次后锁定 15 分钟（Redis 计数） |
| Token 时效 | 30 分钟无操作过期，每次请求自动续期 |
| XSS 防护 | 前端输出用户信息时使用 `textContent` 而非 `innerHTML` |
| CSRF 防护 | Cookie 设置 SameSite=Strict |
| 强制登出 | 管理员可通过 `DEL auth:session:{token}` 强制踢人 |

---

## 八、代码结构

### 8.1 新增文件（全部已实现）

```
common/src/main/java/com/substation/common/auth/
├── model/
│   ├── LoginRequest.java        ← 登录请求 record
│   ├── LoginResponse.java       ← 登录响应 record (含 ok/fail 工厂方法+toJson)
│   ├── UserInfo.java            ← 用户信息 record
│   └── SessionInfo.java         ← 会话信息 record
├── UserStore.java               ← Redis 用户存储 + 预设账号 + 注册 + 改密
├── SessionManager.java          ← Token 会话管理 (TTL 30min, SecureRandom)
├── AuthApiHandler.java          ← HTTP API: register/login/logout/me/change-password
└── AuthFilter.java              ← HTTP 鉴权过滤器 (独立类，当前由 HttpFileServer 内置鉴权替代)

common/src/test/java/com/substation/common/auth/
├── UserStoreTest.java           ← 16 个测试 (认证+改密+注册7个)
└── SessionManagerTest.java      ← 6 个测试

display/src/main/resources/web/
├── login.html                   ← 登录+注册双表单页面
└── js/
    └── auth.js                  ← 前端认证逻辑 (login/register/logout/checkAuth)
```

### 8.2 修改的文件

| 文件 | 修改内容 |
|------|---------|
| `pom.xml` (root) | 添加 jbcrypt 依赖到 dependencyManagement |
| `common/pom.xml` | 引用 jbcrypt 依赖 |
| `display/.../HttpFileServer.java` | 集成 API 路由 + 内置鉴权 (白名单: /api/auth/login, /api/auth/register) |
| `display/.../DisplayMain.java` | 初始化 UserStore/SessionManager/AuthApiHandler/AnalysisApiHandler |

### 8.3 依赖变更

在父 POM 和 common/pom.xml 中添加：
```xml
<dependency>
    <groupId>org.mindrot</groupId>
    <artifactId>jbcrypt</artifactId>
    <version>0.4</version>
</dependency>
```

---

## 九、实施状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 创建 `UserStore`，实现用户 CRUD + 预设账号初始化 | ✅ 已完成 |
| 2 | 创建 `SessionManager`，实现 token 创建/验证/续期/删除 | ✅ 已完成 |
| 3 | 创建 `AuthApiHandler`，实现 register/login/logout/me/change-password 接口 | ✅ 已完成 |
| 4 | `HttpFileServer` 内置鉴权 + 白名单 | ✅ 已完成 |
| 5 | 编写 `login.html` + `auth.js`，实现登录/注册页面 | ✅ 已完成 |
| 6 | 授权页面跳转 (仿真员→index.html, analyst→analysis.html) | 待开发 |
| 7 | 前端权限控制 (按角色隐藏按钮) | 待开发 |
