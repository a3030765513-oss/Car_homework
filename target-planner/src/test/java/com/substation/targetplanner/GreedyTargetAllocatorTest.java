package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GreedyTargetAllocatorTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;
    private GreedyTargetAllocator allocator;
    private Set<Point> allocated;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        allocator = new GreedyTargetAllocator();
        allocated = new HashSet<>();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.initTaskConfig(java.util.Map.of("mapWidth", String.valueOf(MAP_SIZE),
            "mapHeight", String.valueOf(MAP_SIZE)));
    }

    @AfterEach
    void tearDown() {
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        bb.close();
        pool.close();
    }

    // ==================== 正常路径测试 ====================

    @Test
    void allocateReturnsTargetWhenUnexploredCellsExist() {
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isPresent(), "有空闲格子时应该分配目标");
    }

    @Test
    void allocatedTargetIsNotBlocked() {
        bb.setBlock(10, 10, true);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isPresent());
        Point t = target.get();
        assertFalse(bb.isBlocked(t.y(), t.x()), "目标不应在障碍物上");
    }

    @Test
    void allocatedTargetIsNotExplored() {
        markAllExcept(bb, new Point(20, 20));

        Point carPos = new Point(1, 1);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(new Point(20, 20), target.get(), "应分配唯一未探索的格子");
    }

    @Test
    void returnsEmptyWhenAllExplored() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isEmpty(), "全部探索完时应无目标");
    }

    @Test
    void returnsEmptyWhenAllCellsBlockedOrExplored() {
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                bb.setBlock(r, c, true);
            }
        }
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isEmpty());
    }

    // ==================== 距离规则测试 ====================

    @Test
    void distanceRuleAssignsFarEnoughTarget() {
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isPresent());
        int dist = carPos.manhattanDistance(target.get());
        assertTrue(dist >= 10,
            "距离应 ≥ 10，实际=" + dist + "，目标=(" + target.get().x() + "," + target.get().y() + ")");
    }

    @Test
    void lastCellNoDistanceLimit() {
        markAllExcept(bb, new Point(16, 15));

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isPresent());
        assertEquals(new Point(16, 15), target.get(),
            "最后一个格子应无视距离限制");
    }

    @Test
    void noTargetWhenAllCandidatesTooClose() {
        int[][] reserved = {{16, 15}, {15, 16}};
        markAllExceptMultiple(bb, reserved);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, allocated);

        assertTrue(target.isEmpty(),
            "多个候选但都距离<10，应暂不分配");
    }

    @Test
    void alreadyAllocatedTargetsAreExcluded() {
        // 预先占用 (16,15)，验证新目标不等于这些预占的
        Set<Point> preAllocated = new HashSet<>();
        preAllocated.add(new Point(16, 15));

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate(carPos, bb, preAllocated);

        assertTrue(target.isPresent());
        assertNotEquals(new Point(16, 15), target.get(),
            "不应分配到已被占用的 (16,15)");
    }

    // ==================== 辅助方法 ====================

    private void markAllExplored(BlackboardClient bb, int width, int height) {
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                bb.setMapViewBit(r, c, true);
            }
        }
    }

    private void markAllExcept(BlackboardClient bb, Point exception) {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);
        bb.setMapViewBit(exception.y(), exception.x(), false);
    }

    private void markAllExceptMultiple(BlackboardClient bb, int[][] reserved) {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);
        for (int[] rc : reserved) {
            bb.setMapViewBit(rc[1], rc[0], false);
        }
    }
}
