package com.substation.display;

import com.alibaba.fastjson2.JSONObject;
import com.substation.common.mq.MessageBus;
import com.substation.common.redis.BlackboardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/**
 * Display 模块入口。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>创建并管理 {@link BlackboardClient}、{@link MessageBus}、
 *       {@link WebSocketBridge}、{@link HttpFileServer} 四个核心组件</li>
 *   <li>订阅 UpdateView Fanout 交换机，接收 Controller 发出的 REFRESH_ALL 广播</li>
 *   <li>每次收到广播时，触发 WebSocketBridge 读取黑板最新数据并推送给所有浏览器</li>
 *   <li>提供 HTTP 静态文件服务，让浏览器加载前端页面</li>
 * </ul>
 *
 * <h3>启动流程</h3>
 * <ol>
 *   <li>连接 RabbitMQ</li>
 *   <li>声明 UpdateView Fanout 交换机 + ControllerCmd 队列（发送用）</li>
 *   <li>创建独占队列并绑定到 UpdateView Fanout</li>
 *   <li>订阅该队列，回调中解析 REFRESH_ALL 消息</li>
 *   <li>启动 WebSocket 服务（默认 8888 端口）</li>
 *   <li>启动 HTTP 静态文件服务（默认 8887 端口）</li>
 * </ol>
 *
 * <h3>多实例支持</h3>
 * <p>UpdateView 是 Fanout Exchange，每个 DisplayMain 进程绑定自己的独占队列，
 * 同时接收广播独立推送。可启动多个 DisplayMain（不同端口）支持多个浏览器。</p>
 *
 * @author Person D
 */
public class DisplayMain {

    private static final Logger LOG = LoggerFactory.getLogger(DisplayMain.class);

    /** 地图默认尺寸（当 TaskConfig 未设置时使用） */
    private static final int DEFAULT_MAP_SIZE = BlackboardClient.DEFAULT_SIZE;

    /** HTTP 服务默认端口 */
    private static final int DEFAULT_HTTP_PORT = 8887;

    /** WebSocket 服务默认端口 */
    private static final int DEFAULT_WS_PORT = 8888;

    // ────────────────── 核心组件 ──────────────────

    /** Redis 黑板客户端，只读不写 */
    private final BlackboardClient blackboard;

    /** RabbitMQ 消息总线 */
    private final MessageBus messageBus;

    /** WebSocket 服务端，负责推送 SimulationState 到浏览器 */
    private final WebSocketBridge wsBridge;

    /** HTTP 静态文件服务，提供前端 HTML/CSS/JS */
    private final HttpFileServer httpServer;

    /** HTTP 端口（日志输出用） */
    private final int httpPort;

    // ────────────────── 构造 ──────────────────

    /**
     * 创建 DisplayMain 实例。
     *
     * @param redisHost Redis 主机地址
     * @param redisPort Redis 端口
     * @param mqHost    RabbitMQ 主机地址
     * @param mqPort    RabbitMQ 端口
     * @param httpPort  HTTP 静态文件服务端口
     * @param wsPort    WebSocket 服务端口
     * @param webRoot   前端静态文件根目录
     */
    public DisplayMain(String redisHost, int redisPort,
                       String mqHost, int mqPort,
                       int httpPort, int wsPort,
                       Path webRoot) throws IOException {

        this.httpPort = httpPort;

        // 创建 Redis 黑板客户端（Display 只读黑板，不写）
        this.blackboard = new BlackboardClient(redisHost, redisPort,
                DEFAULT_MAP_SIZE, DEFAULT_MAP_SIZE);

        // 创建 RabbitMQ 消息总线（启用自动恢复）
        this.messageBus = new MessageBus(mqHost, mqPort, "guest", "guest");

        // 创建 MQ 发送适配器 —— 将 WebSocketBridge 需要发送 MQ 消息的动作
        // 委托给 MessageBus.publish()
        WebSocketBridge.MqSender mqSender = (queue, message) -> {
            try {
                messageBus.publish(queue, message);
            } catch (IOException e) {
                LOG.error("MQ 发送失败: queue={}", queue, e);
            }
        };

        this.wsBridge = new WebSocketBridge(wsPort, blackboard, mqSender);
        this.httpServer = new HttpFileServer(httpPort, webRoot);

        LOG.info("DisplayMain 初始化完成");
        LOG.info("  Redis:  {}:{}", redisHost, redisPort);
        LOG.info("  MQ:     {}:{}", mqHost, mqPort);
        LOG.info("  HTTP:   port={}", httpPort);
        LOG.info("  WS:     port={}", wsPort);
    }

    // ────────────────── 启动 / 停止 ──────────────────

