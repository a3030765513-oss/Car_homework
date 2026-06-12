# Person C（navigator / target-planner / task-configurator）代码审查问题清单

> 审查人：Person A  
> 日期：2026-06-05  
> 结论：代码能编译，模块结构清晰。发现 7 个问题，其中 3 个高危需在联调前修复。

---

## 问题 1（⚠️ 高危—运行时）TaskInitializer 没把初始车位写入 mapBlock

**位置**：`task-configurator/.../TaskInitializer.java` 第 147-151 行

```java
// 当前
private void initSingleCar(BlackboardClient bb, String carId, Point position) {
    bb.setCarPosition(carId, position);
    bb.setCarStatus(carId, CarStatus.IDLE);
    bb.setCarSteps(carId, 0);
}
```

初始化时写了 Position / Status / Steps，但漏了 `setBlock`。Navigator 规划路径时看不到其他车的初始位置，会算出穿过车位的路径。Car 上路后做 `isBlocked` 检测，发现下一步被占 → BLOCKED → 重新分配目标，影响探索效率。

**修复**：加一行 `bb.setBlock(position.y(), position.x(), true);`

---

## 问题 2（⚠️ 高危—运行时）FLUSHDB 会删掉 controller:instance 锁

**位置**：`task-configurator/.../TaskConfiguratorMain.java` 第 83、98 行

```java
jedis.flushDB();  // 删除全部 Redis key
```

Controller 启动时设了 `controller:instance` 锁确保单实例。`FLUSHDB` 把它也干掉了。虽然设计文档写了 "FLUSHDB（或按 key 逐个清理）"，但"逐个清理"才是安全的做法。

**修复**：改为选择性清理，排除 `controller:instance`：

```java
Set<String> keys = jedis.keys("Car*");
keys.add("mapView");
keys.add("mapBlock");
keys.add("mapHeat");
keys.add("TaskConfig");
if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));
```

---

## 问题 3（⚠️ 高危—逻辑）GreedyTargetAllocator 缺少去重

**位置**：`target-planner/.../GreedyTargetAllocator.java` 第 20 行

设计文档（人员分工 §3.4）的签名是：

```java
Point allocateTarget(String carId, Point currentPos, BlackboardClient bb,
                     Set<Point> alreadyAllocated, int mapWidth, int mapHeight)
```

实际代码少了 `alreadyAllocated` 参数。同一 tick 内 Controller 为多辆车发送 ASSIGN_TARGET 时，每次分配独立执行，两辆车可能被分到同一个格子。后一次 `setCarTarget` 会覆盖前一次，前车拿着"成功"的回复但目标已被抢走。

**修复**：加回 `alreadyAllocated` 参数（或改为实例变量），每次分配后 `add`，候选集过滤掉已分配的。

---

## 问题 4（中危—代码质量）allocate() 的 carId 参数未使用

**位置**：`target-planner/.../GreedyTargetAllocator.java` 第 20 行

`carId` 在方法体内从未被读取。要么用于日志/去重，要么删掉，方法参数从 5 个减到 4 个（CLAUDE.md 要求参数不超过 4 个）。

---

## 问题 5（中危—部署）三个模块 Redis 端口写死

**位置**：
- `navigator/.../NavigatorMain.java` 第 24 行
- `target-planner/.../TargetPlannerMain.java` 第 23 行  
- `task-configurator/.../TaskConfiguratorMain.java` 第 24 行

```java
private static final int REDIS_PORT = 6379;
```

Person B 的 CarMain 支持命令行指定 Redis 端口（`java -jar car.jar Car001 6380`）。如果 Redis 跑在非标准端口，你的三个模块连不上。建议统一支持 CLI 参数或环境变量。

---

## 问题 6（低危—依赖）pom.xml 没显式声明 fastjson2

**位置**：navigator / target-planner / task-configurator 的 `pom.xml`

三个模块源码里导入了 `com.alibaba.fastjson2.JSONObject`，但 pom.xml 里没声明这个依赖。目前靠 common 的传递依赖跑通。如果 common 调整 scope，三个模块会编译失败。建议补上：

```xml
<dependency>
    <groupId>com.alibaba.fastjson2</groupId>
    <artifactId>fastjson2</artifactId>
</dependency>
```

---

## 问题 7（低危—风格）catch (Exception) 吞异常

**位置**：`car/.../CarAgent.java` 第 57 行（Person B 的代码，但你也可以参考）

```java
} catch (Exception e) {
    log.warn("[{}] 收到非法消息: {}", carId, json);
}
```

CLAUDE.md 要求捕获具体异常（如 `JSONException`）。当前写法丢弃了异常信息，排查问题时会缺线索。仅供参考，不强求修改。

---

## 汇总

| # | 严重程度 | 位置 | 一句话 |
|---|----------|------|--------|
| 1 | ⚠️ 高危 | TaskInitializer | 初始车位没写 mapBlock |
| 2 | ⚠️ 高危 | TaskConfiguratorMain | FLUSHDB 删掉 controller 锁 |
| 3 | ⚠️ 高危 | GreedyTargetAllocator | 缺少去重，多车抢同一目标 |
| 4 | 中危 | GreedyTargetAllocator | carId 参数未使用 |
| 5 | 中危 | 3 个 Main | Redis 端口写死 6379 |
| 6 | 低危 | 3 个 pom.xml | 缺 fastjson2 显式依赖 |
| 7 | 低危 | CarAgent | catch(Exception) 吞异常 |

---

## 评价

三个模块结构清楚，BFS/A* / 贪心分配 / 8 步初始化的核心逻辑正确。第 1-3 个高危问题建议优先修，修完后基本可以跟 Controller 联调。辛苦了！
