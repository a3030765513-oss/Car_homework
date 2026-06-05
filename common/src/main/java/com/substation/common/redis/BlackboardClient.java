package com.substation.common.redis;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

public class BlackboardClient implements AutoCloseable {

    private static final String KEY_MAP_VIEW = "mapView";
    private static final String KEY_MAP_BLOCK = "mapBlock";
    private static final String KEY_MAP_HEAT = "mapHeat";
    private static final String KEY_TASK_CONFIG = "TaskConfig";
    private static final String KEY_CONTROLLER_INSTANCE = "controller:instance";
    private static final String FIELD_X = "x";
    private static final String FIELD_Y = "y";
    private static final String FIELD_ACTIVE = "active";
    private static final String FIELD_MAP_WIDTH = "mapWidth";
    private static final String FIELD_MAP_HEIGHT = "mapHeight";
    private static final String FIELD_CAR_COUNT = "carCount";
    private static final String FIELD_ALGORITHM = "algorithm";
    private static final String FIELD_TICK_INTERVAL = "tickInterval";
    private static final String FIELD_OBSTACLE_RATIO = "obstacleRatio";

    private final JedisPool pool;
    private final int mapWidth;
    private final int mapHeight;

    public BlackboardClient(String host, int port, int mapWidth, int mapHeight) {
        this.pool = new JedisPool(host, port);
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
    }

    private long bitmapOffset(int row, int col) {
        return (long) row * mapWidth + col;
    }

    // ==================== mapView ====================

    public boolean getMapViewBit(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_VIEW, bitmapOffset(row, col));
        }
    }

    public void setMapViewBit(int row, int col, boolean explored) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_VIEW, bitmapOffset(row, col), explored);
        }
    }

    public int getExplorationRate() {
        try (Jedis jedis = pool.getResource()) {
            long total = (long) mapWidth * mapHeight;
            long blocked = jedis.bitcount(KEY_MAP_BLOCK);
            long explorable = total - blocked;
            if (explorable <= 0) {
                return 100;
            }
            long explored = jedis.bitcount(KEY_MAP_VIEW);
            return (int) (explored * 100 / explorable);
        }
    }

    public long countExploredCells() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.bitcount(KEY_MAP_VIEW);
        }
    }

    // ==================== mapBlock ====================

    public boolean isBlocked(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_BLOCK, bitmapOffset(row, col));
        }
    }

    public void setBlock(int row, int col, boolean blocked) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_BLOCK, bitmapOffset(row, col), blocked);
        }
    }

    // ==================== CarID:Position ====================

    public Optional<Point> getCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Position";
            String sx = jedis.hget(key, FIELD_X);
            if (sx == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(sx), Integer.parseInt(jedis.hget(key, FIELD_Y))));
        }
    }

    public void setCarPosition(String carId, Point pos) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Position";
            jedis.hset(key, FIELD_X, String.valueOf(pos.x()));
            jedis.hset(key, FIELD_Y, String.valueOf(pos.y()));
        }
    }

    public void clearCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Position");
        }
    }

    // ==================== CarID:Target ====================

    public Optional<Point> getCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Target";
            String sx = jedis.hget(key, FIELD_X);
            if (sx == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(sx), Integer.parseInt(jedis.hget(key, FIELD_Y))));
        }
    }

    public void setCarTarget(String carId, Point target) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Target";
            jedis.hset(key, FIELD_X, String.valueOf(target.x()));
            jedis.hset(key, FIELD_Y, String.valueOf(target.y()));
        }
    }

    public void clearCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Target");
        }
    }

    public boolean hasTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(carId + ":Target");
        }
    }

    // ==================== CarID:RouteList ====================

    public List<Point> getCarRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            List<String> items = jedis.lrange(carId + ":RouteList", 0, -1);
            List<Point> route = new ArrayList<>();
            for (String item : items) {
                route.add(Point.fromJson(item));
            }
            return Collections.unmodifiableList(route);
        }
    }

    public Optional<Point> peekNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lindex(carId + ":RouteList", -1);
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    public Optional<Point> popNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.rpop(carId + ":RouteList");
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    public void pushRoute(String carId, List<Point> route) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":RouteList";
            for (Point p : route) {
                jedis.lpush(key, p.toJson());
            }
        }
    }

    public void clearRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":RouteList");
        }
    }

    // ==================== CarID:Status ====================

    public Optional<CarStatus> getCarStatus(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Status");
            return val != null ? Optional.of(CarStatus.valueOf(val)) : Optional.empty();
        }
    }

    public void setCarStatus(String carId, CarStatus status) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Status", status.name());
        }
    }

    // ==================== CarID:Steps ====================

    public int getCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Steps");
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    public void incrementCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(carId + ":Steps");
        }
    }

    public void setCarSteps(String carId, int steps) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Steps", String.valueOf(steps));
        }
    }

    // ==================== CarID:BlockedTick ====================

    public int getBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":BlockedTick");
            return val != null ? Integer.parseInt(val) : -1;
        }
    }

    public void setBlockedTick(String carId, int tick) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":BlockedTick", String.valueOf(tick));
        }
    }

    public void clearBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":BlockedTick");
        }
    }

    // ==================== mapHeat ====================

    public void incrementMapHeat(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hincrBy(KEY_MAP_HEAT, row + "," + col, 1);
        }
    }

    public Map<String, String> getMapHeat() {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> heat = jedis.hgetAll(KEY_MAP_HEAT);
            return heat != null ? Collections.unmodifiableMap(heat) : Collections.emptyMap();
        }
    }

    // ==================== controller:instance ====================

    public boolean acquireControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.setnx(KEY_CONTROLLER_INSTANCE, "1") == 1L;
        }
    }

    public void releaseControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_CONTROLLER_INSTANCE);
        }
    }

    // ==================== TaskConfig ====================

    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(KEY_TASK_CONFIG);
        }
    }

    public boolean isTaskActive() {
        try (Jedis jedis = pool.getResource()) {
            return "true".equals(jedis.hget(KEY_TASK_CONFIG, FIELD_ACTIVE));
        }
    }

    public void setTaskActive(boolean active) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, FIELD_ACTIVE, String.valueOf(active));
        }
    }

    public int getMapWidth() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    public int getMapHeight() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    public int getCarCount() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_CAR_COUNT);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    public String getAlgorithm() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(KEY_TASK_CONFIG, FIELD_ALGORITHM);
        }
    }

    public int getTickInterval() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_TICK_INTERVAL);
            return val != null ? Integer.parseInt(val) : 500;
        }
    }

    public double getObstacleRatio() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_OBSTACLE_RATIO);
            return val != null ? Double.parseDouble(val) : 0.15;
        }
    }

    public void initTaskConfig(Map<String, String> config) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, config);
        }
    }

    public Set<String> discoverCarIds() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.keys("Car*:Status").stream()
                .map(key -> key.replace(":Status", ""))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
