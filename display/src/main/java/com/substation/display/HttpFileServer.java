package com.substation.display;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 极简 HTTP 静态文件服务器。
 *
 * <h3>技术选型</h3>
 * <p>使用 JDK 内置的 {@code com.sun.net.httpserver.HttpServer}，无需引入
 * Jetty、Tomcat 等外部依赖。功能完全满足 Display 模块需求：
 * 将 {@code display/src/main/resources/web/} 目录下的 HTML/CSS/JS
 * 文件提供给浏览器。</p>
 *
 * <h3>设计决策</h3>
 * <ul>
 *   <li>包级私有——仅供 {@link DisplayMain} 使用，不对外暴露</li>
 *   <li>同步阻塞模型——前端文件请求量极低（一个浏览器仅 3 个文件），
 *       无需 NIO 异步处理</li>
 *   <li>内存全量读取——文件体积小（HTML/CSS/JS 合计 &lt;20KB），
 *       不需要流式分块传输</li>
 * </ul>
 *
 * @author Person D
 */
final class HttpFileServer {

    /**
     * 文件扩展名 → HTTP Content-Type 映射。
     * 只包含前端需要的类型，其余回退为 octet-stream。
     */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css",  "text/css; charset=utf-8",
            "js",   "application/javascript; charset=utf-8",
            "json", "application/json",
            "png",  "image/png",
            "svg",  "image/svg+xml"
    );

    private final HttpServer server;
    private final Path webRoot;

    /**
     * @param port    HTTP 监听端口（默认 8887）
     * @param webRoot 前端静态文件根目录，如 {@code display/src/main/resources/web}
     */
    HttpFileServer(int port, Path webRoot) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.webRoot = webRoot;
        this.server.createContext("/", this::handle);
    }

    // ────────────────── 生命周期 ──────────────────

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    // ────────────────── 请求处理 ──────────────────

    /**
     * 处理 HTTP 请求。
     *
     * <p>实现要点：
     * <ol>
     *   <li>{@code /} 映射到 {@code /index.html}</li>
     *   <li>根据请求路径在 webRoot 下查找文件</li>
     *   <li>找到 → 读文件 → 设 Content-Type → 返回 200</li>
     *   <li>未找到或为目录 → 返回 404</li>
     * </ol>
     */
    private void handle(HttpExchange exchange) throws IOException {
        String requestPath = exchange.getRequestURI().getPath();
        String resolvedPath = "/".equals(requestPath) ? "/index.html" : requestPath;

        Path filePath = webRoot.resolve(resolvedPath.substring(1));
        if (Files.isRegularFile(filePath)) {
            serveFile(exchange, filePath);
        } else {
            sendNotFound(exchange);
        }
    }

    /**
     * 返回 200 OK，正文为文件内容。
     *
     * <p>使用 try-with-resources 确保 OutputStream 被正确关闭，
     * 即使写响应过程中发生异常也不会泄漏资源。</p>
     */
    private void serveFile(HttpExchange exchange, Path filePath) throws IOException {
        String contentType = detectContentType(filePath);
        byte[] content = Files.readAllBytes(filePath);

        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * 返回 404 Not Found。
     */
    private void sendNotFound(HttpExchange exchange) throws IOException {
        byte[] body = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ────────────────── 工具 ──────────────────

    /**
     * 根据文件扩展名检测 Content-Type。
     *
     * @param filePath 文件路径（仅使用其扩展名部分）
     * @return MIME 类型字符串，未知扩展名返回 {@code application/octet-stream}
     */
    static String detectContentType(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) {
            return "application/octet-stream";
        }
        String extension = fileName.substring(lastDot + 1);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
