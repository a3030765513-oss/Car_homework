# Display 模块设计 + Launcher 模块设计

> 负责人：Person D  
> 日期：2026-06-05

---

## 一、Display 模块架构设计

### 1.1 架构概览

```
DisplayMain（入口）
  ├── MessageBus（RabbitMQ 客户端）
  │     ├── 订阅 UpdateView Fanout（接收 REFRESH_ALL 广播）
  │     └── 发布到 ControllerCmd（转发浏览器命令）
  ├── WebSocketBridge（WebSocket 服务端，端口 8888）
  │     ├── 管理浏览器连接（ConcurrentHashMap）
  │     ├── 下行：读 Redis 黑板 → 组装 SimulationState → JSON 广播
  │     └── 上行：接收浏览器消息 → 原样转发到 MQ ControllerCmd
  └── HttpFileServer（HTTP 静态文件服务，端口 8887）
        └── 提供 display/src/main/resources/web/ 目录下的 HTML/CSS/JS
```

Display 模块是系统的"眼睛"——将 Redis 黑板中的数字状态转化为浏览器中可视化的地图、小车、路径和状态面板。

### 1.2 三大组件

| 组件 | 技术选型 | 端口 | 职责 |
|------|----------|------|------|
| DisplayMain | 纯 Java，手动依赖注入 | — | 创建组件、连接 MQ、订阅 Fanout、编排启动 |
| WebSocketBridge | `org.java-websocket:Java-WebSocket:1.5.6` | 8888 | 双向消息桥接（Java ↔ 浏览器） |
| HttpFileServer | `com.sun.net.httpserver.HttpServer` (JDK 内置) | 8887 | 极简静态文件服务，零外部依赖 |

**技术选型理由**：

- **WebSocket 而非 SSE**：需要双向通信——前端发控制命令（开始/暂停/重置/调速），后端推状态快照，WebSocket 的全双工特性天然适合
- **JDK 内置 HTTP 服务而非 Jetty/Tomcat**：前端仅 3 个静态文件（HTML/CSS/JS 合计 < 20KB），引入 Servlet 容器是过度设计
- **同步阻塞而非 NIO**：前端文件请求量极低（一个浏览器仅 3 次请求），NIO 异步在此场景下没有收益

### 1.3 数据流

```
Controller.tick()
  → messageBus.publishFanout("UpdateView", REFRESH_ALL 消息)
    → DisplayMain.onRefreshAllReceived(rawMsg)
      → 解析 tick + explorationRate
        → wsBridge.pushSimulationState(tick, explorationRate)
          → BlackboardClient 读取 Redis 全部数据:
              ├── getTaskConfig()          → Map<String,String>
              ├── getMapViewBit() × 900    → boolean[][] (30×30)
              ├── isBlocked() × 900        → boolean[][] (30×30)
              │
              └── discoverCarIds() → 遍历每台车:
                    ├── getCarPosition()    → Point
                    ├── getCarTarget()      → Point (nullable)
                    ├── getCarRoute()       → List<Point>
                    ├── getCarStatus()      → CarStatus
                    └── getCarSteps()       → int
                          ↓
              组装 SimulationState record
                          ↓
              JSON.toJSONString(state) —— fastjson2 序列化
                          ↓
              broadcast(json) —— 推送给所有连接的浏览器
```

**关键优化**：如果当前无浏览器连接（`clients.isEmpty()`），直接跳过 Redis 读取和 JSON 序列化，避免无效 I/O。

### 1.4 多实例支持

UpdateView 是 Fanout Exchange，每个 DisplayMain 进程绑定自己的**独占队列**（exclusive queue），同时接收广播独立推送。可启动多个 DisplayMain 进程（不同端口）支持多个浏览器同时查看，互不干扰。

---

## 二、WebSocket 协议设计

### 2.1 连接信息

| 项目 | 值 |
|------|-----|
| 协议 | ws:// |
| 地址 | localhost |
| 端口 | 8888（默认，可通过 `--ws-port` 修改） |
| 心跳 | 无应用层心跳（依赖 TCP keepalive） |
| 重连 | 前端 `onclose` 触发 3 秒后自动重连 |

### 2.2 下行消息格式（Java → 浏览器）

每节拍推送一次，由 WebSocketBridge 将 SimulationState record 直接 JSON 序列化：

```json
{
  "tick": 42,
  "explorationRate": 67,
  "taskConfig": {
    "mapWidth": "30",
    "mapHeight": "30",
    "carCount": "5",
    "obstacleRatio": "0.15",
    "algorithmType": "ASTAR",
    "tickInterval": "500",
    "active": "true"
  },
  "cars": [
    {
      "carId": "Car001",
      "number": 1,
      "position": { "x": 5, "y": 10 },
      "target": { "x": 20, "y": 15 },
      "routeList": [
        { "x": 6, "y": 10 },
        { "x": 7, "y": 10 }
      ],
      "status": "MOVING",
      "steps": 42
    }
  ],
  "mapView": [[false, true, ...], ...],
  "mapBlock": [[false, false, ...], ...]
}
```

