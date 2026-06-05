package com.substation.car;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;
import com.substation.common.redis.DistributedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Optional;

/**
 * 小车移动执行器 —— 负责 8 步原子移动操作。
 *
 * <p>核心流程：
 * <ol>
 *   <li>分布式锁加锁</li>
 *   <li>检查状态是否为 READY</li>
 *   <li>状态切换为 MOVING（心跳）</li>
 *   <li>peek 下一步位置</li>
 *   <li>检查障碍物</li>
 *   <li>pop 消费该步</li>
 *   <li>清除旧位置 + 更新新位置</li>
 *   <li>更新 mapBlock 占位标记</li>
 *   <li>点亮 3×3 区域 + 热力图计数器</li>
 *   <li>递增步数 + 记录 History</li>
 *   <li>路径状态判定（IDLE / READY）</li>
 *   <li>分布式锁释放</li>
 * </ol>
 */
public class MoveExecutor {

    private static final Logger log = LoggerFactory.getLogger(MoveExecutor.class);
    private static final int DEFAULT_MAP_SIZE = 30;

    private final String carId;
    private final BlackboardClient bb;
    private final MessageBus mb;
    private final JedisPool pool;
    private final int mapWidth;
    private final int mapHeight;

    public MoveExecutor(String carId, BlackboardClient bb, MessageBus mb,
                        JedisPool pool, int mapWidth, int mapHeight) {
        this.carId = carId;
        this.bb = bb;
        this.mb = mb;
        this.pool = pool;
        this.mapWidth = mapWidth > 0 ? mapWidth : DEFAULT_MAP_SIZE;
        this.mapHeight = mapHeight > 0 ? mapHeight : DEFAULT_MAP_SIZE;
    }

    /**
     * 执行一次节拍移动。获取锁失败时跳过本拍，等下一节拍重试。
     */
    public void executeMove(int tick) {
        DistributedLock lock = new DistributedLock(pool, carId);
        if (!lock.tryLock()) {
            log.warn("[{}] 获取分布式锁失败，跳过 tick={}", carId, tick);
            return;
        }
        try {
            doMove(tick);
        } catch (Exception e) {
            log.error("[{}] 移动异常 tick={}: {}", carId, tick, e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    // ==================== 主流程 ====================

    private void doMove(int tick) {
        // Step 2: 检查状态
        Optional<CarStatus> statusOpt = bb.getCarStatus(carId);
        if (statusOpt.isEmpty() || statusOpt.get() != CarStatus.READY) {
            return;
        }

        // 状态切换为 MOVING（心跳，防 Controller 误判崩溃）
        bb.setCarStatus(carId, CarStatus.MOVING);

        // Step 3: peek 下一步
        Optional<Point> nextStep = bb.peekNextRouteStep(carId);
        if (nextStep.isEmpty()) {
            log.warn("[{}] 状态为 READY 但 RouteList 为空", carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            return;
        }
        Point nextPos = nextStep.get();
        int nx = nextPos.x();
        int ny = nextPos.y();

        // Step 4: 障碍物检测
        if (bb.isBlocked(ny, nx)) {
            handleObstacleDetected(tick, nextPos);
            return;
        }

        // Step 5: pop 消费该步
        bb.popNextRouteStep(carId);

        // Step 6 & 7: 清除旧位置 mapBlock 标记
        Optional<Point> oldPos = bb.getCarPosition(carId);
        oldPos.ifPresent(p -> bb.setBlock(p.y(), p.x(), false));

        // 更新新位置
        bb.setCarPosition(carId, nextPos);

        // Step 8: 新位置标记为占用
        bb.setBlock(ny, nx, true);

        // Step 9: 点亮 3×3 + 热力图
        illuminateAndHeat(nextPos);

        // Step 10: 步数 +1
        bb.incrementCarSteps(carId);

        // History 路径记录
        recordHistory(nextPos, tick);

        // Step 11: 路径完成判定
        boolean routeDone = bb.getCarRoute(carId).isEmpty();
        if (routeDone) {
            bb.clearCarTarget(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            sendRouteDone(tick, nextPos);
            log.info("[{}] 路径走完，最终位置({},{})，tick={}", carId, nx, ny, tick);
        } else {
            bb.setCarStatus(carId, CarStatus.READY);
            sendMoved(tick, nextPos);
            log.debug("[{}] 移动到({},{})，路径剩余 {} 步", carId, nx, ny,
                    bb.getCarRoute(carId).size());
        }
    }

    // ==================== 辅助方法 ====================

    private void handleObstacleDetected(int tick, Point blockedPos) {
        bb.clearRoute(carId);
        bb.clearCarTarget(carId);
        bb.setCarStatus(carId, CarStatus.BLOCKED);
        bb.setBlockedTick(carId, tick);
        sendBlocked(tick, blockedPos);
        log.warn("[{}] 下一步位置({},{})有障碍，进入 BLOCKED，tick={}",
                carId, blockedPos.x(), blockedPos.y(), tick);
    }

    private void illuminateAndHeat(Point center) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = center.y() + dr;
                int c = center.x() + dc;
                if (r >= 0 && r < mapHeight && c >= 0 && c < mapWidth) {
                    bb.setMapViewBit(r, c, true);
                    bb.incrementMapHeat(r, c);
                }
            }
        }
    }

    private void recordHistory(Point position, int tick) {
        try (Jedis jedis = pool.getResource()) {
            JSONObject record = new JSONObject();
            record.put("x", position.x());
            record.put("y", position.y());
            record.put("tick", tick);
            jedis.rpush(carId + ":History", record.toJSONString());
        }
    }

    // ==================== 消息发送 ====================

    private void sendMoved(int tick, Point newPos) {
        try {
            Map<String, Object> data = Map.of(
                    "newPosition", Map.of("x", newPos.x(), "y", newPos.y()),
                    "routeRemaining", bb.getCarRoute(carId).size());
            String msg = MessageBuilder.build(MessageTypes.MOVED, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 MOVED 消息失败", carId, e);
        }
    }

    private void sendRouteDone(int tick, Point finalPos) {
        try {
            Map<String, Object> data = Map.of(
                    "finalPosition", Map.of("x", finalPos.x(), "y", finalPos.y()));
            String msg = MessageBuilder.build(MessageTypes.ROUTE_DONE, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 ROUTE_DONE 消息失败", carId, e);
        }
    }

    private void sendBlocked(int tick, Point blockedPos) {
        try {
            Map<String, Object> data = Map.of(
                    "blockedPosition", Map.of("x", blockedPos.x(), "y", blockedPos.y()),
                    "blockedTick", tick);
            String msg = MessageBuilder.build(MessageTypes.BLOCKED, tick, carId, data);
            mb.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (Exception e) {
            log.error("[{}] 发送 BLOCKED 消息失败", carId, e);
        }
    }
}
