# Common 接口设计 + Controller 调度算法说明

> 负责人：Person A  
> 日期：2026-06-05

---

## 一、Common 模块接口设计

Common 是全部模块的公共基础层，提供数据结构、Redis 黑板客户端、RabbitMQ 消息总线三类能力。

### 1.1 数据结构

#### Point — 坐标

```java
public record Point(int x, int y) {
    int manhattanDistance(Point other);  // |dx| + |dy|
    String toJson();                     // fastjson2 序列化
    static Point fromJson(String json);  // fastjson2 反序列化
}
```

使用 Java 17 record，自动生成 equals/hashCode/toString，不可变，线程安全。

#### CarStatus — 车辆状态枚举

| 枚举值 | 中文名 | 颜色 | 含义 |
|--------|--------|------|------|
| IDLE | 空闲 | #9E9E9E | 等待分配目标 |
| WAITING_ROUTE | 等待路径 | #FF9800 | 已有目标，等待 Navigator 规划路径 |
| READY | 就绪 | #4CAF50 | 已有路径，等待 TICK_MOVE 指令 |
| MOVING | 移动中 | #2196F3 | 正在执行移动 |
| BLOCKED | 受阻 | #F44336 | 下一步被障碍物阻挡 |

#### AlgorithmType — 路径算法枚举

```java
public enum AlgorithmType { BFS, ASTAR }
```

#### RouteStep — 路径步骤

```java
public record RouteStep(Point position, int stepIndex) {}
```

#### SimulationState — 全局状态快照

```java
public record SimulationState(
    int tick,                      // 当前节拍号
    int explorationRate,           // 探索率百分比
    Map<String, String> taskConfig,// 任务配置
    List<CarInfo> cars,            // 所有车辆信息
    boolean[][] mapView,           // 已探索地图
    boolean[][] mapBlock           // 障碍物地图
) {
    public record CarInfo(
        String carId,              // 车辆编号
        int number,                // 显示编号
        Point position,            // 当前位置
        Point target,              // 目标位置
        List<Point> routeList,     // 规划路径
        CarStatus status,          // 当前状态
        int steps                  // 已走步数
    ) {}
}
```

供 Display 模块（WebSocketBridge）每节拍构建前端 JSON 的数据载体。

---

### 1.2 Redis 黑板客户端 — BlackboardClient

全部模块通过 BlackboardClient 读写 Redis 黑板，不直接接触 Jedis API。

#### 构造

```java
BlackboardClient bb = new BlackboardClient("localhost", 6379, mapWidth, mapHeight);
```

#### MapView / MapBlock（Bitmap 操作）

| 方法 | 说明 |
|------|------|
| `boolean getMapViewBit(int row, int col)` | 某格是否已探索 |
| `void setMapViewBit(int row, int col, boolean explored)` | 设置探索状态 |
| `int getExplorationRate()` | 探索率 0-100（已探索/可探索*100） |
| `boolean isBlocked(int row, int col)` | 某格是否为障碍 |
| `void setBlock(int row, int col, boolean blocked)` | 设置障碍状态 |

位图索引公式：`offset = row * mapWidth + col`，30×30 地图使用 900 bit。

#### Car Position / Target / Route / Status / Steps / BlockedTick

| 方法 | 说明 |
|------|------|
| `Optional<Point> getCarPosition(String carId)` | 获取车辆位置 |
| `void setCarPosition(String carId, Point pos)` | 设置车辆位置（HSET x y） |
| `Optional<Point> getCarTarget(String carId)` | 获取目标点 |
| `void setCarTarget(String carId, Point target)` | 设置目标点 |
| `void clearCarTarget(String carId)` | 移除目标 |
| `boolean hasTarget(String carId)` | 是否有目标 |
| `List<Point> getCarRoute(String carId)` | 获取完整路径 |
| `Optional<Point> peekNextRouteStep(String carId)` | 查看下一步（不移除） |
| `Optional<Point> popNextRouteStep(String carId)` | 取出下一步 |
| `void pushRoute(String carId, List<Point> route)` | 批量写入路径 |
| `void clearRoute(String carId)` | 清空路径 |
| `Optional<CarStatus> getCarStatus(String carId)` | 获取状态 |
| `void setCarStatus(String carId, CarStatus status)` | 设置状态 |
| `int getCarSteps(String carId)` | 获取步数 |
| `void incrementCarSteps(String carId)` | 步数+1 |
| `int getBlockedTick(String carId)` | 获取受阻节拍（不存在返回-1） |
| `void setBlockedTick(String carId, int tick)` | 记录受阻节拍 |
| `void clearBlockedTick(String carId)` | 清除受阻记录 |

**路径存储约定**：LPUSH 写入（路径首点最先 push），RPOP 取出（LINDEX -1 查看下一步）。这样保证了先进先出的行走顺序。

#### TaskConfig / mapHeat / 控制器锁 / 车辆发现

