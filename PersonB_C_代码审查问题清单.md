# Person B + Person C 代码审查问题清单

> 审查人：Person A  
> 日期：2026-06-05  
> 结论：代码能编译，与 common/controller 兼容。发现 10 个问题，3 个高危需在联调前修复。

---

## 一、Person B（Car 模块）— 3 个问题

### 问题 B1（高危—运行时）TaskInitializer 没把初始车位写入 mapBlock

**位置**：`task-configurator/.../TaskInitializer.java` 第 147-151 行

```java
// 当前代码
private void initSingleCar(BlackboardClient bb, String carId, Point position) {
    bb.setCarPosition(carId, position);
    bb.setCarStatus(carId, CarStatus.IDLE);
    bb.setCarSteps(carId, 0);
    // 缺少：bb.setBlock(position.y(), position.x(), true);
}
```

**后果**：Navigator 规划路径时会穿过其他车的初始位置，导致上路后频繁碰撞。

**修复**：加一行 `bb.setBlock(position.y(), position.x(), true);`

---

### 问题 B2（低危—风格）doMove() 超过 30 行

**位置**：`car/.../MoveExecutor.java` 第 82-143 行

`doMove()` 方法约 60 行，违反 CLAUDE.md "单个函数不超过 30 行" 规则。建议拆分为 `validateAndPeekStep()` / `executePositionUpdate()` / `finalizeMoveStatus()`。

---

### 问题 B3（低危—封装）recordHistory 绕过 BlackboardClient

**位置**：`car/.../MoveExecutor.java` 第 171-179 行

直接拿 Jedis 连接手动拼 Redis key `carId + ":History"`，其他操作都走 BlackboardClient。建议在 BlackboardClient 加 `appendCarHistory(carId, position, tick)` 方法。

---

## 二、Person C（navigator / target-planner / task-configurator）— 7 个问题

### 问题 C1（高危—运行时）FLUSHDB 会删掉 controller:instance 锁

**位置**：`task-configurator/.../TaskConfiguratorMain.java` 第 83、98 行

```java
jedis.flushDB();  // 删除全部 key，包括 controller:instance
```

**后果**：先启动 Controller，后点"开始"触发初始化，Controller 的单实例锁被清掉。如果第二个 Controller 在此期间启动，会出现双 Controller。

**修复**：改为按 key 逐个清理，排除 `controller:instance`：

```java
Set<String> keys = jedis.keys("Car*");
keys.add("mapView");
keys.add("mapBlock");
keys.add("mapHeat");
keys.add("TaskConfig");
if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));
```

---

### 问题 C2（高危—逻辑）GreedyTargetAllocator 没有去重

**位置**：`target-planner/.../GreedyTargetAllocator.java` 第 20-29 行

设计文档要求 `allocate(..., Set<Point> alreadyAllocated, ...)`，实际方法签名少了 `alreadyAllocated` 参数。同一 tick 内多辆车分配目标时，可能分到同一个格子。

**修复**：加回 `alreadyAllocated` 参数，每次分配后将目标加入集合。

---

### 问题 C3（中危—代码质量）GreedyTargetAllocator 的 carId 参数没用到

**位置**：`target-planner/.../GreedyTargetAllocator.java` 第 20 行

`carId` 参数在整个方法体里从未被读取。要么用于日志/去重，要么删掉（减到 4 个参数符合 CLAUDE.md 要求）。

---

### 问题 C4（中危—部署）三个模块 Redis 端口写死

**位置**：
- `navigator/.../NavigatorMain.java` 第 24 行
- `target-planner/.../TargetPlannerMain.java` 第 23 行
- `task-configurator/.../TaskConfiguratorMain.java` 第 24 行

```java
private static final int REDIS_PORT = 6379;
```

Person B 的 CarMain 支持命令行指定 Redis 端口。建议统一支持 CLI 参数或环境变量。

---

### 问题 C5（低危—依赖）pom.xml 没声明 fastjson2

**位置**：navigator / target-planner / task-configurator 的 `pom.xml`

三个模块源码里导入了 `com.alibaba.fastjson2.JSONObject`，但 pom.xml 没声明 fastjson2 依赖。目前靠 common 传递依赖跑通，如果 common 调整 scope 就会编译失败。建议加上显式依赖。

---

### 问题 C6（低危—风格）CarAgent 用 catch (Exception) 吞异常

**位置**：`car/.../CarAgent.java` 第 57 行

```java
} catch (Exception e) {
    log.warn("[{}] 收到非法消息: {}", carId, json);
}
```

CLAUE.md 要求捕获具体异常（如 `JSONException`），且异常信息 `e` 被丢弃，失去定位线索。

---

### 问题 C7（低危—效率）CarMain.selfRegister 重复调用 getCarStatus

**位置**：`car/.../CarMain.java` 第 136-138 行

```java
if (bb.getCarStatus(carId).isPresent()) {      // 第一次 Redis 查询
    CarStatus s = bb.getCarStatus(carId).orElseThrow(); // 第二次查询
```

同一个值查了 Redis 两次。改用一个变量存 `Optional<CarStatus>` 复用。

---

## 三、汇总

| # | 归属 | 严重程度 | 位置 | 一句话 |
|---|------|----------|------|--------|
| B1/C1 | C | ⚠️ 高危 | TaskInitializer | 初始车位没写 mapBlock，路径会穿车 |
| C1 | C | ⚠️ 高危 | TaskConfiguratorMain | FLUSHDB 删掉 controller 锁 |
| C2 | C | ⚠️ 高危 | GreedyTargetAllocator | 缺少去重，多车抢同一目标 |
| C3 | C | 中危 | GreedyTargetAllocator | carId 未使用，参数冗余 |
| C4 | C | 中危 | 3 个 Main | Redis 端口写死 |
| B2 | B | 低危 | MoveExecutor | doMove 超 30 行 |
| B3 | B | 低危 | MoveExecutor | 绕过 BlackboardClient 写 History |
| C5 | C | 低危 | 3 个 pom.xml | 缺 fastjson2 显式依赖 |
| C6 | B | 低危 | CarAgent | catch(Exception) 吞异常 |
| C7 | B | 低危 | CarMain | 重复调用 getCarStatus |

---

## 四、整体评价

- Person B 的 Car 模块质量较高，12 步原子移动逻辑完整，有 19 个测试。主要问题是 doMove 过长和个别封装不一致
- Person C 的代码能跑通流程，但 3 个高危问题都需要在联调前修复，否则会出现路径冲突、双 Controller 等严重故障
- 双方的代码与 Person A 的 common 接口全部兼容，没有编译错误

建议 B 和 C 修完高危问题后，三人一起跑一次 ConsoleDemo 联调验证。
