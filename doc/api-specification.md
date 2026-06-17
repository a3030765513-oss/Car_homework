# 变电站巡检仿真系统 — 接口文档

> 文档版本：v1.1（已全部实现）  
> 编写日期：2026-06-12  
> 最后更新：2026-06-15  
> 适用系统：变电站巡检仿真系统  

---

## 〇、文档约定

### 0.1 通信方式

系统采用两种通信方式：

| 通道 | 传输方向 | 协议 | 用途 |
|------|---------|------|------|
| HTTP | 浏览器 ↔ Display | REST API (JSON) | 登录认证、分析查询、静态资源 |
| WebSocket | 浏览器 ↔ Display | ws://localhost:8888 | 实时仿真状态推送、前端指令上传 |
| RabbitMQ | Display ↔ Controller ↔ 各模块 | AMQP | 模块间异步消息 |

### 0.2 鉴权方式

所有 `/api/` 路径下的接口（除 `/api/auth/login` 和 `/api/auth/register` 外）需要在 HTTP Header 中携带 Token：

```
Authorization: Bearer {token}
```

Token 由登录接口返回，有效期 30 分钟（每次请求自动续期）。

### 0.3 响应格式

所有 HTTP API 统一使用 JSON 响应：

```json
{
  "success": true,        // boolean, 请求是否成功
  "error": "错误描述",     // 失败时出现，成功时省略
  // ...业务数据字段
}
```

HTTP 状态码：
- `200` 成功
- `400` 请求参数错误
- `401` 未登录或 Token 过期
- `403` 权限不足
- `404` 资源不存在
- `500` 服务器内部错误

### 0.4 现有接口（不予变更）

以下 WebSocket + MQ 消息接口是现有系统已有，本文档仅列出不修改：

| 消息类型 | 方向 | 说明 |
|---------|------|------|
| `SET_CONFIG` | 浏览器 → WS → MQ → Controller | 配置任务参数并开始仿真 |
| `RESET` | 浏览器 → WS → MQ → TaskConfigurator | 重置系统 |
| `TOGGLE_PAUSE` | 浏览器 → WS → MQ → Controller | 暂停/继续 |
| `SET_TICK_INTERVAL` | 浏览器 → WS → MQ → Controller | 调整节拍间隔 |
| `ADD_CAR` | 浏览器 → WS → Display | 动态添加小车 |
| `TOGGLE_OBSTACLE` | 浏览器 → WS → MQ → Controller | 动态切换障碍物 |
| `REFRESH_ALL` | Controller → MQ → Display → WS → 浏览器 | 推送完整仿真状态 |

---

## 一、认证模块接口 (Auth)

**Base Path**: `/api/auth`

### 1.1 登录

```
POST /api/auth/login
```

**鉴权**: 不需要

**请求体**:
```json
{
  "username": "string, 必填, 登录用户名",
  "password": "string, 必填, 明文密码"
}
```

**成功响应** (200):
```json
{
  "success": true,
  "token": "a1b2c3d4e5f6... (string, 会话令牌, 后续请求放入 Authorization header)",
  "username": "string, 登录用户名",
  "role": "string, 角色, 'admin' | 'simulator' | 'analyst'",
  "displayName": "string, 显示名称"
}
```

**失败响应** (401):
```json
{
  "success": false,
  "error": "用户名或密码错误"
}
```

**失败响应** (429):
```json
{
  "success": false,
  "error": "登录尝试次数过多，请15分钟后再试"
}
```

---

### 1.2 注册

```
POST /api/auth/register
```

**鉴权**: 不需要

**请求体**:
```json
{
  "username": "string, 必填, 用户名",
  "password": "string, 必填, 至少6位",
  "role": "string, 必填, 'simulator' | 'analyst' (admin不可自注册)",
  "displayName": "string, 可选, 显示名称, 留空使用用户名"
}
```

**成功响应** (200):
```json
{
  "success": true,
  "message": "注册成功，请登录"
}
```

**失败响应** (400):
```json
{
  "success": false,
  "error": "用户名已存在 / 密码至少需要6位 / 无效的角色，可选: simulator, analyst"
}
```

---

### 1.3 登出

```
POST /api/auth/logout
```

**鉴权**: 需要（任意角色）

**请求头**: `Authorization: Bearer {token}`

**请求体**: 无

**成功响应** (200):
```json
{
  "success": true,
  "message": "已退出登录"
}
```

---

### 1.4 获取当前用户信息

```
GET /api/auth/me
```

**鉴权**: 需要（任意角色）

**成功响应** (200):
```json
{
  "success": true,
  "username": "string, 登录用户名",
  "role": "string, 'admin' | 'user'",
  "displayName": "string, 显示名称"
}
```

---

### 1.5 修改密码

```
POST /api/auth/change-password
```

**鉴权**: 需要（任意角色）

**请求体**:
```json
{
  "oldPassword": "string, 必填, 旧密码明文",
  "newPassword": "string, 必填, 新密码明文, 至少6位"
}
```

**成功响应** (200):
```json
{
  "success": true,
  "message": "密码修改成功"
}
```

**失败响应** (400):
```json
{
  "success": false,
  "error": "旧密码不正确"
}
```

---

## 二、分析模块接口 (Analysis)

**Base Path**: `/api/analysis`

> **注意**: 此模块当前为框架设计阶段，所有接口返回空数据（字段存在但数值为 0）。具体实现留空，由后续开发填充。

### 2.1 获取汇总统计

```
GET /api/analysis/summary
```

**鉴权**: 需要（任意角色）

**请求参数**: 无