| 方法 | 说明 |
|------|------|
| `Map<String, String> getTaskConfig()` | 获取任务配置 |
| `boolean isTaskActive()` | 任务是否激活 |
| `void initTaskConfig(Map<String, String> config)` | 批量写入配置 |
| `void incrementMapHeat(int row, int col)` | 热力图计数+1 |
| `Map<String, String> getMapHeat()` | 获取热力图数据 |
| `boolean acquireControllerLock()` | 获取单实例锁（SET NX EX 30） |
| `void releaseControllerLock()` | 释放单实例锁 |
| `Set<String> discoverCarIds()` | 动态发现已注册车辆（KEYS Car*:Status） |
| `JedisPool getJedisPool()` | 暴露连接池，供需要原生 Jedis 的场景 |

#### Redis Key 全景

| Key | 类型 | 写入者 | 读取者 |
|-----|------|--------|--------|
| mapView | bitmap | Car, TaskConfigurator | 全员 |
| mapBlock | bitmap | Car, TaskConfigurator | 全员 |
| {carId}:Position | hash {x,y} | Car, TaskConfigurator | 全员 |
| {carId}:Target | hash {x,y} | TargetPlanner, Controller | 全员 |
| {carId}:RouteList | list | Navigator, Car, Controller | 全员 |
| {carId}:History | list | Car | Display |
| {carId}:Status | string | Controller, Car | 全员 |
| {carId}:Steps | string | Car | 全员 |
| {carId}:BlockedTick | string | Car, Controller | Controller |
| TaskConfig | hash | TaskConfigurator, Controller | 全员 |
| mapHeat | hash | Car | Display |
| controller:instance | string | Controller | — |

---

### 1.3 RabbitMQ 消息总线 — MessageBus

#### 构造与连接

```java
MessageBus bus = new MessageBus("localhost", 5672, "guest", "guest");
bus.connect();
```

#### 队列声明

| 方法 | 声明的队列 |
|------|-----------|
| `declareCarQueue(String carId)` | Car_{carId} (Classic, durable) |
| `declareNavigatorQueue()` | NavigatorCmd |
| `declareTargetPlannerQueue()` | TargetPlannerCmd |
| `declareTaskConfigQueue()` | TaskConfigCmd |
| `declareControllerQueue()` | ControllerCmd |
| `declareFanoutExchange()` | UpdateView (Fanout Exchange) |
| `bindFanoutQueue()` | 自动生成独占队列绑定到 UpdateView |

#### 消息收发

```java
void publish(String queueName, String message);          // 点对点
void publishFanout(String exchangeName, String message); // 广播
void subscribe(String queueName, Consumer<String> handler); // 订阅
```

---

### 1.4 消息格式

#### 统一 JSON 结构

```json
{
  "type": "消息类型",
  "tick": 当前节拍,
  "carId": "Car001",
  "timestamp": 1717400000000,
  "data": { }
}
```

由 `MessageBuilder.build(type, tick, carId, data)` 统一构建，fastjson2 序列化。

#### 17 种消息类型

```
ASSIGN_TARGET         Controller → TargetPlanner   分配目标
TARGET_ASSIGNED       TargetPlanner → Controller   目标分配结果
PLAN_ROUTE            Controller → Navigator       请求路径
ROUTE_PLANNED         Navigator → Controller       路径规划结果
TICK_MOVE             Controller → Car             节拍移动指令
MOVED                 Car → Controller             移动完成
ROUTE_DONE            Car → Controller             路径走完
BLOCKED               Car → Controller             遇到障碍
BLOCKED_TIMEOUT       Controller → Car             阻塞超时通知
REFRESH_ALL           Controller → Fanout          广播刷新
SET_CONFIG            Display → Controller         Web 配置
FORWARD_CONFIG        Controller → TaskConfigurator 转发配置
RESET                 Display → Controller         重置
FORWARD_RESET         Controller → TaskConfigurator 转发重置
TASK_READY            TaskConfigurator → Controller 初始化完成
TOGGLE_PAUSE          Display → Controller         暂停/继续
SET_TICK_INTERVAL     Display → Controller         调速
```

---

### 1.5 分布式锁 — DistributedLock

```java
DistributedLock lock = new DistributedLock(jedisPool, "Car001");
boolean ok = lock.tryLock(5000);  // 超时 5 秒
lock.unlock();                    // Lua 脚本验证 ownership 后释放
```

基于 `SET key value NX PX 5000`，释放使用 Lua 脚本确保原子性：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

---

## 二、Controller 调度算法

### 2.1 架构概览

```
ControllerMain（入口）
  ├── TickScheduler（定时器）
  │     └── 驱动 StatusDispatcher.dispatch()
  └── CommandHandler（MQ 订阅）
        └── 处理知识源回复，更新黑板 + 调度器状态
```

### 2.2 单实例保障

