package com.substation.common.model;

public enum CarStatus {
    IDLE("空闲", "#9E9E9E"),
    WAITING_ROUTE("等待路径", "#FF9800"),
    READY("就绪", "#4CAF50"),
    MOVING("移动中", "#2196F3"),
    BLOCKED("受阻", "#F44336");

    private final String chineseName;
    private final String color;

    CarStatus(String chineseName, String color) {
        this.chineseName = chineseName;
        this.color = color;
    }

    public String chineseName() {
        return chineseName;
    }

    public String color() {
        return color;
    }
}
