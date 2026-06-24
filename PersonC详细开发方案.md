# Person C 详细开发方案

> **负责模块**：navigator + target-planner + task-configurator + DynamicObstacleUtil  
> **难度**：⭐⭐⭐⭐  
> **基准文档**：参考架构 plan_all_v3.md (v3.3) + 开发计划.md + 人员分工.md

---

## 一、你的模块全景

```
common（Person A 交付，你依赖它）
  ↑
  ├── task-configurator/     ← 你（地图初始化 + 黑板数据字典落盘）
  ├── target-planner/        ← 你（贪心目标分配）
  ├── navigator/             ← 你（BFS/A* 路径搜索）
  └── common/DynamicObstacleUtil.java  ← 你（动态障碍物，放 common）
```

**启动依赖**：common 必须先完成（Person A 负责），你三个模块之间彼此独立，可以任意顺序开发。

---

## 二、你与外界的交互边界（必须死死记住）

### 2.1 你的模块能做什么

| 你的模块 | 订阅的队列 | 发布到的队列 | 写黑板 | 读黑板 |
|---------|-----------|------------|--------|--------|
| TaskConfigurator | TaskConfigCmd | ControllerCmd | mapView, mapBlock, Car:Position, Car:Status, Car:Steps, TaskConfig | - |
| TargetPlanner | TargetPlannerCmd | ControllerCmd | Car:Target | mapView, mapBlock, Car:Position |
| Navigator | NavigatorCmd | ControllerCmd | Car:RouteList | Car:Position, Car:Target, mapBlock, TaskConfig.algorithm |

### 2.2 绝对不能做的事

- **TargetPlanner 和 Navigator 绝不写 `CarID:Status`**——这是 Controller 和 Car 的专属权限
- **绝不与其他知识源直接通信**——你只和 Controller 对话，也只接受 Controller 的命令
- **不知道其他知识源的存在**——你只看到黑板数据和 Controller 发来的 MQ 消息

---

## 三、推荐开发顺序及每个模块的关键要点

### 第 1 步：TaskConfigurator（优先实现，因为它是系统的起点）

**为什么先做**：没有它，地图和车辆数据都不存在，Controller 无法开始 tick。

**两个文件**：
- `TaskConfiguratorMain.java`：入口，连接 Redis + RabbitMQ，订阅 `TaskConfigCmd`，收到消息后转交 `TaskInitializer` 处理
- `TaskInitializer.java`：纯逻辑类，执行 8 步初始化流程

**初始化流程（8 步）**：

```
收到 FORWARD_CONFIG {mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval}
  ↓
1. FLUSHDB 清空所有 Redis key
2. 解析配置参数（缺失时用默认值：30×30, 5车, 15%障碍, BFS, 500ms）
3. HSET TaskConfig 写入全部配置字段 + taskActive=true
4. 分配初始位置：
   - Car001→(1,1)  左上角
   - Car002→(W-2,1)  右上角
   - Car003→(1,H-2)  左下角
   - Car004→(W-2,H-2)  右下角
   - Car005→(W/2,H/2)  中心
   - 若 carCount>5 或初始位置被占，随机选取
5. 生成随机障碍物到 mapBlock（避开小车初始位置，数量 = 可放置格 × obstacleRatio）
6. 为每辆车写入：CarID:Position(Hash) + CarID:Status=IDLE + CarID:Steps=0
7. 每辆车初始位置点亮 3×3 到 mapView（避免前端黑屏）
8. 发布 TASK_READY 到 ControllerCmd，通知 Controller 可以开始 tick
```

**测试重点**：
- 初始化后检查每辆车的 Position/Status/Steps 正确
- 障碍物数量 ≈ 预期值，且不覆盖小车位置
- 初始位置 3×3 区域 mapView 已点亮
- 两次连续初始化不会遗留旧数据

**关键参数默认值**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| mapWidth / mapHeight | 30 | 30×30=900 格 |
| carCount | 5 | 5 台巡检车 |
| obstacleRatio | 0.15 | 约 135 个障碍物 |
| algorithm | BFS | 默认 BFS |
| tickInterval | 500 | 500ms 一拍 |

