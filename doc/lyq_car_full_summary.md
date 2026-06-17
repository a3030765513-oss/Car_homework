# lyq_car 分支 — 全部操作总结

> 分支：`lyq_car` | 操作人：刘倚岐 | 远程仓库：https://github.com/a3030765513-oss/Car_homework.git  
> 操作时间：2026-06-12 ~ 2026-06-15

---

## 一、操作概览

| 阶段 | 内容 | 提交数 |
|------|------|--------|
| 1. 重叠修复 | 设计实现位置预约锁，修复 B 小车重叠问题 | 1 |
| 2. 项目合并 | 从 hzx_common/main 拉取完整 9 模块项目 | 3 |
| 3. 启动修复 | 修复 start_all.bat 依赖缺失 | 1 |
| 4. 文档编写 | 设计文档三件套 | 1 |
| 5. hzx_common 更新合并 | 三轮拉取合并最新改动 | 3 |
| 6. 登录认证系统 | 后端 + 前端 + 测试（三角色+注册） | 2 |
| 7. 统计分析框架 | 后端空壳 + 前端框架 | 合入第 6 步 |
| **合计** | **7 次 push，涵盖全部改动的完整分支** | **11** |

---

## 二、核心功能实现

### 2.1 位置预约锁（防小车重叠）

**问题**：两辆小车同时对同一格子 `isBlocked()` 返回 false → 都移动过去 → 重叠。

**方案**：移动前使用 Redis `SET NX EX` 原子命令预约目标位置。预约成功才移动，预约失败退到 READY 等下个 tick。TTL=5s 防止死锁。

**涉及文件**：

| 文件 | 路径 | 改动 |
|------|------|------|
| BlackboardClient.java | `common/src/main/java/com/substation/common/redis/` | 新增 `tryReservePosition`、`releaseReservePosition`、`appendCarHistory`，修复 `getExplorationRate` 逐格计算 |
| MoveExecutor.java | `car/src/main/java/com/substation/car/` | 重构为 `executeStep` + `finalizeMove`，集成位置预约锁 |
| CarAgent.java | `car/src/main/java/com/substation/car/` | 精确异常：`Exception` → `JSONException` |
| CarMain.java | `car/src/main/java/com/substation/car/` | 构造器模式重构，支持 Launcher 调用 |
| DynamicObstacleUtil.java | `common/src/main/java/com/substation/common/` | **新增**：动态障碍物管理工具 |

### 2.2 登录认证系统

**技术栈**：JDK HttpServer + Redis + BCrypt（jbcrypt 0.4）

**三角色权限**：

| 用户名 | 密码 | 角色 | 权限 |
|--------|------|------|------|
| `admin` | `admin123` | admin（管理员） | 全部：仿真控制 + 统计分析 + 导出 |
| `simulator1` | `sim123` | simulator（仿真员） | 控制仿真、调整参数 |
| `analyst1` | `ana123` | analyst（统计分析员） | 只读仿真 + 查看统计分析 |

**新增文件**（共 14 个）：

```
common/src/main/java/com/substation/common/auth/
├── model/
│   ├── LoginRequest.java             ← 登录请求 record
│   ├── LoginResponse.java            ← 登录响应 record（含 ok/fail 工厂方法）
│   ├── UserInfo.java                 ← 用户信息 record
│   └── SessionInfo.java             ← 会话信息 record
├── UserStore.java                    ← Redis HSET 存储 + 预设账号 + register() + changePassword()
├── SessionManager.java              ← Token CRUD（SecureRandom + TTL 30min）
├── AuthApiHandler.java              ← HTTP API: register/login/logout/me/change-password
└── AuthFilter.java                  ← HTTP 鉴权过滤器

display/src/main/resources/web/
├── login.html                        ← 登录+注册双表单页面（暗色工业风）
└── js/
    └── auth.js                       ← 前端认证（login/register/logout/checkAuth/authHeaders）

common/src/test/java/com/substation/common/auth/
├── UserStoreTest.java               ← 22 测试（认证9+改密7+注册7，合并后16个）
└── SessionManagerTest.java          ← 6 测试
```

**认证 API**（全部在 `HttpFileServer` 中路由，无需登录的注册/登录已在白名单中放行）：

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/auth/register` | 免 | 注册（角色限 simulator/analyst，admin 仅预设） |
| POST | `/api/auth/login` | 免 | 登录，返回 token |
| POST | `/api/auth/logout` | 需 | 登出，销毁 token |
| GET | `/api/auth/me` | 需 | 获取当前用户信息 |
| POST | `/api/auth/change-password` | 需 | 修改密码 |

**依赖变更**：`pom.xml` 添加 `org.mindrot:jbcrypt:0.4`

### 2.3 统计分析框架

**当前状态**：基础框架已就绪，后端 API 返回空数据，前端页面为占位框架，具体分析逻辑待后续填充。

**新增文件**（共 7 个）：

```
common/src/main/java/com/substation/common/analysis/
├── model/
│   ├── AnalysisQuery.java            ← 查询参数 record
│   ├── SummaryStatistics.java       ← 汇总统计 record
│   └── CarStatistics.java           ← 单车统计 record
├── AnalysisApiHandler.java          ← HTTP API: summary/car/leaderboard/query/export
└── AnalysisEngine.java              ← 分析引擎接口（待实现）

