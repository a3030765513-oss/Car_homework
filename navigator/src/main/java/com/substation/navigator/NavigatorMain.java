package com.substation.navigator;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.Point;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public class NavigatorMain {

    private static final Logger log = LoggerFactory.getLogger(NavigatorMain.class);
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = Integer.parseInt(
        System.getenv().getOrDefault("REDIS_PORT", "6379"));
    private static final String MQ_HOST = "localhost";
    private static final int MQ_PORT = Integer.parseInt(
        System.getenv().getOrDefault("MQ_PORT", "5672"));
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";
    private static final int INITIAL_MAP_SIZE = 30;
    private static final String DEFAULT_ALGORITHM = "BFS";

    public static void main(String[] args) throws IOException, TimeoutException {
        BlackboardClient bb = new BlackboardClient(REDIS_HOST, REDIS_PORT,
            INITIAL_MAP_SIZE, INITIAL_MAP_SIZE);
        MessageBus messageBus = new MessageBus(MQ_HOST, MQ_PORT, MQ_USER, MQ_PASS);
        messageBus.connect();
        messageBus.declareNavigatorQueue();

        log.info("[Navigator] 启动完成，等待路径规划命令...");

        messageBus.subscribe(QueueNames.NAVIGATOR_CMD, rawMessage -> {
            JSONObject msg = JSONObject.parseObject(rawMessage);
            String type = msg.getString("type");
            int tick = msg.getIntValue("tick", 0);

            if (!MessageTypes.PLAN_ROUTE.equals(type)) {
                log.warn("[Navigator] 未知消息类型: {}", type);
                return;
            }

            String carId = msg.getString("carId");
            JSONObject data = msg.getJSONObject("data");
            String algorithm = extractAlgorithm(data);

            log.info("[Navigator] 收到 PLAN_ROUTE carId={} algorithm={} tick={}",
                carId, algorithm, tick);

            try {
                handlePlanRoute(bb, messageBus, carId, algorithm, tick);
            } catch (Exception e) {
                log.error("[Navigator] 规划失败 carId={}", carId, e);
                sendRoutePlanned(messageBus, carId, false, 0, tick);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Navigator] 关闭中...");
            messageBus.close();
            bb.close();
        }));

        synchronized (NavigatorMain.class) {
            try {
                NavigatorMain.class.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 消息处理 ====================

    private static void handlePlanRoute(BlackboardClient bb, MessageBus messageBus,
                                         String carId, String algorithm, int tick)
            throws IOException {
        Optional<Point> posOpt = bb.getCarPosition(carId);
        Optional<Point> targetOpt = bb.getCarTarget(carId);

        if (posOpt.isEmpty() || targetOpt.isEmpty()) {
            log.warn("[Navigator] carId={} 缺少位置或目标", carId);
            sendRoutePlanned(messageBus, carId, false, 0, tick);
            return;
        }

        Point start = posOpt.get();
        Point target = targetOpt.get();
        PathPlanner planner = PathPlannerFactory.create(algorithm);
        List<Point> route = planner.plan(start, target, bb);

        if (route.isEmpty()) {
            log.warn("[Navigator] carId={} 无可用路径 start=({},{}) target=({},{})",
                carId, start.x(), start.y(), target.x(), target.y());
            sendRoutePlanned(messageBus, carId, false, 0, tick);
            return;
        }

        bb.clearRoute(carId);
        bb.pushRoute(carId, route);
        log.info("[Navigator] carId={} 路径规划成功 长度={}", carId, route.size());
        sendRoutePlanned(messageBus, carId, true, route.size(), tick);
    }

    private static void sendRoutePlanned(MessageBus messageBus, String carId,
                                          boolean routeFound, int routeLength, int tick) {
        try {
            Map<String, Object> data = Map.of(
                "carId", carId,
                "routeFound", routeFound,
                "routeLength", routeLength
            );
            String reply = MessageBuilder.build(MessageTypes.ROUTE_PLANNED, tick, carId, data);
            messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
        } catch (IOException e) {
            log.error("[Navigator] 发送 ROUTE_PLANNED 失败 carId={}", carId, e);
        }
    }

    private static String extractAlgorithm(JSONObject data) {
        if (data == null) {
            return DEFAULT_ALGORITHM;
        }
        String alg = data.getString("algorithm");
        return alg != null ? alg : DEFAULT_ALGORITHM;
    }
}
