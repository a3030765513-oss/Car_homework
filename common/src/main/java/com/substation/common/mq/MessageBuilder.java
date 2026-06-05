package com.substation.common.mq;

import com.alibaba.fastjson2.JSONObject;

import java.util.Collections;
import java.util.Map;

public final class MessageBuilder {

    private MessageBuilder() {}

    public static String build(String type, int tick, String carId, Map<String, Object> data) {
        JSONObject msg = new JSONObject();
        msg.put("type", type);
        msg.put("tick", tick);
        if (carId != null) {
            msg.put("carId", carId);
        }
        msg.put("timestamp", System.currentTimeMillis());
        msg.put("data", data != null ? data : Collections.emptyMap());
        return msg.toJSONString();
    }

    public static String build(String type, int tick, String carId) {
        return build(type, tick, carId, null);
    }

    public static String build(String type, int tick) {
        return build(type, tick, null, null);
    }
}
