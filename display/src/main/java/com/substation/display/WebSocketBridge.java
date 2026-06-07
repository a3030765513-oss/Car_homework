package com.substation.display;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.substation.common.model.CarStatus;
import com.substation.common.model.Point;
import com.substation.common.model.SimulationState;
import com.substation.common.mq.QueueNames;
import com.substation.common.redis.BlackboardClient;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 桥接器 —— Display 模块的核心。
 *
 * <h3>两大方向</h3>
 * <ol>
 *   <li><b>下行（Java → 浏览器）</b>：
 *       收到 DisplayMain 的触发 → 读取 Redis 黑板全部数据 →
 *       构建 {@link SimulationState} → JSON 序列化 → 广播给所有浏览器</li>
 *   <li><b>上行（浏览器 → Java）</b>：
 *       接收浏览器 WebSocket 消息 → 原样转发到 RabbitMQ ControllerCmd 队列</li>
 * </ol>
 *
 * <h3>关于状态颜色</h3>
 * <p>{@link CarStatus} 枚举自带颜色值，但此处不将颜色写入 JSON。
 * 前端根据 {@code status} 字段名本地查表映射——减少 JSON 传输冗余。</p>
 *
 * <h3>关于动态车辆编号</h3>
 * <p>不预设车辆数量。通过 {@link BlackboardClient#discoverCarIds()} 动态发现，
 * 遍历结果集构建 {@link SimulationState.CarInfo} 列表。</p>
 *
 * @author Person D
 */
public class WebSocketBridge extends WebSocketServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketBridge.class);

    /** 地图默认尺寸（TaskConfig 未设置时回退） */
    private static final int DEFAULT_MAP_SIZE = 30;
    /** 车辆 ID 前缀长度，"Car" 占 3 个字符 */
    private static final int CAR_PREFIX_LENGTH = 3;

    // ────────────────── 依赖 ──────────────────

    /** Redis 黑板读取客户端（只读） */
    private final BlackboardClient blackboard;

    /** MQ 消息发送适配器（转发浏览器命令到 Controller） */
    private final MqSender mqSender;

    /** 当前连接的浏览器客户端集合（线程安全） */
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();

    // ────────────────── 构造 ──────────────────

    /**
     * @param port       WebSocket 监听端口（默认 8888）
     * @param blackboard Redis 黑板客户端
     * @param mqSender   MQ 发送适配器，用于转发浏览器命令到 ControllerCmd
     */
    public WebSocketBridge(int port, BlackboardClient blackboard, MqSender mqSender) {
        super(new InetSocketAddress(port));
        this.blackboard = blackboard;
        this.mqSender = mqSender;
    }

    // ════════════════════════════════════════════════════════════════
    // WebSocket 生命周期回调（由父类 WebSocketServer 驱动）
    // ════════════════════════════════════════════════════════════════

    /** 浏览器连接建立 */
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        LOG.info("浏览器连接: {}", conn.getRemoteSocketAddress());
    }

    /** 浏览器连接断开 */
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        LOG.info("浏览器断开: {}", conn.getRemoteSocketAddress());
    }

    /**
     * 收到浏览器发来的消息。
     *
     * <p>不解析消息内容，直接原样转发到 ControllerCmd 队列。
     * 支持的消息类型由 Controller 的 CommandHandler 负责识别和分发：</p>
     * <ul>
     *   <li>SET_CONFIG —— 开始任务</li>
     *   <li>RESET —— 重置</li>
     *   <li>TOGGLE_PAUSE —— 暂停/继续</li>
     *   <li>SET_TICK_INTERVAL —— 调速</li>
     * </ul>
     *
     * @param conn    发送消息的浏览器连接
     * @param message 原始 JSON 字符串
     */
    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            mqSender.send(QueueNames.CONTROLLER_CMD, message);
        } catch (RuntimeException e) {
            LOG.warn("转发浏览器命令失败: {}", message, e);
        }
    }

    /** WebSocket 异常 */
    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.error("WebSocket 错误: {}", ex.getMessage());
    }

    /** 服务启动完成 */
    @Override
    public void onStart() {
        LOG.info("WebSocket 服务已启动，端口: {}", getPort());
    }

    // ════════════════════════════════════════════════════════════════
    // 下行：读取黑板 → 构建快照 → 推浏览器
    // ════════════════════════════════════════════════════════════════

    /**
     * 构建当前全局状态快照并推送给所有浏览器。
     *
     * <p>由 {@code DisplayMain.onRefreshAllReceived()} 调用，
     * 触发时机是每次收到 Controller 的 REFRESH_ALL 广播。</p>
     *
     * <p>如果当前没有浏览器连接，直接跳过黑板读取——避免无效 I/O。</p>
     *
     * @param tick            当前节拍号
     * @param explorationRate 当前探索率（0-100）
     */
    public void pushSimulationState(int tick, int explorationRate) {
        if (clients.isEmpty()) {
            return;
        }
        SimulationState state = buildSimulationState(tick, explorationRate);
        broadcast(JSON.toJSONString(state));
    }

    /**
     * 从 Redis 黑板读取所有数据，组装为一个不可变的快照对象。
     *
     * <p>读取的内容包括：
     * <ul>
     *   <li>mapView bitmap → boolean[][]</li>
     *   <li>mapBlock bitmap → boolean[][]</li>
     *   <li>TaskConfig hash → Map</li>
     *   <li>每个车的 Position / Target / RouteList / Status / Steps</li>
     * </ul>
     */
    private SimulationState buildSimulationState(int tick, int explorationRate) {
        Map<String, String> config = blackboard.getTaskConfig();
        int mapWidth = parseIntOrDefault(config.get("mapWidth"), DEFAULT_MAP_SIZE);
        int mapHeight = parseIntOrDefault(config.get("mapHeight"), DEFAULT_MAP_SIZE);

        boolean[][] mapView = readViewBitmap(mapWidth, mapHeight);
        boolean[][] mapBlock = readBlockBitmap(mapWidth, mapHeight);
        List<SimulationState.CarInfo> cars = buildCarInfoList();

        return new SimulationState(tick, explorationRate, config, cars, mapView, mapBlock);
    }

    /**
     * 从 Redis mapView bitmap 逐格读取，构建二维 boolean 数组。
     *
     * <p>30×30 地图共 900 格，每格一次 Redis GETBIT 调用。
     * 通过连接池复用连接，900 次调用在局域网中耗时约 10-20ms。</p>
     */
    private boolean[][] readViewBitmap(int mapWidth, int mapHeight) {
        boolean[][] bitmap = new boolean[mapHeight][mapWidth];
        for (int r = 0; r < mapHeight; r++) {
            for (int c = 0; c < mapWidth; c++) {
                bitmap[r][c] = blackboard.getMapViewBit(r, c);
            }
        }
        return bitmap;
    }

    /**
     * 从 Redis mapBlock bitmap 逐格读取，构建二维 boolean 数组。
     */
    private boolean[][] readBlockBitmap(int mapWidth, int mapHeight) {
        boolean[][] bitmap = new boolean[mapHeight][mapWidth];
        for (int r = 0; r < mapHeight; r++) {
            for (int c = 0; c < mapWidth; c++) {
                bitmap[r][c] = blackboard.isBlocked(r, c);
            }
        }
        return bitmap;
    }

    // ────────────────── 车辆信息构建 ──────────────────

    /**
     * 遍历所有已注册车辆，构建按编号排序的 CarInfo 列表。
     *
     * <p>动态发现：调用 {@link BlackboardClient#discoverCarIds()}，
     * 而非遍历 1..N 预设范围。未注册到黑板的 carId 将被跳过。</p>
     */
    private List<SimulationState.CarInfo> buildCarInfoList() {
        List<SimulationState.CarInfo> cars = new ArrayList<>();
        for (String carId : discoverActiveCarIds()) {
            buildSingleCarInfo(carId).ifPresent(cars::add);
        }
        cars.sort(Comparator.comparingInt(SimulationState.CarInfo::number));
        return cars;
    }

    /**
     * 构建单个车辆的 CarInfo。
     *
     * <p>如果车辆尚未在黑板中记录位置（{@code CarID:Position} key 不存在），
     * 认为该车尚未完成注册，返回空 Optional 跳过。</p>
     */
    private Optional<SimulationState.CarInfo> buildSingleCarInfo(String carId) {
        Optional<Point> positionOpt = blackboard.getCarPosition(carId);
        if (positionOpt.isEmpty()) {
            return Optional.empty();
        }

        int number = extractCarNumber(carId);
        Point target = blackboard.getCarTarget(carId).orElse(null);
        List<Point> route = blackboard.getCarRoute(carId);
        CarStatus status = blackboard.getCarStatus(carId).orElse(CarStatus.IDLE);
        int steps = blackboard.getCarSteps(carId);

        return Optional.of(new SimulationState.CarInfo(
                carId, number, positionOpt.get(), target, route, status, steps));
    }

    /**
     * 从 carId 中提取显示编号。
     *
     * <p>示例: {@code "Car001"} → {@code 1}, {@code "Car012"} → {@code 12}</p>
     */
    static int extractCarNumber(String carId) {
        if (carId == null || carId.length() <= CAR_PREFIX_LENGTH) {
            return 0;
        }
        try {
            return Integer.parseInt(carId.substring(CAR_PREFIX_LENGTH));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 调用 {@link BlackboardClient#discoverCarIds()} 获取已注册车辆。
     */
    private Set<String> discoverActiveCarIds() {
        return blackboard.discoverCarIds();
    }

    // ────────────────── 工具方法 ──────────────────

    /**
     * 安全地将字符串解析为 int，解析失败时返回默认值。
     *
     * <p>用于处理 TaskConfig Hash 中的数值字段（mapWidth、mapHeight 等），
     * 这些字段在 TaskConfigurator 未初始化时为 null。</p>
     */
    static int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 内部接口
    // ════════════════════════════════════════════════════════════════

    /**
     * MQ 消息发送适配器。
     *
     * <p>由 DisplayMain 在构造时注入实现（委托给 MessageBus.publish()）。
     * WebSocketBridge 不直接依赖 MessageBus，只依赖此抽象——遵循依赖倒置原则。</p>
     */
    @FunctionalInterface
    public interface MqSender {
        /**
         * @param queue   目标队列名称（如 "ControllerCmd"）
         * @param message 消息体（JSON 字符串）
         */
        void send(String queue, String message);
    }
}
