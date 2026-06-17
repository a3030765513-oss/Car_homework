package com.substation.common.analysis;

import com.alibaba.fastjson2.JSON;
import com.substation.common.analysis.model.CarStatistics;
import com.substation.common.analysis.model.SummaryStatistics;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 统计分析 API 处理器（空壳框架）。
 * 所有接口返回空数据结构，具体实现留空。
 */
public class AnalysisApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisApiHandler.class);

    public AnalysisApiHandler() {}

    /** 分发 /api/analysis/* 请求 */
    public void handle(String path, HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        String method = exchange.getRequestMethod();
        String subPath = path.replace("/api/analysis", "");

        try {
            if (subPath.equals("/summary") && "GET".equals(method)) {
                handleSummary(exchange);
            } else if (subPath.startsWith("/car/") && "GET".equals(method)) {
                String carId = subPath.substring(5);
                handleCarStats(exchange, carId);
            } else if (subPath.equals("/leaderboard") && "GET".equals(method)) {
                handleLeaderboard(exchange);
            } else if (subPath.equals("/query") && "POST".equals(method)) {
                handleQuery(exchange);
            } else if (subPath.equals("/export") && "GET".equals(method)) {
                handleExport(exchange);
            } else {
                sendJson(exchange, 404, "404");
            }
        } catch (Exception e) {
            log.error("Analysis API 处理异常: {}", e.getMessage(), e);
            sendJson(exchange, 500,
                    "{\"success\":false,\"error\":\"服务器内部错误\"}");
        }
    }

    private void handleSummary(HttpExchange exchange) {
        SummaryStatistics s = SummaryStatistics.empty();
        String json = JSON.toJSONString(Map.of("success", true, "data", s));
        sendJson(exchange, 200, json);
    }

    private void handleCarStats(HttpExchange exchange, String carId) {
        CarStatistics s = CarStatistics.empty(carId);
        String json = JSON.toJSONString(Map.of("success", true, "data", s));
        sendJson(exchange, 200, json);
    }

    private void handleLeaderboard(HttpExchange exchange) {
        var lb = List.of(
                Map.of("carId", "Car001", "steps", 0, "coverage", 0, "efficiency", 0.0),
                Map.of("carId", "Car002", "steps", 0, "coverage", 0, "efficiency", 0.0),
                Map.of("carId", "Car003", "steps", 0, "coverage", 0, "efficiency", 0.0));
        String json = JSON.toJSONString(Map.of("success", true, "leaderboard", lb));
        sendJson(exchange, 200, json);
    }

    private void handleQuery(HttpExchange exchange) throws IOException {
        String bodyStr = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String json = JSON.toJSONString(Map.of("success", true, "data", Map.of()));
        sendJson(exchange, 200, json);
    }

    private void handleExport(HttpExchange exchange) {
        String json = JSON.toJSONString(Map.of("success", true,
                "message", "导出功能开发中"));
        sendJson(exchange, 200, json);
    }

    private void sendJson(HttpExchange exchange, int code, String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.error("响应写入失败", e);
        }
    }
}
