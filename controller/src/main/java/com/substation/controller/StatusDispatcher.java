package com.substation.controller;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StatusDispatcher {

    private static final double EXPLORATION_COMPLETE = 99.9;
    private static final int BLOCKED_TIMEOUT_TICKS = 2;
    private static final int MOVING_STUCK_TICKS = 2;
    private static final String FIELD_CAR_ID = "carId";
    private static final String FIELD_SUB_START = "start";
    private static final String FIELD_SUB_TARGET = "target";
    private static final String FIELD_ALGORITHM = "algorithm";
    private static final String FIELD_TICK = "tick";
    private static final String FIELD_ELAPSED_SECONDS = "elapsedSeconds";

    private final BlackboardClient bb;
    private final MessageBus bus;
    private final Set<String> pendingTargetRequests;
    private final Map<String, Integer> movingTickCounts;
    private volatile int tick;
    private volatile boolean taskActive;
    private volatile long taskStartTime;

    public StatusDispatcher(BlackboardClient bb, MessageBus bus) {
        this.bb = bb;
        this.bus = bus;
        this.pendingTargetRequests = ConcurrentHashMap.newKeySet();
        this.movingTickCounts = new ConcurrentHashMap<>();
    }

    // ==================== dispatch ====================

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

    public void onTargetAssigned(String carId, boolean success) {
        if (pendingTargetRequests.remove(carId) && success) {
            bb.setCarStatus(carId, CarStatus.WAITING_ROUTE);
        }
    }

    public void onRoutePlanned(String carId, boolean routeFound) {
        if (routeFound) {
            bb.setCarStatus(carId, CarStatus.READY);
        }
    }

    public void onTaskReady() {
        taskActive = true;
        taskStartTime = System.currentTimeMillis();
        tick = 0;
    }

    public void forwardConfig(Map<String, Object> config) {
        try {
            String msg = MessageBuilder.build(MessageTypes.FORWARD_CONFIG, tick, null, config);
            bus.publish(QueueNames.TASK_CONFIG_CMD, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    private void dispatchCar(String carId, CarStatus status) {
        if (status != CarStatus.MOVING) {
            movingTickCounts.remove(carId);
        }
        switch (status) {
            case IDLE -> sendAssignTarget(carId);
            case WAITING_ROUTE -> checkAndPlanRoute(carId);
            case MOVING -> checkMovingStuck(carId);
            case BLOCKED -> checkBlockedTimeout(carId);
            case READY -> {} // handled in second pass
        }
    }

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

    private void checkAndPlanRoute(String carId) {
        bb.getCarTarget(carId).ifPresent(target ->
            bb.getCarPosition(carId).ifPresent(pos -> {
                String algorithm = bb.getAlgorithm();
                Map<String, Object> data = Map.of(
                    FIELD_CAR_ID, carId,
                    FIELD_SUB_START, Map.of("x", pos.x(), "y", pos.y()),
                    FIELD_SUB_TARGET, Map.of("x", target.x(), "y", target.y()),
                    FIELD_ALGORITHM, algorithm != null ? algorithm : "BFS"
                );
                try {
                    String msg = MessageBuilder.build(MessageTypes.PLAN_ROUTE, tick, carId, data);
                    bus.publish(QueueNames.NAVIGATOR_CMD, msg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));
    }

    private void checkMovingStuck(String carId) {
        int count = movingTickCounts.getOrDefault(carId, 0) + 1;
        if (count >= MOVING_STUCK_TICKS) {
            movingTickCounts.remove(carId);
            bb.setCarStatus(carId, CarStatus.READY);
        } else {
            movingTickCounts.put(carId, count);
        }
    }

    private void checkBlockedTimeout(String carId) {
        if (tick - bb.getBlockedTick(carId) >= BLOCKED_TIMEOUT_TICKS) {
            bb.clearRoute(carId);
            bb.clearCarTarget(carId);
            bb.clearBlockedTick(carId);
            bb.setCarStatus(carId, CarStatus.IDLE);
            sendBlockedTimeout(carId);
        }
    }

    private void sendTickMove(String carId) {
        Map<String, Object> data = Map.of(FIELD_TICK, tick);
        try {
            String msg = MessageBuilder.build(MessageTypes.TICK_MOVE, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendBlockedTimeout(String carId) {
        Map<String, Object> data = Map.of(FIELD_CAR_ID, carId);
        try {
            String msg = MessageBuilder.build(MessageTypes.BLOCKED_TIMEOUT, tick, carId, data);
            bus.publish(QueueNames.carQueue(carId), msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    private void completeTask() {
        taskActive = false;
        long elapsed = (System.currentTimeMillis() - taskStartTime) / 1000;
        bb.initTaskConfig(Map.of(FIELD_ELAPSED_SECONDS, String.valueOf(elapsed)));
    }
}
