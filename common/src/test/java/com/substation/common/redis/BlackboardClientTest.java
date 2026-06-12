package com.substation.common.redis;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.*;

class BlackboardClientTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.close();
    }

    @Test
    void mapViewBit() {
        assertFalse(bb.getMapViewBit(5, 10));
        bb.setMapViewBit(5, 10, true);
        assertTrue(bb.getMapViewBit(5, 10));
        bb.setMapViewBit(5, 10, false);
        assertFalse(bb.getMapViewBit(5, 10));
    }

    @Test
    void mapBlockBit() {
        assertFalse(bb.isBlocked(3, 7));
        bb.setBlock(3, 7, true);
        assertTrue(bb.isBlocked(3, 7));
    }

    @Test
    void carPosition() {
        assertTrue(bb.getCarPosition("Car001").isEmpty());

        bb.setCarPosition("Car001", new Point(5, 10));
        Optional<Point> pos = bb.getCarPosition("Car001");
        assertTrue(pos.isPresent());
        assertEquals(new Point(5, 10), pos.get());

        bb.clearCarPosition("Car001");
        assertTrue(bb.getCarPosition("Car001").isEmpty());
    }

    @Test
    void carTarget() {
        assertFalse(bb.hasTarget("Car001"));
        bb.setCarTarget("Car001", new Point(20, 15));
        assertTrue(bb.hasTarget("Car001"));

        Optional<Point> target = bb.getCarTarget("Car001");
        assertTrue(target.isPresent());
        assertEquals(new Point(20, 15), target.get());

        bb.clearCarTarget("Car001");
        assertFalse(bb.hasTarget("Car001"));
    }

    @Test
    void carRoute() {
        List<Point> route = List.of(new Point(5, 10), new Point(6, 10), new Point(7, 10));
        bb.pushRoute("Car001", route);

        List<Point> stored = bb.getCarRoute("Car001");
        assertEquals(3, stored.size());

        Optional<Point> next = bb.peekNextRouteStep("Car001");
        assertTrue(next.isPresent());
        assertEquals(new Point(5, 10), next.get());

        Optional<Point> popped = bb.popNextRouteStep("Car001");
        assertTrue(popped.isPresent());
        assertEquals(new Point(5, 10), popped.get());

        assertEquals(2, bb.getCarRoute("Car001").size());
    }

    @Test
    void carStatus() {
        assertTrue(bb.getCarStatus("Car001").isEmpty());

        bb.setCarStatus("Car001", CarStatus.READY);
        assertEquals(CarStatus.READY, bb.getCarStatus("Car001").orElseThrow());

        bb.setCarStatus("Car001", CarStatus.BLOCKED);
        assertEquals(CarStatus.BLOCKED, bb.getCarStatus("Car001").orElseThrow());
    }

    @Test
    void carSteps() {
        assertEquals(0, bb.getCarSteps("Car001"));

        bb.setCarSteps("Car001", 10);
        assertEquals(10, bb.getCarSteps("Car001"));

        bb.incrementCarSteps("Car001");
        assertEquals(11, bb.getCarSteps("Car001"));
    }

    @Test
    void blockedTick() {
        assertEquals(-1, bb.getBlockedTick("Car001"));

        bb.setBlockedTick("Car001", 42);
        assertEquals(42, bb.getBlockedTick("Car001"));

        bb.clearBlockedTick("Car001");
        assertEquals(-1, bb.getBlockedTick("Car001"));
    }

    @Test
    void controllerLock() {
        assertTrue(bb.acquireControllerLock());
        assertFalse(bb.acquireControllerLock());
        bb.releaseControllerLock();
        assertTrue(bb.acquireControllerLock());
    }

    @Test
    void taskConfig() {
        Map<String, String> config = Map.of(
            "mapWidth", "30",
            "mapHeight", "30",
            "carCount", "5",
            "algorithm", "BFS",
            "active", "true"
        );
        bb.initTaskConfig(config);

        assertTrue(bb.isTaskActive());
        assertEquals(30, bb.getMapWidth());
        assertEquals(30, bb.getMapHeight());
        assertEquals(5, bb.getCarCount());
        assertEquals("BFS", bb.getAlgorithm());

        bb.setTaskActive(false);
        assertFalse(bb.isTaskActive());
    }

    @Test
    void mapHeat() {
        bb.incrementMapHeat(5, 10);
        bb.incrementMapHeat(5, 10);
        bb.incrementMapHeat(3, 7);

        Map<String, String> heat = bb.getMapHeat();
        assertEquals("2", heat.get("5,10"));
        assertEquals("1", heat.get("3,7"));
    }

    @Test
    void explorationRate_noBlocked() {
        bb.setMapViewBit(0, 0, true);
        bb.setMapViewBit(0, 1, true);
        int rate = bb.getExplorationRate();
        assertTrue(rate >= 0);
    }

    // ==================== History 记录 ====================

    @Test
    void appendCarHistory() {
        bb.appendCarHistory("Car001", new Point(5, 10), 1);
        bb.appendCarHistory("Car001", new Point(6, 10), 2);

        try (Jedis jedis = pool.getResource()) {
            List<String> records = jedis.lrange("Car001:History", 0, -1);
            assertEquals(2, records.size());
            assertTrue(records.get(0).contains("5") && records.get(0).contains("10"));
            assertTrue(records.get(1).contains("6") && records.get(1).contains("10"));
        }
    }

    // ==================== 位置预约锁（防重叠） ====================

    @Test
    void tryReservePosition() {
        assertTrue(bb.tryReservePosition(10, 5, "Car001"));
        // 同一位置不能被其他车重复预约
        assertFalse(bb.tryReservePosition(10, 5, "Car002"));
        // 不同位置不冲突
        assertTrue(bb.tryReservePosition(11, 5, "Car002"));
        bb.releaseReservePosition(10, 5, "Car001");
        bb.releaseReservePosition(11, 5, "Car002");
    }

    @Test
    void releaseAndReacquireReservation() {
        assertTrue(bb.tryReservePosition(3, 3, "Car001"));
        // 非预约者不能释放
        bb.releaseReservePosition(3, 3, "Car002");
        assertFalse(bb.tryReservePosition(3, 3, "Car002"));
        // 预约者释放后可以重新预约
        bb.releaseReservePosition(3, 3, "Car001");
        assertTrue(bb.tryReservePosition(3, 3, "Car002"));
        bb.releaseReservePosition(3, 3, "Car002");
    }

    @Test
    void sameCarCanReserveDifferentPositions() {
        assertTrue(bb.tryReservePosition(1, 1, "Car001"));
        assertTrue(bb.tryReservePosition(2, 2, "Car001"));
        bb.releaseReservePosition(1, 1, "Car001");
        bb.releaseReservePosition(2, 2, "Car001");
    }
}