ControllerMain 启动时通过 `SET controller:instance "1" NX EX 30` 获取互斥锁。若已有实例运行，获取失败，打印错误并 `System.exit(1)`。正常退出时 shutdown hook 主动释放锁；异常崩溃时锁在 30 秒后自动过期。

### 2.3 节拍驱动

TickScheduler 使用 `ScheduledExecutorService.scheduleWithFixedDelay`，默认间隔 500ms。每个节拍执行一次完整的 `dispatch()` 流程。

**暂停/继续**：收到 `TOGGLE_PAUSE` 消息后翻转 `paused` 标志，tick 循环入口检查标志，暂停时跳过所有调度逻辑。

**节拍调速**：收到 `SET_TICK_INTERVAL` 消息后，取消当前 ScheduledFuture，以新间隔重建定时任务。间隔范围 100ms ~ 2000ms。

### 2.4 核心调度算法：dispatch()

```
dispatch():
  ├── 1. 任务激活检查 → 未激活则跳过
  ├── 2. 探索率判定 → ≥99% 则完成，记录耗时
  ├── 3. 动态发现车辆（KEYS Car*:Status）
  ├── 4. 状态分派（第一遍遍历）
  │     ├── IDLE          → 发 ASSIGN_TARGET
  │     ├── WAITING_ROUTE → 有目标则发 PLAN_ROUTE
  │     ├── READY         → 跳过（第二遍处理）
  │     ├── MOVING        → 连续≥2 tick 仍 MOVING 则重置为 READY
  │     └── BLOCKED       → tick - blockedTick ≥ 2 则清空路径/目标，设 IDLE
  ├── 5. TICK_MOVE 广播（第二遍遍历）
  │     └── 所有 READY 车 → 发 TICK_MOVE
  └── 6. 广播 REFRESH_ALL 到 UpdateView Fanout
```

### 2.5 状态变迁表

```
                  Controller 写入           Car 写入
                  ──────────────           ────────
初始化        → IDLE
IDLE          → (发 ASSIGN_TARGET)
收到目标      → WAITING_ROUTE
收到路径      → READY                     
收到 TICK_MOVE                            → MOVING
移动一步                                  → READY（路径非空）
                                         → IDLE（路径走完）
                                         → BLOCKED（有障碍）
受阻超时      → IDLE（≥2 tick 后）
移动卡死      → READY（≥2 tick 后）
```

**关键设计决策**：Controller 和 Car 各自写入自己管辖的状态变迁，互不越界。TargetPlanner 和 Navigator 只写自己领域数据（Target 和 RouteList），不写 Status。

### 2.6 超时与异常恢复

| 场景 | 检测条件 | 恢复动作 |
|------|----------|----------|
| 目标不可达 | Navigator 返回 routeFound=false | 设 IDLE，下一轮重新分配 |
| 移动卡死 | 连续 ≥2 tick 状态仍为 MOVING（Car 可能崩溃） | 强制设 READY，重新发 TICK_MOVE |
| 阻塞超时 | 当前 tick - blockedTick ≥ 2 | 清空路径/目标/受阻标记，设 IDLE，通知 Car |
| 控制器崩溃 | controller:instance 锁 30s 过期 | 新实例可启动 |

### 2.7 消息路由表

| 消息 | 发送方 | 接收方 | 队列 |
|------|--------|--------|------|
| ASSIGN_TARGET | Controller | TargetPlanner | TargetPlannerCmd |
| TARGET_ASSIGNED | TargetPlanner | Controller | ControllerCmd |
| PLAN_ROUTE | Controller | Navigator | NavigatorCmd |
| ROUTE_PLANNED | Navigator | Controller | ControllerCmd |
| TICK_MOVE | Controller | Car | Car_{carId} |
| MOVED | Car | Controller | ControllerCmd |
| ROUTE_DONE | Car | Controller | ControllerCmd |
| BLOCKED | Car | Controller | ControllerCmd |
| BLOCKED_TIMEOUT | Controller | Car | Car_{carId} |
| REFRESH_ALL | Controller | Display | UpdateView (Fanout) |
| SET_CONFIG | Display | Controller | ControllerCmd |
| FORWARD_CONFIG | Controller | TaskConfigurator | TaskConfigCmd |
| RESET | Display | Controller | ControllerCmd |
| FORWARD_RESET | Controller | TaskConfigurator | TaskConfigCmd |
| TASK_READY | TaskConfigurator | Controller | ControllerCmd |
| TOGGLE_PAUSE | Display | Controller | ControllerCmd |
| SET_TICK_INTERVAL | Display | Controller | ControllerCmd |

### 2.8 任务完成判定

每个 tick 检查 `getExplorationRate()`，当探索率 ≥ 99% 时触发完成：停止 tick 循环，计算从 tick=1 到当前的耗时秒数，写入 Redis TaskConfig.elapsedSeconds，供前端显示。
