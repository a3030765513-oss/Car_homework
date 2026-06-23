# Person D 修复方案

---

## 问题 1：地图中小车显示为红色块而非状态色圆圈

**问题描述**

Canvas 中小车显示为红色方块，与障碍物颜色（`#e94560`）相同。小车应显示为带状态色外圈的圆形 + 白色编号。

**原因**

前端 `app.js` 的 `renderObstacles()`（L3）在 `renderCars()`（L5）之后被调用，导致障碍物的红色覆盖了小车渲染。也可能 `renderCars()` 绘制代码未正确读取车辆状态色。

**涉及文件**

`display/src/main/resources/web/js/app.js`

**当前代码**（`render()` 或各 `render*` 调用顺序）

当前可能的渲染顺序：

```javascript
function render() {
    renderGrid();        // L1
    renderExplored();    // L2
    renderCars();        // L5 ← 错误：在 L3 前调用了
    renderObstacles();   // L3 ← 覆盖了 L5
    renderRoutes();      // L4
}
```

**修改为**

```javascript
function render() {
    renderGrid();        // L1 网格
    renderExplored();    // L2 已探索区域
    renderObstacles();   // L3 障碍物
    renderRoutes();      // L4 规划路径
    renderCars();        // L5 小车（最上层）
}
```

同时确认 `renderCars()` 正确使用了状态颜色：

```javascript
function renderCars() {
    state.cars.forEach(car => {
        const { x, y } = car.position;
        const color = STATUS_COLORS[car.status] || '#9E9E9E';  // 状态色，默认灰
        const cx = x * CELL_SIZE + CELL_SIZE / 2;
        const cy = y * CELL_SIZE + CELL_SIZE / 2;

        // 外圈 — 状态颜色
        ctx.beginPath();
        ctx.arc(cx, cy, 8, 0, 2 * Math.PI);
        ctx.fillStyle = color;
        ctx.fill();

        // 内圈 — 小车本体
        ctx.beginPath();
        ctx.arc(cx, cy, 5, 0, 2 * Math.PI);
        ctx.fillStyle = '#FFFFFF';
        ctx.fill();

        // 编号文字
        ctx.fillStyle = '#000';
        ctx.font = '9px monospace';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(car.number, cx, cy);
    });
}
```

---

## 问题 2：规划路径渲染出现斜线

**问题描述**

小车规划路径（RouteList）在 Canvas 上渲染时出现斜线。正常路径由 4 方向 BFS 产生（上下左右），相邻网格中心点连线应全部为水平/垂直线段，不应出现对角斜线。

**原因**

`renderRoutes()` 可能直接沿用 `car.routeList` 的原始顺序逐点连线，但路径的第一个点与小车当前位置之间跳过了中间格（Navigator 返回的路径不含起点）。或者 Canvas 画线时使用的是格子左上角坐标而非格子中心坐标，产生视觉偏移。

**涉及文件**

`display/src/main/resources/web/js/app.js`

**当前代码**（`renderRoutes()` 函数）

```javascript
function renderRoutes() {
    state.cars.forEach(car => {
        if (!car.routeList || car.routeList.length === 0) return;
        ctx.strokeStyle = 'rgba(100, 200, 255, 0.5)';
        ctx.lineWidth = 2;
        ctx.beginPath();

        const startX = car.position.x * CELL_SIZE + CELL_SIZE / 2;
        const startY = car.position.y * CELL_SIZE + CELL_SIZE / 2;
        ctx.moveTo(startX, startY);

        car.routeList.forEach(p => {
            const px = p.x * CELL_SIZE + CELL_SIZE / 2;
            const py = p.y * CELL_SIZE + CELL_SIZE / 2;
            ctx.lineTo(px, py);
        });

        ctx.stroke();
    });
}
```

**修改为**