---

### 第 2 步：TargetPlanner（贪心目标分配）

**两个文件**：
- `TargetPlannerMain.java`：入口，连接 Redis + RabbitMQ，订阅 `TargetPlannerCmd`，收到 `ASSIGN_TARGET` 后调用分配器
- `GreedyTargetAllocator.java`：核心算法，纯逻辑不涉及 MQ

**消息交互**：
- 收：`TargetPlannerCmd` ← Controller 发来 `ASSIGN_TARGET {carId, tick}`
- 发：`ControllerCmd` → 回复 `TARGET_ASSIGNED {carId, target{x,y}, success}`

**贪心分配算法流程**：

```
1. 从黑板读取 carId 对应的 CarID:Position（当前位置）
2. 遍历全图，收集所有"未探索且非障碍"的格子作为候选集
   - 未探索：bb.getMapViewBit(r, c) == false
   - 非障碍：bb.isBlocked(r, c) == false
3. 如果候选集为空 → 所有区域已探索/被占，返回 success=false
4. 按曼哈顿距离从小到大排序
5. 距离规则（核心）：
   ┌─ 候选集 > 1 个 → 只分配距离 ≥ 10 的最近格子
   │  如果所有格子距离都 < 10 → 暂不分配，返回 success=false
   │  （让其他车先探索附近的，避免小车反复去已探索区域）
   └─ 候选集 = 1 个 → 直接分配，无距离限制（最后一个必须覆盖）
6. HSET CarID:Target x {x} y {y}  ← 只写 Target！
7. 回复 Controller：TARGET_ASSIGNED {success=true, target{x,y}}
```

**关键细节**：
- ⚠️ 绝不在黑板写 CarID:Status——那是 Controller 的事
- 曼哈顿距离 = `|x1-x2| + |y1-y2|`
- 如果 Controller 同时为多辆车发 ASSIGN_TARGET（同一 tick 多车 IDLE），你会收到多条消息；每条独立处理

**测试重点**：
- 有未探索格子 → success=true
- 全探索完 → success=false
- 距离 ≥ 10 规则生效
- 最后 1 个格子无距离限制
- 分配的目标不在障碍物上
- 不写 CarID:Status

---

### 第 3 步：Navigator（路径搜索，最核心模块）

**五个文件**：
- `NavigatorMain.java`：入口，连接 Redis + RabbitMQ，订阅 `NavigatorCmd`
- `PathPlanner.java`：接口，定义 `List<Point> plan(Point start, Point target, BlackboardClient bb)`
- `BfsPathFinder.java`：BFS 实现（**先做这个，这是 MVP**）
- `AStarPathFinder.java`：A* 实现（第 5-6 天补充）
- `PathPlannerFactory.java`：根据 `TaskConfig.algorithm` 选择 BFS 或 A*

**消息交互**：
- 收：`NavigatorCmd` ← Controller 发来 `PLAN_ROUTE {carId, start{x,y}, target{x,y}, algorithm, tick}`
- 发：`ControllerCmd` → 回复 `ROUTE_PLANNED {carId, routeFound, routeLength}`

**处理流程**：
```
收到 PLAN_ROUTE
  ↓
1. 从黑板读：CarID:Position(start) + CarID:Target(target) + TaskConfig.algorithm
2. 从黑板读 mapWidth / mapHeight
3. PathPlannerFactory.create(algorithm) → 拿到 BFS 或 A* 实例
4. pathPlanner.plan(start, target, bb) → 执行搜索
5. 如果找到路径：
   - clearRoute(carId)  清空旧路径
   - pushRoute(carId, route)   LPUSH 逐点写入新路径
   - 回复 ROUTE_PLANNED {routeFound=true, routeLength=N}
6. 如果找不到路径：
   - 回复 ROUTE_PLANNED {routeFound=false, routeLength=0}
   - ⚠️ 不写 CarID:Status（Controller 收到 false 后自己处理）
```

