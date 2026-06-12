# Person D（display + launcher）代码审查问题清单

> 审查人：Person A  
> 日期：2026-06-05  
> 结论：整体设计完整，架构清晰，与 common 接口兼容。8 模块全部编译通过。发现 4 个需对齐的问题。

---

## 问题 1（中危—字段名）前端 JSON 字段 `algorithmType` 应改为 `algorithm`

**位置**：设计文档 §2.2 + 前端 js/app.js 读取 `taskConfig.algorithmType`

Person D 的设计文档里下行 JSON 用了 `"algorithmType": "ASTAR"`，但 Redis `TaskConfig` hash 中实际存储的字段名是 `"algorithm"`。

`BlackboardClient.getAlgorithm()` 返回的是 `jedis.hget("TaskConfig", "algorithm")`，Person C 的 `TaskInitializer` 初始化时写的是 `"algorithm"`。

前端如果按 `algorithmType` 取值，会拿到 `undefined`，算法下拉框默认值失效。

**修复**：统一使用 `"algorithm"`。前端读 `taskConfig.algorithm`，或 TaskConfigurator 写 `algorithmType`，二选一统一。

---

## 问题 2（低危—对齐）Launcher 中 B/C 模块标记"待开发"，实际代码已就绪

**位置**：`launcher/.../LauncherMain.java`

当前 Launcher 对 task-configurator / navigator / target-planner / car 四个模块打印 "LOG WARN 跳过"。实际上这些模块的代码已经从 Person B 和 Person C 的分支拉下来了，编译也通过了。

建议确认 LauncherMain 需要的构造函数签名（参考 ControllerMain 的模式），然后开启启动：

```java
// 预期签名（需与 B、C 对齐）
new TaskConfiguratorMain(redisHost, redisPort, mqHost, mqPort).start()
new NavigatorMain(redisHost, redisPort, mqHost, mqPort).start()
new TargetPlannerMain(redisHost, redisPort, mqHost, mqPort).start()
new CarMain(carId, redisHost, redisPort, mqHost, mqPort).start()
```

Person B 的 CarMain 还需要多一个 `carId` 参数。具体签名需和 B、C 确认。

---

## 问题 3（低危—对齐）ControllerMain 构造函数已适配

**位置**：`controller/.../ControllerMain.java`

ControllerMain 已按 Launcher 期望的模式重构：

```java
// Launcher 调用方式
new ControllerMain(redisHost, redisPort, mqHost, mqPort).start();

// 独立运行仍可用
java ControllerMain
```

DisplayMain 本身已符合 Launcher 的构造函数模式，无需改动。

---

## 个问题 4（低危—缺失）热力图模式未在设计文档中提及

**位置**：设计文档 §三 Canvas 渲染

Person B 的 Car 模块已经在 `MoveExecutor` 中每步调用 `bb.incrementMapHeat(r, c)` 写入热力图计数器。Redis `mapHeat` hash 中已经有数据积累。

虽然热力图是"选做"，但建议在前端预留渲染接口——Canvas 渲染层增加一层可切换的热力图层，读取 `mapHeat` 数据按色调映射（蓝→黄→红）。

---

## 汇总

| # | 严重程度 | 位置 | 一句话 |
|---|----------|------|--------|
| 1 | 中危 | 前端/文档 | `algorithmType` → `algorithm`，字段名对齐 |
| 2 | 低危 | LauncherMain | 四个模块可开启启动，确认构造函数 |
| 3 | 低危 | ControllerMain | 已适配 Launcher 构造函数，无需改动 |
| 4 | 低危 | 设计文档 | 热力图预留渲染层 |

---

## 评价

设计文档和代码质量都很高：
- WebSocket 协议设计清晰，上行下行职责分明
- Canvas 五层渲染顺序合理，配色与设计文档完全一致
- 动态车辆适配、路径回放、步数排行榜等附加功能都有完整方案
- Launcher 的启动顺序和线程模型设计得当
- 47 个测试全部通过

4 个问题都是小对齐，不涉及架构改动。辛苦了！
