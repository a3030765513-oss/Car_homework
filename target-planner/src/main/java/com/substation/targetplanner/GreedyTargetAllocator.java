package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GreedyTargetAllocator {

    private static final int MIN_TARGET_DISTANCE = 10;

    /**
     * 为一辆车分配探索目标。
     *
     * @param currentPosition  车辆当前位置
     * @param bb               BlackboardClient
     * @param alreadyAllocated  本轮节拍已分配的目标集合（防止多车抢同一格子）
     * @return 目标位置，无可用目标时返回 Optional.empty()
     */
    Optional<Point> allocate(Point currentPosition, BlackboardClient bb,
                             Set<Point> alreadyAllocated) {
        int mapWidth = bb.getMapWidth();
        int mapHeight = bb.getMapHeight();

        List<Point> candidates = collectUnexploredCells(bb, mapWidth, mapHeight);
        candidates.removeAll(alreadyAllocated);

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Optional<Point> chosen = selectByDistanceRule(currentPosition, candidates);
        chosen.ifPresent(alreadyAllocated::add);
        return chosen;
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

        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        return candidates.stream()
            .filter(p -> p.manhattanDistance(currentPos) >= MIN_TARGET_DISTANCE)
            .findFirst();
    }
}