**BFS 算法设计（先实现这个）**：
```
输入：start(x,y), target(x,y), BlackboardClient bb
输出：路径列表（不含 start，含 target），无路径返回空列表

1. Queue<Point> + boolean[][] visited + Point[][] parent
2. visited[start] = true, queue.add(start)
3. 循环：
   - current = queue.poll()
   - 如果 current == target → 回溯 parent 构建路径，reverse 后返回
   - 遍历 4 方向（上下左右）：
     - 边界检查（x∈[0,W), y∈[0,H)）
     - visited 检查
     - bb.isBlocked(row, col) 检查（障碍 + 车辆位置都在 mapBlock 中）
     - 通过 → visited[next]=true, parent[next]=current, queue.add(next)
4. 队列空且未到达 → 返回空列表

时间复杂度：O(W×H)，30×30=900 节点完全可以接受
```

**A* 算法设计（第 5-6 天补充）**：
```
与 BFS 的区别：
- 用 PriorityQueue<Node> 替代普通 Queue，按 fScore = gScore + h 排序
- gScore[start]=0, gScore 默认 INF
- h = 曼哈顿距离(当前, target)
- 每次从 openSet 取 fScore 最小的节点扩展
- 当 tentative_g < gScore[next] 时更新 gScore 和 parent

性能：比 BFS 更快收敛，但实现复杂一点
```

**路径存储方向（团队统一约定）**：
```
写入：LPUSH 逐点压入 → 链表头部是最新压入的，尾部是第一步
读取：LINDEX -1  peek 队首（下一步要走的）
消费：RPOP       取出下一步（从尾部取）

示例：LPUSH [A,B,C,D] → 链表 D→C→B→A
       LINDEX -1 → A（下一步）
       RPOP → A（消费）
```

**Navigator 多实例支持（任务书明确要求）**：
- `NavigatorCmd` 是 Classic Queue（非独占），RabbitMQ 默认轮询分发
- 启动多个 `NavigatorMain` 进程即可，天然负载均衡
- 验收：启动 2 个 Navigator 实例，RabbitMQ 管理界面看到 2 个消费者

**测试重点（BFS）**：
- 无障碍直线路径：start→target 路径长度 = 曼哈顿距离
- 绕过障碍物：路径避开所有 mapBlock 格子
- 无路径（被障碍物完全包围）：返回空列表
- 返回路径不含 start，含 target
- 边界不越界
- 30×30 地图 100 次搜索 < 1 秒

**测试重点（A*）**：
- 与 BFS 结果比较：路径步数相同（都是最短路径）
- 启发函数正确：f = g + 曼哈顿距离
- 终点被围时返回空列表

---

### 第 4 步：DynamicObstacleUtil（动态障碍物）

**放在 common 模块**：`common/src/main/java/com/substation/common/DynamicObstacleUtil.java`

**原因**：只依赖 `BlackboardClient`，无外部依赖，你可以独立完成。Controller 在 tick() 中调用。

**功能设计**：
```
方法签名：List<String> generateDynamicObstacles(bb, mapWidth, mapHeight, carPositions, tick)

每次调用（默认每 20 拍调用一次）：
1. 收集当前所有障碍物位置（遍历 mapBlock）
2. 随机移除 ≤ 2 个障碍物（shuffle + 取前 N）
3. 随机新增 ≤ 2 个障碍物：
   - 随机选位置，避开 carPositions（小车占位）
   - 避开已有障碍物位置
   - 最多尝试 50 次
4. 返回变更日志：["新增(12,8)", "移除(25,3)"]
```

**Controller 集成方式（你不写，但要知道）**：
```java
// Controller.tick() 中：
if (tick % 20 == 0) {
    List<String> changes = dynamicObstacleUtil.generateDynamicObstacles(...);
    for (String c : changes) log.info("[Controller] 动态障碍: {}", c);
}
```

