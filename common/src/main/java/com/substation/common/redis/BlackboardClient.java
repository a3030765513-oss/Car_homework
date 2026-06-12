package com.substation.common.redis;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 黑板客户端，封装所有与Redis的交互操作。
 * 包括地图状态（探索/障碍）、小车信息（位置/目标/路径/状态/步数）、热力图、任务配置、控制器锁、位置预约锁等。
 * 实现AutoCloseable，使用完毕后需调用close释放连接池。
 */
public class BlackboardClient implements AutoCloseable {

    /** Redis key: 地图探索状态（位图） */
    private static final String KEY_MAP_VIEW = "mapView";
    /** Redis key: 地图障碍物状态（位图） */
    private static final String KEY_MAP_BLOCK = "mapBlock";
    /** Redis key: 地图热力图（Hash） */
    private static final String KEY_MAP_HEAT = "mapHeat";
    /** Redis key: 任务配置（Hash） */
    private static final String KEY_TASK_CONFIG = "TaskConfig";
    /** Redis key: 控制器实例锁 */
    private static final String KEY_CONTROLLER_INSTANCE = "controller:instance";
    /** 控制器锁的TTL（秒），防止宕机后锁永不释放 */
    private static final int CONTROLLER_LOCK_TTL_SECONDS = 30;
    /** 位置预约锁 key 前缀，防止多车同时移动到同一格子 */
    private static final String POSITION_RESERVE_PREFIX = "pos:reserve:";
    /** 位置预约锁 TTL（秒），防止小车崩溃后锁永不释放 */
    private static final int POSITION_RESERVE_TTL_SECONDS = 5;
    /** Hash字段名: X坐标 */
    private static final String FIELD_X = "x";
    /** Hash字段名: Y坐标 */
    private static final String FIELD_Y = "y";
    /** Hash字段名: 任务是否激活 */
    private static final String FIELD_ACTIVE = "active";
    /** Hash字段名: 地图宽度 */
    private static final String FIELD_MAP_WIDTH = "mapWidth";
    /** Hash字段名: 地图高度 */
    private static final String FIELD_MAP_HEIGHT = "mapHeight";
    /** Hash字段名: 小车数量 */
    private static final String FIELD_CAR_COUNT = "carCount";
    /** Hash字段名: 路径规划算法 */
    private static final String FIELD_ALGORITHM = "algorithm";
    /** Hash字段名: tick间隔（毫秒） */
    private static final String FIELD_TICK_INTERVAL = "tickInterval";
    /** Hash字段名: 障碍物比例 */
    private static final String FIELD_OBSTACLE_RATIO = "obstacleRatio";

    /** Redis连接池 */
    private final JedisPool pool;
    /** 地图宽度（列数） */
    private final int mapWidth;
    /** 地图高度（行数） */
    private final int mapHeight;

    /**
     * 构造黑板客户端，创建Redis连接池。
     *
     * @param host     Redis主机地址
     * @param port     Redis端口
     * @param mapWidth  地图宽度
     * @param mapHeight 地图高度
     */
    public BlackboardClient(String host, int port, int mapWidth, int mapHeight) {
        this.pool = new JedisPool(host, port);
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
    }

    /**
     * 将二维坐标(row, col)转换为位图中的一维偏移量。
     *
     * @param row 行号
     * @param col 列号
     * @return 位图偏移量
     */
    private long bitmapOffset(int row, int col) {
        return (long) row * mapWidth + col;
    }

    // ==================== mapView ====================

