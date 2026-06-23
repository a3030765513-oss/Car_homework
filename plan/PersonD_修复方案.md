# Person D 修复方案

> 日期：2026-06-08

---

## 一、前端显示修复（5 项）

### 1. 小车双圈渲染

**问题**：小车显示为单色实心圆，不够醒目。

**修复**：改为双圈模式：
- 外圈：状态颜色环（半径 `CELL_SIZE/2 - 1`）
- 内圈：白色填充（半径 `outerR - 3`）
- 编号：黑色文字

**文件**：`display/src/main/resources/web/js/app.js` → `drawCar()`

---

### 2. 路径渲染防斜线

**问题**：Canvas 画路径线时可能出现对角斜线（非相邻格点之间连线）。

**修复**：
- 从 `car.position` 做起点
- 用网格坐标直接计算曼哈顿距离（`|dx| + |dy|`）
- 仅当 `|dx| + |dy| === 1`（四方向相邻）时才画线段
- 非相邻点跳过不画

**文件**：`display/src/main/resources/web/js/app.js` → `drawRouteForCar()`

---

### 3. 画布放大 1.5 倍

**问题**：540×540px 画布显示偏小。

**修复**：`CELL_SIZE` 从 18 改为 27，画布自动变为 810×810px。保留动态地图尺寸支持（`getGridW/getGridH`）。

**文件**：`display/src/main/resources/web/js/app.js` → 常量区

---

### 4. 动态添加小车按钮

**问题**：运行中无法动态添加新车。

**修复**：
- HTML：新增 `+ 添加小车` 按钮
- JS：点击发送 `{ type: 'ADD_CAR' }` WebSocket 消息

**文件**：
- `display/src/main/resources/web/index.html`
- `display/src/main/resources/web/js/app.js` → `onAddCarClick()`

---

### 5. Canvas 右键切换障碍物

**问题**：运行中无法手动增删障碍物。

**修复**：右键点击 Canvas 发送 `{ type: 'TOGGLE_OBSTACLE', data: { row, col } }`。支持 Retina 屏缩放。

**文件**：`display/src/main/resources/web/js/app.js` → `onCanvasContextMenu()`

---

## 二、代码审查修复（4 项）

### 1. 字段名对齐 `algorithmType` → `algorithm`

**问题**：设计文档 JSON 示例用了 `algorithmType`，Redis 实际存的是 `algorithm`。

**修复**：设计文档修正。前端 JS 代码实际已正确使用 `algorithm`，无 bug。

**文件**：`display+launcher设计文档.md`

---

### 2. Launcher 开启 B/C 模块

**问题**：Launcher 对 task-configurator/navigator/target-planner/car 打印 WARN 跳过。

**修复**：
- 合并 Person B (`lyq_car`) 和 Person C (`ylj_navigator`) 代码
- LauncherMain 替换所有 `startPlaceholder()` 为真实 `main()` 调用
- pom.xml 补全 4 个模块依赖

**文件**：
- `launcher/src/main/java/com/substation/launcher/LauncherMain.java`
- `launcher/pom.xml`

---

### 3. ControllerMain 适配

Person A 已将 ControllerMain 重构为 `main()` 模式，Launcher 同步适配。

---

### 4. 热力图渲染层预留

**问题**：前端无热力图渲染接口。

**修复**：设计文档 Canvas 章节增加 L6 热力图层的色调映射规则说明。

**文件**：`display+launcher设计文档.md`

---

## 三、修改文件清单

| 文件 | 改动内容 |
|------|----------|
| `display/.../js/app.js` | CELL_SIZE 27、双圈小车、路径相邻判定、添加小车按钮、右键障碍物 |
| `display/.../index.html` | `+ 添加小车` 按钮 |
| `launcher/.../LauncherMain.java` | 开启全部 6 模块启动 |
| `launcher/pom.xml` | 补全 B/C 模块依赖 |
| `display+launcher设计文档.md` | algorithmType→algorithm、L6 热力图 |

---

## 四、测试结果

```
HttpFileServerTest:     14 passed ✅
WebSocketBridgeTest:     9 passed ✅
LauncherMainTest:       24 passed ✅
全项目编译:              8 modules ✅
```