**测试重点**：
- 新增障碍物 ≤ 2 个，移除 ≤ 2 个
- 新增的不在小车位置上
- 不移除小车当前位置的障碍物标记
- 地图满障碍时不移除超过已有数量

---

## 四、每个模块的 MQ 自声明

根据开发计划 §2.4，每个模块**只声明自己需要订阅的队列**：

| 你的模块 | 启动时声明 |
|---------|-----------|
| TaskConfigurator | `messageBus.declareTaskConfigQueue()` → 声明 `TaskConfigCmd` |
| TargetPlanner | `messageBus.declareTargetPlannerQueue()` → 声明 `TargetPlannerCmd` |
| Navigator | `messageBus.declareNavigatorQueue()` → 声明 `NavigatorCmd` |

所有模块回复都发到 `ControllerCmd`（由 Controller 声明，你直接 publish 即可）。

---

## 五、黑板读写权限（你的模块）

| Redis Key | TaskConfigurator | TargetPlanner | Navigator |
|-----------|:---:|:---:|:---:|
| `mapView` | **W**（初始点亮 3×3） | R（读未探索） | - |
| `mapBlock` | **W**（初始障碍） | R（避开障碍） | R（避开障碍） |
| `CarID:Position` | **W**（设置初始位置） | R（计算距离） | R（路径起点） |
| `CarID:Target` | - | **W**（分配目标） | R（路径终点） |
| `CarID:RouteList` | - | - | **W**（写入路径） |
| `CarID:Status` | **W**（初始=IDLE） | ⛔ **禁止** | ⛔ **禁止** |
| `CarID:Steps` | **W**（初始=0） | - | - |
| `TaskConfig` | **W**（全部字段） | - | R（algorithm） |

---

## 六、降级路径（时间不够时的保底方案）

| 模块 | MVP（第 3-4 天必须完成） | 完整版（第 5-6 天） |
|------|--------------------------|---------------------|
| TaskConfigurator | 固定参数初始化（30×30, 5车, 0.15, BFS） | Web 表单动态配置 |
| TargetPlanner | 简单随机分配未探索格子（不做距离规则） | 贪心 + 距离 ≥ 10 + 最后一格限制 |
| Navigator | **仅 BFS**（4 方向 + 避开障碍 + 边界检查） | BFS + A* + PathPlannerFactory |
| DynamicObstacleUtil | 跳过不做 | 完整实现 |

**降级目标**：阶段 2a 结束时，必须能用 BFS + 简单目标分配跑通初始化→探索率 ≥ 99.9%。

---

## 七、11 天每日分工表（Person C 视角）

| 天 | 阶段 | 做什么 | 交付物 |
|----|------|--------|--------|
| **第 1 天** | 阶段 1 | Maven 骨架搭建 + 学 Jedis/RabbitMQ API + 等 Person A 交付 common | 3 个模块的 `Main` 空壳 + 环境连接测试 |
| **第 2 天** | 阶段 1 | **TaskConfigurator 完整实现**（common 到手后立即开工） | `TaskInitializer.java` + 初始化跑通 |
| **第 3 天** | 阶段 2a | **TargetPlanner 实现** + 单元测试 | `GreedyTargetAllocator.java` + 测试通过 |
| **第 4 天** | 阶段 2a | **Navigator 消息骨架 + BFS 实现** | `BfsPathFinder.java` + 能收到 PLAN_ROUTE |
| **第 5 天** | 阶段 2b | **BFS 完善 + TaskConfigurator/TargetPlanner 单元测试** | BFS 与 Person A 的 Controller 联调通过 |
| **第 6 天** | 阶段 2b | **A* 实现 + PathPlannerFactory + DynamicObstacleUtil** | `AStarPathFinder.java` + `DynamicObstacleUtil.java` |
| **第 7 天** | 阶段 3 | **与 Controller 联调** + bugfix + 多实例验证 | 完整流程跑通 |
| **第 8 天** | 阶段 3 | 补充单元测试 + A* 性能对比 | 测试全绿，文档草稿 |
| **第 9 天** | 阶段 3 | 配合全员联调 + 动态障碍物集成验证 | 联调通过 |
| **第 10 天** | 阶段 4 | 写设计文档（navigator/target-planner/task-configurator 章节） | 设计文档交付给 Person D 汇总 |
| **第 11 天** | 阶段 4 | 最终测试 + 文档定稿 | 完整交付 |