    /**
     * 获取指定格子的探索状态。
     *
     * @param row 行号
     * @param col 列号
     * @return true表示已探索
     */
    public boolean getMapViewBit(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_VIEW, bitmapOffset(row, col));
        }
    }

    /**
     * 设置指定格子的探索状态。
     *
     * @param row      行号
     * @param col      列号
     * @param explored true表示已探索
     */
    public void setMapViewBit(int row, int col, boolean explored) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_VIEW, bitmapOffset(row, col), explored);
        }
    }

    /**
     * 计算地图探索率（百分比），排除障碍物格子。
     *
     * @return 探索率 0-100
     */
    public int getExplorationRate() {
        try (Jedis jedis = pool.getResource()) {
            String ws = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
            String hs = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
            int w = ws != null ? Integer.parseInt(ws) : mapWidth;
            int h = hs != null ? Integer.parseInt(hs) : mapHeight;
            long explored = 0;
            long blocked = 0;
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    long offset = (long) r * mapWidth + c;
                    if (jedis.getbit(KEY_MAP_VIEW, offset)) explored++;
                    if (jedis.getbit(KEY_MAP_BLOCK, offset)) blocked++;
                }
            }
            long explorable = (long) w * h - blocked;
            if (explorable <= 0) {
                return 100;
            }
            return (int) (explored * 100 / explorable);
        }
    }

    /**
     * 统计已探索的格子总数。
     *
     * @return 已探索格子数
     */
    public long countExploredCells() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.bitcount(KEY_MAP_VIEW);
        }
    }

    // ==================== mapBlock ====================

    /**
     * 判断指定格子是否为障碍物。
     *
     * @param row 行号
     * @param col 列号
     * @return true表示是障碍物
     */
    public boolean isBlocked(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.getbit(KEY_MAP_BLOCK, bitmapOffset(row, col));
        }
    }

    /**
     * 设置指定格子的障碍物状态。
     *
     * @param row     行号
     * @param col     列号
     * @param blocked true表示标记为障碍物
     */
    public void setBlock(int row, int col, boolean blocked) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setbit(KEY_MAP_BLOCK, bitmapOffset(row, col), blocked);
        }
    }

    // ==================== CarID:Position ====================

    /**
     * 获取小车当前位置。
     *
     * @param carId 小车ID
     * @return 当前位置，不存在则为empty
     */
    public Optional<Point> getCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Position";
            String sx = jedis.hget(key, FIELD_X);
            if (sx == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(sx), Integer.parseInt(jedis.hget(key, FIELD_Y))));
        }
    }

    /**
     * 设置小车当前位置。
     *
     * @param carId 小车ID
     * @param pos   坐标
     */
    public void setCarPosition(String carId, Point pos) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Position";
            jedis.hset(key, FIELD_X, String.valueOf(pos.x()));
            jedis.hset(key, FIELD_Y, String.valueOf(pos.y()));
        }
    }

    /** 清除小车位置信息。 */
    public void clearCarPosition(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Position");
        }
    }

    // ==================== CarID:Target ====================

    /**
     * 获取小车当前目标点。
     *
     * @param carId 小车ID
     * @return 目标坐标，不存在则为empty
     */
    public Optional<Point> getCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Target";
            String sx = jedis.hget(key, FIELD_X);
            if (sx == null) return Optional.empty();
            return Optional.of(new Point(Integer.parseInt(sx), Integer.parseInt(jedis.hget(key, FIELD_Y))));
        }
    }

    /**
     * 设置小车目标点。
     *
     * @param carId  小车ID
     * @param target 目标坐标
     */
    public void setCarTarget(String carId, Point target) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":Target";
            jedis.hset(key, FIELD_X, String.valueOf(target.x()));
            jedis.hset(key, FIELD_Y, String.valueOf(target.y()));
        }
    }

    /** 清除小车目标点。 */
    public void clearCarTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":Target");
        }
    }

    /**
     * 判断小车是否有目标点。
     *
     * @param carId 小车ID
     * @return true表示已有目标
     */
    public boolean hasTarget(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(carId + ":Target");
        }
    }

    // ==================== CarID:RouteList ====================

    /**
     * 获取小车的完整路径列表（从起点到终点）。
     *
     * @param carId 小车ID
     * @return 路径点列表（不可变）
     */
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

    /**
     * 查看路径的下一步（不移除）。
     *
     * @param carId 小车ID
     * @return 下一步坐标，路径为空则为empty
     */
    public Optional<Point> peekNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lindex(carId + ":RouteList", 0);
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    /**
     * 弹出路径的下一步（移除并返回）。
     *
     * @param carId 小车ID
     * @return 下一步坐标，路径为空则为empty
     */
    public Optional<Point> popNextRouteStep(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.lpop(carId + ":RouteList");
            return json != null ? Optional.of(Point.fromJson(json)) : Optional.empty();
        }
    }

    /**
     * 将整条路径推入Redis列表（逆序推入，保证弹出顺序正确）。
     *
     * @param carId 小车ID
     * @param route 路径点列表（从起点到终点）
     */
    public void pushRoute(String carId, List<Point> route) {
        try (Jedis jedis = pool.getResource()) {
            String key = carId + ":RouteList";
            // 逆序 LPUSH：终点先推，起点最后推，保证索引 0 是第一步
            for (int i = route.size() - 1; i >= 0; i--) {
                jedis.lpush(key, route.get(i).toJson());
            }
        }
    }

    /** 清除小车路径。 */
    public void clearRoute(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":RouteList");
        }
    }

    // ==================== CarID:Status ====================

    /**
     * 获取小车当前状态。
     *
     * @param carId 小车ID
     * @return 状态枚举值，不存在则为empty
     */
    public Optional<CarStatus> getCarStatus(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Status");
            return val != null ? Optional.of(CarStatus.valueOf(val)) : Optional.empty();
        }
    }

    /**
     * 设置小车状态。
     *
     * @param carId  小车ID
     * @param status 状态枚举值
     */
    public void setCarStatus(String carId, CarStatus status) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Status", status.name());
        }
    }

    // ==================== CarID:Steps ====================

    /**
     * 获取小车已移动步数。
     *
     * @param carId 小车ID
     * @return 步数，不存在返回0
     */
    public int getCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":Steps");
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /** 小车步数加1。 */
    public void incrementCarSteps(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.incr(carId + ":Steps");
        }
    }

    /**
     * 设置小车步数值。
     *
     * @param carId 小车ID
     * @param steps 步数值
     */
    public void setCarSteps(String carId, int steps) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":Steps", String.valueOf(steps));
        }
    }

    // ==================== CarID:History ====================

    /** 向小车History追加一条移动记录 RPUSH {x, y, tick} */
    public void appendCarHistory(String carId, Point position, int tick) {
        try (Jedis jedis = pool.getResource()) {
            JSONObject record = new JSONObject();
            record.put("x", position.x());
            record.put("y", position.y());
            record.put("tick", tick);
            jedis.rpush(carId + ":History", record.toJSONString());
        }
    }

    // ==================== 位置预约锁（防重叠） ====================

    /**
     * 尝试预约目标格子，防止多车同时移动到同一位置。
     * 使用 SET NX EX 原子命令，只有第一个成功。
     *
     * @param x    目标列号
     * @param y    目标行号
     * @param carId 预约者ID
     * @return true 表示预约成功，false 表示已被其他车预约
     */
    public boolean tryReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = POSITION_RESERVE_PREFIX + x + ":" + y;
            SetParams params = SetParams.setParams().nx().ex(POSITION_RESERVE_TTL_SECONDS);
            return "OK".equals(jedis.set(key, carId, params));
        }
    }

    /** 释放目标格子的预约锁。使用Lua脚本保证只有预约者本人才能释放。 */
    public void releaseReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = POSITION_RESERVE_PREFIX + x + ":" + y;
            String script =
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "    return redis.call('del', KEYS[1]) " +
                "else " +
                "    return 0 " +
                "end";
            jedis.eval(script, Collections.singletonList(key), Collections.singletonList(carId));
        }
    }

    // ==================== CarID:BlockedTick ====================

    /**
     * 获取小车被阻塞时的tick号。
     *
     * @param carId 小车ID
     * @return 阻塞tick号，不存在返回-1
     */
    public int getBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.get(carId + ":BlockedTick");
            return val != null ? Integer.parseInt(val) : -1;
        }
    }

    /**
     * 记录小车被阻塞时的tick号。
     *
     * @param carId 小车ID
     * @param tick  当前tick号
     */
    public void setBlockedTick(String carId, int tick) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(carId + ":BlockedTick", String.valueOf(tick));
        }
    }

    /** 清除小车阻塞tick记录。 */
    public void clearBlockedTick(String carId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(carId + ":BlockedTick");
        }
    }

    // ==================== mapHeat ====================

    /**
     * 热力图指定格子计数加1。
     *
     * @param row 行号
     * @param col 列号
     */
    public void incrementMapHeat(int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hincrBy(KEY_MAP_HEAT, row + "," + col, 1);
        }
    }

    /**
     * 获取完整热力图数据。
     *
     * @return 格子坐标到访问次数的映射（不可变）
     */
    public Map<String, String> getMapHeat() {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> heat = jedis.hgetAll(KEY_MAP_HEAT);
            return heat != null ? Collections.unmodifiableMap(heat) : Collections.emptyMap();
        }
    }

    // ==================== controller:instance ====================

    /**
     * 尝试获取控制器实例锁（SET NX EX），防止多个控制器同时运行。
     *
     * @return true表示获取成功
     */
    public boolean acquireControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().ex(CONTROLLER_LOCK_TTL_SECONDS);
            return "OK".equals(jedis.set(KEY_CONTROLLER_INSTANCE, "1", params));
        }
    }

    /** 释放控制器实例锁。 */
    public void releaseControllerLock() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_CONTROLLER_INSTANCE);
        }
    }

    // ==================== TaskConfig ====================

    /**
     * 获取任务配置的全部字段。
     *
     * @return 配置键值对Map
     */
    public Map<String, String> getTaskConfig() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(KEY_TASK_CONFIG);
        }
    }

    /**
     * 判断任务是否处于激活状态。
     *
     * @return true表示任务正在运行
     */
    public boolean isTaskActive() {
        try (Jedis jedis = pool.getResource()) {
            return "true".equals(jedis.hget(KEY_TASK_CONFIG, FIELD_ACTIVE));
        }
    }

    /**
     * 设置任务激活状态。
     *
     * @param active true表示激活
     */
    public void setTaskActive(boolean active) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, FIELD_ACTIVE, String.valueOf(active));
        }
    }

    /**
     * 从任务配置中读取地图宽度。
     *
     * @return 地图宽度，不存在返回0
     */
    public int getMapWidth() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /**
     * 从任务配置中读取地图高度。
     *
     * @return 地图高度，不存在返回0
     */
    public int getMapHeight() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /**
     * 从任务配置中读取小车数量。
     *
     * @return 小车数量，不存在返回0
     */
    public int getCarCount() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_CAR_COUNT);
            return val != null ? Integer.parseInt(val) : 0;
        }
    }

    /**
     * 从任务配置中读取路径规划算法名称。
     *
     * @return 算法名称，不存在返回null
     */
    public String getAlgorithm() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(KEY_TASK_CONFIG, FIELD_ALGORITHM);
        }
    }

    /**
     * 从任务配置中读取tick间隔。
     *
     * @return tick间隔毫秒数，不存在返回500
     */
    public int getTickInterval() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_TICK_INTERVAL);
            return val != null ? Integer.parseInt(val) : 500;
        }
    }

    /**
     * 从任务配置中读取障碍物比例。
     *
     * @return 障碍物比例，不存在返回0.15
     */
    public double getObstacleRatio() {
        try (Jedis jedis = pool.getResource()) {
            String val = jedis.hget(KEY_TASK_CONFIG, FIELD_OBSTACLE_RATIO);
            return val != null ? Double.parseDouble(val) : 0.15;
        }
    }

    /** 设置任务耗时秒数（单字段写入，不覆盖其他 TaskConfig 字段） */
    public void setElapsedSeconds(long seconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, "elapsedSeconds", String.valueOf(seconds));
        }
    }

    /**
     * 批量初始化任务配置。
     *
     * @param config 配置键值对
     */
    public void initTaskConfig(Map<String, String> config) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, config);
        }
    }

    /**
     * 扫描Redis中所有已注册的小车ID（通过 Car*:Status 键匹配）。
     *
     * @return 小车ID集合
     */
    public Set<String> discoverCarIds() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.keys("Car*:Status").stream()
                .map(key -> key.replace(":Status", ""))
                .collect(Collectors.toSet());
        }
    }

    /**
     * 获取底层Redis连接池，供外部组件（如DistributedLock）使用。
     *
     * @return JedisPool实例
     */
    public JedisPool getJedisPool() {
        return pool;
    }

    /** 关闭连接池，释放所有Redis连接。 */
    @Override
    public void close() {
        pool.close();
    }
}
