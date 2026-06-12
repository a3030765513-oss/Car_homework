# Research Notes: hzx_common 分支 & B小车重叠问题

## 项目背景
- 软件体系结构综合实验 — 小车调度相关（黑板架构）
- 仓库: https://github.com/a3030765513-oss/Car_homework.git
- 多模块: car, common, controller, display, launcher, navigator, target-planner, task-configurator

## 关键问题
1. hzx_common 分支之前解决了重叠问题，但引入了死锁
2. B 小车仍然有重叠问题
3. 需要在不引入死锁的前提下修复重叠

## 架构分析

### 移动流程
1. Controller 通过 RabbitMQ 发送 TICK_MOVE 到各小车队列
2. CarAgent.handleMessage → MoveExecutor.executeMove(tick)
3. executeMove: 获取分布式锁(per-carId) → doMove → 释放锁
4. doMove: 检查READY → 切换MOVING → peek下一步 → 检查isBlocked → 执行移动

### 重叠根因 (check-then-act 竞态条件)
```
CarA: isBlocked(2,3) → false
CarB: isBlocked(2,3) → false  ← 同时检查，都看到空闲
CarA: pop → setPosition → setBlock(2,3)=true
CarB: pop → setPosition → setBlock(2,3)=true  ← 两车重叠！
```

**根本原因**: DistributedLock 只锁 carId，不同小车之间不互斥。isBlocked 检查和 setBlock 设置之间没有原子性保证。

### hzx_common 关键变更
1. `MoveExecutor.handleObstacleDetected`: 移除 `clearCarTarget(carId)` — 保留目标便于重路由
2. `MoveExecutor`: 重构为 executeStep + finalizeMove 方法
3. `BlackboardClient`: 新增 appendCarHistory（从 MoveExecutor 提取）
4. `BlackboardClient.getExplorationRate`: 改用逐格 GETBIT（兼容任意地图尺寸）
5. 新增 `DynamicObstacleUtil`: 动态障碍物管理
6. `CarMain`: 重构为构造器模式，支持 Launcher 调用

### 修复方案：位置预约锁 (Position Reservation Lock)
- 移动前用 Redis SET NX 对目标格子加短期预约锁（TTL 5s）
- 预约失败 → 目标正被其他车占用 → 阻塞
- 预约成功 → 执行移动 → 释放预约锁
- 无死锁：只锁一个外部位置 + TTL 自动过期

---
## 最终变更清单

### 修改的文件
| 文件 | 变更内容 |
|------|---------|
| `common/.../BlackboardClient.java` | +appendCarHistory, +tryReservePosition, +releaseReservePosition, 修复getExplorationRate逐格计算, +中文注释 |
| `common/.../DistributedLock.java` | +中文注释 (已与 hzx_common 一致) |
| `car/.../MoveExecutor.java` | 重构: executeStep+finalizeMove, 位置预约锁集成, 移除recordHistory(提取到BlackboardClient), handleObstacleDetected不再clearCarTarget |
| `car/.../CarAgent.java` | 精确异常: Exception→JSONException |
| `car/.../CarMain.java` | 重构为构造器模式(支持Launcher调用), selfRegister返回Optional, +appendCarHistory初始记录 |

### 新增的文件
| 文件 | 内容 |
|------|------|
| `common/.../DynamicObstacleUtil.java` | 动态障碍物管理工具 |
| `common/.../DynamicObstacleUtilTest.java` | (待添加) |

### 更新的测试文件
| 文件 | 新增测试 |
|------|---------|
| `car/.../MoveExecutorTest.java` | +positionReservationPreventsOverlap, +positionReservationReleasedAfterMove, +positionReservationReleasedOnObstacle; 修正blockedByObstacle(目标保留) |
| `common/.../BlackboardClientTest.java` | +appendCarHistory, +tryReservePosition, +releaseAndReacquireReservation, +sameCarCanReserveDifferentPositions |

### 测试结果
- **纯单元测试**: 16/16 ✅ (无需 Redis/RabbitMQ)
- **集成测试**: 需要本地 Redis:6379 + RabbitMQ:5672

### 重叠修复原理
```
Before (竞态):
  CarA: isBlocked(2,3)==false → pop → setPosition → setBlock
  CarB: isBlocked(2,3)==false → pop → setPosition → setBlock  ← 同时到达！

After (位置预约):
  CarA: tryReservePosition(2,3) → OK → isBlocked检查 → executeStep → releaseReserve
  CarB: tryReservePosition(2,3) → FAIL → READY状态返回 (下个tick重试)
```
位置预约锁使用 Redis SET NX EX 原子命令，TTL=5秒防死锁。
