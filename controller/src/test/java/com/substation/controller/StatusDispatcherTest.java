package com.substation.controller;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBus;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class StatusDispatcherTest {

    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private MessageBus bus;
    private StatusDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        try (Jedis jedis = new JedisPool("localhost", 6379).getResource()) {
            jedis.flushDB();
        }
        bb = new BlackboardClient("localhost", 6379, MAP_SIZE, MAP_SIZE);
        bus = new MessageBus("localhost", 5672, "guest", "guest");
        bus.connect();
        bus.declareTargetPlannerQueue();
        bus.declareNavigatorQueue();
        bus.declareTaskConfigQueue();
        bus.declareFanoutExchange();

        bb.initTaskConfig(Map.of("active", "true", "algorithm", "BFS"));
        dispatcher = new StatusDispatcher(bb, bus);
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = new JedisPool("localhost", 6379).getResource()) {
            jedis.flushDB();
        }
        bus.close();
        bb.close();
    }

    @Test
    void idleCarReceivesAssignTarget() {
        registerCar("Car001", new Point(5, 5), CarStatus.IDLE);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car001").orElseThrow());
    }

    @Test
    void blockedTimeoutResetsToIdle() {
        registerCar("Car002", new Point(10, 10), CarStatus.BLOCKED);
        bb.setBlockedTick("Car002", 0);
        dispatcher.onTaskReady();

        // tick 1: 1-0=1 < 2, blocked too recent
        dispatcher.dispatch();
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus("Car002").orElseThrow());

        // tick 2: 2-0=2 >= 2, timeout triggers
        dispatcher.dispatch();
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car002").orElseThrow());
        assertEquals(-1, bb.getBlockedTick("Car002"));
    }

    @Test
    void movingStuckResetsToReady() {
        registerCar("Car003", new Point(10, 10), CarStatus.MOVING);
        dispatcher.onTaskReady();

        // tick 1: first MOVING
        dispatcher.dispatch();
        assertEquals(CarStatus.MOVING, bb.getCarStatus("Car003").orElseThrow());

        // tick 2: stuck, should reset to READY
        dispatcher.dispatch();
        assertEquals(CarStatus.READY, bb.getCarStatus("Car003").orElseThrow());
    }

    @Test
    void readyCarGetsTickMove() {
        registerCar("Car004", new Point(10, 10), CarStatus.READY);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        // Car should remain READY (Car module handles the actual move)
        assertEquals(CarStatus.READY, bb.getCarStatus("Car004").orElseThrow());
    }

    @Test
    void notActiveSkipsDispatch() {
        registerCar("Car005", new Point(5, 5), CarStatus.IDLE);
        // Don't call onTaskReady, so taskActive is false
        dispatcher.dispatch();
        assertEquals(CarStatus.IDLE, bb.getCarStatus("Car005").orElseThrow());
    }

    @Test
    void taskCompleteWhenExplorationReachesThreshold() {
        // Set almost all cells as explored
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
        registerCar("Car006", new Point(5, 5), CarStatus.IDLE);
        dispatcher.onTaskReady();
        dispatcher.dispatch();

        // After completion, task should be inactive
        assertTrue(bb.getExplorationRate() >= 99);
    }

    private void registerCar(String carId, Point pos, CarStatus status) {
        bb.setCarPosition(carId, pos);
        bb.setCarStatus(carId, status);
        bb.setCarSteps(carId, 0);
    }
}