**成功响应** (200):
```json
{
  "success": true,
  "data": {
    "totalSteps": 0,
    "explorationRate": 0,
    "efficiency": 0,
    "duration": 0,
    "blockCount": 0,
    "idleRate": 0,
    "reExploreRate": 0,
    "activeCars": 3
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| totalSteps | int | 所有小车累计步数 |
| explorationRate | int | 探索覆盖率 0-100 |
| efficiency | double | 探索效率 = exploredCount / Σsteps |
| duration | long | 任务运行时间（秒） |
| blockCount | int | 阻塞总次数 |
| idleRate | double | 空闲时间占比 |
| reExploreRate | double | 重复探索占比 |
| activeCars | int | 活跃小车数量 |

---

### 2.2 获取单车统计

```
GET /api/analysis/car/{carId}
```

**鉴权**: 需要（任意角色）

**路径参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| carId | string | 车辆 ID，如 `Car001` |

**成功响应** (200):
```json
{
  "success": true,
  "data": {
    "carId": "Car001",
    "steps": 0,
    "pathCount": 0,
    "avgPathLength": 0,
    "blockCount": 0,
    "idleRate": 0,
    "pathHistory": []
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| carId | string | 车辆 ID |
| steps | int | 总步数 |
| pathCount | int | 路径规划次数 |
| avgPathLength | double | 平均路径长度 |
| blockCount | int | 被阻塞次数 |
| idleRate | double | 空闲时间占比 |
| pathHistory | array | 路径历史点列表（当前为空数组） |

---

### 2.3 获取排行榜

```
GET /api/analysis/leaderboard
```

**鉴权**: 需要（任意角色）

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sortBy | string | 否 | 排序字段，默认 `steps`。可选: `steps`, `coverage`, `efficiency` |

**成功响应** (200):
```json
{
  "success": true,
  "leaderboard": [
    {
      "carId": "string, 车辆ID",
      "steps": 0,
      "coverage": 0,
      "efficiency": 0
    }
  ]
}
```

---

### 2.4 按条件查询分析数据

```
POST /api/analysis/query
```

**鉴权**: 需要（任意角色）

**请求体**:
```json
{
  "carIds": ["string array, 车辆ID列表, 空数组表示全部"],
  "startTick": "int, 开始节拍号, null 表示从头",
  "endTick": "int, 结束节拍号, null 表示到最新",
  "metrics": ["string array, 需要的指标, 可选: steps/coverage/efficiency/blockCount"]
}
```

**请求示例**:
```json
{
  "carIds": ["Car001", "Car002"],
  "startTick": 0,
  "endTick": 100,
  "metrics": ["steps", "coverage"]
}
```

**成功响应** (200):
```json
{
  "success": true,
  "data": {
    "Car001": {
      "steps": 0,
      "coverage": 0
    },
    "Car002": {
      "steps": 0,
      "coverage": 0
    }
  }
}
```

---

### 2.5 导出报表

```
GET /api/analysis/export
```

**鉴权**: 需要（仅 admin）

**请求参数**:

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| format | string | 否 | 导出格式，`csv` 或 `json`，默认 `json` |
| carIds | string | 否 | 逗号分隔的车辆 ID，如 `Car001,Car002`，默认全部 |

**成功响应** (200):
- `format=json`: 返回 JSON 格式报表数据
- `format=csv`: 返回 CSV 文件下载（Content-Disposition: attachment）

---

## 三、通用错误响应

### 3.1 未登录

```
HTTP 401 Unauthorized
```
```json
{
  "success": false,
  "error": "请先登录"
}
```

### 3.2 Token 过期

```
HTTP 401 Unauthorized
```
```json
{
  "success": false,
  "error": "会话已过期，请重新登录"
}
```

### 3.3 权限不足

```
HTTP 403 Forbidden
```
```json
{
  "success": false,
  "error": "权限不足，仅管理员可执行此操作"
}
```

### 3.4 参数校验失败

```
HTTP 400 Bad Request
```
```json
{
  "success": false,
  "error": "参数校验失败: {具体原因}"
}
```

---

## 四、接口路由总览

```
HTTP Server (port 8887, 由 Display 模块的 HttpFileServer 提供)
│
├─ /                          → 重定向到 /index.html（需登录）
├─ /login.html                → 登录页面（免鉴权）
├─ /index.html                → 仿真控制主界面（需登录）
├─ /analysis.html             → 统计分析界面（需登录）
├─ /css/*                     → 样式文件（免鉴权）
├─ /js/*                      → JS 文件（免鉴权）
│
├─ /api/auth/register         → POST  注册新用户
├─ /api/auth/login            → POST  登录
├─ /api/auth/logout           → POST  登出
├─ /api/auth/me               → GET   获取当前用户信息
├─ /api/auth/change-password  → POST  修改密码
│
├─ /api/analysis/summary      → GET   汇总统计
├─ /api/analysis/car/{carId}  → GET   单车统计
├─ /api/analysis/leaderboard   → GET   排行榜
├─ /api/analysis/query        → POST  条件查询
└─ /api/analysis/export       → GET   导出报表
```

---

## 五、WebSocket 消息协议（与现有协议兼容）

### 5.1 现有消息（不变）

见 §0.4。

### 5.2 未来可扩展的消息

| 类型 | 方向 | 说明 |
|------|------|------|
| `LOGIN_VIA_WS` | 浏览器 → Display | 通过 WebSocket 直接登录（备选方案，可替代 HTTP 登录） |
| `SESSION_EXPIRED` | Display → 浏览器 | 服务端主动通知会话过期（强制跳转登录页） |
| `ANALYSIS_UPDATE` | Display → 浏览器 | 分析数据实时更新推送 |
