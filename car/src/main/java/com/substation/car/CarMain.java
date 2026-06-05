package com.substation.car;

import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/**
 * Car 模块入口。
 *
 * <p>用法：
 * <pre>{@code
 * java -jar car.jar Car001          # 默认 Redis 端口 6380
 * java -jar car.jar Car002 6379     # 指定 Redis 端口
 * }</pre>
 *
 * <p>动态添加小车：任意时刻启动新的 CarMain 进程（如 Car006），
 * 进程自动在黑板注册并参与任务探索。
 */
public class CarMain {

    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6380;
    private static final String DEFAULT_MQ_HOST = "localhost";
    private static final int DEFAULT_MQ_PORT = 5672;
    private static final int FALLBACK_MAP_SIZE = 30;
    private static final int MAX_INIT_ATTEMPTS = 1000;

    public static void main(String[] args) throws IOException, TimeoutException {
        String carId = parseCarId(args);
        int redisPort = parseRedisPort(args);

        log.info("╔══════════════════════════════════════╗");
        log.info("║  {} 模块启动中...", padRight(carId, 24));
        log.info("║  Redis: {}:{}", padRight(DEFAULT_REDIS_HOST + ":" + redisPort, 26));
        log.info("║  RabbitMQ: {}:{}",
                padRight(DEFAULT_MQ_HOST + ":" + DEFAULT_MQ_PORT, 22));
        log.info("╚══════════════════════════════════════╝");

        // 创建 BlackboardClient（内部自管理连接池）
        BlackboardClient bb = new BlackboardClient(
                DEFAULT_REDIS_HOST, redisPort, FALLBACK_MAP_SIZE, FALLBACK_MAP_SIZE);

        // 读取黑板中已有的地图配置，优先使用（TaskConfigurator 已初始化时有效）
        int mapW = bb.getMapWidth();
        int mapH = bb.getMapHeight();
        if (mapW == 0) {
            mapW = FALLBACK_MAP_SIZE;
        }
        if (mapH == 0) {
            mapH = FALLBACK_MAP_SIZE;
        }

        // 自注册（需先知道地图尺寸，避免越界）
        selfRegister(bb, carId, mapW, mapH);

        // 复用 BlackboardClient 内部的 JedisPool，避免重复创建连接池
        JedisPool sharedPool = bb.getJedisPool();

        MessageBus mb = new MessageBus(DEFAULT_MQ_HOST, DEFAULT_MQ_PORT, "guest", "guest");
        mb.connect();
        mb.declareCarQueue(carId);

        // 组装核心组件
        MoveExecutor moveExecutor = new MoveExecutor(carId, bb, mb, sharedPool, mapW, mapH);
        CarAgent agent = new CarAgent(carId, bb, moveExecutor);

        // 订阅消息队列
        mb.subscribe(QueueNames.carQueue(carId), agent::handleMessage);

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[{}] 收到关闭信号，清理资源...", carId);
            mb.close();
            bb.close();
        }));

        log.info("[{}] 启动完成，等待 TICK_MOVE 消息 (Ctrl+C 退出)", carId);

        // 主线程保持存活
        while (mb.isConnected()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("[{}] 连接断开，退出", carId);
    }

    // ==================== CLI 参数解析 ====================

    private static String parseCarId(String[] args) {
        if (args.length >= 1 && !args[0].isBlank()) {
            String id = args[0].trim();
            if (!id.startsWith("Car")) {
                id = "Car" + id;
            }
            return id;
        }
        return "Car001";
    }

    private static int parseRedisPort(String[] args) {
        if (args.length >= 2) {
            try {
                return Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                log.warn("非法端口号: {}, 使用默认端口 {}", args[1], DEFAULT_REDIS_PORT);
            }
        }
        return DEFAULT_REDIS_PORT;
    }

    // ==================== 自注册 ====================

    /**
     * 若黑板中尚无本车状态，则随机选择空地完成初始化。
     * 若已有状态（说明 TaskConfigurator 已初始化），则跳过。
     */
    private static void selfRegister(BlackboardClient bb, String carId,
                                      int mapWidth, int mapHeight) {
        if (bb.getCarStatus(carId).isPresent()) {
            CarStatus s = bb.getCarStatus(carId).orElseThrow();
            log.info("[{}] 已在黑板注册，当前状态: {}", carId, s.chineseName());
            return;
        }

        log.info("[{}] 未注册，正在自初始化...", carId);
        Random rng = new Random();
        for (int attempt = 0; attempt < MAX_INIT_ATTEMPTS; attempt++) {
            int x = rng.nextInt(mapWidth);
            int y = rng.nextInt(mapHeight);
            if (!bb.isBlocked(y, x)) {
                Point pos = new Point(x, y);
                bb.setCarPosition(carId, pos);
                bb.setCarStatus(carId, CarStatus.IDLE);
                bb.setCarSteps(carId, 0);
                bb.setBlock(y, x, true);
                illuminateAndHeat(bb, pos, mapWidth, mapHeight);
                log.info("[{}] 自初始化完成，初始位置: ({},{})", carId, x, y);
                return;
            }
        }
        log.error("[{}] 无法找到初始位置（{}×{} 地图尝试 {} 次均失败）！",
                carId, mapWidth, mapHeight, MAX_INIT_ATTEMPTS);
    }

    /**
     * 点亮初始位置周围 3×3 区域，与 MoveExecutor 行为一致（含热力图）。
     */
    private static void illuminateAndHeat(BlackboardClient bb, Point center,
                                           int mapWidth, int mapHeight) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = center.y() + dr;
                int c = center.x() + dc;
                if (r >= 0 && r < mapHeight && c >= 0 && c < mapWidth) {
                    bb.setMapViewBit(r, c, true);
                    bb.incrementMapHeat(r, c);
                }
            }
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}
