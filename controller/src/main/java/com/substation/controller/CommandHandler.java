package com.substation.controller;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;

import java.util.Map;

/** 命令处理器：解析入站消息并按消息类型路由到调度器/分派器/配置模块 */
public class CommandHandler {

    /** 最小时调节拍间隔（毫秒），防止过于频繁的调度 */
    private static final int MIN_TICK_INTERVAL_MS = 100;
    /** 最大时调节拍间隔（毫秒），防止间隔过大导致响应迟钝 */
    private static final int MAX_TICK_INTERVAL_MS = 2000;

    /** 消息总线 */
    private final MessageBus bus;
    /** 状态分派器 */
    private final StatusDispatcher dispatcher;
    /** 节拍调度器 */
    private final TickScheduler scheduler;

    /** 创建命令处理器，绑定消息总线、状态分派器和节拍调度器 */
    public CommandHandler(MessageBus bus, StatusDispatcher dispatcher, TickScheduler scheduler) {
        this.bus = bus;
        this.dispatcher = dispatcher;
        this.scheduler = scheduler;
    }

    /** 处理入站消息：解析JSON并按消息类型分发到对应组件 */
    public void handle(String raw) {
        try {
            JSONObject msg = JSONObject.parse(raw);
            String type = msg.getString("type");
            String carId = msg.getString("carId");
            JSONObject data = msg.getJSONObject("data");

            switch (type) {
                case MessageTypes.TASK_READY -> {
                    System.out.println("[Controller] 收到 TASK_READY，启动调度");
                    dispatcher.onTaskReady();
                    scheduler.start();
                }
                case MessageTypes.TARGET_ASSIGNED -> {
                    boolean success = data != null && data.getBooleanValue("success", false);
                    dispatcher.onTargetAssigned(carId, success);
                }
                case MessageTypes.ROUTE_PLANNED -> {
                    boolean routeFound = data != null && data.getBooleanValue("routeFound", false);
                    dispatcher.onRoutePlanned(carId, routeFound);
                }
                case MessageTypes.MOVED, MessageTypes.ROUTE_DONE, MessageTypes.BLOCKED ->
                    {} // Car 已自行写入状态，无需额外处理
                case MessageTypes.SET_CONFIG -> {
                    if (data != null) {
                        dispatcher.forwardConfig(data);
                    }
                }
                case MessageTypes.RESET -> dispatcher.forwardReset();
                case MessageTypes.TOGGLE_PAUSE -> scheduler.togglePause();
                case MessageTypes.SET_TICK_INTERVAL -> {
                    if (data != null) {
                        int interval = data.getIntValue("interval");
                        if (interval >= MIN_TICK_INTERVAL_MS && interval <= MAX_TICK_INTERVAL_MS) {
                            scheduler.setInterval(interval);
                        }
                    }
                }
                default -> System.out.println("[Controller] 未知消息类型: " + type);
            }
        } catch (Exception e) {
            System.err.println("[Controller] 消息处理异常: " + raw);
            e.printStackTrace();
        }
    }
}
