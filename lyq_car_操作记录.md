# lyq_car 分支操作记录

> 操作时间：2026-06-12
> 操作人：刘倚岐 (lyq_car 分支)

---

## 一、操作概述

基于 `hzx_common` 分支拉取完整项目代码，分析 B 小车重叠问题的根因，设计并实现位置预约锁修复方案，在 `lyq_car` 分支完成编码、测试、合并后推送。

---

## 二、主要改动

### 2.1 位置预约锁（防重叠核心修复）

**问题根因**：两辆小车同时检查同一目标格子的 `isBlocked()` → 都看到空闲 → 都 `setBlock()` → 重叠。

原有分布式锁 (`DistributedLock`) 仅锁单辆小车自身（`lock:CarID`），不同小车之间互不排斥。

**修复方案**：在 `MoveExecutor.doMove()` 中，移动前对**目标格子**进行 `tryReservePosition(x, y, carId)`（Redis SET NX EX 原子命令）：
- 预约成功 → 执行移动 → finally 释放预约锁
- 预约失败 → 退回到 READY 状态，下个 tick 重试
- TTL=5秒，防止小车崩溃后锁永不释放（无死锁）

```
修复前 (check-then-act 竞态):
  CarA: isBlocked(2,3)==false → pop → setPosition → setBlock
  CarB: isBlocked(2,3)==false → pop → setPosition → setBlock  ← 同时到达！

修复后 (位置预约):
  CarA: tryReservePosition(2,3) → SET NX EX 成功 → 执行移动 → releaseReserve
  CarB: tryReservePosition(2,3) → SET NX 失败 → 退回 READY (下 tick 重试)
```

### 2.2 修改的文件

| 文件 | 变更内容 |
|------|---------|
| `common/.../BlackboardClient.java` | 新增 `tryReservePosition` / `releaseReservePosition` / `appendCarHistory`；修复 `getExplorationRate` 逐格 GETBIT；添加中文注释 |
| `car/.../MoveExecutor.java` | 重构为 `executeStep` + `finalizeMove`；集成位置预约锁；`handleObstacleDetected` 不再清除目标；移除 `recordHistory`（提取到 BlackboardClient） |
| `car/.../CarAgent.java` | 精确异常处理：`Exception` → `JSONException` |
| `car/.../CarMain.java` | 重构为构造器模式（支持 Launcher 调用）；`selfRegister` 添加 `appendCarHistory` 初始记录 |

### 2.3 新增的文件

| 文件 | 内容 |
|------|------|
| `common/.../DynamicObstacleUtil.java` | 动态障碍物管理工具（从 hzx_common 合入） |

### 2.4 从 hzx_common 合入的完整模块

| 模块 | 文件 |
|------|------|
| `navigator/` | AStarPathFinder, BfsPathFinder, NavigatorMain, PathPlanner, PathPlannerFactory + 测试 |
| `target-planner/` | GreedyTargetAllocator, TargetPlannerMain + 测试 |
| `task-configurator/` | TaskConfiguratorMain, TaskInitializer + 测试 |
| `display/` | DisplayMain, HttpFileServer, WebSocketBridge + 前端 (HTML/CSS/JS) + 测试 |
| `launcher/` | LauncherMain + 测试 |
| `controller/` | 更新 CommandHandler, StatusDispatcher, TickScheduler, ControllerMain |

### 2.5 合入的工程文件

- `mvnw` / `mvnw.cmd` — Maven Wrapper（无需安装 Maven 即可编译）
- `.mvn/wrapper/maven-wrapper.properties` — Maven Wrapper 配置
- `start_all.bat` — 一键启动全部 8 个模块
- `docker-compose.yml` — Docker 编排（Redis + RabbitMQ）
- 设计文档：`检查/`、`plan/` 目录下的审查修复方案文档
- 人员分工、开发计划等管理文档

---

## 三、冲突解决记录

从 `hzx_common` 合并到 `lyq_car` 时，以下文件产生冲突，均已手动解决：

| 文件 | 冲突原因 | 解决方案 |
|------|---------|---------|
| `car/.../MoveExecutor.java` | 两边都修改了 `doMove` 和辅助方法 | 保留位置预约锁逻辑 + hzx_common 注释 |
| `car/.../CarMain.java` | 两边都修改了 `selfRegister` | 保留 `appendCarHistory` 初始记录 |
| `common/.../BlackboardClient.java` | 两边都添加了注释和位置预约锁 | 合并注释 + 保留位置预约锁 + 去重 `appendCarHistory` |
| `common/.../DynamicObstacleUtil.java` | 两边新增了同一文件 | 内容一致，移除冲突标记 |
| `car/.../MoveExecutorTest.java` | 两边都修改了测试 | 保留重叠测试用例 + hzx_common 注释 |

---

## 四、测试结果

### 全量测试（需要 Redis + RabbitMQ）

```
--------------------------------------------------------
模块                 测试数    Failures   Errors   结果
--------------------------------------------------------
Common               46        0          0         ✅
Controller           12        0          0         ✅
Car                  22        0          0         ✅
Navigator            21        0          0         ✅
Target Planner        9        0          0         ✅
Task Configurator    13        0          0         ✅
Display              23        0          0         ✅
Launcher             24        0          0         ✅
--------------------------------------------------------
合计                170        0          0         ✅
--------------------------------------------------------
```

### 新增的核心测试用例

| 测试类 | 测试方法 | 说明 |
|--------|---------|------|
| `MoveExecutorTest` | `positionReservationPreventsOverlap` | 验证位置预约锁阻止重叠 |
| `MoveExecutorTest` | `positionReservationReleasedAfterMove` | 验证移动后预约锁被释放 |
| `MoveExecutorTest` | `positionReservationReleasedOnObstacle` | 验证障碍物处理后预约锁被释放 |
| `BlackboardClientTest` | `tryReservePosition` | 验证同位置不能被两车同时预约 |
| `BlackboardClientTest` | `releaseAndReacquireReservation` | 验证只有预约者才能释放 |
| `BlackboardClientTest` | `sameCarCanReserveDifferentPositions` | 验证同一车可预约不同位置 |

---

## 五、如何运行

### 1. 启动中间件（Docker）
```bash
docker-compose up -d
```

### 2. 编译打包
```bash
.\mvnw package -DskipTests
```

### 3. 一键启动全部模块
```bash
.\start_all.bat
```

### 4. 访问前端
```
http://localhost:8887
```

### 5. 运行测试
```bash
.\mvnw test
```

---

## 六、Git 提交记录

```
81fe23c fix(car+common): 位置预约锁修复小车重叠问题，整合hzx_common分支改动
706eedc merge: 从hzx_common拉取完整项目（全部9个模块 + Maven wrapper + 设计文档）
```
