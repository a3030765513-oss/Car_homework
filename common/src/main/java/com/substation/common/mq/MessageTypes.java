package com.substation.common.mq;

public final class MessageTypes {

    private MessageTypes() {}

    public static final String ASSIGN_TARGET = "ASSIGN_TARGET";
    public static final String TARGET_ASSIGNED = "TARGET_ASSIGNED";
    public static final String PLAN_ROUTE = "PLAN_ROUTE";
    public static final String ROUTE_PLANNED = "ROUTE_PLANNED";
    public static final String TICK_MOVE = "TICK_MOVE";
    public static final String MOVED = "MOVED";
    public static final String ROUTE_DONE = "ROUTE_DONE";
    public static final String BLOCKED = "BLOCKED";
    public static final String BLOCKED_TIMEOUT = "BLOCKED_TIMEOUT";
    public static final String REFRESH_ALL = "REFRESH_ALL";
    public static final String SET_CONFIG = "SET_CONFIG";
    public static final String FORWARD_CONFIG = "FORWARD_CONFIG";
    public static final String RESET = "RESET";
    public static final String FORWARD_RESET = "FORWARD_RESET";
    public static final String TASK_READY = "TASK_READY";
    public static final String TOGGLE_PAUSE = "TOGGLE_PAUSE";
    public static final String SET_TICK_INTERVAL = "SET_TICK_INTERVAL";
}