**设计决策**：状态颜色（如 MOVING → `#2196F3`）不写入 JSON。前端本地维护 `STATUS_COLORS` 查表对象，根据 `status` 字段名映射颜色。减少每帧 ~20 字节传输冗余。

### 2.3 上行消息格式（浏览器 → Java）

浏览器通过 WebSocket 发送 JSON 消息，WebSocketBridge.onMessage() 不解析内容，**原样转发到 RabbitMQ ControllerCmd 队列**。Controller 的 CommandHandler 负责识别 type 并分发处理。

#### SET_CONFIG — 开始任务

```json
{
  "type": "SET_CONFIG",
  "data": {
    "mapWidth": 30,
    "mapHeight": 30,
    "carCount": 5,
    "obstacleRatio": 0.15,
    "algorithm": "ASTAR",
    "tickInterval": 500,
    "active": true
  }
}
```

#### TOGGLE_PAUSE — 暂停/继续

```json
{ "type": "TOGGLE_PAUSE", "data": {} }
```

#### RESET — 重置

```json
{ "type": "RESET", "data": {} }
```

#### SET_TICK_INTERVAL — 调速

```json
{
  "type": "SET_TICK_INTERVAL",
  "data": { "interval": 1000 }
}
```

**为什么 WebSocketBridge 不解析消息**：遵循"通信层不含业务逻辑"原则。WebSocketBridge 只做转发，消息类型的识别和分发交给 Controller 的 CommandHandler。这样新增消息类型不需要改动 Display 模块。

### 2.4 异常处理

| 场景 | 处理方式 |
|------|----------|
| 浏览器发送非法 JSON | `onMessage` 中 try-catch，LOG WARN + 丢弃，不崩溃 |
| MQ 连接断开 | RabbitMQ 自动恢复（AutorecoveringConnection），恢复后继续接收 REFRESH_ALL |
| Redis 连接断开 | pushSimulationState 中 catch 异常 → LOG ERROR → 不崩溃，等下个 REFRESH_ALL 重试 |
| WebSocket 连接断开 | 前端 `onclose` → 3 秒后自动重连，onClose 中从 clients 集合移除 |
| BlackboardClient 返回空数据 | Optional 处理 → 跳过该车，不抛异常 |

---

## 三、Canvas 分层渲染设计

### 3.1 画布规格

| 参数 | 值 |
|------|-----|
| 画布尺寸 | 540 × 540 px |
| 格数 | 30 × 30（动态适配 TaskConfig.mapWidth/mapHeight） |
| 每格像素 | 18 px |
| 渲染频率 | 每次 WebSocket 消息到达即全量重绘 |

### 3.2 五层渲染顺序

```
L1: 网格背景        ← ctx.strokeStyle = '#2a2a4a'
    ├── 清空 Canvas
    └── 画 30×30 网格线

L2: 已探索区域      ← 数据源: mapView[r][c]
    └── 填充色 '#16213e'（比未探索 '#0f0f23' 亮一级）

L3: 障碍物          ← 数据源: mapBlock[r][c]
    └── 实心方块 '#e94560'（红色，醒目提示）

L4: 规划路径        ← 数据源: car.routeList[]
    └── 半透明折线 rgba(100,200,255,0.5)，最多绘制前 10 步

L5: 小车            ← 数据源: car.position
    ├── 实心圆（半径 7px）
    ├── 状态颜色外圈（线宽 2px）
    └── 编号文字（白色 10px 字体居中）
```

### 3.3 配色方案

| 元素 | 色值 | 说明 |
|------|------|------|
| 页面背景 | `#1a1a2e` | 暗色工业风基调 |
| 面板背景 | `#16213e` | 比页面背景稍亮 |
| 面板边框 | `#2a2a4a` | 层次分隔 |
| Canvas 未探索 | `#0f0f23` | 极深蓝 |
| Canvas 已探索 | `#16213e` | 略亮于未探索 |
| Canvas 网格线 | `#2a2a4a` | 半隐 |
| 障碍物 | `#e94560` | 醒目红色 |
| 规划路径 | `rgba(100,200,255,0.5)` | 半透明青蓝 |
| 标题强调 | `#e94560` | 与障碍色统一 |
| 正文 | `#e0e0e0` | 高可读性 |
| 次要文字 | `#a0a0b0` | 标签、辅助信息 |
| 排行第一 | `#FFD700` | 金色高亮 |

