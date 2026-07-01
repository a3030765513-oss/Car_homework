# Car 模块设计文档

> **负责人**：Person B  
> **分支**：`lyq_car`  
> **日期**：2026-06-05  
> **状态**：✅ 已完成，19 个单元测试全部通过

---

## 一、模块概述

Car 是变电站巡检仿真系统的**小车知识源模块**，每个 Car 实例是一个独立 Java 进程。收到 Controller 下发的 `TICK_MOVE` 指令后，沿 Navigator 规划的路径前进一步，并点亮地图、记录轨迹。

**任务书对应要求**：

| 任务书要求 | 实现 |
|-----------|------|
| 小车可独立启动 | ✅ `CarMain` 接受 `carId` 命令行参数 |
| 可动态添加新车 | ✅ 新进程启动即自注册到 Redis 黑板 |
| 5 状态机 | ✅ IDLE / WAITING_ROUTE / READY / MOVING / BLOCKED |
| 原子移动操作 | ✅ 分布式锁保护 12 步流程 |
| History 路径记录 | ✅ 每步 RPUSH `{x, y, tick}` |
| mapHeat 热力图 | ✅ 每步 HINCRBY 点亮计数器 |

---

## 二、文件结构

```
car/
├── pom.xml
└── src/
    ├── main/java/com/substation/car/
    │   ├── CarMain.java          # 进程入口
    │   ├── CarAgent.java         # 消息分发 + 状态机
    │   └── MoveExecutor.java     # 12 步原子移动执行器
    ├── main/resources/
    │   └── logback.xml           # 日志配置
    └── test/java/com/substation/car/
        ├── CarAgentTest.java     # 6 个测试
        └── MoveExecutorTest.java # 13 个测试
```

---

## 三、核心类设计

### 3.1 CarMain — 进程入口

**启动方式**：

```bash
java -jar car.jar Car001          # 默认 Redis 端口 6379
java -jar car.jar Car002 6380     # 指定 Redis 端口
```

**启动流程**：

```
1. 解析 CLI 参数（carId、redisPort）
2. 创建 BlackboardClient（复用内部 JedisPool）
3. 从黑板读取地图尺寸（优先 TaskConfig，fallback 30×30）
4. 自注册：若黑板中无本车数据，随机找空地初始化
5. 连接 RabbitMQ，声明 Car_{carId} 队列
6. 创建 CarAgent → 订阅消息
7. 注册 shutdown hook（关闭 MQ + Redis 连接）
8. 主线程保持存活
```

**自注册逻辑**：调用 `bb.getCarStatus(carId)` 判断是否已在黑板注册：
- 已注册 → 跳过，打印当前状态
- 未注册 → 随机选取非障碍空地，写入 Position/Status/Steps/mapBlock，点亮 3×3 并写入 mapHeat

### 3.2 CarAgent — 消息分发

实现消息回调接口，处理两种消息：

| 消息类型 | 来源 | 处理 |
|---------|------|------|
| `TICK_MOVE` | Controller → Car_{carId} | 调用 `MoveExecutor.executeMove(tick)` |
| `BLOCKED_TIMEOUT` | Controller → Car_{carId} | 日志记录，从 Redis 确认状态（不写黑板） |
| 非法 JSON | — | WARN 日志并丢弃，不崩溃 |
| 未知类型 | — | WARN 日志并丢弃，不崩溃 |

**异常处理约定**：`BLOCKED_TIMEOUT` 到达时 Controller 已完成全部黑板清理（清 RouteList/Target/BlockedTick，写 IDLE），Car 只读不写，等待下一轮从 IDLE 重新分配目标。

### 3.3 MoveExecutor — 12 步原子移动

#### 主流程

