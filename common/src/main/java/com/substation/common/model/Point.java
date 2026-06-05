package com.substation.common.model;

import com.alibaba.fastjson2.JSON;

public record Point(int x, int y) {

    public int manhattanDistance(Point other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y);
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    public static Point fromJson(String json) {
        return JSON.parseObject(json, Point.class);
    }
}