### 3.4 车辆状态色映射

前端本地维护 `STATUS_COLORS` 对象，与 `CarStatus` 枚举保持一致。Person A 修改 `CarStatus.color()` 时，前端只需相应更新此映射。

| 状态 | 颜色 | 含义 |
|------|------|------|
| IDLE | `#9E9E9E` 灰 | 等待分配目标 |
| WAITING_ROUTE | `#FF9800` 橙 | 已有目标，等待路径规划 |
| READY | `#4CAF50` 绿 | 已有路径，等待移动指令 |
| MOVING | `#2196F3` 蓝 | 正在执行移动 |
| BLOCKED | `#F44336` 红 | 被障碍物阻挡 |

---

## 四、前端 JS 模块设计

### 4.1 模块划分

```
app.js（679 行，单一 JS 文件，IIFE 包裹）
├── 常量层        CELL_SIZE, DEFAULT_GRID, RECONNECT_DELAY_MS
├── 状态层        STATUS_COLORS, STATUS_NAMES, DOM 缓存
├── 连接层        connectWebSocket() → 重连 / 消息收发
│                 onmessage → 解析 JSON → 更新 state → render()
├── 渲染层        render() → 调度 5 层渲染 + 面板 + 排行榜 + 信息
│                 renderGrid()      L1 网格
│                 renderExplored()  L2 探索区域
│                 renderObstacles() L3 障碍物
│                 renderRoutes()    L4 路径
│                 renderCars()      L5 小车
├── 面板层        renderCarsPanel()     右侧车辆状态卡
│                 renderLeaderboard()   步数排行榜 (sort by steps desc)
│                 updateGlobalInfo()    节拍号/探索率/耗时
├── 交互层        onStartClick()          收集表单 → SET_CONFIG
│                 onPauseClick()          TOGGLE_PAUSE
│                 onResetClick()          RESET
│                 onTickIntervalChange()  SET_TICK_INTERVAL
└── 回放层        onReplayClick()     进入回放模式
                  exitReplay()        返回实时模式
                  replayTickForward() 逐帧播放
                  lookupReplayPosition() 历史位置查询
```

### 4.2 关键实现细节

**动态车辆适配**：不预设 5 台固定数组，遍历 `data.cars[]` 动态渲染。`carId` → 提取数字部分显示（`Car001` → `001`）。Canvas 和状态卡均动态适配任意车辆数。

**耗时统计**：首次收到 `tick >= 1` 的消息时记录 `startTimestamp`，启动 `setInterval` 每秒更新 `⏱ MM:SS`。收到 `explorationRate >= 99` 或点击 RESET 时停止计时器。

**路径回放状态机**：

```
live ──[点击"回放"]──→ replay
replay ──[点击"实时"]──→ live

replay 模式:
  1. 收集所有车的 history 数据 → Map<carId, [{x,y,tick}]>
  2. 构建时间轴: minTick / maxTick 范围
  3. 渲染时查表获取各车位置（而非读取 WebSocket 实时数据）
  4. 控制: [◀◀ 上帧] [▶ 播放/⏸ 暂停] [▶▶ 下帧] 滑块拖动
```

---

## 五、Launcher 模块设计

### 5.1 职责

LauncherMain 是开发/演示阶段的"一键启动器"。系统由 7 个独立模块组成，每个模块有自己的入口类。LauncherMain 在同进程内按依赖顺序启动全部模块，用一个命令替代 7 个终端窗口。

### 5.2 启动顺序与等待时间

```
LauncherMain.main()
  ├─ 解析命令行参数（--redis-host / --mq-host / --cars / --http-port / --ws-port）
  ├─ 打印配置横幅
  ├─ 注册 JVM ShutdownHook（逆序关闭）
  │
  ├─ ① TaskConfigurator → sleep 500ms
  ├─ ② Navigator
  ├─ ③ TargetPlanner → sleep 300ms
  ├─ ④ Car × N → 每台间隔 200ms 错峰
  ├─ ⑤ Display → sleep 300ms
  ├─ ⑥ Controller → sleep 1000ms
  │
  └─ Thread.currentThread().join() 等待 Ctrl+C
```

**等待时间的必要性**：模块初始化不是瞬时的（写 Redis、声明 MQ 队列），间隔不足会导致下游读到未就绪的数据。Controller 最后启动是因为它一开始就发 tick——如果 Navigator 还没订阅队列，第一条路径规划请求就丢了。

### 5.3 线程模型

每个模块在独立**非守护线程**中调用 `new XxxMain(...).start()`，触发即返回（start 内部已启动各自的 accept/consumer/scheduler 后台线程）。JVM 由各模块的非守护线程保持存活。按 Ctrl+C 触发逆序优雅关闭。