    /**
     * 启动 Display 模块：连接消息总线 → 订阅广播 → 启动 WebSocket + HTTP 服务。
     *
     * <p>启动后进程进入阻塞状态（WebSocket 和 HTTP 服务持续运行）。</p>
     */
    public void start() throws IOException, TimeoutException {
        messageBus.connect();
        LOG.info("MQ 连接成功");

        // 1. 声明需要的队列/交换机（幂等操作）
        messageBus.declareFanoutExchange();
        messageBus.declareControllerQueue();

        // 2. 创建独占队列并绑定到 UpdateView Fanout
        //    每个 Display 实例获得独立的独占队列，互不影响
        String fanoutQueueName = messageBus.bindFanoutQueue();
        LOG.info("绑定 Fanout 队列: {}", fanoutQueueName);

        // 3. 订阅 REFRESH_ALL 广播
        //    每次 Controller 发出广播，此回调被触发
        messageBus.subscribe(fanoutQueueName, this::onRefreshAllReceived);

        // 4. 启动 WebSocket 服务
        wsBridge.start();
        LOG.info("WebSocket 服务已启动: ws://localhost:{}", wsBridge.getPort());

        // 5. 启动 HTTP 静态文件服务
        httpServer.start();
        LOG.info("HTTP 服务已启动: http://localhost:{}", httpPort);
        LOG.info("========== Display 模块启动完成 ==========");
    }

    /**
     * 优雅关闭：依次停止 WebSocket、HTTP、消息总线，释放 Redis 连接池。
     *
     * <p>关闭顺序是先停外部服务再停内部连接，避免正在处理的请求中断。</p>
     */
    public void stop() {
        LOG.info("Display 正在关闭...");

        // 先停 WebSocket（通知浏览器断开连接）
        try {
            wsBridge.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("WebSocket 关闭被中断");
        }

        // 再停 HTTP 服务
        httpServer.stop();

        // 关闭 MQ 连接
        messageBus.close();

        // 最后释放 Redis 连接池
        blackboard.close();

        LOG.info("Display 已关闭");
    }

    // ────────────────── MQ 消息处理 ──────────────────

    /**
     * 处理收到的 REFRESH_ALL 广播消息。
     *
     * <p>消息格式（由 Controller 发出）：</p>
     * <pre>{@code
     * {
     *   "type": "REFRESH_ALL",
     *   "tick": 42,
     *   "data": {
     *     "tick": 42,
     *     "explorationRate": 67
     *   }
     * }
     * }</pre>
     *
     * <p>处理流程：
     * <ol>
     *   <li>解析 JSON 获取 tick 和 explorationRate</li>
     *   <li>调用 {@link WebSocketBridge#pushSimulationState(int, int)}
     *       读取黑板最新数据并推送给所有浏览器</li>
     * </ol>
     *
     * @param rawMessage MQ 收到的原始 JSON 字符串
     */
    private void onRefreshAllReceived(String rawMessage) {
        try {
            JSONObject mqMsg = JSONObject.parseObject(rawMessage);

            int tick = mqMsg.getIntValue("tick");
            JSONObject data = mqMsg.getJSONObject("data");
            int explorationRate = 0;
            if (data != null) {
                explorationRate = data.getIntValue("explorationRate");
            }

            if (tick == 1 || tick % 50 == 0) {
                LOG.info("MQ收到 tick={} rawData={} parsedRate={}",
                    tick, data != null ? data.toJSONString() : "null", explorationRate);
            }

            wsBridge.pushSimulationState(tick, explorationRate);
        } catch (Exception e) {
            LOG.warn("解析 REFRESH_ALL 消息失败: {}", rawMessage, e);
        }
    }

    // ────────────────── 入口 ──────────────────

    /**
     * 程序入口。
     *
     * <p>使用默认端口（HTTP 8887, WS 8888）启动 Display 模块。
     * 可通过命令行参数覆盖默认值。</p>
     *
     * @param args 命令行参数（可选，当前使用默认值）
     */
    public static void main(String[] args) throws IOException, TimeoutException,
            InterruptedException {
        Path webRoot = findWebRoot();
        DisplayMain display = new DisplayMain(
                "localhost", 6379,        // Redis
                "localhost", 5672,        // RabbitMQ
                DEFAULT_HTTP_PORT,        // HTTP port
                DEFAULT_WS_PORT,          // WebSocket port
                webRoot                   // 前端静态文件目录
        );
        display.start();

        // 保持主线程存活（WebSocket 和 HTTP 在后台线程运行）
        Thread.currentThread().join();
    }

    // ────────────────── 辅助 ──────────────────

    /**
     * 查找前端静态文件目录。
     *
     * <p>按以下顺序依次尝试：</p>
     * <ol>
     *   <li>{@code display/src/main/resources/web}（从项目根目录运行）</li>
     *   <li>{@code src/main/resources/web}（从 display 子目录运行）</li>
     * </ol>
     *
     * <p>如果都不存在，返回第一个路径作为默认值（运行时可能不存在，
     * 此时 HTTP 服务对任意请求都返回 404）。</p>
     *
     * @return 前端静态文件根目录路径
     */
    // package-private for testing
    static Path findWebRoot() {
        Path[] candidates = {
                Path.of("display/src/main/resources/web"),
                Path.of("src/main/resources/web"),
        };
        for (Path candidate : candidates) {
            if (candidate.toFile().isDirectory()) {
                LOG.debug("Web 根目录: {}", candidate.toAbsolutePath());
                return candidate;
            }
        }
        LOG.warn("未找到 web 目录，使用默认路径");
        return Path.of("display/src/main/resources/web");
    }
}
