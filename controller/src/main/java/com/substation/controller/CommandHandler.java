package com.substation.controller;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBus;
import com.substation.common.mq.MessageTypes;

import java.util.Map;

public class CommandHandler {

    private static final int MIN_TICK_INTERVAL_MS = 100;
    private static final int MAX_TICK_INTERVAL_MS = 2000;

    private final MessageBus bus;
    private final StatusDispatcher dispatcher;
    private final TickScheduler scheduler;

    public CommandHandler(MessageBus bus, StatusDispatcher dispatcher, TickScheduler scheduler) {
        this.bus = bus;
        this.dispatcher = dispatcher;
        this.scheduler = scheduler;
    }

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
                    {} // Car already wrote status, no action needed
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
