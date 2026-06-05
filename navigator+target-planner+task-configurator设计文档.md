# Navigator + TargetPlanner + TaskConfigurator 设计文档

> 负责人：Person C  
> 日期：2026-06-05  
> 范围：task-configurator / target-planner / navigator / DynamicObstacleUtil

---

## 一、模块全景

```
common（Person A 交付）
  ↑
  ├── task-configurator/       ← 地图初始化，黑板数据字典落盘
  ├── target-planner/          ← 贪心目标分配
  ├── navigator/               ← BFS/A* 路径搜索
  └── common/DynamicObstacleUtil.java  ← 动态障碍物
```

三个模块都属于"规划层"：TaskConfigurator 决定**初始状态**、TargetPlanner 决定**去哪**、Navigator 决定**怎么走**。三者共享对 mapView/mapBlock 的理解，逻辑连贯。

**核心架构约束**（来自 v3.3）：
- 只接受 Controller 的 MQ 命令，不与其他知识源直接通信
- TargetPlanner 和 Navigator **绝不写 CarID:Status**
- 所有回复统一发到 ControllerCmd

---

## 二、TaskConfigurator 模块

### 2.1 模块职责

唯一负责黑板初始化的知识源。收到 Controller 转发的 FORWARD_CONFIG / FORWARD_RESET 后执行初始化流程，完成后通知 Controller TASK_READY。

### 2.2 文件清单

```
task-configurator/
└── src/main/java/com/substation/taskconfigurator/
    ├── TaskConfiguratorMain.java    # 入口：MQ 通信 + 流程协调
    └── TaskInitializer.java         # 纯逻辑：8 步初始化流程
```

### 2.3 初始化流程

```
收到 FORWARD_CONFIG {mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval}
  ↓
1. FLUSHDB 清空所有 Redis key
2. 解析配置参数（缺失时用默认值 30×30 / 5车 / 0.15障碍 / BFS / 500ms）
3. HSET TaskConfig 写入 7 个字段 + taskActive=true
4. 分配初始位置：
     Car001→(1,1)   左上角    Car002→(W-2,1)   右上角
     Car003→(1,H-2) 左下角    Car004→(W-2,H-2) 右下角
     Car005→(W/2,H/2) 中心    若>5 或位置被占则随机选
5. 生成随机障碍物到 mapBlock（避开小车初始位置）
6. 每车写入 CarID:Position + Status=IDLE + Steps=0
7. 每车点亮初始位置周围 3×3 到 mapView
8. 发布 TASK_READY 到 ControllerCmd
```

### 2.4 默认参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| mapWidth / mapHeight | 30 | 30×30=900 格 |
| carCount | 5 | Car001~Car005 |
| obstacleRatio | 0.15 | 内部格子 × 15% |
| algorithm | BFS | 路径搜索算法 |
| tickInterval | 500 | 节拍间隔 ms |

### 2.5 黑板写入权限

| Key | 写入方法 |
|-----|---------|
| TaskConfig | `initTaskConfig()` — 批量写入 7 字段 |
| mapBlock | `setBlock()` — 随机障碍物 |
| CarID:Position | `setCarPosition()` — 初始位置 |
| CarID:Status | `setCarStatus()` — 初始=IDLE |
| CarID:Steps | `setCarSteps()` — 初始=0 |
| mapView | `setMapViewBit()` — 点亮 3×3 |

### 2.6 MQ 消息

**订阅**：`TaskConfigCmd`

```
← FORWARD_CONFIG {mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval}
← FORWARD_RESET  {}
```

**发布**：`ControllerCmd`

```
→ TASK_READY {}
```

---

## 三、TargetPlanner 模块

### 3.1 模块职责

接收 Controller 的 ASSIGN_TARGET 请求，扫描地图找出未探索且非障碍的格子，使用贪心算法分配探索目标。**只写 CarID:Target，不写 CarID:Status。**

### 3.2 文件清单

```
target-planner/
└── src/main/java/com/substation/targetplanner/
    ├── TargetPlannerMain.java        # 入口：MQ 通信
    └── GreedyTargetAllocator.java    # 贪心分配算法（纯逻辑，不碰 MQ）
```

### 3.3 贪心分配算法

