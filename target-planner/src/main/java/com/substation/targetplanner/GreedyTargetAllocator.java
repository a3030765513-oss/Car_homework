package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class GreedyTargetAllocator {

    private static final int MIN_TARGET_DISTANCE = 10;

    /**
     * 为一辆车分配探索目标。
     *
     * @return 目标位置，无可用目标时返回 Optional.empty()
     */
    Optional<Point> allocate(String carId, Point currentPosition, BlackboardClient bb,
                             int mapWidth, int mapHeight) {
        List<Point> candidates = collectUnexploredCells(bb, mapWidth, mapHeight);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return selectByDistanceRule(currentPosition, candidates);
    }

    // ==================== 候选收集 ====================

    private List<Point> collectUnexploredCells(BlackboardClient bb, int width, int height) {
        List<Point> cells = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (!bb.getMapViewBit(r, c) && !bb.isBlocked(r, c)) {
                    cells.add(new Point(c, r));
                }
            }
        }
        return cells;
    }

    // ==================== 距离规则选择 ====================

    private Optional<Point> selectByDistanceRule(Point currentPos, List<Point> candidates) {
        candidates.sort(Comparator.comparingInt(p -> p.manhattanDistance(currentPos)));

        // 最后 1 个格子：无距离限制
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        // 剩余多个：只选距离 ≥ 10 的最近格子
        return candidates.stream()
            .filter(p -> p.manhattanDistance(currentPos) >= MIN_TARGET_DISTANCE)
            .findFirst();
    }
}
