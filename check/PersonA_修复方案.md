# Person A 修复方案

---

## 问题 1：重置后再点开始，系统不运行

**问题描述**

首次运行正常，点击重置后再点"开始"，系统不再运行，页面显示"等待任务启动"。

**原因**

`TaskConfiguratorMain.handleReset()` 只执行 `selectiveClear()` 清空 Redis 所有数据，然后发送 `TASK_READY`。Controller 收到后启动 tick 循环，但板中无小车、无配置。系统处于"激活但空"状态。

**修复方案**

重置时沿用上一次的配置参数重新执行初始化，而非仅清空。

**涉及文件**

`task-configurator/src/main/java/com/substation/taskconfigurator/TaskConfiguratorMain.java`

**当前代码**（第 96-103 行附近）

```java
private void handleReset(int tick) throws IOException {
    selectiveClear();
    log.info("[TaskConfigurator] 已重置黑板");

    String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
    messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
}
```

**修改为**

```java
// 新增字段，保存最近一次 SET_CONFIG 的配置
private Map<String, Object> lastConfig;

private void handleConfig(TaskInitializer initializer, JSONObject data, int tick)
        throws IOException {
    Map<String, Object> config = data != null
        ? data.toJavaObject(Map.class) : Map.of();
    this.lastConfig = config;  // 保存配置用于重置

    selectiveClear();
    initializer.initialize(bb, config);
    log.info("[TaskConfigurator] 初始化完成");

    String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
    messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
}

private void handleReset(int tick) throws IOException {
    selectiveClear();

    if (lastConfig != null) {
        TaskInitializer initializer = new TaskInitializer();
        initializer.initialize(bb, lastConfig);  // 用上次配置重建
        log.info("[TaskConfigurator] 已重置并重建");
    } else {
        log.warn("[TaskConfigurator] 无上次配置，仅清空");
    }

    String reply = MessageBuilder.build(MessageTypes.TASK_READY, tick);
    messageBus.publish(QueueNames.CONTROLLER_CMD, reply);
}
```

---

## 问题 2：一键启动所有模块

**问题描述**

当前需要手动开 8 个终端逐个启动模块，操作繁琐。

**修复方案**

创建 Windows 批处理脚本 `start_all.bat`，用 `start` 命令为每个模块打开独立终端窗口，`timeout` 控制启动间隔。各模块保持独立进程，通过 MQ 与 Redis 通讯解耦。

**涉及文件**

`D:\car_homework\start_all.bat`（新建）

**内容**

```batch
@echo off
echo ============================================
echo   变电站巡检仿真系统 — 一键启动
echo ============================================
echo.

REM ① 任务配置器
start "TaskConfigurator" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl task-configurator "-Dexec.mainClass=com.substation.taskconfigurator.TaskConfiguratorMain""
timeout /t 2 /nobreak >nul

REM ② 导航器
start "Navigator" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl navigator "-Dexec.mainClass=com.substation.navigator.NavigatorMain""

REM ③ 目标规划器
start "TargetPlanner" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl target-planner "-Dexec.mainClass=com.substation.targetplanner.TargetPlannerMain""
timeout /t 1 /nobreak >nul

REM ④ 小车（3 台）
start "Car001" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car001""
start "Car002" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car002""
start "Car003" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl car "-Dexec.mainClass=com.substation.car.CarMain" "-Dexec.args=Car003""
timeout /t 1 /nobreak >nul

REM ⑤ 前端展示
start "Display" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl display "-Dexec.mainClass=com.substation.display.DisplayMain""
timeout /t 1 /nobreak >nul

REM ⑥ 控制器（最后启动）
start "Controller" cmd /k "cd /d D:\car_homework && .\mvnw.cmd exec:java -pl controller "-Dexec.mainClass=com.substation.controller.ControllerMain""

echo.
echo 全部模块已启动，浏览器打开 http://localhost:8887，点"开始"启动任务。
echo.
pause
```

---

## 问题 3：动态添加小车 — 后端部分

**问题描述**

