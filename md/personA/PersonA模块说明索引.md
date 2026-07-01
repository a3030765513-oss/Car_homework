# Person A 模块说明索引

> **读者**：Person A（`hzx_common` 分支）  
> **职责**：`common` 公约层、`controller` 节拍调度、`strategy-supervisor` 路线监督、`common/map` 探索优化算法  
> **配套**：[PersonA代码阅读路线.md](./PersonA代码阅读路线.md)（学习顺序）、[../人员分工.md](../人员分工.md)（原始分工）

---

## 文档列表

| 文档 | 模块 | 一句话 |
|------|------|--------|
| [数据模型说明.md](./数据模型说明.md) | `common/model` | 坐标、五态、算法枚举、前端快照结构 |
| [消息与MQ说明.md](./消息与MQ说明.md) | `common/mq` | 消息类型、队列名、JSON 格式、MessageBus |
| [Redis黑板说明.md](./Redis黑板说明.md) | `common/redis` | 共享状态读写、位图、车辆发现、分布式锁 |
| [Controller调度器说明.md](./Controller调度器说明.md) | `controller` | 系统大脑：节拍、五态分发、发令与收回调 |
| [策略监督器说明.md](./策略监督器说明.md) | `strategy-supervisor` | 算路后的路线质检与加权替换 |
| [探索地图算法说明.md](./探索地图算法说明.md) | `common/map` | 前沿格、加权寻路、未探索聚类 |

---

## Person A 在系统中的位置

```
浏览器 → Display
           ↓ MQ
    Controller（A）←→ Redis 黑板（A）
      ↓ ASSIGN_TARGET / PLAN_ROUTE / TICK_MOVE / SUPERVISE_ROUTE
TargetPlanner / Navigator / Car / StrategySupervisor（A）
```

**原则**：业务模块之间不直接调用，只通过 **MQ 消息** + **Redis 黑板** 协作。Person A 维护这两层的「公约」和 Controller 调度逻辑。

---

## 按问题快速跳转

| 你想搞懂… | 读哪篇 |
|-----------|--------|
| 消息 `type` 有哪些、走哪个队列 | [消息与MQ说明.md](./消息与MQ说明.md) |
| Redis 里 key 叫什么、谁读写 | [Redis黑板说明.md](./Redis黑板说明.md) |
| 每拍 Controller 干什么、`TICK_MOVE` 谁发 | [Controller调度器说明.md](./Controller调度器说明.md) |
| 监督器何时介入、会不会改路线 | [策略监督器说明.md](./策略监督器说明.md) |
| 前沿格、加权路径代价怎么算 | [探索地图算法说明.md](./探索地图算法说明.md) |
| `CarStatus` 五态含义、谁写状态 | [数据模型说明.md](./数据模型说明.md) + Controller 篇 §五态 |

---

## 源码主路径速查

| 领域 | 路径 |
|------|------|
| 消息公约 | `common/.../mq/MessageTypes.java`、`QueueNames.java`、`MessageBuilder.java`、`MessageBus.java` |
| 黑板 | `common/.../redis/BlackboardClient.java`、`DistributedLock.java` |
| 数据模型 | `common/.../model/*.java` |
| 节拍调度 | `controller/.../StatusDispatcher.java`、`TickScheduler.java`、`CommandHandler.java` |
| 路线监督 | `strategy-supervisor/.../StrategySupervisorMain.java` |
| 探索算法 | `common/.../map/FrontierCellFinder.java`、`ExplorationWeightedPathFinder.java` |

---

**文档版本**：2026-06-24  
**维护**：Person A；Person A 专题文档统一放在 `md/personA/` 目录
