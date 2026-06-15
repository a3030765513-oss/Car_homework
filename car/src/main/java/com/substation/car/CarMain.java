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
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeoutException;

/**
 * Car 模块入口。
 *
 * <p>独立运行用法：
 * <pre>{@code
 * java -jar car.jar Car001          # 默认参数
 * java -jar car.jar Car002 6380     # 指定 Redis 端口
 * }</pre>
 *
 * <p>Launcher 调用方式：
 * <pre>{@code
 * new CarMain("Car001", "localhost", 6379, "localhost", 5672).start();
 * }</pre>
 */
public class CarMain {

    private static final Logger log = LoggerFactory.getLogger(CarMain.class);

    private static final int FALLBACK_MAP_SIZE = 30;
    private static final int MAX_INIT_ATTEMPTS = 1000;

    private final String carId;
    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private BlackboardClient bb;
    private MessageBus mb;

    /** 供 Launcher 调用的构造函数 */
    public CarMain(String carId, String redisHost, int redisPort, String mqHost, int mqPort) {
        this.carId = carId;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
    }

    /** 启动小车：连接中间件、自注册、订阅 TICK_MOVE。返回不阻塞 */
    public void start() throws IOException, TimeoutException {
        bb = new BlackboardClient(redisHost, redisPort, FALLBACK_MAP_SIZE, FALLBACK_MAP_SIZE);

        int mapW = Math.max(bb.getMapWidth(), FALLBACK_MAP_SIZE);
        int mapH = Math.max(bb.getMapHeight(), FALLBACK_MAP_SIZE);

        selfRegister(bb, carId, mapW, mapH);

        JedisPool sharedPool = bb.getJedisPool();

        mb = new MessageBus(mqHost, mqPort, "guest", "guest");
        mb.connect();
        mb.declareCarQueue(carId);

        MoveExecutor moveExecutor = new MoveExecutor(carId, bb, mb, sharedPool, mapW, mapH);
        CarAgent agent = new CarAgent(carId, bb, moveExecutor);

        mb.subscribe(QueueNames.carQueue(carId), agent::handleMessage);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[{}] 收到关闭信号，清理资源...", carId);
            mb.close();
            bb.close();
        }));

        log.info("[{}] 启动完成，等待 TICK_MOVE 消息", carId);
    }

    /** 独立运行入口（阻塞等待 Ctrl+C） */
    public static void main(String[] args) throws IOException, TimeoutException {
        String carId = parseCarId(args);
        int redisPort = parseRedisPort(args);

        log.info("╔══════════════════════════════════════╗");
        log.info("║  {} 模块启动中...", padRight(carId, 24));
        log.info("║  Redis: localhost:{}", padRight(String.valueOf(redisPort), 26));
        log.info("║  RabbitMQ: localhost:5672");
        log.info("╚══════════════════════════════════════╝");

        CarMain car = new CarMain(carId, "localhost", redisPort, "localhost", 5672);
        car.start();

        while (car.mb != null && car.mb.isConnected()) {
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
                log.warn("非法端口号: {}, 使用默认端口 {}", args[1], 6379);
            }
        }
        return 6379;
    }

    // ==================== 自注册 ====================

    static void selfRegister(BlackboardClient bb, String carId,
                              int mapWidth, int mapHeight) {
        Optional<CarStatus> existing = bb.getCarStatus(carId);
        if (existing.isPresent()) {
            log.info("[{}] 已在黑板注册，当前状态: {}", carId, existing.get().chineseName());
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
                illuminateAndHeat(bb, pos, mapWidth, mapHeight);
                bb.appendCarHistory(carId, pos, 0);
                log.info("[{}] 自初始化完成，初始位置: ({},{})", carId, x, y);
                return;
            }
        }
        log.error("[{}] 无法找到初始位置（{}×{} 地图尝试 {} 次均失败）！",
                carId, mapWidth, mapHeight, MAX_INIT_ATTEMPTS);
    }

    static void illuminateAndHeat(BlackboardClient bb, Point center,
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