```
executeMove(tick):
  ┌─ 1. 分布式锁 lock:{carId}（SET NX PX 5000）
  │     └─ 获取失败 → WARN 日志，跳过本拍（等下一 tick）
  ├─ 2. 检查状态 == READY
  │     └─ 非 READY → 释放锁，返回
  ├─ 3. 写 Status = MOVING（心跳，防 Controller 误判崩溃）
  ├─ 4. peekNextRouteStep → 获取下一步（不移除）
  │     └─ RouteList 为空 → 写 IDLE，释放锁，返回
  ├─ 5. 检查下一步是否障碍物
  │     └─ 是 → handleObstacleDetected（清路线/目标 → BLOCKED → 发 BLOCKED 消息）
  │            释放锁，返回
  ├─ 6. popNextRouteStep → 消费该步
  ├─ 7. 清除旧位置 mapBlock 标记
  ├─ 8. 更新 CarID:Position 到新位置
  ├─ 9. 新位置 mapBlock 标记占用
  ├─10. illuminateAndHeat → 3×3 点亮 + HINCRBY mapHeat
  ├─11. incrementCarSteps + recordHistory (RPUSH {x,y,tick})
  ├─12. 路径判定
  │     ├─ RouteList 为空 → clearCarTarget → IDLE → 发 ROUTE_DONE
  │     └─ RouteList 非空 → READY → 发 MOVED
  └─13. Lua 脚本原子释放锁
```

#### 3×3 点亮算法

```java
void illuminateAndHeat(Point center) {
    for (int dr = -1; dr <= 1; dr++) {
        for (int dc = -1; dc <= 1; dc++) {
            int r = center.y + dr;
            int c = center.x + dc;
            if (r >= 0 && r < mapHeight && c >= 0 && c < mapWidth) {
                bb.setMapViewBit(r, c, true);     // 点亮地图
                bb.incrementMapHeat(r, c);        // 热力图计数
            }
        }
    }
}
```

边界格子被自动裁剪，不会越界访问 bitmap。

---

## 四、状态机

Car 只写入自己管辖的状态变迁，不越界写 `WAITING_ROUTE`：

```
                    Car 写入
                    ────────
初始化注册        → IDLE

收到 TICK_MOVE    → MOVING（心跳）
  移动成功        → READY（路径仍有剩余）
                  → IDLE（路径走完）
  下一步障碍      → BLOCKED
```

| 变迁 | 触发条件 | 写入值 |
|------|----------|--------|
| (初始化) → IDLE | CarMain 自注册 | IDLE |
| READY → MOVING | 收到 TICK_MOVE，下一步无障碍 | MOVING |
| MOVING → READY | pop 后路径非空 | READY |
| MOVING → IDLE | pop 后路径为空（走完） | IDLE |
| MOVING → BLOCKED | peek 到下一步是障碍 | BLOCKED |

> ⚠️ Car **永不写** `WAITING_ROUTE`——那是 Controller 的管辖范围。

---

## 五、消息接口

### 接收消息（订阅 `Car_{carId}` 队列）

| 消息类型 | 字段 | 处理 |
|---------|------|------|
| `TICK_MOVE` | `{type, tick}` | executeMove |
| `BLOCKED_TIMEOUT` | `{type, carId}` | 只读验证 |

### 发送消息（发布到 `ControllerCmd` 队列）

| 消息类型 | data 字段 |
|---------|----------|
| `MOVED` | `{newPosition: {x,y}, routeRemaining: int}` |
| `ROUTE_DONE` | `{finalPosition: {x,y}}` |
| `BLOCKED` | `{blockedPosition: {x,y}, blockedTick: int}` |

---

## 六、Redis Key 写入

| Key | 写入场景 | 说明 |
|-----|----------|------|
| `{carId}:Position` | 自注册 / 每步移动后 | HSET x y |
| `{carId}:Status` | 状态变迁 | IDLE/READY/MOVING/BLOCKED |
| `{carId}:Steps` | 每步移动后 | INCR |
| `{carId}:BlockedTick` | 检测到障碍 | 记录受阻 tick |
| `{carId}:History` | 每步移动后 | RPUSH `{x,y,tick}` JSON |
| `mapView` | 3×3 点亮 | SETBIT |
| `mapBlock` | 自注册 / 旧位置清除 / 新位置标记 | SETBIT |
| `mapHeat` | 3×3 点亮 | HINCRBY row,col 1 |

只读 Key：`{carId}:RouteList`（peek/pop）、`TaskConfig`、`{carId}:Target`

---

## 七、分布式锁

```java
DistributedLock lock = new DistributedLock(jedisPool, carId);
if (lock.tryLock()) {    // SET lock:Car001 value NX PX 5000
    try {
        doMove(tick);
    } finally {
        lock.unlock();   // Lua: if get == value then del
    }
}
```

- 超时 5 秒，获取失败时跳过本拍（不阻塞 tick 循环）
- 释放用 Lua 脚本验证 ownership，避免误删其他进程的锁

