/**
 * 变电站巡检仿真系统 — 前端核心逻辑。
 *
 * 职责：
 *   1. WebSocket 客户端（连接 / 重连 / 消息收发）
 *   2. Canvas 五层渲染（网格 → 探索 → 障碍 → 路径 → 小车）
 *   3. 车辆状态卡 + 步数排行榜 + 全局信息
 *   4. 控制面板交互（配置表单 + 开始/暂停/重置按钮）
 *   5. 路径回放模式（时间轴滑块 + 逐帧播放）
 *
 * 设计依据：开发计划.md §4.2（Canvas 分层渲染 + 暗色工业风配色）
 */

(function () {
  'use strict';

  // ═══════════════════════════════════════════════════════════
  // 常量
  // ═══════════════════════════════════════════════════════════

  /** 每格像素尺寸（30 格 × 27px = 810px 画布） */
  var CELL_SIZE = 27;

  /** 默认地图尺寸 */
  var DEFAULT_GRID = 30;

  /** WebSocket 重连间隔（毫秒） */
  var RECONNECT_DELAY_MS = 3000;

  /** 路径线最多绘制步数 */
  var MAX_ROUTE_DRAW = 20;

  /** 回放默认播放速度：每帧间隔（毫秒） */
  var REPLAY_FRAME_MS = 200;

  // ═══════════════════════════════════════════════════════════
  // 小车固定颜色（按索引：红、紫、粉、绿、蓝）
  // ═══════════════════════════════════════════════════════════

  var CAR_COLORS = ['#FF0000', '#800080', '#FF69B4', '#00CC00', '#0066FF'];

  // ═══════════════════════════════════════════════════════════
  // 状态色映射（保留用于状态面板和回放）
  // ═══════════════════════════════════════════════════════════

  var STATUS_COLORS = {
    IDLE:           '#9E9E9E',
    WAITING_ROUTE:  '#FF9800',
    READY:          '#4CAF50',
    MOVING:         '#2196F3',
    BLOCKED:        '#F44336'
  };

  var STATUS_NAMES = {
    IDLE:           '空闲',
    WAITING_ROUTE:  '等待路径',
    READY:          '就绪',
    MOVING:         '移动中',
    BLOCKED:        '受阻'
  };

  // ═══════════════════════════════════════════════════════════
  // DOM 引用（一次性获取，避免重复 querySelector）
  // ═══════════════════════════════════════════════════════════

  var mapCanvas = document.getElementById('map-canvas');
  var mapCtx    = mapCanvas.getContext('2d');
  var carCanvas = document.getElementById('car-canvas');
  var carCtx    = carCanvas.getContext('2d');

  /** 地图层是否需要重绘（障碍物变化时设为 true） */
  var mapLayerDirty = true;

  var $tick       = document.getElementById('info-tick');
  var $rate       = document.getElementById('info-rate');
  var $elapsed    = document.getElementById('info-elapsed');
  var $modeTag    = document.getElementById('info-mode');
  var $carsPanel  = document.getElementById('cars-panel');
  var $leaderboard = document.getElementById('leaderboard');

  var $btnStart   = document.getElementById('btn-start');
  var $btnPause   = document.getElementById('btn-pause');
  var $btnReset   = document.getElementById('btn-reset');
  var $btnAddCar  = document.getElementById('btn-addcar');
  var $btnReplay  = document.getElementById('btn-replay');
  var $btnLive    = document.getElementById('btn-live');

  var $replayControls = document.getElementById('replay-controls');
  var $replaySlider   = document.getElementById('replay-slider');
  var $replayTickLbl  = document.getElementById('replay-tick-label');
  var $replayToggle   = document.getElementById('replay-toggle');

  var $cfgWidth        = document.getElementById('cfg-width');
  var $cfgHeight       = document.getElementById('cfg-height');
  var $cfgCarCount     = document.getElementById('cfg-carCount');
  var $cfgObstacleRatio = document.getElementById('cfg-obstacleRatio');
  var $cfgAlgorithm    = document.getElementById('cfg-algorithm');
  var $cfgTickInterval = document.getElementById('cfg-tickInterval');

  var $lblObstacleRatio = document.getElementById('lbl-obstacleRatio');
  var $lblTickInterval  = document.getElementById('lbl-tickInterval');

  // ═══════════════════════════════════════════════════════════
  // 运行时状态
  // ═══════════════════════════════════════════════════════════

  /** WebSocket 实例 */
  var ws = null;

  /** 最新收到的实时数据（SimulationState JSON 解析结果） */
  var liveData = null;

  /** 当前模式：'live' | 'replay' */
  var mode = 'live';

  /** 回放模式专用状态 */
  var replay = {
    histories: {},     // { carId: [{x, y, tick}, ...] }
    minTick: 0,
    maxTick: 0,
    currentTick: 0,
    playing: false,
    timerId: null
  };

  /** 耗时计时器 ID */
  var elapsedTimerId = null;

  /** 任务开始时刻的本地时间戳（用于算耗时） */
  var startTimestamp = null;

  // ── 平滑移动动画 ──

  /** 每步动画时长（ms） */
  var ANIM_DURATION = 350;

  /** 动画队列: { carId: [{x,y}, {x,y}, ...] } 逐格移动 */
  var animQueue = {};

  /** 当前动画起点: { carId: {x, y} } */
  var animFrom = {};

  /** 当前渲染位置: { carId: {x, y} } */
  var animCurrent = {};

  /** 动画帧 ID */
  var animFrameId = null;

  /** 每一步动画开始时的时间戳 */
  var animStepStart = 0;

  /** 小车走过的格子 */
  var visitedCells = {};

  // ═══════════════════════════════════════════════════════════
  // WebSocket 连接管理
  // ═══════════════════════════════════════════════════════════

  function connectWebSocket() {
    ws = new WebSocket('ws://localhost:8888');
    ws.onopen  = onSocketOpen;
    ws.onmessage = onSocketMessage;
    ws.onclose = onSocketClose;
    ws.onerror = onSocketError;
  }

  function onSocketOpen() {
    console.log('[app] WebSocket 已连接');
  }

  function onSocketMessage(event) {
    liveData = JSON.parse(event.data);
    if (mode === 'live') {
      var w = (liveData.taskConfig && parseInt(liveData.taskConfig.mapWidth, 10))  || DEFAULT_GRID;
      var h = (liveData.taskConfig && parseInt(liveData.taskConfig.mapHeight, 10)) || DEFAULT_GRID;
      var needResize = (mapCanvas.width !== Math.max(w, h) * CELL_SIZE);
      if (needResize) {
        initCanvasSize();
      }
      startCarAnimations(liveData);
      renderLive();
    }
    if (mode === 'replay') {
      collectHistories(liveData);
    }
  }

  /** Build path from current to target one cell at a time (no diagonal) */
  function buildStepPath(fromX, fromY, toX, toY) {
    var steps = [];
    var cx = fromX, cy = fromY;
    // Move X first, then Y
    while (cx !== toX) {
      cx += (toX > cx) ? 1 : -1;
      steps.push({ x: cx, y: cy });
    }
    while (cy !== toY) {
      cy += (toY > cy) ? 1 : -1;
      steps.push({ x: cx, y: cy });
    }
    return steps;
  }

  /** Detect car position changes and enqueue step-by-step animation */
  function startCarAnimations(data) {
    if (!data.cars) { return; }
    var hasNew = false;

    for (var i = 0; i < data.cars.length; i++) {
      var car = data.cars[i];
      if (!car.position) { continue; }
      var id = car.carId;
      var nx = car.position.x;
      var ny = car.position.y;

      if (!animCurrent[id]) {
        animCurrent[id] = { x: nx, y: ny };
        animFrom[id] = { x: nx, y: ny };
      }

      var prev = animFrom[id];
      if (prev.x !== nx || prev.y !== ny) {
        // Build steps from current render position (not snapped)
        var cur = animCurrent[id] || prev;
        var steps = buildStepPath(cur.x, cur.y, nx, ny);
        // Append to existing queue (格子变色延迟到动画走完时)
        var q = animQueue[id] || [];
        for (var si = 0; si < steps.length; si++) {
          q.push(steps[si]);
        }
        animQueue[id] = q;
        animFrom[id] = { x: nx, y: ny };
        hasNew = true;
      }
    }

    if (hasNew && !animFrameId) {
      animStepStart = performance.now();
      animFrameId = requestAnimationFrame(animateCars);
    }
  }

  /** Animation loop: one step per car queue, timed per-step */
  function animateCars(timestamp) {
    var alive = false;
    var stepAdvanced = false;

    for (var id in animQueue) {
      var q = animQueue[id];
      if (!q || q.length === 0) { continue; }
      alive = true;

      var target = q[0];
      var from = animCurrent[id];
      if (!from) {
        animCurrent[id] = { x: target.x, y: target.y };
        from = animCurrent[id];
      }

      var elapsed = timestamp - animStepStart;
      var progress = Math.min(elapsed / ANIM_DURATION, 1.0);
      var t = 1 - Math.pow(1 - progress, 3);

      animCurrent[id] = {
        x: from.x + (target.x - from.x) * t,
        y: from.y + (target.y - from.y) * t
      };

      if (progress >= 1.0) {
        animCurrent[id] = { x: target.x, y: target.y };
        visitedCells[target.x + ',' + target.y] = true;
        q.shift();
        stepAdvanced = true;
      }
    }

    if (stepAdvanced) {
      animStepStart = timestamp;
    }

    carCtx.clearRect(0, 0, carCanvas.width, carCanvas.height);
    if (liveData) {
      renderExploredUpdates(liveData);
      renderRoutes(liveData);
      renderCars(liveData);
    }

    if (alive) {
      animFrameId = requestAnimationFrame(animateCars);
    } else {
      animFrameId = null;
    }
  }

  function onSocketClose() {
    console.log('[app] WebSocket 断开，' + (RECONNECT_DELAY_MS / 1000) + 's 后重连');
    setTimeout(connectWebSocket, RECONNECT_DELAY_MS);
  }

  function onSocketError(err) {
    console.error('[app] WebSocket 错误', err);
  }

  // ═══════════════════════════════════════════════════════════
  // Canvas 尺寸初始化
  // ═══════════════════════════════════════════════════════════

  function initCanvasSize() {
    if (!liveData || !liveData.taskConfig) { return; }
    var w = parseInt(liveData.taskConfig.mapWidth, 10)  || DEFAULT_GRID;
    var h = parseInt(liveData.taskConfig.mapHeight, 10) || DEFAULT_GRID;
    var size = Math.max(w, h);
    var px = size * CELL_SIZE;
    mapCanvas.width  = px;
    mapCanvas.height = px;
    carCanvas.width  = px;
    carCanvas.height = px;
    mapLayerDirty = true;
  }

  // ═══════════════════════════════════════════════════════════
  // 实时渲染入口
  // ═══════════════════════════════════════════════════════════

  function renderLive() {
    var data = liveData;
    if (!data) { return; }

    // 地图层：只在标记脏时完整重绘（首次、reset、地图尺寸变化）
    if (mapLayerDirty) {
      clearMapBase(data);
      renderExplored(data);
      renderObstacles(data);
      renderGridLines(data);
      mapLayerDirty = false;
    }

    // 每 tick 补画格子（动画循环也同步画）
    renderExploredUpdates(data);

    // DOM 面板
    renderCarsPanel(data);
    renderLeaderboard(data);
    updateGlobalInfo(data);
  }

  // ═══════════════════════════════════════════════════════════
  // Canvas 分层渲染 L1-L5
  // ═══════════════════════════════════════════════════════════

  /** Clear canvas and fill dark gray base */
  function clearMapBase(data) {
    var gridW = getGridW(data);
    var gridH = getGridH(data);
    mapCtx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);
    mapCtx.fillStyle = '#D0D0D0';
    mapCtx.fillRect(0, 0, gridW * CELL_SIZE, gridH * CELL_SIZE);
  }

  /** Draw grid lines on top of everything */
  function renderGridLines(data) {
    var gridW = getGridW(data);
    var gridH = getGridH(data);
    mapCtx.strokeStyle = '#AAAAAA';
    mapCtx.lineWidth = 0.8;
    for (var i = 0; i <= gridW; i++) {
      var x = i * CELL_SIZE;
      mapCtx.beginPath(); mapCtx.moveTo(x, 0); mapCtx.lineTo(x, gridH * CELL_SIZE); mapCtx.stroke();
    }
    for (var j = 0; j <= gridH; j++) {
      var y = j * CELL_SIZE;
      mapCtx.beginPath(); mapCtx.moveTo(0, y); mapCtx.lineTo(gridW * CELL_SIZE, y); mapCtx.stroke();
    }
  }

  /** L2: 已探索区域全量绘制（仅首次或 reset 时调用） */
  function renderExplored(data) {
    for (var key in visitedCells) {
      var parts = key.split(',');
      var c = parseInt(parts[0], 10);
      var r = parseInt(parts[1], 10);
      if (data.mapBlock && data.mapBlock[r] && data.mapBlock[r][c]) { continue; }
      mapCtx.fillStyle = '#FFFFFF';
      mapCtx.fillRect(c * CELL_SIZE, r * CELL_SIZE, CELL_SIZE, CELL_SIZE);
    }
  }

  /** 增量绘制新走过的格子 */
  var drawnCount = 0;
  function renderExploredUpdates(data) {
    var keys = Object.keys(visitedCells);
    if (keys.length === drawnCount) { return; }
    // 只画新增的格子
    for (var i = drawnCount; i < keys.length; i++) {
      var parts = keys[i].split(',');
      var c = parseInt(parts[0], 10);
      var r = parseInt(parts[1], 10);
      if (data && data.mapBlock && data.mapBlock[r] && data.mapBlock[r][c]) { continue; }
      var x = c * CELL_SIZE;
      var y = r * CELL_SIZE;
      mapCtx.fillStyle = '#FFFFFF';
      mapCtx.fillRect(x, y, CELL_SIZE, CELL_SIZE);
      mapCtx.strokeStyle = '#AAAAAA';
      mapCtx.lineWidth = 0.8;
      mapCtx.strokeRect(x + 0.5, y + 0.5, CELL_SIZE, CELL_SIZE);
    }
    drawnCount = keys.length;
  }

  /** L3: 障碍物。黑色方块 */
  function renderObstacles(data) {
    if (!data.mapBlock) { return; }
    var gridW = getGridW(data);
    var gridH = getGridH(data);
    mapCtx.fillStyle = '#333333';
    for (var r = 0; r < gridH; r++) {
      if (!data.mapBlock[r]) { continue; }
      for (var c = 0; c < gridW; c++) {
        if (data.mapBlock[r][c]) {
          mapCtx.fillRect(c * CELL_SIZE, r * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }
      }
    }
  }

  /** L4: 规划路径。每车用自己颜色的半透明线，最多画 10 步 */
  function renderRoutes(data) {
    if (!data.cars) { return; }
    for (var i = 0; i < data.cars.length; i++) {
      drawRouteForCar(data.cars[i], i);
    }
  }

  function drawRouteForCar(car, idx) {
    if (!car.position) { return; }

    // 优先用动画队列（与实际运动一致），回退到后端 routeList
    var path = animQueue[car.carId];
    if (!path || path.length === 0) {
      if (!car.routeList || car.routeList.length === 0) { return; }
      path = car.routeList;
    }
    if (path.length === 0) { return; }

    var routeColor = CAR_COLORS[idx % CAR_COLORS.length];
    carCtx.strokeStyle = routeColor;
    carCtx.lineWidth = 3;
    carCtx.lineCap = 'round';
    carCtx.setLineDash([8, 6]);

    var anim = animCurrent[car.carId];
    var prevGx = anim ? anim.x : car.position.x;
    var prevGy = anim ? anim.y : car.position.y;
    var prevPx = prevGx * CELL_SIZE + CELL_SIZE / 2;
    var prevPy = prevGy * CELL_SIZE + CELL_SIZE / 2;

    // 从小车位置连到路径第一个点
    var px0 = path[0].x * CELL_SIZE + CELL_SIZE / 2;
    var py0 = path[0].y * CELL_SIZE + CELL_SIZE / 2;
    carCtx.beginPath();
    carCtx.moveTo(prevPx, prevPy);
    carCtx.lineTo(px0, py0);
    carCtx.stroke();

    var drawLen = Math.min(path.length, MAX_ROUTE_DRAW);
    for (var i = 1; i < drawLen; i++) {
      var px = path[i].x * CELL_SIZE + CELL_SIZE / 2;
      var py = path[i].y * CELL_SIZE + CELL_SIZE / 2;
      carCtx.beginPath();
      carCtx.moveTo(px0, py0);
      carCtx.lineTo(px, py);
      carCtx.stroke();
      px0 = px; py0 = py;
    }
  }

  /** L5: 小车。圆形 + 固定颜色 + 编号文字 */
  function renderCars(data) {
    if (!data.cars) { return; }
    for (var i = 0; i < data.cars.length; i++) {
      drawCar(data.cars[i], i);
    }
  }

  function drawCar(car, idx) {
    if (!car.position) { return; }
    // Use animated position if available, otherwise raw position
    var anim = animCurrent[car.carId];
    var gx = anim ? anim.x : car.position.x;
    var gy = anim ? anim.y : car.position.y;
    var cx = gx * CELL_SIZE + CELL_SIZE / 2;
    var cy = gy * CELL_SIZE + CELL_SIZE / 2;
    var color = CAR_COLORS[idx % CAR_COLORS.length];
    var radius = CELL_SIZE / 2 - 2;

    // 纯色圆形小车
    carCtx.beginPath();
    carCtx.arc(cx, cy, radius, 0, Math.PI * 2);
    carCtx.fillStyle = color;
    carCtx.fill();

    // 编号文字（白色，居中）
    carCtx.fillStyle = '#FFF';
    carCtx.font = 'bold 10px Consolas, monospace';
    carCtx.textAlign = 'center';
    carCtx.textBaseline = 'middle';
    carCtx.fillText(String(car.number), cx, cy);
  }

  // ═══════════════════════════════════════════════════════════
  // 右侧栏：车辆状态卡
  // ═══════════════════════════════════════════════════════════

  function renderCarsPanel(data) {
    if (!data.cars || data.cars.length === 0) {
      $carsPanel.innerHTML = '<div class="car-card placeholder">' +
        '<p>等待车辆数据...</p></div>';
      return;
    }
    var html = '';
    for (var i = 0; i < data.cars.length; i++) {
      html += buildCarCard(data.cars[i]);
    }
    $carsPanel.innerHTML = html;
  }

  function buildCarCard(car) {
    var statusName = STATUS_NAMES[car.status] || car.status;
    var color     = STATUS_COLORS[car.status] || '#9E9E9E';
    var posStr    = car.position ? '(' + car.position.x + ', ' + car.position.y + ')' : 'N/A';
    var targetStr = car.target   ? '(' + car.target.x   + ', ' + car.target.y   + ')' : '无';
    var stepsPct  = Math.min(100, car.steps);

    return '<div class="car-card">' +
      '<div class="car-header">' +
        '<span class="car-number">Car ' + car.number + '</span>' +
        '<span class="car-status-tag" style="background:' + color + '">' +
          statusName + '</span>' +
      '</div>' +
      '<div class="car-detail">' +
        '<div>位置: ' + posStr + '</div>' +
        '<div>目标: ' + targetStr + '</div>' +
        '<div>步数: ' + car.steps + '</div>' +
        '<div class="steps-bar-wrap">' +
          '<div class="steps-bar-fill" style="width:' + stepsPct + '%"></div>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  // ═══════════════════════════════════════════════════════════
  // 左侧栏：步数排行榜
  // ═══════════════════════════════════════════════════════════

  function renderLeaderboard(data) {
    if (!data.cars || data.cars.length === 0) {
      $leaderboard.innerHTML = '';
      return;
    }
    var sorted = data.cars.slice().sort(function (a, b) {
      return b.steps - a.steps;
    });
    var html = '';
    for (var i = 0; i < sorted.length; i++) {
      html += '<li>Car ' + sorted[i].number + ': ' + sorted[i].steps + ' 步</li>';
    }
    $leaderboard.innerHTML = html;
  }

  // ═══════════════════════════════════════════════════════════
  // 顶栏：全局信息
  // ═══════════════════════════════════════════════════════════

  function updateGlobalInfo(data) {
    $tick.textContent = '节拍: ' + (data.tick || 0);
    $rate.textContent = '探索率: ' + (data.explorationRate || 0) + '%';

    // 第一个有效 tick 时启动耗时计时器
    if (data.tick === 1 && !startTimestamp) {
      startTimestamp = Date.now();
      startElapsedTimer();
    }
    // 探索完成时停止计时 + 提示
    if (data.explorationRate >= 99 && elapsedTimerId) {
      stopElapsedTimer();
      $modeTag.textContent = '✓ 任务完成';
      $modeTag.style.color = '#3fb950';
      $modeTag.hidden = false;
    }
  }

  function startElapsedTimer() {
    elapsedTimerId = setInterval(function () {
      var elapsed = Math.floor((Date.now() - startTimestamp) / 1000);
      var min = String(Math.floor(elapsed / 60)).padStart(2, '0');
      var sec = String(elapsed % 60).padStart(2, '0');
      $elapsed.textContent = '⏱ ' + min + ':' + sec;
    }, 1000);
  }

  function stopElapsedTimer() {
    clearInterval(elapsedTimerId);
    elapsedTimerId = null;
  }

  // ═══════════════════════════════════════════════════════════
  // 控制面板交互
  // ═══════════════════════════════════════════════════════════

  function sendCommand(msg) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  }

  function onStartClick() {
    startTimestamp = null;
    if (elapsedTimerId) { stopElapsedTimer(); }

    var config = {
      type: 'SET_CONFIG',
      data: {
        mapWidth:      String($cfgWidth.value),
        mapHeight:     String($cfgHeight.value),
        carCount:      String($cfgCarCount.value),
        obstacleRatio: String($cfgObstacleRatio.value),
        algorithm:     $cfgAlgorithm.value,
        tickInterval:  String($cfgTickInterval.value),
        active:        'true'
      }
    };
    sendCommand(config);
    $btnStart.disabled = true;
    $btnPause.disabled = false;
  }

  function onPauseClick() {
    sendCommand({ type: 'TOGGLE_PAUSE' });
    var label = $btnPause.textContent;
    $btnPause.textContent = (label.indexOf('暂停') !== -1) ? '▶ 继续' : '⏯ 暂停';
  }

  function clearCanvas() {
    mapCtx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);
    mapCtx.fillStyle = '#D0D0D0';
    mapCtx.fillRect(0, 0, mapCanvas.width, mapCanvas.height);
    carCtx.clearRect(0, 0, carCanvas.width, carCanvas.height);

    $carsPanel.innerHTML = '<div class="car-card placeholder"><p>等待车辆数据...</p></div>';
    $leaderboard.innerHTML = '';
    $tick.textContent = '节拍: 0';
    $rate.textContent = '探索率: 0%';
    $elapsed.textContent = '⏱ 00:00';
    $modeTag.hidden = true;

    // Clear all state
    visitedCells = {};
    drawnCount = 0;
    animQueue = {};
    animCurrent = {};
    animFrom = {};
    replay.histories = {};
    mapLayerDirty = true;
  }

  function onResetClick() {
    sendCommand({ type: 'RESET' });
    $btnStart.disabled  = false;
    $btnPause.disabled  = true;
    $btnPause.textContent = '⏯ 暂停';
    if (elapsedTimerId) { stopElapsedTimer(); }
    startTimestamp = null;
    clearCanvas();
    if (mode === 'replay') { exitReplay(); }
  }

  function onAddCarClick() {
    sendCommand({ type: 'ADD_CAR' });
  }

  // ────────── 滑块实时反馈 ──────────

  function onObstacleRatioInput() {
    $lblObstacleRatio.textContent = Math.round($cfgObstacleRatio.value * 100) + '%';
  }

  function onTickIntervalInput() {
    $lblTickInterval.textContent = $cfgTickInterval.value + 'ms';
  }

  function onTickIntervalChange() {
    sendCommand({
      type: 'SET_TICK_INTERVAL',
      data: { interval: parseInt($cfgTickInterval.value, 10) }
    });
  }

  // ═══════════════════════════════════════════════════════════
  // 路径回放
  // ═══════════════════════════════════════════════════════════

  function collectHistories(data) {
    if (!data.cars) { return; }
    for (var i = 0; i < data.cars.length; i++) {
      var car = data.cars[i];
      if (!car.position) { continue; }
      if (!replay.histories[car.carId]) {
        replay.histories[car.carId] = [];
      }
      var entry = { x: car.position.x, y: car.position.y, tick: data.tick };
      var arr = replay.histories[car.carId];
      // 避免同 tick 重复记录
      if (arr.length === 0 || arr[arr.length - 1].tick !== data.tick) {
        arr.push(entry);
      }
    }
  }

  function enterReplay() {
    // 基于已收集的 histories 确定时间轴范围
    replay.minTick = Number.MAX_VALUE;
    replay.maxTick = 0;
    var hasData = false;
    for (var carId in replay.histories) {
      var arr = replay.histories[carId];
      if (arr.length > 0) {
        hasData = true;
        replay.minTick = Math.min(replay.minTick, arr[0].tick);
        replay.maxTick = Math.max(replay.maxTick, arr[arr.length - 1].tick);
      }
    }
    if (!hasData) {
      alert('暂无历史数据可回放，请先运行一段时间');
      return;
    }

    mode = 'replay';
    replay.currentTick = replay.minTick;
    replay.playing = false;

    $btnReplay.hidden = true;
    $btnLive.hidden   = false;
    $replayControls.hidden = false;
    $modeTag.hidden = false;

    $replaySlider.min = replay.minTick;
    $replaySlider.max = replay.maxTick;
    $replaySlider.value = replay.minTick;
    updateReplayLabel();
    renderReplayFrame();
  }

  function exitReplay() {
    mode = 'replay';
    stopReplayTimer();
    mode = 'live';

    $btnReplay.hidden = false;
    $btnLive.hidden   = true;
    $replayControls.hidden = true;
    $modeTag.hidden = true;

    // 恢复实时渲染
    if (liveData) { renderLive(); }
  }

  function renderReplayFrame() {
    // 查表：找到当前 tick 每台车的位置
    if (!liveData) { return; }

    // 用 liveData 的结构做模板，替换 car 位置
    var frame = {
      tick: replay.currentTick,
      explorationRate: liveData.explorationRate,
      taskConfig: liveData.taskConfig,
      mapView: liveData.mapView,
      mapBlock: liveData.mapBlock,
      cars: []
    };

    for (var i = 0; i < liveData.cars.length; i++) {
      var carTemplate = liveData.cars[i];
      var pos = lookupReplayPosition(carTemplate.carId, replay.currentTick);
      frame.cars.push({
        carId:     carTemplate.carId,
        number:    carTemplate.number,
        position:  pos,
        target:    carTemplate.target,
        routeList: [],               // 回放不显示路径
        status:    pos ? 'MOVING' : 'IDLE',
        steps:     carTemplate.steps
      });
    }

    if (mapLayerDirty) {
      clearMapBase(frame);
      renderObstacles(frame);
      renderGridLines(frame);
      mapLayerDirty = false;
    }
    carCtx.clearRect(0, 0, carCanvas.width, carCanvas.height);
    renderCars(frame);
    updateReplayLabel();
  }

  function lookupReplayPosition(carId, tick) {
    var arr = replay.histories[carId];
    if (!arr || arr.length === 0) { return null; }
    // 找到 ≤ tick 的最后一条记录
    var best = null;
    for (var i = 0; i < arr.length; i++) {
      if (arr[i].tick <= tick) {
        best = arr[i];
      } else {
        break;
      }
    }
    return best ? { x: best.x, y: best.y } : null;
  }

  function updateReplayLabel() {
    $replayTickLbl.textContent =
      'Tick ' + replay.currentTick + ' / ' + replay.maxTick;
    $replaySlider.value = replay.currentTick;
  }

  function startReplayTimer() {
    stopReplayTimer();
    replay.playing = true;
    $replayToggle.textContent = '⏸';  // ⏸
    replay.timerId = setInterval(replayTickForward, REPLAY_FRAME_MS);
  }

  function stopReplayTimer() {
    replay.playing = false;
    $replayToggle.textContent = '▶';  // ▶
    if (replay.timerId) {
      clearInterval(replay.timerId);
      replay.timerId = null;
    }
  }

  function replayTickForward() {
    if (replay.currentTick < replay.maxTick) {
      replay.currentTick++;
      $replaySlider.value = replay.currentTick;
      renderReplayFrame();
    } else {
      stopReplayTimer();
    }
  }

  // ────────── 回放按钮事件 ──────────

  function onReplayClick() {
    enterReplay();
  }

  function onLiveClick() {
    exitReplay();
  }

  function onReplaySliderInput() {
    replay.currentTick = parseInt($replaySlider.value, 10);
    renderReplayFrame();
  }

  function onReplayToggleClick() {
    if (replay.playing) {
      stopReplayTimer();
    } else {
      startReplayTimer();
    }
  }

  function onReplayStepPrev() {
    replay.currentTick = Math.max(replay.minTick, replay.currentTick - 1);
    renderReplayFrame();
  }

  function onReplayStepNext() {
    replay.currentTick = Math.min(replay.maxTick, replay.currentTick + 1);
    renderReplayFrame();
  }

  // ═══════════════════════════════════════════════════════════
  // 工具函数
  // ═══════════════════════════════════════════════════════════

  // ═══════════════════════════════════════════════════════════════
  // Canvas 右键：手动切换障碍物（运行时可增删）
  // ═══════════════════════════════════════════════════════════════

  function onCanvasContextMenu(e) {
    e.preventDefault();
    if (!liveData) { return; }

    var rect = mapCanvas.getBoundingClientRect();
    var scaleX = mapCanvas.width / rect.width;
    var scaleY = mapCanvas.height / rect.height;
    var col = Math.floor((e.clientX - rect.left) * scaleX / CELL_SIZE);
    var row = Math.floor((e.clientY - rect.top)  * scaleY / CELL_SIZE);

    var gridW = getGridW(liveData);
    var gridH = getGridH(liveData);
    if (row < 0 || row >= gridH || col < 0 || col >= gridW) { return; }

    sendCommand({
      type: 'TOGGLE_OBSTACLE',
      data: { row: row, col: col }
    });
  }

  function getGridW(data) {
    return (data.taskConfig && parseInt(data.taskConfig.mapWidth, 10)) || DEFAULT_GRID;
  }

  function getGridH(data) {
    return (data.taskConfig && parseInt(data.taskConfig.mapHeight, 10)) || DEFAULT_GRID;
  }

  // ═══════════════════════════════════════════════════════════
  // 事件绑定
  // ═══════════════════════════════════════════════════════════

  $btnStart.addEventListener('click', onStartClick);
  $btnPause.addEventListener('click', onPauseClick);
  $btnReset.addEventListener('click', onResetClick);
  if ($btnAddCar) {
    $btnAddCar.addEventListener('click', onAddCarClick);
  }
  mapCanvas.addEventListener('contextmenu', onCanvasContextMenu);
  $btnReplay.addEventListener('click', onReplayClick);
  $btnLive.addEventListener('click', onLiveClick);

  $cfgObstacleRatio.addEventListener('input', onObstacleRatioInput);
  $cfgTickInterval.addEventListener('input', onTickIntervalInput);
  $cfgTickInterval.addEventListener('change', onTickIntervalChange);

  $replaySlider.addEventListener('input', onReplaySliderInput);
  $replayToggle.addEventListener('click', onReplayToggleClick);
  document.getElementById('replay-step-prev').addEventListener('click', onReplayStepPrev);
  document.getElementById('replay-step-next').addEventListener('click', onReplayStepNext);

  // ═══════════════════════════════════════════════════════════
  // 启动
  // ═══════════════════════════════════════════════════════════

  connectWebSocket();
})();