```javascript
function renderRoutes() {
    state.cars.forEach(car => {
        if (!car.routeList || car.routeList.length === 0) return;

        ctx.strokeStyle = 'rgba(100, 200, 255, 0.6)';
        ctx.lineWidth = 2;
        ctx.lineCap = 'round';

        // 起点：小车当前所在格子中心
        let prevX = car.position.x * CELL_SIZE + CELL_SIZE / 2;
        let prevY = car.position.y * CELL_SIZE + CELL_SIZE / 2;

        car.routeList.forEach(p => {
            const cx = p.x * CELL_SIZE + CELL_SIZE / 2;
            const cy = p.y * CELL_SIZE + CELL_SIZE / 2;

            // 仅当两点相邻（曼哈顿距离 = 1）时才画线段，防止跨格斜线
            const dx = Math.abs(p.x - Math.round((prevX - CELL_SIZE / 2) / CELL_SIZE));
            const dy = Math.abs(p.y - Math.round((prevY - CELL_SIZE / 2) / CELL_SIZE));
            if ((dx + dy) === 1) {
                ctx.beginPath();
                ctx.moveTo(prevX, prevY);
                ctx.lineTo(cx, cy);
                ctx.stroke();
            }

            prevX = cx;
            prevY = cy;
        });
    });
}
```

关键改动：
- 从 `car.position`（小车当前坐标）开始，而非从 routeList[0] 开始
- 每段独立 `beginPath` + `stroke`
- 非相邻点跳过不画，确保只显示水平/垂直线段

---

## 问题 3：地图画布放大

**问题描述**

当前 Canvas 固定 540×540px，每格 18px，30×30 网格显示较小，希望整体放大。

**涉及文件**

`display/src/main/resources/web/js/app.js`

`display/src/main/resources/web/css/style.css`

**当前代码**（`app.js` 顶部常量区）

```javascript
const CELL_SIZE = 18;
const CANVAS_SIZE = 540;  // CELL_SIZE × 30
```

**修改为**（例如放大到 1.5 倍：每格 27px，画布 810px）

```javascript
const GRID_COUNT = 30;
const CELL_SIZE = 27;       // 每格像素，18→27 放大 1.5 倍
const CANVAS_SIZE = CELL_SIZE * GRID_COUNT;  // 810px
```

同时修改 `style.css` 中 Canvas 相关样式：

```css
canvas {
    width: 810px;
    height: 810px;
}
```

或在 JS 中动态设置更为可靠：

```javascript
const canvas = document.getElementById('mapCanvas');
canvas.width = CANVAS_SIZE;
canvas.height = CANVAS_SIZE;
canvas.style.width = CANVAS_SIZE + 'px';
canvas.style.height = CANVAS_SIZE + 'px';
```

右侧状态面板宽度也需相应调整。

---

## 问题 4：页面上动态添加小车 — 前端部分

**问题描述**

需要在页面添加按钮，点击后通过 WebSocket 通知 Display 启动新 Car 进程。Person D 负责前端按钮和 WebSocket 消息发送。

**涉及文件**

`display/src/main/resources/web/index.html`

`display/src/main/resources/web/js/app.js`

**步骤 1 — HTML**（控制面板按钮区域）

```html
<button id="addCarBtn" class="btn btn-secondary" title="添加一台新车">+ 添加小车</button>
```

**步骤 2 — JS**（按钮绑定，约在 `onStartClick` / `onResetClick` 等事件绑定附近）

```javascript
const addCarBtn = document.getElementById('addCarBtn');

addCarBtn.addEventListener('click', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ADD_CAR' }));
    }
});
```

---

## 问题 5：手动添加障碍物 — 前端部分

**问题描述**

需要在运行中手动在地图上增加/移除障碍物。Person D 负责 Canvas 右键交互和 WebSocket 消息发送。

**涉及文件**

`display/src/main/resources/web/js/app.js`

**新增代码**（Canvas 事件绑定区域）

```javascript
canvas.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const col = Math.floor((e.clientX - rect.left) / CELL_SIZE);
    const row = Math.floor((e.clientY - rect.top) / CELL_SIZE);

    if (row >= 0 && row < GRID_COUNT && col >= 0 && col < GRID_COUNT) {
        const msg = {
            type: 'TOGGLE_OBSTACLE',
            data: { row: row, col: col }
        };
        ws.send(JSON.stringify(msg));
    }
});
```
