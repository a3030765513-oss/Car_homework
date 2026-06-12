package com.substation.car;

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
        Optional<CarStatus> statusOpt = bb.getCarStatus(carId);
        if (statusOpt.isEmpty() || statusOpt.get() != CarStatus.READY) {
            return;
        }
        bb.setCarStatus(carId, CarStatus.MOVING);

        Optional<Point> nextStep = bb.peekNextRouteStep(carId);
        if (nextStep.isEmpty()) {
            log.warn("[{}] 状态为 READY 但 RouteList 为空", carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            return;
        }
        Point nextPos = nextStep.get();

        if (bb.isBlocked(nextPos.y(), nextPos.x())) {
            handleObstacleDetected(tick, nextPos);
            return;
        }

        executeStep(nextPos, tick);
    }

    private void executeStep(Point nextPos, int tick) {
        bb.popNextRouteStep(carId);
        bb.getCarPosition(carId).ifPresent(old -> bb.setBlock(old.y(), old.x(), false));
        bb.setCarPosition(carId, nextPos);
        bb.setBlock(nextPos.y(), nextPos.x(), true);
        illuminateAndHeat(nextPos);
        bb.incrementCarSteps(carId);
        bb.appendCarHistory(carId, nextPos, tick);
        finalizeMove(tick, nextPos);
    }

    private void finalizeMove(int tick, Point pos) {
        if (bb.getCarRoute(carId).isEmpty()) {
            bb.setCarStatus(carId, CarStatus.IDLE);
            sendRouteDone(tick, pos);
            log.info("[{}] 路径走完，最终位置({},{})，tick={}", carId, pos.x(), pos.y(), tick);
        } else {
            bb.setCarStatus(carId, CarStatus.READY);
            sendMoved(tick, pos);
        }
    }

    // ==================== 辅助方法 ====================

    private void handleObstacleDetected(int tick, Point blockedPos) {
        bb.clearRoute(carId);
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
