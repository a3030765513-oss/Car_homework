package com.substation.strategysupervisor;

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

/**
 * 策略监督器入口：订阅 StrategySupervisorCmd，收到 SUPERVISE_ROUTE 后评估路线是否需要优化，
 * 必要时用加权路径搜索替换为偏好未探索区域的路线。
 */
public class StrategySupervisorMain {

    private static final Logger log = LoggerFactory.getLogger(StrategySupervisorMain.class);
    private static final int INITIAL_MAP_SIZE = 30;
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";
    private static final double MAX_PATH_LENGTH_RATIO = 2.0;

    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private BlackboardClient bb;
    private MessageBus messageBus;

    public StrategySupervisorMain(String redisHost, int redisPort, String mqHost, int mqPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
    }

    public void start() throws IOException, TimeoutException {
        bb = new BlackboardClient(redisHost, redisPort, INITIAL_MAP_SIZE, INITIAL_MAP_SIZE);
        messageBus = new MessageBus(mqHost, mqPort, MQ_USER, MQ_PASS);
        messageBus.connect();
        messageBus.declareStrategySupervisorQueue();

        RouteEvaluator evaluator = new RouteEvaluator();
        WeightedPathPlanner planner = new WeightedPathPlanner();
        log.info("[StrategySupervisor] 启动完成，等待监督命令...");

        messageBus.subscribe(QueueNames.STRATEGY_SUPERVISOR_CMD, rawMessage -> {
            JSONObject msg = JSONObject.parseObject(rawMessage);
            String type = msg.getString("type");
            int tick = msg.getIntValue("tick", 0);

            if (!MessageTypes.SUPERVISE_ROUTE.equals(type)) {
                log.warn("[StrategySupervisor] 未知消息类型: {}", type);
                return;
            }

            String carId = msg.getString("carId");
            log.info("[StrategySupervisor] 收到 SUPERVISE_ROUTE carId={}, tick={}", carId, tick);

            try {
                handleSupervise(evaluator, planner, carId, tick);
            } catch (Exception e) {
                log.error("[StrategySupervisor] 监督失败 carId={}", carId, e);
                sendResult(carId, false, 0, 0, tick);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[StrategySupervisor] 关闭中...");
            if (messageBus != null) messageBus.close();
            if (bb != null) bb.close();
        }));
    }

    public static void main(String[] args) throws IOException, TimeoutException {
        new StrategySupervisorMain("localhost", 6379, "localhost", 5672).start();
        synchronized (StrategySupervisorMain.class) {
            try { StrategySupervisorMain.class.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void handleSupervise(RouteEvaluator evaluator, WeightedPathPlanner planner,
                                  String carId, int tick) throws IOException {
        Optional<Point> posOpt = bb.getCarPosition(carId);
        Optional<Point> targetOpt = bb.getCarTarget(carId);
        List<Point> currentRoute = bb.getCarRoute(carId);

        if (posOpt.isEmpty() || targetOpt.isEmpty() || currentRoute.isEmpty()) {
            sendResult(carId, false, 0, 0, tick);
            return;
        }

        RouteEvaluator.Result evalResult = evaluator.evaluate(bb, currentRoute);
        if (evalResult == RouteEvaluator.Result.SKIP) {
            sendResult(carId, false, 0, 0, tick);
            return;
        }

        log.info("[StrategySupervisor] carId={} 路线已探索比例超标，尝试优化", carId);
        List<Point> newRoute = planner.plan(posOpt.get(), targetOpt.get(), bb, currentRoute);

        if (newRoute == currentRoute || newRoute.isEmpty()) {
            sendResult(carId, false, 0, 0, tick);
            return;
        }

        if (newRoute.size() > currentRoute.size() * MAX_PATH_LENGTH_RATIO) {
            log.info("[StrategySupervisor] carId={} 新路线太长({}>{})，弃用",
                carId, newRoute.size(), (int)(currentRoute.size() * MAX_PATH_LENGTH_RATIO));
            sendResult(carId, false, 0, 0, tick);
            return;
        }

        bb.clearRoute(carId);
        bb.pushRoute(carId, newRoute);
        log.info("[StrategySupervisor] carId={} 路线已优化 {}→{}", carId, currentRoute.size(), newRoute.size());
        sendResult(carId, true, currentRoute.size(), newRoute.size(), tick);
    }

    private void sendResult(String carId, boolean optimized, int oldLen, int newLen, int tick) {
        try {
            Map<String, Object> data = Map.of(
                "carId", carId,
                "optimized", optimized,
                "oldLength", oldLen,
                "newLength", newLen
            );
            String msg = MessageBuilder.build(MessageTypes.ROUTE_OPTIMIZED, tick, carId, data);
            messageBus.publish(QueueNames.CONTROLLER_CMD, msg);
        } catch (IOException e) {
            log.error("[StrategySupervisor] 发送 ROUTE_OPTIMIZED 失败 carId={}", carId, e);
        }
    }
}