---

## 八、测试覆盖

### MoveExecutorTest（13 个测试）

| 测试 | 覆盖场景 |
|------|----------|
| `executeSingleMove_READYtoREADY` | 正常移动一步，路径有剩余 |
| `executeLastMove_READYtoIDLE` | 最后一步，路径走完 |
| `blockedByObstacle` | 下一步有障碍 → BLOCKED |
| `skipWhenNotREADY_idle` | IDLE 状态跳过 |
| `skipWhenNotREADY_moving` | MOVING 状态跳过 |
| `skipWhenNotREADY_blocked` | BLOCKED 状态跳过 |
| `readyButEmptyRoute_becomesIdle` | READY 但 RouteList 为空 |
| `illuminate3x3_onMove` | 3×3 区域正确点亮 |
| `illuminateEdgeClipped` | 边界格子裁剪 |
| `recordHistory_onMove` | History 路径记录 |
| `incrementMapHeat_onMove` | 热力图计数器 |
| `lockPreventsConcurrentMove` | 分布式锁互斥 |
| `statusGoesToMovingThenBack` | MOVING 心跳 |

### CarAgentTest（6 个测试）

| 测试 | 覆盖场景 |
|------|----------|
| `handleTickMove_executesMove` | TICK_MOVE 正常处理 |
| `handleTickMove_routeDone` | TICK_MOVE 走完路径 |
| `handleBlockedTimeout_whenIdle` | BLOCKED_TIMEOUT IDLE 状态 |
| `handleBlockedTimeout_whenBlocked` | BLOCKED_TIMEOUT BLOCKED 状态 |
| `handleInvalidJson_doesNotCrash` | 非法 JSON 不崩溃 |
| `handleUnknownType_doesNotCrash` | 未知消息类型不崩溃 |

### 运行方式

```bash
# 前置条件：Redis (6379) + RabbitMQ (5672) 已启动
cd Car_homework
mvn test -pl common,car -am
```

**结果**：Common 35 + Car 19 = **54/54 全部通过**

---

## 九、与其他模块的接口契约

### 启动顺序

Car 依赖 common 模块。Car 可在 Controller 之前或之后启动——启动后进入 IDLE 等待 TICK_MOVE。

### 与 Controller 的协作

```
Controller                  Car
    │                         │
    ├── TICK_MOVE ──────────→│  (Car_{carId} 队列)
    │                         │
    │                    executeMove()
    │                         │
    │←── MOVED ──────────────┤  (ControllerCmd 队列)
    │←── ROUTE_DONE ─────────┤
    │←── BLOCKED ────────────┤
    │                         │
    ├── BLOCKED_TIMEOUT ────→│  (仅读取确认，不写黑板)
```

### 与 Navigator 的间接协作

- Navigator 写 `{carId}:RouteList`（LPUSH）
- Car 读 `{carId}:RouteList`（peek=LINDEX -1，pop=RPOP）
- 二者不直接通信，通过 Redis 黑板交换路径数据

### 与 Display 的间接协作

- Display 读取 `{carId}:Position`、`{carId}:Status`、`{carId}:Steps`、`{carId}:History`、`mapHeat`
- Car 不直接向 Display 发消息

---

## 十、环境依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | 编译运行 |
| Redis | 7.x (Docker) | 黑板读写 + 分布式锁 |
| RabbitMQ | 3.12+ (Docker) | MQ 消息收发 |
| Jedis | 5.1.x | Redis 客户端 |
| AMQP Client | 5.21.x | RabbitMQ 客户端 |
| fastjson2 | 2.0.47 | JSON 序列化 |
| SLF4J + Logback | 2.0.x / 1.5.x | 日志 |

---

## 十一、常见问题

**Q: 如何添加新车？**
```bash
java -jar car.jar Car006
```
进程自动在黑板注册，Controller 下一个 tick 通过 `KEYS Car*:Status` 发现新车并分配目标。

**Q: 移动失败怎么办？**
- 锁获取失败 → 跳过本拍，等下一 tick
- 下一步障碍 → 清路线/目标，发 BLOCKED，等 Controller 重新分配
- 路径走完 → 发 ROUTE_DONE，等下一轮 IDLE → 分配新目标

**Q: 如何修改 Redis 端口？**
```bash
java -jar car.jar Car001 6380   # 第二个参数指定端口
```
