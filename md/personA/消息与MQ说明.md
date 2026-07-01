# 消息与 MQ（common/mq）说明

`common/mq` 定义全系统的 **通信公约**：消息类型常量、队列名、JSON 构建、RabbitMQ 封装。所有业务模块只通过这里约定的格式收发消息，不直接互调。

---

## 一、什么时候用到？

- **任何模块启动时**：`MessageBus.connect()` + `declareXxxQueue()` + `subscribe()`
- **发命令时**：`MessageBuilder.build(...)` → `bus.publish(队列名, json)`
- **收回调时**：订阅回调里 `parse` JSON，读 `type` 字段分支处理

模块之间 **零直接依赖**，只靠 MQ + Redis。

---

## 二、消息 JSON 格式

统一由 `MessageBuilder` 生成：

```json
{
  "type": "PLAN_ROUTE",
  "tick": 12,
  "carId": "Car001",
  "timestamp": 1719200000000,
  "data": { ... }
}
```

| 字段 | 说明 |
|------|------|
| `type` | 见 `MessageTypes` |
| `tick` | 当前仿真节拍号（Controller 递增） |
| `carId` | 车辆相关消息必填；系统级消息可为 null |
| `timestamp` | 毫秒时间戳 |
| `data` | 业务载荷，无数据时为 `{}` |

---

## 三、队列一览（QueueNames）

| 常量 | 队列名 | 典型消费者 |
|------|--------|------------|
| `CONTROLLER_CMD` | `ControllerCmd` | Controller |
| `TARGET_PLANNER_CMD` | `TargetPlannerCmd` | TargetPlanner |
| `NAVIGATOR_CMD` | `NavigatorCmd` | Navigator |
| `TASK_CONFIG_CMD` | `TaskConfigCmd` | TaskConfigurator |
| `STRATEGY_SUPERVISOR_CMD` | `StrategySupervisorCmd` | StrategySupervisor |
| `carQueue(carId)` | `Car_{carId}` | 对应 Car 进程 |
| `UPDATE_VIEW_EXCHANGE` | `UpdateView`（fanout） | Display 等 |

**重要**：每个业务队列在同一时刻应只有 **一个消费者**（多开会抢消息，导致车卡住、TASK_READY 丢失）。

---

## 四、消息类型与流向（MessageTypes）

### 任务生命周期

| 类型 | 方向 | 作用 |
|------|------|------|
| `SET_CONFIG` | Display → Controller | 用户点「开始」，带地图/车数等参数 |
| `FORWARD_CONFIG` | Controller → TaskConfigurator | 转发配置，初始化黑板 |
| `TASK_READY` | TaskConfigurator → Controller | 初始化完成，开始 tick |
| `RESET` / `FORWARD_RESET` | 前端 → Controller → TC | 重置仿真 |

### 探索循环

| 类型 | 方向 | 作用 |
|------|------|------|
| `ASSIGN_TARGET` | Controller → TargetPlanner | 给 IDLE 车分配探索目标 |
| `TARGET_ASSIGNED` | TargetPlanner → Controller | 分配结果（`success`） |
| `PLAN_ROUTE` | Controller → Navigator | 请求算路 |
| `ROUTE_PLANNED` | Navigator → Controller | 算路结果（`routeFound`） |
| `SUPERVISE_ROUTE` | Controller → StrategySupervisor | 请求路线监督 |
| `ROUTE_OPTIMIZED` | StrategySupervisor → Controller | 监督结果 |
| `TICK_MOVE` | Controller → Car | **唯一发送方**，触发走一格 |
| `MOVED` / `ROUTE_DONE` | Car → Controller | 移动完成 / 路径走完 |
| `BLOCKED` | Car → Controller | 下一步被挡 |
| `BLOCKED_TIMEOUT` | Controller → Car | 阻塞超时，通知重规划 |

### 前端与控制

| 类型 | 方向 | 作用 |
|------|------|------|
| `REFRESH_ALL` | Controller → fanout | 刷新探索率与画面 |
| `TOGGLE_PAUSE` | Display → Controller | 暂停/继续 tick |
| `SET_TICK_INTERVAL` | Display → Controller | 调节拍间隔（100～2000ms） |

---

## 五、MessageBus 做什么？

封装 RabbitMQ 常见操作：

| 方法 | 用途 |
|------|------|
| `connect()` | 建连，开启自动恢复 |
| `declareCarQueue` / `declareControllerQueue` 等 | 声明持久化队列 |
| `declareFanoutExchange` | 声明 `UpdateView` 广播交换机 |
| `publish(queue, json)` | 发到指定队列 |
| `publishFanout(exchange, json)` | 广播（不指定 routing key） |
| `subscribe(queue, callback)` | 消费消息 |
| `purgeQueue` | Controller 启动时清空 `ControllerCmd` 积压 |

---

## 六、典型消息链（点开始后第一格）

```
SET_CONFIG → FORWARD_CONFIG → (写 Redis) → TASK_READY
  → ASSIGN_TARGET → TARGET_ASSIGNED
  → PLAN_ROUTE → ROUTE_PLANNED
  → [可选] SUPERVISE_ROUTE → ROUTE_OPTIMIZED
  → TICK_MOVE → MOVED → REFRESH_ALL
```

详见 [Controller调度器说明.md](./Controller调度器说明.md)。

---

## 七、改接口前的检查

| 你改了什么 | 必须做 |
|------------|--------|
| `MessageTypes` 新增/改名 | 全仓库 grep + 通知 B/C/D |
| `MessageBuilder` 字段 | 所有发布方/订阅方对齐 |
| `QueueNames` | 各模块 `declare` 与 `subscribe` 一致 |

测试：`common` 模块 `MessageBuilderTest`、`MessageTypesTest`。

---

## 八、一句话总结

**MQ 层 = 系统的神经系统**：`MessageTypes` 定义语义，`QueueNames` 定义通道，`MessageBuilder` 统一 JSON，`MessageBus` 负责收发。

---

## 九、相关源码

| 文件 | 路径 |
|------|------|
| 消息类型 | `common/.../mq/MessageTypes.java` |
| 队列名 | `common/.../mq/QueueNames.java` |
| JSON 构建 | `common/.../mq/MessageBuilder.java` |
| RabbitMQ 封装 | `common/.../mq/MessageBus.java` |
