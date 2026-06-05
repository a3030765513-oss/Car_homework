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
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class TaskConfiguratorMain {

    private static final Logger log = LoggerFactory.getLogger(TaskConfiguratorMain.class);
    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final String MQ_HOST = "localhost";
    private static final int MQ_PORT = 5672;
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";
    private static final int INITIAL_MAP_SIZE = 30;

    public static void main(String[] args) throws IOException, TimeoutException {
        BlackboardClient bb = new BlackboardClient(REDIS_HOST, REDIS_PORT,
            INITIAL_MAP_SIZE, INITIAL_MAP_SIZE);
        MessageBus messageBus = new MessageBus(MQ_HOST, MQ_PORT, MQ_USER, MQ_PASS);
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
                    handleConfig(bb, initializer, messageBus, data, tick);
                } else if (MessageTypes.FORWARD_RESET.equals(type)) {
                    handleReset(bb, messageBus, tick);
                } else {
                    log.warn("[TaskConfigurator] 未知消息类型: {}", type);
                }
            } catch (Exception e) {
                log.error("[TaskConfigurator] 处理消息失败 type={}", type, e);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[TaskConfigurator] 关闭中...");
            messageBus.close();
            bb.close();
        }));

        synchronized (TaskConfiguratorMain.class) {
            try {
                TaskConfiguratorMain.class.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleConfig(BlackboardClient bb, TaskInitializer initializer,
                                      MessageBus messageBus, JSONObject data, int tick)
            throws IOException {
        Map<String, Object> config = data != null
            ? data.toJavaObject(Map.class) : Map.of();

        JedisPool pool = bb.getJedisPool();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }

        initializer.initialize(bb, config);
        log.info("[TaskConfigurator] 初始化完成");

        String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
        messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
        log.info("[TaskConfigurator] 已发送 TASK_READY");
    }

    private static void handleReset(BlackboardClient bb, MessageBus messageBus,
                                     int tick) throws IOException {
        JedisPool pool = bb.getJedisPool();
        try (Jedis jedis = pool.getResource()) {
            jedis.flushDB();
        }
        log.info("[TaskConfigurator] 已重置黑板");

        String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
        messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
    }
}
