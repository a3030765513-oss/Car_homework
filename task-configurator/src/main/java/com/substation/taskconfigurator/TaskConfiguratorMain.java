package com.substation.taskconfigurator;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBuilder;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class TaskConfiguratorMain {

    private static final Logger log = LoggerFactory.getLogger(TaskConfiguratorMain.class);
    private static final int INITIAL_MAP_SIZE = 30;
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";

    private final String redisHost;
    private final int redisPort;
    private final String mqHost;
    private final int mqPort;
    private BlackboardClient bb;
    private MessageBus messageBus;

    /** 供 Launcher 调用的构造函数 */
    public TaskConfiguratorMain(String redisHost, int redisPort, String mqHost, int mqPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.mqHost = mqHost;
        this.mqPort = mqPort;
    }

    /** 启动任务配置服务：连接中间件、声明队列、订阅 FORWARD_CONFIG/FORWARD_RESET。返回不阻塞 */
    public void start() throws IOException, TimeoutException {
        bb = new BlackboardClient(redisHost, redisPort, INITIAL_MAP_SIZE, INITIAL_MAP_SIZE);
        messageBus = new MessageBus(mqHost, mqPort, MQ_USER, MQ_PASS);
        messageBus.connect();
        messageBus.declareTaskConfigQueue();

        TaskInitializer initializer = new TaskInitializer();
        log.info("[TaskConfigurator] 启动完成，等待配置命令...");

        messageBus.subscribe(QueueNames.TASK_CONFIG_CMD, rawMessage -> {
            JSONObject msg = JSONObject.parseObject(rawMessage);
            String type = msg.getString("type");
            int tick = msg.getIntValue("tick", 0);
            JSONObject data = msg.getJSONObject("data");

            try {
                if (MessageTypes.FORWARD_CONFIG.equals(type)) {
                    handleConfig(initializer, data, tick);
                } else if (MessageTypes.FORWARD_RESET.equals(type)) {
                    handleReset(tick);
                } else {
                    log.warn("[TaskConfigurator] 未知消息类型: {}", type);
                }
            } catch (Exception e) {
                log.error("[TaskConfigurator] 处理消息失败 type={}", type, e);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[TaskConfigurator] 关闭中...");
            if (messageBus != null) messageBus.close();
            if (bb != null) bb.close();
        }));
    }

    /** 独立运行入口 */
    public static void main(String[] args) throws IOException, TimeoutException {
        new TaskConfiguratorMain("localhost", 6379, "localhost", 5672).start();
        synchronized (TaskConfiguratorMain.class) {
            try { TaskConfiguratorMain.class.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleConfig(TaskInitializer initializer, JSONObject data, int tick)
            throws IOException {
        Map<String, Object> config = data != null
            ? data.toJavaObject(Map.class) : Map.of();

        selectiveClear();

        initializer.initialize(bb, config);
        log.info("[TaskConfigurator] 初始化完成");

        String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
        messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
        log.info("[TaskConfigurator] 已发送 TASK_READY");
    }

    private void handleReset(int tick) {
        log.info("[TaskConfigurator] 已重置黑板，等待用户点击开始");
    }

    private void selectiveClear() {
        try (Jedis jedis = bb.getJedisPool().getResource()) {
            jedis.flushDB();
        }
    }
}
