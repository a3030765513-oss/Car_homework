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

  /** 路径线最多绘制步数（避免 Canvas 被长路径拖慢） */
  var MAX_ROUTE_DRAW = 10;

  /** 回放默认播放速度：每帧间隔（毫秒） */
  var REPLAY_FRAME_MS = 200;

  // ═══════════════════════════════════════════════════════════
  // 状态色映射（与 CarStatus 枚举保持一致）
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

  var canvas   = document.getElementById('map-canvas');
  var ctx      = canvas.getContext('2d');

  var $tick       = document.getElementById('info-tick');
  var $rate       = document.getElementById('info-rate');
  var $elapsed    = document.getElementById('info-elapsed');
  var $modeTag    = document.getElementById('info-mode');
  var $carsPanel  = document.getElementById('cars-panel');
  var $leaderboard = document.getElementById('leaderboard');

  var $btnStart   = document.getElementById('btn-start');
  var $btnPause   = document.getElementById('btn-pause');
  var $btnReset   = document.getElementById('btn-reset');
  var $btnUnity   = document.getElementById('btn-unity');
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
    currentTick: 0,
    maxTick: 0,
    playing: false,
    timerId: null
  };

  /** 从Redis加载的回放数据（REQUEST_REPLAY响应） */
  var replayData = null;

  /** 耗时计时器 ID */
  var elapsedTimerId = null;

  /** 任务开始时刻的本地时间戳（用于算耗时） */
  var startTimestamp = null;

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

  var wasEverConnected = false;

  function onSocketOpen() {
    console.log('[app] WebSocket 已连接');
    if (wasEverConnected) {
      // 重连后自动重置，恢复系统状态
      ws.send(JSON.stringify({ type: 'RESET', data: {} }));
      console.log('[app] 检测到重连，已发送 RESET');
    }
  }

  function onSocketMessage(event) {
    wasEverConnected = true;
    var msg = JSON.parse(event.data);
    if (msg.type === 'REPLAY_DATA') {
      receiveReplayData(msg);
      return;
    }
    liveData = msg;
    if (mode === 'live') {
      initCanvasSize();
      renderLive();
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
    canvas.width  = size * CELL_SIZE;
    canvas.height = size * CELL_SIZE;
  }

  // ═══════════════════════════════════════════════════════════
  // 实时渲染入口
  // ═══════════════════════════════════════════════════════════

  function renderLive() {
    var data = liveData;
    if (!data) { return; }
    renderGrid(data);
    renderExplored(data);
    renderObstacles(data);
    renderRoutes(data);
    renderCars(data);
    renderCarsPanel(data);
    renderLeaderboard(data);
    updateGlobalInfo(data);
  }

  // ═══════════════════════════════════════════════════════════
  // Canvas 分层渲染 L1-L5
  // ═══════════════════════════════════════════════════════════

  /** L1: 网格背景。先清空画布，再画 30×30 网格线 */
  function renderGrid(data) {
    var gridW = getGridW(data);
    var gridH = getGridH(data);
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // 未探索区域底色
    ctx.fillStyle = '#0f0f23';
    ctx.fillRect(0, 0, gridW * CELL_SIZE, gridH * CELL_SIZE);

    // 网格线
    ctx.strokeStyle = '#2a2a4a';
    ctx.lineWidth = 0.5;
    for (var i = 0; i <= gridW; i++) {
      var x = i * CELL_SIZE;
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, gridH * CELL_SIZE); ctx.stroke();
    }
    for (var j = 0; j <= gridH; j++) {
      var y = j * CELL_SIZE;
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(gridW * CELL_SIZE, y); ctx.stroke();
    }
  }

  /** L2: 已探索区域。遍历 mapView bitmap，点亮已探索格 */
  function renderExplored(data) {
    if (!data.mapView) { return; }
    var gridW = getGridW(data);
    var gridH = getGridH(data);
    ctx.fillStyle = '#16213e';
    for (var r = 0; r < gridH; r++) {
      if (!data.mapView[r]) { continue; }
      for (var c = 0; c < gridW; c++) {
        if (data.mapView[r][c]) {
          ctx.fillRect(c * CELL_SIZE, r * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }
      }
    }
  }

  /** L3: 障碍物。跳过有车格子，避免小车背上红色方块 */
  function renderObstacles(data) {
    if (!data.mapBlock) { return; }

    var occupied = {};
    if (data.cars) {
      for (var i = 0; i < data.cars.length; i++) {
        var p = data.cars[i].position;
        occupied[p.x + ',' + p.y] = true;
      }
    }

    var gridW = getGridW(data);
    var gridH = getGridH(data);
    ctx.fillStyle = '#e94560';
    for (var r = 0; r < gridH; r++) {
      if (!data.mapBlock[r]) { continue; }
      for (var c = 0; c < gridW; c++) {
        if (data.mapBlock[r][c] && !occupied[c + ',' + r]) {
          ctx.fillRect(c * CELL_SIZE, r * CELL_SIZE, CELL_SIZE, CELL_SIZE);
        }
      }
    }
  }

  /** L4: 规划路径。半透明蓝色折线，每车最多画 10 步 */
  function renderRoutes(data) {
    if (!data.cars) { return; }
    for (var i = 0; i < data.cars.length; i++) {
      drawRouteForCar(data.cars[i]);
    }
  }

  function drawRouteForCar(car) {
    if (!car.position || !car.routeList || car.routeList.length === 0) { return; }

    ctx.strokeStyle = 'rgba(100, 200, 255, 0.6)';
    ctx.lineWidth = 2;
    ctx.lineCap = 'round';

    // 起点：小车当前格子中心
    var prevGx = car.position.x;
    var prevGy = car.position.y;
    var prevPx = prevGx * CELL_SIZE + CELL_SIZE / 2;
    var prevPy = prevGy * CELL_SIZE + CELL_SIZE / 2;

    var steps = Math.min(car.routeList.length, MAX_ROUTE_DRAW);
    for (var i = 0; i < steps; i++) {
      var gx = car.routeList[i].x;
      var gy = car.routeList[i].y;

      // 仅当曼哈顿距离 = 1（四方向相邻）时画线段，防止跨格斜线
      var manhattan = Math.abs(gx - prevGx) + Math.abs(gy - prevGy);
      if (manhattan === 1) {
        var px = gx * CELL_SIZE + CELL_SIZE / 2;
        var py = gy * CELL_SIZE + CELL_SIZE / 2;
        ctx.beginPath();
        ctx.moveTo(prevPx, prevPy);
        ctx.lineTo(px, py);
        ctx.stroke();
      }

      prevGx = gx;
      prevGy = gy;
      prevPx = prevGx * CELL_SIZE + CELL_SIZE / 2;
      prevPy = prevGy * CELL_SIZE + CELL_SIZE / 2;
    }
  }

  /** L5: 小车。圆形 + 状态颜色 + 编号文字 */
  function renderCars(data) {
    if (!data.cars) { return; }
    for (var i = 0; i < data.cars.length; i++) {
      drawCar(data.cars[i]);
    }
  }

  function drawCar(car) {
    if (!car.position) { return; }
    var cx = car.position.x * CELL_SIZE + CELL_SIZE / 2;
    var cy = car.position.y * CELL_SIZE + CELL_SIZE / 2;
    var color = STATUS_COLORS[car.status] || '#9E9E9E';
    var outerR = CELL_SIZE / 2 - 1;   // 外圈（状态色），半径与格子留 1px 间距
    var innerR = outerR - 3;           // 内圈（白色），与外圈差 3px

    // 外圈 —— 状态颜色环
    ctx.beginPath();
    ctx.arc(cx, cy, outerR, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();

    // 内圈 —— 白色填充，与外圈形成对比
    ctx.beginPath();
    ctx.arc(cx, cy, innerR, 0, Math.PI * 2);
    ctx.fillStyle = '#FFFFFF';
    ctx.fill();

    // 编号文字（黑色，白色内圈上可读）
    ctx.fillStyle = '#000';
    ctx.font = 'bold 9px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(car.number), cx, cy);
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
    // 动态更新车辆数量显示
    if (data.cars) { $cfgCarCount.value = data.cars.length; }

    // 第一个有效 tick 时启动耗时计时器
    if (data.tick === 1 && !startTimestamp) {
      startTimestamp = Date.now();
      startElapsedTimer();
    }
    // 探索完成时停止计时并显示完成提示
    if (data.explorationRate >= 99) {
      $rate.textContent = '探索率: ' + (data.explorationRate || 0) + '% ✓ 任务完成';
      $modeTag.textContent = '✓ 任务完成';
      $modeTag.hidden = false;
      if (elapsedTimerId) { stopElapsedTimer(); }
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

  function onResetClick() {
    sendCommand({ type: 'RESET' });
    $btnStart.disabled  = false;
    $btnPause.disabled  = true;
    $btnPause.textContent = '⏯ 暂停';
    if (elapsedTimerId) { stopElapsedTimer(); }
    startTimestamp = null;
    $elapsed.textContent = '⏱ 00:00';
    // 如果正在回放，返回实时
    if (mode === 'replay') { exitReplay(); }
    // 清空地图数据，等待下一次开始
    liveData = null;
    replayData = null;
    replay.currentTick = 0;
    replay.maxTick = 0;
    initCanvasSize();
    clearCanvas();
  }

  function clearCanvas() {
    var canvas = document.getElementById('map-canvas');
    if (canvas) {
      var ctx = canvas.getContext('2d');
      ctx.fillStyle = '#0f0f23';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
    }
    $carsPanel.innerHTML = '<div class="car-card placeholder"><p>等待车辆数据...</p></div>';
    $leaderboard.innerHTML = '';
    $rate.textContent = '探索率: 0%';
    $tick.textContent = '节拍: 0';
    $modeTag.hidden = true;
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
  // 路径回放（基于Redis存储的完整历史数据）
  // ═══════════════════════════════════════════════════════════

  function receiveReplayData(msg) {
    replayData = msg;
    replay.maxTick = msg.maxTick || 0;
    replay.currentTick = 0;
    replay.playing = false;

    // 构建每辆车的tick→位置索引（提升回放查表性能）
    replayData._carIndex = {};
    var histories = msg.carHistories || {};
    for (var carId in histories) {
      var arr = histories[carId];
      if (!Array.isArray(arr)) { arr = []; }
      var parsed = [];
      for (var i = 0; i < arr.length; i++) {
        var e = (typeof arr[i] === 'string') ? JSON.parse(arr[i]) : arr[i];
        parsed.push({ x: e.x, y: e.y, tick: e.tick });
      }
      replayData._carIndex[carId] = parsed;
    }

    // 构建tick→mapView索引：从explorationEvents按tick累积
    var events = msg.explorationEvents || [];
    if (!Array.isArray(events)) { events = []; }
    var w = msg.mapWidth || 30;
    var h = msg.mapHeight || 30;
    replayData._tickViews = [];
    var currentView = createEmptyView(w, h);
    replayData._tickViews[0] = cloneView(currentView);
    var eventIdx = 0;
    // 对tick排序（event格式："tick,row,col"）
    var parsedEvents = [];
    for (var i = 0; i < events.length; i++) {
      var parts = (typeof events[i] === 'string') ? events[i].split(',') : [events[i].tick, events[i].row, events[i].col];
      parsedEvents.push({ tick: parseInt(parts[0], 10), row: parseInt(parts[1], 10), col: parseInt(parts[2], 10) });
    }
    parsedEvents.sort(function(a, b) { return a.tick - b.tick; });

    for (var tick = 1; tick <= replay.maxTick; tick++) {
      while (eventIdx < parsedEvents.length && parsedEvents[eventIdx].tick <= tick) {
        var ev = parsedEvents[eventIdx];
        if (ev.row >= 0 && ev.row < h && ev.col >= 0 && ev.col < w) {
          currentView[ev.row][ev.col] = true;
        }
        eventIdx++;
      }
      replayData._tickViews[tick] = cloneView(currentView);
    }
    replayData._mapBlock = msg.mapBlock || [];

    mode = 'replay';
    replay.playing = false;
    $btnReplay.hidden = true;
    $btnLive.hidden = false;
    $replayControls.hidden = false;
    $modeTag.hidden = false;
    $modeTag.textContent = '回放';
    $replaySlider.min = 0;
    $replaySlider.max = replay.maxTick;
    $replaySlider.value = 0;
    updateReplayLabel();
    renderReplayFrame();
  }

  function createEmptyView(w, h) {
    var view = [];
    for (var r = 0; r < h; r++) {
      view[r] = [];
      for (var c = 0; c < w; c++) {
        view[r][c] = false;
      }
    }
    return view;
  }

  function cloneView(view) {
    return view.map(function(row) { return row.slice(); });
  }

  function enterReplay() {
    if (replayData && replayData.maxTick > 0) {
      // 已有数据，直接进入回放
      replay.currentTick = 0;
      replay.playing = false;
      mode = 'replay';
      $btnReplay.hidden = true;
      $btnLive.hidden = false;
      $replayControls.hidden = false;
      $modeTag.hidden = false;
      $modeTag.textContent = '回放';
      $replaySlider.min = 0;
      $replaySlider.max = replay.maxTick;
      $replaySlider.value = 0;
      updateReplayLabel();
      renderReplayFrame();
      return;
    }
    // 向服务器请求回放数据
    $modeTag.hidden = false;
    $modeTag.textContent = '加载回放数据...';
    ws.send(JSON.stringify({ type: 'REQUEST_REPLAY' }));
  }

  function exitReplay() {
    mode = 'replay';
    stopReplayTimer();
    mode = 'live';

    $btnReplay.hidden = false;
    $btnLive.hidden   = true;
    $replayControls.hidden = true;
    $modeTag.hidden = true;

    if (liveData) { renderLive(); }
  }

  function renderReplayFrame() {
    if (!replayData) { return; }
    var tick = replay.currentTick;
    var mapView = (replayData._tickViews && replayData._tickViews[tick]) || replayData._tickViews[0] || createEmptyView(replayData.mapWidth || 30, replayData.mapHeight || 30);

    var frame = {
      tick: tick,
      explorationRate: 0,
      taskConfig: { mapWidth: String(replayData.mapWidth || 30), mapHeight: String(replayData.mapHeight || 30) },
      mapView: mapView,
      mapBlock: replayData._mapBlock || [],
      cars: []
    };

    var index = replayData._carIndex || {};
    for (var carId in index) {
      var pos = lookupReplayPosition(carId, tick);
      if (pos) {
        var num = carId.replace('Car', '');
        frame.cars.push({
          carId: carId,
          number: parseInt(num, 10) || 0,
          position: pos,
          target: null,
          routeList: [],
          status: 'MOVING',
          steps: 0
        });
      }
    }

    renderGrid(frame);
    renderExplored(frame);
    renderObstacles(frame);
    renderCars(frame);
    renderCarsPanel(frame);
    updateReplayLabel();
  }

  function lookupReplayPosition(carId, tick) {
    var arr = replayData && replayData._carIndex && replayData._carIndex[carId];
    if (!arr || arr.length === 0) { return null; }
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
    $replayToggle.textContent = '⏸';
    replay.timerId = setInterval(replayTickForward, REPLAY_FRAME_MS);
  }

  function stopReplayTimer() {
    replay.playing = false;
    $replayToggle.textContent = '▶';
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

    var rect = canvas.getBoundingClientRect();
    var scaleX = canvas.width / rect.width;
    var scaleY = canvas.height / rect.height;
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
  $btnUnity.addEventListener('click', onUnityClick);
  if ($btnAddCar) {
    $btnAddCar.addEventListener('click', onAddCarClick);
  }
  canvas.addEventListener('contextmenu', onCanvasContextMenu);
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

  // ═══════════════════════════════════════════════════════════
  // Unity 3D 视图切换
  // ═══════════════════════════════════════════════════════════

  function onUnityClick() {
    var canvas  = document.getElementById('map-canvas');
    var iframe  = document.getElementById('unity-frame');
    var is3D    = iframe.style.display !== 'none';

    if (is3D) {
      // 切回 2D
      iframe.style.display = 'none';
      canvas.style.display = '';
      $btnUnity.textContent = '🎮 3D视图';
    } else {
      // 切到 3D
      canvas.style.display = 'none';
      iframe.style.display = '';
      $btnUnity.textContent = '📐 2D视图';
    }
  }
})();
