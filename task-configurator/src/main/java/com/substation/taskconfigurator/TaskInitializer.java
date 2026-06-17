package com.substation.taskconfigurator;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class TaskInitializer {

    private static final int DEFAULT_MAP_WIDTH = BlackboardClient.DEFAULT_SIZE;
    private static final int DEFAULT_MAP_HEIGHT = BlackboardClient.DEFAULT_SIZE;
    private static final int DEFAULT_CAR_COUNT = 5;
    private static final double DEFAULT_OBSTACLE_RATIO = 0.15;
    private static final String DEFAULT_ALGORITHM = "BFS";
    private static final int DEFAULT_TICK_INTERVAL = 200;
    private static final int EDGE_MARGIN = 1;
    private static final int ILLUMINATION_RADIUS = 1;
    private static final int RANDOM_POSITION_MAX_ATTEMPTS = 200;
    private static final int OBSTACLE_PLACEMENT_MULTIPLIER = 10;

    private static final String KEY_MAP_WIDTH = "mapWidth";
    private static final String KEY_MAP_HEIGHT = "mapHeight";
    private static final String KEY_CAR_COUNT = "carCount";
    private static final String KEY_OBSTACLE_RATIO = "obstacleRatio";
    private static final String KEY_ALGORITHM = "algorithm";
    private static final String KEY_TICK_INTERVAL = "tickInterval";
    private static final String KEY_ACTIVE = "active";

    private final Random random = new Random();

    void initialize(BlackboardClient bb, Map<String, Object> config) {
        int mapWidth = parseIntParam(config, KEY_MAP_WIDTH, DEFAULT_MAP_WIDTH);
        int mapHeight = parseIntParam(config, KEY_MAP_HEIGHT, DEFAULT_MAP_HEIGHT);
        int carCount = parseIntParam(config, KEY_CAR_COUNT, DEFAULT_CAR_COUNT);
        double obstacleRatio = parseDoubleParam(config, KEY_OBSTACLE_RATIO, DEFAULT_OBSTACLE_RATIO);
        String algorithm = parseStringParam(config, KEY_ALGORITHM, DEFAULT_ALGORITHM);
        int tickInterval = parseIntParam(config, KEY_TICK_INTERVAL, DEFAULT_TICK_INTERVAL);

        writeTaskConfig(bb, mapWidth, mapHeight, carCount, obstacleRatio, algorithm, tickInterval);

        List<String> carIds = generateCarIds(carCount);
        List<Point> initialPositions = assignInitialPositions(carIds, mapWidth, mapHeight);
        Set<Point> occupied = new HashSet<>(initialPositions);

        placeObstacles(bb, mapWidth, mapHeight, obstacleRatio, occupied);

        for (int i = 0; i < carIds.size(); i++) {
            initSingleCar(bb, carIds.get(i), initialPositions.get(i));
            lightUpArea(bb, initialPositions.get(i), mapWidth, mapHeight);
        }
    }

    // ==================== 步骤实现 ====================

    private void writeTaskConfig(BlackboardClient bb, int mapWidth, int mapHeight,
                                  int carCount, double obstacleRatio, String algorithm,
                                  int tickInterval) {
        Map<String, String> config = new HashMap<>();
        config.put(KEY_MAP_WIDTH, String.valueOf(mapWidth));
        config.put(KEY_MAP_HEIGHT, String.valueOf(mapHeight));
        config.put(KEY_CAR_COUNT, String.valueOf(carCount));
        config.put(KEY_OBSTACLE_RATIO, String.valueOf(obstacleRatio));
        config.put(KEY_ALGORITHM, algorithm);
        config.put(KEY_TICK_INTERVAL, String.valueOf(tickInterval));
        config.put(KEY_ACTIVE, "true");
        bb.initTaskConfig(config);
    }

    private List<String> generateCarIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(String.format("Car%03d", i));
        }
        return ids;
    }

    private List<Point> assignInitialPositions(List<String> carIds, int mapWidth, int mapHeight) {
        Point[] corners = {
            new Point(EDGE_MARGIN, EDGE_MARGIN),
            new Point(mapWidth - 1 - EDGE_MARGIN, EDGE_MARGIN),
            new Point(EDGE_MARGIN, mapHeight - 1 - EDGE_MARGIN),
            new Point(mapWidth - 1 - EDGE_MARGIN, mapHeight - 1 - EDGE_MARGIN),
            new Point(mapWidth / 2, mapHeight / 2)
        };

        List<Point> positions = new ArrayList<>();
        Set<Point> used = new HashSet<>();
        for (int i = 0; i < carIds.size(); i++) {
            Point chosen = (i < corners.length)
                ? corners[i]
                : selectRandomPosition(mapWidth, mapHeight, used);
            positions.add(chosen);
            used.add(chosen);
        }
        return positions;
    }

    private Point selectRandomPosition(int width, int height, Set<Point> used) {
        for (int attempt = 0; attempt < RANDOM_POSITION_MAX_ATTEMPTS; attempt++) {
            int x = EDGE_MARGIN + random.nextInt(width - 2 * EDGE_MARGIN);
            int y = EDGE_MARGIN + random.nextInt(height - 2 * EDGE_MARGIN);
            Point candidate = new Point(x, y);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return scanForEmpty(width, height, used);
    }

    private Point scanForEmpty(int width, int height, Set<Point> used) {
        for (int r = EDGE_MARGIN; r < height - EDGE_MARGIN; r++) {
            for (int c = EDGE_MARGIN; c < width - EDGE_MARGIN; c++) {
                Point p = new Point(c, r);
                if (!used.contains(p)) {
                    return p;
                }
            }
        }
        return new Point(EDGE_MARGIN, EDGE_MARGIN);
    }

    private void placeObstacles(BlackboardClient bb, int width, int height,
                                 double ratio, Set<Point> exclude) {
        int interiorCells = (width - 2 * EDGE_MARGIN) * (height - 2 * EDGE_MARGIN);
        int targetCount = (int) (interiorCells * ratio);
        int placed = 0;
        int maxAttempts = targetCount * OBSTACLE_PLACEMENT_MULTIPLIER;

        for (int attempt = 0; attempt < maxAttempts && placed < targetCount; attempt++) {
            int x = EDGE_MARGIN + random.nextInt(width - 2 * EDGE_MARGIN);
            int y = EDGE_MARGIN + random.nextInt(height - 2 * EDGE_MARGIN);
            Point candidate = new Point(x, y);
            if (!exclude.contains(candidate) && !bb.isBlocked(y, x)) {
                bb.setBlock(y, x, true);
                placed++;
            }
        }
    }

    private void initSingleCar(BlackboardClient bb, String carId, Point position) {
        bb.setCarPosition(carId, position);
        bb.setCarStatus(carId, CarStatus.IDLE);
        bb.setCarSteps(carId, 0);
        bb.appendCarHistory(carId, position, 0);
    }

    private void lightUpArea(BlackboardClient bb, Point center, int mapWidth, int mapHeight) {
        int rStart = Math.max(0, center.y() - ILLUMINATION_RADIUS);
        int rEnd = Math.min(mapHeight - 1, center.y() + ILLUMINATION_RADIUS);
        int cStart = Math.max(0, center.x() - ILLUMINATION_RADIUS);
        int cEnd = Math.min(mapWidth - 1, center.x() + ILLUMINATION_RADIUS);

        for (int r = rStart; r <= rEnd; r++) {
            for (int c = cStart; c <= cEnd; c++) {
                bb.recordExploration(0, r, c);
            }
        }
    }

    // ==================== 参数解析 ====================

    private int parseIntParam(Map<String, Object> config, String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private double parseDoubleParam(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultValue;
    }

    private String parseStringParam(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
