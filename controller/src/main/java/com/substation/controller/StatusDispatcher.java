package com.substation.controller;

import com.substation.common.DynamicObstacleUtil;
import com.substation.common.model.AlgorithmType;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 状态分派器：每节拍发现车辆、按状态分发处理、发送移动指令、广播刷新 */
public class StatusDispatcher {

    /** 探索完成阈值（百分比），达到后自动结束任务 */
    private static final int EXPLORATION_COMPLETE = 99;
    /** 阻塞超时节拍数，超时后清除路径并重新分配目标 */
    private static final int BLOCKED_TIMEOUT_TICKS = 2;
    /** 移动卡住节拍数，连续MOVING超此时长则强制切为READY */
    private static final int MOVING_STUCK_TICKS = 2;
    /** 消息字段名：车辆ID */
    private static final String FIELD_CAR_ID = "carId";
    /** 消息字段名：路径起点 */
    private static final String FIELD_SUB_START = "start";
    /** 消息字段名：路径终点 */
    private static final String FIELD_SUB_TARGET = "target";
    /** 消息字段名：寻路算法 */
    private static final String FIELD_ALGORITHM = "algorithm";
    /** 消息字段名：当前节拍号 */
    private static final String FIELD_TICK = "tick";
    /** 消息字段名：任务耗时（秒） */
    private static final String FIELD_ELAPSED_SECONDS = "elapsedSeconds";

    /** 黑板客户端（Redis 共享状态） */
    private final BlackboardClient bb;
    /** 消息总线（RabbitMQ） */
    private final MessageBus bus;
    /** 待响应的目标分配请求集合（线程安全） */
    private final Set<String> pendingTargetRequests;
    /** 车辆连续处于MOVING状态的节拍计数 */
    private final Map<String, Integer> movingTickCounts;
    /** 当前节拍号 */
    private volatile int tick;
    /** 任务是否活跃 */
    private volatile boolean taskActive;
    /** 动态障碍物生成间隔（每N个tick触发一次） */
    private static final int DYNAMIC_OBSTACLE_INTERVAL = 20;
    /** 任务开始时间戳（毫秒） */
    private volatile long taskStartTime;
    /** 动态障碍物工具 */
    private final DynamicObstacleUtil dynamicObstacleUtil = new DynamicObstacleUtil();

    /** 创建状态分派器，初始化并发集合 */
    public StatusDispatcher(BlackboardClient bb, MessageBus bus) {
        this.bb = bb;
        this.bus = bus;
        this.pendingTargetRequests = ConcurrentHashMap.newKeySet();
        this.movingTickCounts = new ConcurrentHashMap<>();
    }

    // ==================== dispatch ====================

    /** 执行一次节拍调度：发现车辆 → 按状态分发 → 发送移动指令 → 广播刷新 */
    public void dispatch() {
        if (!taskActive) {
            return;
        }
        tick++;

        if (bb.getExplorationRate() >= EXPLORATION_COMPLETE) {
            completeTask();
            return;
        }

        Set<String> carIds = bb.discoverCarIds();
        if (carIds.isEmpty()) {
            return;
        }

        for (String carId : carIds) {
            bb.getCarStatus(carId).ifPresent(status -> dispatchCar(carId, status));
        }
        for (String carId : carIds) {
            bb.getCarStatus(carId).ifPresent(status -> {
                if (status == CarStatus.READY) {
                    sendTickMove(carId);
                }
            });
        }

        broadcastRefresh();
    }

    // ==================== callbacks for CommandHandler ====================

    /** 目标分配结果回调：成功则车辆进入等待路径状态，失败则移除待响应记录 */
    public void onTargetAssigned(String carId, boolean success) {
        if (pendingTargetRequests.remove(carId) && success) {
            bb.setCarStatus(carId, CarStatus.WAITING_ROUTE);
        }
    }

    /** 路径规划结果回调：成功则车辆就绪等待移动，失败则回到待机状态 */
    public void onRoutePlanned(String carId, boolean routeFound) {
        if (routeFound) {
            bb.setCarStatus(carId, CarStatus.READY);
        } else {
            bb.setCarStatus(carId, CarStatus.IDLE);
        }
    }

    /** 切换指定格子的障碍物状态（右键菜单触发） */
    public void toggleObstacle(int row, int col) {
        boolean current = bb.isBlocked(row, col);
        bb.setBlock(row, col, !current);
        System.out.println("[Controller] 障碍物 " + (!current ? "新增" : "移除") + "(" + col + "," + row + ")");
    }

    /** 任务就绪回调：激活调度、记录开始时间、重置节拍计数 */
    public void onTaskReady() {
        taskActive = true;
        taskStartTime = System.currentTimeMillis();
        tick = 0;
    }