display/src/main/resources/web/
├── analysis.html                     ← 统计分析页面（占位框架）
└── js/
    └── analysis.js                   ← 分析页 JS（空壳）
```

**分析 API**（全部需登录）：

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| GET | `/api/analysis/summary` | 汇总统计 | ✅ 空壳 |
| GET | `/api/analysis/car/{carId}` | 单车统计 | ✅ 空壳 |
| GET | `/api/analysis/leaderboard` | 排行榜 | ✅ 空壳 |
| POST | `/api/analysis/query` | 条件查询 | ✅ 空壳 |
| GET | `/api/analysis/export` | 导出报表 | ✅ 空壳（仅 admin） |

### 2.4 启动脚本修复

`start_all.bat` 在每个 `exec:java -pl` 之前增加 `call .\mvnw.cmd install -DskipTests`，确保依赖模块已安装到本地仓库。

---

## 三、修改的现有文件

| 文件 | 修改原因 |
|------|---------|
| `pom.xml`（根） | 添加 jbcrypt 依赖 |
| `common/pom.xml` | 引用 jbcrypt |
| `display/.../HttpFileServer.java` | 集成 API 路由 + HTTP 鉴权（白名单） |
| `display/.../DisplayMain.java` | 初始化 UserStore/SessionManager/AuthApiHandler/AnalysisApiHandler |
| `doc/login-auth-design.md` | 三角色矩阵 + 注册接口 + 更新实施状态 |
| `doc/statistics-analysis-design.md` | 更新实施状态为 v1.1 |
| `doc/api-specification.md` | 新增 register 接口 + 修正角色值 |
| `start_all.bat` | 启动前 install |
| `car/.../MoveExecutorTest.java` | 适配 hzx_common 最新版（移除 isBlocked 旧断言） |
| `car/.../CarMain.java` | selfRegister 增加 appendCarHistory |
| `common/.../BlackboardClient.java` | 合入 hzx_common 的 pushRoute 逆序 + peek/lpop + setElapsedSeconds |

---

## 四、从 hzx_common 合入的模块与文件

以下模块/文件在合并操作中从 `hzx_common` / `main` 分支完整拉取，不属于 lyq_car 原创但包含在当前分支中：

| 模块 | 说明 |
|------|------|
| `navigator/` | A* / BFS 路径规划 + 测试 |
| `target-planner/` | Greedy 目标分配 + 测试 |
| `task-configurator/` | Task 初始化 + 测试 |
| `display/` | HTTP 服务 + WebSocket + 前端（index.html/CSS/JS）+ 测试 |
| `launcher/` | 一键启动 + 测试 |
| `controller/` | 调度 + 状态分发 + 命令处理（来自 hzx_common 多轮更新） |
| `.mvn/`、`mvnw`、`mvnw.cmd` | Maven Wrapper |
| `docker-compose.yml` | Redis + RabbitMQ 编排 |
| `start_all.bat` | 一键启动脚本 |
| `doc/` 下设计文档 | `doc/login-auth-design.md`、`doc/statistics-analysis-design.md`、`doc/api-specification.md` 三件套（v1.0 时编写，后更新到 v1.1） |

---

## 五、测试统计

| 模块 | 测试数 | 结果 |
|------|--------|------|
| Common（含 auth 22 个） | 61 | ✅ |
| Controller | 12 | ✅ |
| Car | 22 | ✅ |
| Navigator | 21 | ✅ |
| Target Planner | 9 | ✅ |
| Task Configurator | 13 | ✅ |
| Display | 23 | ✅ |
| Launcher | 24 | ✅ |
| **合计** | **185** | **✅ BUILD SUCCESS** |

新增的认证测试：
- `UserStoreTest`：16 个（认证 9 + 注册 7）
- `SessionManagerTest`：6 个（创建/验证/过期/销毁/提取token）

---

## 六、完整文件树（lyq_car 原创新增部分）

```
Car_homework/
├── pom.xml ................................................ [修改] 添加 jbcrypt
├── start_all.bat ......................................... [修改] 启动前 install
├── notes.md .............................................. [新增] 开发笔记
├── task_plan.md .......................................... [新增] 任务计划
│
├── common/src/main/java/com/substation/common/
│   ├── DynamicObstacleUtil.java .......................... [新增] 动态障碍物
│   ├── auth/
│   │   ├── model/
│   │   │   ├── LoginRequest.java ......................... [新增] 登录请求 DTO
│   │   │   ├── LoginResponse.java ........................ [新增] 登录响应 DTO
│   │   │   ├── UserInfo.java ............................. [新增] 用户信息 DTO
│   │   │   └── SessionInfo.java .......................... [新增] 会话信息 DTO
│   │   ├── UserStore.java ................................ [新增] 用户存储 (Redis HSET)
│   │   ├── SessionManager.java ........................... [新增] 会话管理 (Token)
│   │   ├── AuthApiHandler.java ........................... [新增] 认证 API
│   │   └── AuthFilter.java ............................... [新增] 鉴权过滤器
│   ├── analysis/
│   │   ├── model/
│   │   │   ├── AnalysisQuery.java ........................ [新增] 查询参数 DTO
│   │   │   ├── SummaryStatistics.java .................... [新增] 汇总统计 DTO
│   │   │   └── CarStatistics.java ........................ [新增] 单车统计 DTO
│   │   ├── AnalysisApiHandler.java ....................... [新增] 分析 API (空壳)
│   │   └── AnalysisEngine.java ........................... [新增] 分析引擎接口
│   └── redis/
│       └── BlackboardClient.java ......................... [修改] +位置预约锁 +History
│
├── car/src/main/java/com/substation/car/
│   ├── MoveExecutor.java ................................. [修改] 重构 + 位置预约锁
│   ├── CarAgent.java ..................................... [修改] 精确异常
│   └── CarMain.java ...................................... [修改] 构造器模式
│
├── car/src/test/java/com/substation/car/
│   └── MoveExecutorTest.java ............................. [修改] 适配新架构
│
├── display/src/main/java/com/substation/display/
│   ├── HttpFileServer.java ............................... [修改] 集成 API 路由 + 鉴权
│   └── DisplayMain.java .................................. [修改] 初始化认证/分析模块
│
├── display/src/main/resources/web/
│   ├── login.html ........................................ [新增] 登录+注册页面
│   ├── analysis.html ..................................... [新增] 统计分析页面
│   └── js/
│       ├── auth.js ....................................... [新增] 前端认证逻辑
│       └── analysis.js ................................... [新增] 分析页 JS (空壳)
│
├── common/src/test/java/com/substation/common/auth/
│   ├── UserStoreTest.java ................................ [新增] 用户存储测试
│   └── SessionManagerTest.java ........................... [新增] 会话管理测试
│
└── doc/
    ├── README.md ..........................................[新增] 文档导航
    ├── login-auth-design.md ............................... [新增] 登录认证设计
    ├── statistics-analysis-design.md ...................... [新增] 统计分析设计
    ├── api-specification.md ............................... [新增] 接口文档
    └── lyq_car_full_summary.md ............................ [新增] 本文件（操作总结）