### 5.4 命令行参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--redis-host` | localhost | Redis 地址 |
| `--redis-port` | 6379 | Redis 端口 |
| `--mq-host` | localhost | RabbitMQ 地址 |
| `--mq-port` | 5672 | RabbitMQ 端口 |
| `--cars` | 5 | 小车数量 |
| `--http-port` | 8887 | HTTP 文件服务端口 |
| `--ws-port` | 8888 | WebSocket 服务端口 |
| `--help` | — | 打印帮助并退出 |

### 5.5 当前启动状态

| 模块 | 状态 | 行为 |
|------|------|------|
| controller | ✅ 已实现 | `new ControllerMain(...).start()` |
| display | ✅ 已实现 | `new DisplayMain(...).start()` |
| task-configurator | ❌ 待开发 | LOG WARN 跳过 |
| navigator | ❌ 待开发 | LOG WARN 跳过 |
| target-planner | ❌ 待开发 | LOG WARN 跳过 |
| car | ❌ 待开发 | LOG WARN 跳过 |

等各模块开发完成后，将对应的 `startPlaceholder()` 替换为实际启动代码即可。

### 5.6 构建方式

使用 maven-shade-plugin 生成包含所有依赖的"胖 JAR"（uber-jar），Main-Class 写入 MANIFEST.MF：

```bash
mvn package -pl launcher
java -jar launcher/target/launcher-1.0-SNAPSHOT.jar --cars 10
```

---

## 六、文件清单

### 6.1 display 模块

| # | 文件 | 行数 | 类型 |
|---|------|------|------|
| 1 | `display/.../DisplayMain.java` | 284 | Java 入口类 |
| 2 | `display/.../WebSocketBridge.java` | 303 | WebSocket 桥接器 |
| 3 | `display/.../HttpFileServer.java` | 142 | HTTP 静态文件服务 |
| 4 | `display/.../web/index.html` | 150 | 前端主页 |
| 5 | `display/.../web/css/style.css` | 622 | 暗色工业风样式 |
| 6 | `display/.../web/js/app.js` | 679 | 前端全部逻辑 |

### 6.2 launcher 模块

| # | 文件 | 行数 | 类型 |
|---|------|------|------|
| 7 | `launcher/.../LauncherMain.java` | 300 | 一键启动入口 |
| 8 | `launcher/pom.xml` | 218 | Maven 构建描述 |

### 6.3 单元测试

| # | 文件 | 测试数 | 覆盖内容 |
|---|------|--------|----------|
| 9 | `display/.../HttpFileServerTest.java` | 14 | Content-Type 检测 + 构造启停 |
| 10 | `display/.../WebSocketBridgeTest.java` | 9 | extractCarNumber + parseIntOrDefault |
| 11 | `launcher/.../LauncherMainTest.java` | 24 | LaunchConfig + parseArgs + nextArg + nextIntArg + findWebRoot |

**全部 47 个测试通过，0 失败 0 错误。**

---

## 七、测试结果汇总

```
模块                  测试数    通过    失败    错误    跳过
─────────────────────────────────────────────────────────
HttpFileServerTest      14      14      0       0       0
WebSocketBridgeTest      9       9      0       0       0
LauncherMainTest        24      24      0       0       0
─────────────────────────────────────────────────────────
合计                    47      47      0       0       0
```

测试覆盖了纯逻辑层（参数解析、整数解析、Content-Type 映射、车辆编号提取），涉及 Redis/RabbitMQ/WebSocket 的集成测试留到阶段 4 联调时补充。

### 已知限制

| 限制 | 说明 |
|------|------|
| HTTP Content-Type 大小写敏感 | `.HTML` 扩展名不会被识别为 text/html（已知，不影响实际使用，所有前端文件用小写扩展名） |
| parseArgs 错误路径未测 | 未知参数/缺失值触发 System.exit，JUnit 无法捕获（文档已记录） |
| 集成测试未覆盖 | 涉及 Redis + RabbitMQ 的功能测试需完整基础设施，阶段 4 补充 |

---

## 八、与 Person A 的接口依赖

| 依赖项 | 使用的类/方法 | 用途 |
|--------|--------------|------|
| 数据结构 | `SimulationState`, `CarInfo`, `Point`, `CarStatus` | 前后端数据传输载体 |
| 黑板读取 | `BlackboardClient` 全部只读方法 | 构建每帧全局状态快照 |
| 消息总线 | `MessageBus.connect/subscribe/publish` | 订阅 REFRESH_ALL + 发送 ControllerCmd |
| 消息格式 | `MessageTypes`, `QueueNames`, `MessageBuilder` | 消息类型常量 + 队列名称 |
| Controller | REFRESH_ALL 消息 + ControllerCmd 消费 | Display 订阅/发送，Controller 消费 |