    /** 转发配置消息到任务配置队列 */
    public void forwardConfig(Map<String, Object> config) {
        try {
            String msg = MessageBuilder.build(MessageTypes.FORWARD_CONFIG, tick, null, config);
            bus.publish(QueueNames.TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 转发重置消息并清理本地状态（待响应请求、移动计数、任务活跃标志） */
    public void forwardReset() {
        try {
            String msg = MessageBuilder.build(MessageTypes.FORWARD_RESET, tick);
            bus.publish(QueueNames.TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
        pendingTargetRequests.clear();
        movingTickCounts.clear();
        taskActive = false;
    }

    // ==================== private dispatch helpers ====================

    /** 按车辆当前状态分派处理：IDLE分配目标、WAITING_ROUTE规划路径、MOVING检测卡住、BLOCKED检测超时 */
    private void dispatchCar(String carId, CarStatus status) {
        if (status != CarStatus.MOVING) {
            movingTickCounts.remove(carId);
        }
        switch (status) {
            case IDLE -> sendAssignTarget(carId);
            case WAITING_ROUTE -> checkAndPlanRoute(carId);
            case MOVING -> checkMovingStuck(carId);
            case BLOCKED -> checkBlockedTimeout(carId);
            case READY -> {} // 在第二轮循环中统一处理
        }
    }

    /** 发送目标分配请求到目标规划器 */
    private void sendAssignTarget(String carId) {
        pendingTargetRequests.add(carId);
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.ASSIGN_TARGET, tick, carId, data);
            bus.publish(QueueNames.TARGET_PLANNER_CMD, msg);
        } catch (Exception e) {
            pendingTargetRequests.remove(carId);
            e.printStackTrace();
        }
    }

    /** 发送路径规划请求到导航器（携带起点、终点、算法） */
    private void checkAndPlanRoute(String carId) {
        bb.getCarTarget(carId).ifPresent(target ->
            bb.getCarPosition(carId).ifPresent(pos -> {
                String algorithm = bb.getAlgorithm();
                Map<String, Object> data = Map.of(
                    FIELD_CAR_ID, carId,
                    FIELD_SUB_START, Map.of("x", pos.x(), "y", pos.y()),
                    FIELD_SUB_TARGET, Map.of("x", target.x(), "y", target.y()),
                    FIELD_ALGORITHM, algorithm != null ? algorithm : AlgorithmType.BFS.name()
                );
                try {
                    String msg = MessageBuilder.build(MessageTypes.PLAN_ROUTE, tick, carId, data);
                    bus.publish(QueueNames.NAVIGATOR_CMD, msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
    }

    /** 检测车辆移动卡住：连续MOVING超过阈值则强制切为READY以重新触发移动 */
    private void checkMovingStuck(String carId) {
        int count = movingTickCounts.getOrDefault(carId, 0) + 1;
        if (count >= MOVING_STUCK_TICKS) {
            movingTickCounts.remove(carId);
            bb.setCarStatus(carId, CarStatus.READY);
        } else {
            movingTickCounts.put(carId, count);
        }
    }

    /** 检测阻塞超时：超时后清除路径、目标与占位标记，车辆回到IDLE，让其他车可穿行 */
    private void checkBlockedTimeout(String carId) {
        if (tick - bb.getBlockedTick(carId) >= BLOCKED_TIMEOUT_TICKS) {
            bb.clearRoute(carId);
            bb.clearCarTarget(carId);
            bb.clearBlockedTick(carId);
            bb.getCarPosition(carId).ifPresent(pos -> bb.setBlock(pos.y(), pos.x(), false));
            bb.setCarStatus(carId, CarStatus.IDLE);
            sendBlockedTimeout(carId);
        }
    }

    /** 发送移动指令给指定车辆 */
    private void sendTickMove(String carId) {
        Map<String, Object> data = Map.of(FIELD_TICK, tick);
        try {
            String msg = MessageBuilder.build(MessageTypes.TICK_MOVE, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 发送阻塞超时通知给指定车辆 */
    private void sendBlockedTimeout(String carId) {
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.BLOCKED_TIMEOUT, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 广播探索进度刷新消息给前端（fanout） */
    private void broadcastRefresh() {
        int rate = bb.getExplorationRate();
        Map<String, Object> data = Map.of("explorationRate", rate);
        try {
            String msg = MessageBuilder.build(MessageTypes.REFRESH_ALL, tick, null, data);
            bus.publishFanout(QueueNames.UPDATE_VIEW_EXCHANGE, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 完成任务：停用调度、计算耗时并写入黑板 */
    private void completeTask() {
        taskActive = false;
        long elapsed = (System.currentTimeMillis() - taskStartTime) / 1000;
        bb.initTaskConfig(Map.of(FIELD_ELAPSED_SECONDS, String.valueOf(elapsed)));
    }
}
