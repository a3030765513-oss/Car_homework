# Person B（Car 模块）代码审查问题清单

> 审查人：Person A  
> 日期：2026-06-05  
> 结论：代码能编译，与 common 接口完全兼容，19 个测试通过。发现 3 个小问题。

---

## 问题 1（低危—风格）doMove() 超过 30 行

**位置**：`car/.../MoveExecutor.java` 第 82-143 行

`doMove()` 方法约 60 行，违反 CLAUDE.md "单个函数不超过 30 行"。建议拆分为 3 个小方法：

- `validateAndPeekStep()` — 检查状态 + peek 下一步 + 障碍检测
- `executePositionUpdate()` — pop + 更新位置 + 点亮 + 记录
- `finalizeMoveStatus()` — 判定状态 + 发消息

## 问题 2（低危—封装）recordHistory 绕过 BlackboardClient

**位置**：`car/.../MoveExecutor.java` 第 171-179 行

```java
// 当前：直接拿 Jedis 手动拼 key
private void recordHistory(Point position, int tick) {
    try (Jedis jedis = pool.getResource()) {
        jedis.rpush(carId + ":History", record.toJSONString());
    }
}
```

其他 Redis 操作都走 BlackboardClient，只有这里绕过去了。如果 History key 格式变化，需要改两处。建议在 BlackboardClient 加 `appendCarHistory(carId, position, tick)` 方法。如果现在不方便加，当前写法也能用。

## 问题 3（低危—效率）selfRegister 重复调用 getCarStatus

**位置**：`car/.../CarMain.java` 第 136-138 行

```java
// 当前：同一个值查了两次 Redis
if (bb.getCarStatus(carId).isPresent()) {          // 第 1 次
    CarStatus s = bb.getCarStatus(carId).orElseThrow(); // 第 2 次
```

改为：

```java
Optional<CarStatus> status = bb.getCarStatus(carId);
if (status.isPresent()) {
    CarStatus s = status.get();
```

---

## 汇总

| # | 严重程度 | 位置 | 一句话 |
|---|----------|------|--------|
| 1 | 低危 | MoveExecutor | doMove 超 30 行 |
| 2 | 低危 | MoveExecutor | 绕过 BlackboardClient 写 History |
| 3 | 低危 | CarMain | 重复调用 getCarStatus |

---

## 评价

Car 模块整体质量不错，12 步原子移动逻辑完整，19 个测试覆盖充分。3 个问题都属于代码风格层面的优化，不影响运行，不改也能正常联调。辛苦了！