```
allocate(carId, currentPosition, bb, mapWidth, mapHeight):

1. 遍历全图，收集候选集：
   ∀ (r,c) ∈ [0,mapSize):
       if getMapViewBit(r,c)==false AND isBlocked(r,c)==false
       → candidates.add(Point(c,r))

2. 候选集为空 → 返回 Optional.empty()（全部已探索/被占）

3. 按曼哈顿距离从小到大排序

4. 距离规则：
   ┌ candidates.size() == 1 → 直接分配（最后一个格子，无距离限制）
   │
   └ candidates.size() > 1  → 过滤 distance ≥ 10 的最近格子
       如果过滤后为空 → 返回 Optional.empty()（暂不分配，等下轮）
       否则 → 返回过滤后第一个（最近且 ≥ 10）
```

### 3.4 距离规则说明

| 剩余候选 | 规则 | 理由 |
|---------|------|------|
| > 1 | 只分配距离 ≥ 10 的最近格子 | 避免小车反复去已探索区域附近 |
| = 1 | 直接分配，无距离限制 | 最后一块必须覆盖 |
| 无满足条件 | 返回空，暂不分配 | 等其他车先探索附近区域 |

### 3.5 黑板读写权限

| Key | 读写 | 方法 |
|-----|------|------|
| mapView | R | `getMapViewBit()` — 判断未探索 |
| mapBlock | R | `isBlocked()` — 避开障碍 |
| CarID:Position | R | `getCarPosition()` — 计算距离 |
| CarID:Target | **W** | `setCarTarget()` — HSET x y |
| CarID:Status | ⛔ | **绝不写入** |

### 3.6 MQ 消息

**订阅**：`TargetPlannerCmd`

```
← ASSIGN_TARGET {carId, tick}
```

**发布**：`ControllerCmd`

```
→ TARGET_ASSIGNED {carId, success: true,  target: {x, y}}
→ TARGET_ASSIGNED {carId, success: false}
```

---

## 四、Navigator 模块

### 4.1 模块职责

接收 Controller 的 PLAN_ROUTE 请求，使用指定算法（BFS/A*）在避开障碍物的前提下搜索从 start 到 target 的最短路径，写入 CarID:RouteList。**只写 RouteList，不写 CarID:Status。** 支持多实例竞争消费。

### 4.2 文件清单

```
navigator/
└── src/main/java/com/substation/navigator/
    ├── NavigatorMain.java          # 入口：MQ 通信
    ├── PathPlanner.java            # 接口：List<Point> plan(start, target, bb)
    ├── BfsPathFinder.java          # BFS 实现
    ├── AStarPathFinder.java        # A* 实现
    └── PathPlannerFactory.java     # 工厂：AlgorithmType → 对应实现
```

### 4.3 PathPlanner 接口

```java
@FunctionalInterface
public interface PathPlanner {
    /**
     * @return 路径列表（不含 start，含 target），无路径时返回空列表
     */
    List<Point> plan(Point start, Point target, BlackboardClient bb);
}
```

### 4.4 BFS 算法

```
BFS(start, target, bb):
  1. 地图尺寸 = bb.getMapWidth() / bb.getMapHeight()
  2. 如果 target 越界 → 返回空列表
  3. 初始化 visited[height][width], parent[height][width], queue
  4. visited[start]=true, queue.add(start)
  5. while queue 非空:
       current = queue.poll()
       如果 current == target → 回溯 parent 构建路径，reverse 后返回
       遍历 4 方向 (上/下/左/右):
         nx, ny = current + direction
         边界检查 / visited 检查 / bb.isBlocked(ny,nx) 检查
         通过 → visited[ny][nx]=true, parent[ny][nx]=current, queue.add
  6. 队列空 → 返回空列表（无法到达）

复杂度：O(W×H) 时间，O(W×H) 空间；30×30=900 节点可接受
```

### 4.5 A* 算法

```
A*(start, target, bb):
  与 BFS 的结构相同，两个区别：

  区别1 — PriorityQueue 替代 Queue：
    openSet = PriorityQueue(按 fScore = g + h 升序)
    gScore[start]=0, 其余=INF
    h = 曼哈顿距离(当前, target)

  区别2 — 节点更新逻辑：
    对每个邻居计算 tentativeG = gScore[current] + 1
    如果 tentativeG < gScore[neighbor]:
      更新 gScore, parent, fScore, 入队

  启发函数：h(a,b) = |a.x-b.x| + |a.y-b.y|（曼哈顿距离）

复杂度：O(W×H × log(W×H)) 时间，实际比 BFS 更快收敛
```

### 4.6 PathPlannerFactory