需要在页面添加按钮，点击后自动启动新的 Car 进程加入任务。Person A 负责 Display 侧的消息接收与进程启动。

**涉及文件**

`display/src/main/java/com/substation/display/WebSocketBridge.java`

**前置步骤：打包 Car 模块**

```powershell
.\mvnw.cmd package -pl car -DskipTests
```

生成 `car/target/car-1.0-SNAPSHOT.jar`。

**当前代码**（`onMessage()` 方法，约在第 120-130 行）

```java
public void onMessage(WebSocket conn, String message) {
    try {
        messageBus.publish(QueueNames.CONTROLLER_CMD, message);
    } catch (IOException e) {
        LOG.error("转发消息失败", e);
    }
}
```

**修改为**

```java
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

private static final String CAR_JAR_PATH = "car/target/car-1.0-SNAPSHOT.jar";
private int carCount = 3;  // 与 TaskConfig.carCount 初始一致

public void onMessage(WebSocket conn, String message) {
    try {
        JSONObject msg = JSON.parseObject(message);
        String type = msg.getString("type");

        if ("ADD_CAR".equals(type)) {
            carCount++;
            String carId = String.format("Car%03d", carCount);
            String cmd = "java -jar " + CAR_JAR_PATH + " " + carId;
            Runtime.getRuntime().exec(cmd);
            LOG.info("已启动新车: {}", carId);
        } else {
            // 其他消息原样转发到 ControllerCmd
            messageBus.publish(QueueNames.CONTROLLER_CMD, message);
        }
    } catch (Exception e) {
        LOG.error("消息处理失败", e);
    }
}
```

---

## 问题 4：手动添加障碍物 — 后端部分

**问题描述**

需要在运行中手动在地图上增加/移除障碍物。Person A 负责 Controller 侧接收 TOGGLE_OBSTACLE 消息并写入 Redis。

**涉及文件**

`controller/src/main/java/com/substation/controller/CommandHandler.java`

`controller/src/main/java/com/substation/controller/ControllerMain.java`

**当前代码** — `CommandHandler` 构造函数（约第 25 行）

```java
public CommandHandler(MessageBus bus, StatusDispatcher dispatcher, TickScheduler scheduler) {
    this.bus = bus;
    this.dispatcher = dispatcher;
    this.scheduler = scheduler;
}
```

**修改为** — 增加 BlackboardClient 注入

```java
private final BlackboardClient bb;

public CommandHandler(MessageBus bus, StatusDispatcher dispatcher, TickScheduler scheduler,
                      BlackboardClient bb) {
    this.bus = bus;
    this.dispatcher = dispatcher;
    this.scheduler = scheduler;
    this.bb = bb;
}
```

**当前代码** — `handle()` 方法 switch 块末尾（约在第 57 行 `default` 之前）

```java
case MessageTypes.SET_TICK_INTERVAL -> {
    if (data != null) {
        int interval = data.getIntValue("interval");
        if (interval >= MIN_TICK_INTERVAL_MS && interval <= MAX_TICK_INTERVAL_MS) {
            scheduler.setInterval(interval);
        }
    }
}
default -> System.out.println("[Controller] 未知消息类型: " + type);
```

**修改为** — 新增 TOGGLE_OBSTACLE case

```java
case "TOGGLE_OBSTACLE" -> {
    if (data != null) {
        int row = data.getIntValue("row");
        int col = data.getIntValue("col");
        boolean current = bb.isBlocked(row, col);
        bb.setBlock(row, col, !current);
        System.out.println("[Controller] 障碍物 " + (!current ? "新增" : "移除") + "(" + col + "," + row + ")");
    }
}
default -> System.out.println("[Controller] 未知消息类型: " + type);
```

**同步修改** — `ControllerMain.start()` 中 CommandHandler 构造调用（约第 45 行）

```java
// 当前
CommandHandler handler = new CommandHandler(bus, dispatcher, scheduler);

// 改为
CommandHandler handler = new CommandHandler(bus, dispatcher, scheduler, bb);
```