---

## 八、你需要从 Person A 获取的 common API

当你动手写代码时，确认 Person A 已经提供了这些：

```
BlackboardClient:
  - 构造函数: BlackboardClient(host, port, mapWidth, mapHeight)
  - mapView:  getMapViewBit(row, col) / setMapViewBit(row, col, explored)
  - mapBlock: isBlocked(row, col) / setBlock(row, col, blocked)
  - Position:  getCarPosition(carId) → Optional<Point> / setCarPosition(carId, pos)
  - Target:    getCarTarget(carId) → Optional<Point> / setCarTarget(carId, target) / clearCarTarget / hasTarget
  - RouteList: getCarRoute / peekNextRouteStep / popNextRouteStep / pushRoute / clearRoute
  - Status:    getCarStatus(carId) / setCarStatus(carId, status)
  - Steps:     getCarSteps / setCarSteps / incrementCarSteps
  - TaskConfig: initTaskConfig(config) / getMapWidth / getMapHeight / getAlgorithm / ...
  - BlockedTick: getBlockedTick / setBlockedTick / clearBlockedTick

MessageBus:
  - connect() / declareXxxQueue() / publish(queue, json) / subscribe(queue, handler) / close()

MessageTypes:  ASSIGN_TARGET, TARGET_ASSIGNED, PLAN_ROUTE, ROUTE_PLANNED,
               FORWARD_CONFIG, FORWARD_RESET, TASK_READY, ...

MessageBuilder: build(type, tick, carId, data) → JSON string

QueueNames: NAVIGATOR_CMD, TARGET_PLANNER_CMD, TASK_CONFIG_CMD, CONTROLLER_CMD

Point: record(int x, int y) + manhattanDistance(Point other) + toJson() + fromJson(String)
AlgorithmType: enum { BFS, ASTAR }
CarStatus: enum { IDLE, WAITING_ROUTE, READY, MOVING, BLOCKED }
```

---

## 九、联调检查清单

- [ ] TaskConfigurator 初始化后，Controller 收到 `TASK_READY`
- [ ] TargetPlanner 收到 `ASSIGN_TARGET` → 写入 `CarID:Target` → Controller 收到 `TARGET_ASSIGNED`
- [ ] Navigator 收到 `PLAN_ROUTE` → 写入 `CarID:RouteList` → Controller 收到 `ROUTE_PLANNED`
- [ ] BFS 路径正确避开所有 mapBlock 障碍物
- [ ] A* 路径和 BFS 路径步数一致（都是最短）
- [ ] 路径存储方向正确：LPUSH 写入 → LINDEX -1 peek → RPOP 消费
- [ ] 动态障碍物不覆盖小车位置
- [ ] 多实例 Navigator 可以并行消费
- [ ] 全流程：初始化 → IDLE → 分配目标 → 规划路径 → 移动 → 探索率 99.9%

---

## 十、设计文档大纲（阶段 4 交付给 Person D）

```
1. Navigator 设计
   1.1 PathPlanner 接口设计
   1.2 BFS 算法流程（附伪代码 + 复杂度分析）
   1.3 A* 算法流程（附伪代码 + 启发函数说明）
   1.4 PathPlannerFactory 工厂模式
   1.5 多实例部署方案

2. TargetPlanner 设计
   2.1 贪心分配策略
   2.2 距离规则（≥10 + 最后区域无限制）
   2.3 算法复杂度分析

3. TaskConfigurator 设计
   3.1 黑板数据字典初始化流程
   3.2 初始位置分配策略
   3.3 障碍物生成算法

4. DynamicObstacleUtil 设计
   4.1 动态障碍物生成策略
   4.2 Controller 集成方式
```