```java
static PathPlanner create(AlgorithmType algorithm) {
    return switch (algorithm) {
        case BFS  -> new BfsPathFinder();
        case ASTAR -> new AStarPathFinder();
    };
}

static PathPlanner create(String algorithmName) {
    // 字符串重载：Enum.valueOf 后调用枚举版本
    // 非法值回落 BFS（安全默认）
}
```

### 4.7 路径存储约定

```
写入（Navigator）：LPUSH CarID:RouteList 逐点压入
读取（Car）：       LINDEX -1  peek 下一步（队首）
消费（Car）：       RPOP      取出下一步（队尾）

示例：LPUSH [A,B,C,D] → 链表 D→C→B→A
       LINDEX -1 → A（下一步要走的位置）
       RPOP → A（消费该步）
```

### 4.8 多实例支持

`NavigatorCmd` 是 Classic Queue（非独占），多个 Navigator 进程共享竞争消费：

- RabbitMQ 默认轮询分发消息
- 启动多个 `NavigatorMain` 进程即可横向扩展
- 验收：启动 2 个实例，RabbitMQ 管理界面可见 2 个消费者

### 4.9 黑板读写权限

| Key | 读写 | 方法 |
|-----|------|------|
| CarID:Position | R | `getCarPosition()` — 路径起点 |
| CarID:Target | R | `getCarTarget()` — 路径终点 |
| mapBlock | R | `isBlocked()` — 避开障碍物和车辆 |
| TaskConfig | R | `getAlgorithm()` — 选择算法 |
| CarID:RouteList | **W** | `clearRoute()` + `pushRoute()` — LPUSH 写入 |
| CarID:Status | ⛔ | **绝不写入** |

### 4.10 MQ 消息

**订阅**：`NavigatorCmd`

```
← PLAN_ROUTE {carId, start{x,y}, target{x,y}, algorithm, tick}
```

**发布**：`ControllerCmd`

```
→ ROUTE_PLANNED {carId, routeFound: true,  routeLength: N}
→ ROUTE_PLANNED {carId, routeFound: false, routeLength: 0}
```

---

## 五、DynamicObstacleUtil（动态障碍物）

### 5.1 模块职责

提供动态障碍物生成方法，供 Controller 在 tick() 中每 N 拍调用。放在 common 模块，只依赖 BlackboardClient。

### 5.2 文件

```
common/src/main/java/com/substation/common/
└── DynamicObstacleUtil.java
```

### 5.3 算法

```
generate(bb, mapWidth, mapHeight, carPositions):

1. 收集当前所有障碍物位置（遍历 mapBlock）
2. shuffle 打乱 → 随机移除 ≤ 2 个（setBlock false）
3. 随机新增 ≤ 2 个：
   - random 选位置（最多尝试 50 次）
   - 避开 carPositions（小车不备覆盖）
   - 避开已存在的障碍物
4. 返回变更日志：["新增(12,8)", "移除(25,3)"]
```

### 5.4 Controller 集成方式

```java
// Controller.tick() 中每 20 拍调用一次：
if (tick % 20 == 0) {
    List<String> changes = util.generate(bb, mapWidth, mapHeight, carPositions);
    for (String c : changes) {
        log.info("[Controller] 动态障碍: {}", c);
    }
}
```

---

## 六、消息路由总表（Person C 全部模块）

| 消息 | 发送方 | 接收方 | 队列 |
|------|--------|--------|------|
| FORWARD_CONFIG | Controller | TaskConfigurator | TaskConfigCmd |
| FORWARD_RESET | Controller | TaskConfigurator | TaskConfigCmd |
| TASK_READY | TaskConfigurator | Controller | ControllerCmd |
| ASSIGN_TARGET | Controller | TargetPlanner | TargetPlannerCmd |
| TARGET_ASSIGNED | TargetPlanner | Controller | ControllerCmd |
| PLAN_ROUTE | Controller | Navigator | NavigatorCmd |
| ROUTE_PLANNED | Navigator | Controller | ControllerCmd |

---

## 七、黑板写入矩阵（Person C 全部模块）

| Key | TaskConfigurator | TargetPlanner | Navigator |
|-----|:---:|:---:|:---:|
| mapView | **W**（初始点亮） | R | - |
| mapBlock | **W**（初始障碍） | R | R |
| CarID:Position | **W**（初始位置） | R | R |
| CarID:Target | - | **W**（分配） | R |
| CarID:RouteList | - | - | **W**（规划） |
| CarID:Status | **W**（初始=IDLE） | ⛔ | ⛔ |
| CarID:Steps | **W**（初始=0） | - | - |
| TaskConfig | **W**（全部字段） | - | R（algorithm） |
