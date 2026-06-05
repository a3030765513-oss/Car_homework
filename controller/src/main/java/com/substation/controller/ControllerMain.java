package com.substation.controller;

import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

public class ControllerMain {

    private static final String REDIS_HOST = "localhost";
    private static final int REDIS_PORT = 6379;
    private static final int MAP_WIDTH = 30;
    private static final int MAP_HEIGHT = 30;
    private static final String MQ_HOST = "localhost";
    private static final int MQ_PORT = 5672;
    private static final String MQ_USER = "guest";
    private static final String MQ_PASS = "guest";

    public static void main(String[] args) throws Exception {
        BlackboardClient bb = new BlackboardClient(REDIS_HOST, REDIS_PORT, MAP_WIDTH, MAP_HEIGHT);
        if (!bb.acquireControllerLock()) {
            System.err.println("[Controller] 已有实例在运行，退出");
            System.exit(1);
        }

        MessageBus bus = new MessageBus(MQ_HOST, MQ_PORT, MQ_USER, MQ_PASS);
        bus.connect();
        bus.declareControllerQueue();
        bus.declareTargetPlannerQueue();
        bus.declareNavigatorQueue();
        bus.declareTaskConfigQueue();
        bus.declareFanoutExchange();

        StatusDispatcher dispatcher = new StatusDispatcher(bb, bus);
        TickScheduler scheduler = new TickScheduler(dispatcher);
        CommandHandler handler = new CommandHandler(bus, dispatcher, scheduler);

        bus.subscribe(QueueNames.CONTROLLER_CMD, handler::handle);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.stop();
            bb.releaseControllerLock();
            bus.close();
            bb.close();
        }));

        System.out.println("[Controller] 控制器已启动，等待任务配置...");
    }
}
