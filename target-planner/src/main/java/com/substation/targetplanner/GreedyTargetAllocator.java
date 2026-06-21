package com.substation.targetplanner;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class GreedyTargetAllocator {

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
        boolean[][] explored = bb.loadExploredBitmap();
        boolean[][] obstacles = bb.loadObstacleBitmap();
        List<Point> cells = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (!explored[row][col] && !obstacles[row][col]) {
                    cells.add(new Point(col, row));
                }
            }
        }
        return cells;
    }

    // ==================== 距离规则选择 ====================

    private Optional<Point> selectByDistanceRule(Point currentPos, List<Point> candidates) {
        // 最远优先：把车分散到地图不同角落，避免多车挤在同一区域互堵
        candidates.sort((a, b) -> b.manhattanDistance(currentPos) - a.manhattanDistance(currentPos));
        return Optional.of(candidates.get(0));
    }
}
