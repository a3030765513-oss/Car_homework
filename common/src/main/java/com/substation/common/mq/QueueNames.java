package com.substation.common.mq;

public final class QueueNames {

    private QueueNames() {}

    public static final String CAR_PREFIX = "Car_";
    public static final String NAVIGATOR_CMD = "NavigatorCmd";
    public static final String TARGET_PLANNER_CMD = "TargetPlannerCmd";
    public static final String TASK_CONFIG_CMD = "TaskConfigCmd";
    public static final String CONTROLLER_CMD = "ControllerCmd";
    public static final String UPDATE_VIEW_EXCHANGE = "UpdateView";

    public static String carQueue(String carId) {
        return CAR_PREFIX + carId;
    }
}
