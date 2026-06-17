package com.substation.common.redis;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.*;

/**
 * 黑板客户端，封装所有与Redis的交互操作。
 * 包括地图状态（探索/障碍）、小车信息（位置/目标/路径/状态/步数）、热力图、任务配置、控制器锁等。
 * 实现AutoCloseable，使用完毕后需调用close释放连接池。
 */
public class BlackboardClient implements AutoCloseable {

    /** 地图默认宽/高，所有模块引用此常量而非各自硬编码 */
    public static final int DEFAULT_WIDTH = 30;
    public static final int DEFAULT_HEIGHT = 30;

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
    /** Redis key: 探索事件列表（回放用），每元素为 {tick,row,col} */
    private static final String KEY_EXPLORATION_EVENTS = "explorationEvents";
    /** 控制器锁的TTL（秒），防止宕机后锁永不释放 */
    private static final int CONTROLLER_LOCK_TTL_SECONDS = 30;
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
    /** 地图宽度（列数）——构造函数默认值 */
    private final int mapWidth;
    /** 地图高度（行数）——构造函数默认值 */
    private final int mapHeight;
    /** 从TaskConfig读取的有效宽度缓存，-1表示未初始化 */
    private volatile int cachedTaskWidth = -1;
    /** 从TaskConfig读取的有效高度缓存，-1表示未初始化 */
    private volatile int cachedTaskHeight = -1;

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
     * 优先使用TaskConfig中的地图宽度，未配置时回退到构造函数默认值。
     */
    private long bitmapOffset(int row, int col) {
        return (long) row * effectiveWidth() + col;
    }

    /** 获取有效地图宽度：优先TaskConfig，回退构造函数默认值 */
    private int effectiveWidth() {
        int w = cachedTaskWidth;
        if (w > 0) return w;
        w = getMapWidth();
        if (w > 0) {
            cachedTaskWidth = w;
            return w;
        }
        return mapWidth;
    }

    /** 获取有效地图高度：优先TaskConfig，回退构造函数默认值 */
    private int effectiveHeight() {
        int h = cachedTaskHeight;
        if (h > 0) return h;
        h = getMapHeight();
        if (h > 0) {
            cachedTaskHeight = h;
            return h;
        }
        return mapHeight;
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

    /** 一次GET读取整个mapView位图的字节数组 */
    public byte[] getMapViewBytes() {
        try (Jedis jedis = pool.getResource()) {
            byte[] bytes = jedis.get(KEY_MAP_VIEW.getBytes());
            return bytes != null ? bytes : new byte[0];
        }
    }

    /** 一次GET读取整个mapBlock位图的字节数组 */
    public byte[] getMapBlockBytes() {
        try (Jedis jedis = pool.getResource()) {
            byte[] bytes = jedis.get(KEY_MAP_BLOCK.getBytes());
            return bytes != null ? bytes : new byte[0];
        }
    }

    /** 从字节数组构建boolean二维数组 */
    public static boolean[][] bytesToBitmap(byte[] bytes, int width, int height) {
        boolean[][] bitmap = new boolean[height][width];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                long offset = (long) r * width + c;
                int byteIdx = (int) (offset / 8);
                int bitIdx = (int) (7 - (offset % 8));
                if (byteIdx < bytes.length) {
                    bitmap[r][c] = ((bytes[byteIdx] >> bitIdx) & 1) == 1;
                }
            }
        }
        return bitmap;
    }

    /**
     * 计算地图探索率（百分比），排除障碍物格子。
     *
     * @return 探索率 0-100
     */
    public int getExplorationRate() {
        try (Jedis jedis = pool.getResource()) {
            int w = readMapWidth(jedis);
            int h = readMapHeight(jedis);
            long total = (long) w * h;
            long blocked = jedis.bitcount(KEY_MAP_BLOCK);
            long explorable = total - blocked;
            if (explorable <= 0) {
                return 100;
            }
            long explored = jedis.bitcount(KEY_MAP_VIEW);
            return (int) (explored * 100 / explorable);
        }
    }

    private int readMapWidth(Jedis jedis) {
        String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_WIDTH);
        return val != null ? Integer.parseInt(val) : mapWidth;
    }

    private int readMapHeight(Jedis jedis) {
        String val = jedis.hget(KEY_TASK_CONFIG, FIELD_MAP_HEIGHT);
        return val != null ? Integer.parseInt(val) : mapHeight;
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

    /** 获取指定小车的全部历史轨迹 */
    public List<String> getCarHistory(String carId) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.lrange(carId + ":History", 0, -1);
        }
    }

    /** 获取所有小车的历史轨迹，返回 carId → 轨迹列表 */
    public Map<String, List<String>> getAllCarHistories() {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (String carId : discoverCarIds()) {
            List<String> history = getCarHistory(carId);
            if (!history.isEmpty()) {
                result.put(carId, history);
            }
        }
        return result;
    }

    // ==================== explorationEvents ====================

    /**
     * 尝试探索指定格子并记录事件。仅在格子首次被探索时记录到回放事件列表。
     * 集成了 setMapViewBit 功能：返回 true 表示之前未被探索（新发现）。
     */
    public boolean recordExploration(int tick, int row, int col) {
        try (Jedis jedis = pool.getResource()) {
            boolean wasExplored = jedis.setbit(KEY_MAP_VIEW, bitmapOffset(row, col), true);
            if (!wasExplored) {
                String event = tick + "," + row + "," + col;
                jedis.rpush(KEY_EXPLORATION_EVENTS, event);
            }
            return !wasExplored;
        }
    }

    /** 获取全部探索事件列表（回放用） */
    public List<String> getExplorationEvents() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.lrange(KEY_EXPLORATION_EVENTS, 0, -1);
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

    /**
     * 批量初始化任务配置。
     *
     * @param config 配置键值对
     */
    /** 设置任务耗时秒数（单字段写入，不覆盖其他 TaskConfig 字段） */
    public void setElapsedSeconds(long seconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(KEY_TASK_CONFIG, "elapsedSeconds", String.valueOf(seconds));
        }
    }

    // ==================== 位置预约锁（防多车重叠） ====================

    /** 尝试预约目标位置，防止两车同时移动到同一格 */
    public boolean tryReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().ex(3);
            String result = jedis.set("pos:reserve:" + x + ":" + y, carId, params);
            return "OK".equals(result);
        }
    }

    /** 释放位置预约 */
    public void releaseReservePosition(int x, int y, String carId) {
        try (Jedis jedis = pool.getResource()) {
            String key = "pos:reserve:" + x + ":" + y;
            String val = jedis.get(key);
            if (carId.equals(val)) {
                jedis.del(key);
            }
        }
    }

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
                .collect(java.util.stream.Collectors.toSet());
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
