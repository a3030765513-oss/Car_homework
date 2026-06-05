package com.substation.controller;

import com.substation.common.mq.MessageBus;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;

/** 控制器主入口，负责初始化所有组件并绑定消息监听 */
public class ControllerMain {

    /** Redis 服务器地址 */
    private static final String REDIS_HOST = "localhost";
    /** Redis 端口 */
    private static final int REDIS_PORT = 6379;
    /** 地图宽度（格子数） */
    private static final int MAP_WIDTH = 30;
    /** 地图高度（格子数） */
    private static final int MAP_HEIGHT = 30;
    /** RabbitMQ 服务器地址 */
    private static final String MQ_HOST = "localhost";
    /** RabbitMQ 端口 */
    private static final int MQ_PORT = 5672;
    /** RabbitMQ 用户名 */
    private static final String MQ_USER = "guest";
    /** RabbitMQ 密码 */
    private static final String MQ_PASS = "guest";

    /** 控制器入口：初始化黑板与消息总线，注册消息监听，设置优雅关闭钩子 */
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
