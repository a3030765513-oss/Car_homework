# Redis 黑板（BlackboardClient）说明

`BlackboardClient` 是系统的 **共享内存**：地图探索/障碍、车辆位置目标路径、任务配置、探索率等全部落在 Redis。各模块通过它读写，不共享 JVM 堆。

---

## 一、什么时候用到？

| 场景 | 典型调用方 |
|------|------------|
| 任务初始化写地图、出生点 | TaskConfigurator |
| 每拍读车辆状态、发令前读目标/路线 | Controller |
| 算路后写 `RouteList` | Navigator |
| 移动一格、点亮、改状态 | Car |
| 监督器读路线、可能覆盖路线 | StrategySupervisor |
| 刷新前端前读整局快照 | Display |

**原则**：键名与语义由 Person A 维护；改 key 或含义需全员联调。

---

## 二、常用 Redis Key

| Key | 类型 | 说明 |
|-----|------|------|
| `mapView` | 位图 | 已探索区域 |
| `mapBlock` | 位图 | 静态/动态障碍物 |
| `mapSealed` | 位图 | 被障碍封死、不可达区 |
| `mapHeat` | Hash | 格子访问热力 |
| `{carId}:Position` | Hash | 当前坐标 `x`,`y` |
| `{carId}:Target` | Hash | 探索目标格 |
| `{carId}:RouteList` | List | 待走路径（Point 序列） |
| `{carId}:History` | List | 历史轨迹（回放） |
| `{carId}:Status` | String | `CarStatus` 枚举名 |
| `{carId}:Steps` | String | 总移动步数 |
| `{carId}:EffectiveSteps` | String | 有效步数（踩入新格次数） |
| `TaskConfig` | Hash | 地图宽高、车数、算法、tick 间隔、`active` 等 |
| `controller:instance` | String | Controller **单实例锁**（SETNX + TTL） |
| `explorationEvents` | List | 探索事件（回放增量绘制） |

**车辆发现**：`discoverCarIds()` 扫描 `Car*:Status`，车数由 TaskConfigurator 配置决定，非写死 5 台。

**勿清键**：`auth:*` 等登录相关键不在仿真 SCAN 范围内，初始化时不能误删。

---

## 三、位图怎么理解？

`mapView` / `mapBlock` / `mapSealed` 用 Redis 位图存储二维格子：

- 偏移：`row * mapWidth + col`
- 读写：`loadExploredBitmap()`、`loadBlockedMap()`、`setBlock(row, col, bool)` 等
- 批量：`writeBlockBitmap` 用于初始化大批量写障碍（减少网络往返）

探索率：`getExplorationRate()` = 已探索可通行格 / 可探索总格 × 100。

---

## 四、车辆相关核心方法

| 方法族 | 用途 |
|--------|------|
| `getCarStatus` / `setCarStatus` | 五态读写 |
| `getCarPosition` / `setCarPosition` | 位置 |
| `getCarTarget` / `setCarTarget` / `clearCarTarget` | 目标 |
| `getCarRoute` / `pushRoute` / `clearRoute` | 路径队列 |
| `peekNextRouteStep` / `popNextRouteStep` | Car 移动时取下一步 |
| `loadBlockedMapWithCars` | 寻路用障碍图（含他车占位） |
| `isExplorationComplete` | 探索是否 100% |
| `clearSimulationState` | 新任务前清空仿真键 |

---

## 五、Controller 单实例锁

任务书要求 Controller **全局只能跑一个实例**：

```java
if (!bb.acquireControllerLock()) {
    System.exit(1);  // 已有实例
}
```

- Key：`controller:instance`
- TTL：30 秒（防宕机死锁）
- 关闭时 `releaseControllerLock()`

---

## 六、DistributedLock（小车移动互斥）

路径：`common/.../redis/DistributedLock.java`

- Key：`lock:{carId}`
- 实现：SET NX + 过期时间；释放用 Lua 校验 `lockValue`
- **使用者**：Car 的 `MoveExecutor`（多实例/重入保护），非 Controller 主路径

默认超时 5 秒；获取失败则跳过本次移动，等下一拍。

---

## 七、与 MQ 的配合

典型模式：

1. **写黑板**（状态/路线/位置）
2. **发 MQ 事件**通知对方「我读 Redis 继续」

例如 Navigator：先 `pushRoute` 到 Redis，再发 `ROUTE_PLANNED`。Controller 以 MQ 为主、Redis 兜底（消息丢失时若路线已在 Redis 则强制 READY）。

---

## 八、分布式联调注意

- Jedis 超时默认已调到 **30s**（Tailscale 远程 Redis 避免 `Read timed out`）
- 初始化应用内存 `boolean[][]` 批量写位图，避免每格一次 Redis
- 全组共用 **一套** Redis；多 Controller 或多 TaskConfigurator 消费者会导致状态错乱

---

## 九、一句话总结

**BlackboardClient = 多进程间的唯一真相来源**：地图与车辆一切可观测状态在 Redis；MQ 只负责「通知你去读/写黑板」。

---

## 十、相关源码

| 文件 | 路径 |
|------|------|
| 黑板客户端 | `common/.../redis/BlackboardClient.java` |
| 分布式锁 | `common/.../redis/DistributedLock.java` |
| 位图快照工具 | `common/.../redis/MapBitmapSnapshot.java` |
| Key 对照表 | [`PROJECT_CONTEXT.md`](../../PROJECT_CONTEXT.md) §6 |

测试：`BlackboardClientTest`（需本机 Redis）。
