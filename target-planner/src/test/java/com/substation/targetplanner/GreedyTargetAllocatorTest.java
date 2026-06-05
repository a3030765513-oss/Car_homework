package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GreedyTargetAllocatorTest {

    private static final String TEST_HOST = "localhost";
    private static final int TEST_PORT = 6379;
    private static final int MAP_SIZE = 30;

    private BlackboardClient bb;
    private JedisPool pool;
    private GreedyTargetAllocator allocator;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(TEST_HOST, TEST_PORT);
        bb = new BlackboardClient(TEST_HOST, TEST_PORT, MAP_SIZE, MAP_SIZE);
        allocator = new GreedyTargetAllocator();
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
        pool.close();
    }

    // ==================== 正常路径测试 ====================

    @Test
    void allocateReturnsTargetWhenUnexploredCellsExist() {
        // 地图全未探索、无障碍 → 应该分配目标
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isPresent(), "有空闲格子时应该分配目标");
    }

    @Test
    void allocatedTargetIsNotBlocked() {
        // 在 (10,10) 放一个障碍物
        bb.setBlock(10, 10, true);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isPresent());
        Point t = target.get();
        assertFalse(bb.isBlocked(t.y(), t.x()), "目标不应在障碍物上");
    }

    @Test
    void allocatedTargetIsNotExplored() {
        // 标记大部分区域已探索，只留一个格子
        markAllExcept(bb, new Point(20, 20));

        Point carPos = new Point(1, 1);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isPresent());
        assertEquals(new Point(20, 20), target.get(), "应分配唯一未探索的格子");
    }

    @Test
    void returnsEmptyWhenAllExplored() {
        markAllExplored(bb, MAP_SIZE, MAP_SIZE);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isEmpty(), "全部探索完时应无目标");
    }

    @Test
    void returnsEmptyWhenAllCellsBlockedOrExplored() {
        // 把所有格子都标为障碍物
        for (int r = 0; r < MAP_SIZE; r++) {
            for (int c = 0; c < MAP_SIZE; c++) {
                bb.setBlock(r, c, true);
            }
        }
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isEmpty());
    }

    // ==================== 距离规则测试 ====================

    @Test
    void distanceRuleAssignsFarEnoughTarget() {
        // 车在 (15,15)，应分配距离 ≥ 10 的格子
        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isPresent());
        int dist = carPos.manhattanDistance(target.get());
        assertTrue(dist >= 10,
            "距离应 ≥ 10，实际=" + dist + "，目标=(" + target.get().x() + "," + target.get().y() + ")");
    }

    @Test
    void lastCellNoDistanceLimit() {
        // 只留 (16, 15) 一个未探索格子（距离=1，< 10）
        bb.setMapViewBit(16, 15, false); // 确保这个先不被标记
        markAllExcept(bb, new Point(16, 15));

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isPresent());
        assertEquals(new Point(16, 15), target.get(),
            "最后一个格子应无视距离限制");
    }

    @Test
    void noTargetWhenAllCandidatesTooClose() {
        // 只留距离 < 10 的区域（但 > 1 个格子），应暂不分配
        // 保留 (16,15) 和 (15,16) — 距离分别为 1 和 1，都 < 10
        int[][] reserved = {{16, 15}, {15, 16}};
        markAllExceptMultiple(bb, reserved);

        Point carPos = new Point(15, 15);
        Optional<Point> target = allocator.allocate("Car001", carPos, bb, MAP_SIZE, MAP_SIZE);

        assertTrue(target.isEmpty(),
            "多个候选但都距离<10，应暂不分配");
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