```

---

## 七、Git 提交历史（完整）

```
69883cf feat(auth): 新增用户注册功能
2204f08 feat(auth+analysis): 登录认证系统后端 + 统计分析空壳 + 前端页面
a1adc01 docs: 登录认证系统 + 统计分析界面 + 接口文档设计文档
7e745e0 merge: 拉取 hzx_common 最新更新 (8ac0f1d)
3478794 merge: 拉取 hzx_common 最新更新 (71b9116)
e74c247 fix: start_all.bat 启动前先 install 全部模块到本地仓库
8ef264c docs: 添加lyq_car分支操作记录文档
706eedc merge: 从hzx_common拉取完整项目
81fe23c fix(car+common): 位置预约锁修复小车重叠问题
```

---

## 八、环境依赖

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 17 | 编译运行 |
| Maven | 3.9 | 构建管理（mvnw） |
| Redis | Docker | 黑板状态 + 用户/会话存储 |
| RabbitMQ | Docker | 模块间 MQ 消息 |
| jbcrypt | 0.4 | 密码哈希（仅新增依赖） |
| fastjson2 | 2.0.47 | JSON 序列化（已有） |
| jedis | 5.1.5 | Redis 客户端（已有） |
| Java-WebSocket | 1.5.6 | WebSocket 服务（已有） |

---

## 九、如何启动

```bash
# 1. 启动中间件
docker-compose up -d

# 2. 编译 + 安装到本地仓库
.\mvnw.cmd install -DskipTests

# 3. 一键启动全部模块
.\start_all.bat

# 4. 打开浏览器
#    登录页:  http://localhost:8887/login.html
#    仿真页:  http://localhost:8887/index.html
#    分析页:  http://localhost:8887/analysis.html
```

**预设账号**：
- 管理员：`admin` / `admin123`
- 仿真员：`simulator1` / `sim123`
- 分析员：`analyst1` / `ana123`

**注册新用户**：访问登录页 → 点击"没有账号？立即注册" → 选择角色 → 填写信息 → 注册
