# 统计分析界面 — 设计文档

> 文档版本：v1.1（基础框架已实现）  
> 编写日期：2026-06-12  
> 最后更新：2026-06-15  
> 适用系统：变电站巡检仿真系统  

---

## 一、需求概述

在现有仿真系统基础上新增「统计分析」功能模块，提供：

1. **分析界面框架** — 独立页面 + 导航路由，预留图表和数据面板位置
2. **分析维度设计** — 定义需要统计的指标和分析维度（界面搭建后逐步实现）
3. **当前交付物** — 界面框架（空壳），具体分析逻辑留空

### 1.1 为什么先做框架

- 登录认证系统需要先完成，统计分析是后续功能
- 前端路由/导航结构需要提前规划，避免后期重构
- 预先定义好接口协议，便于多人并行开发
- 降低耦合：框架就绪后，分析逻辑可由不同成员分别填充

---

## 二、界面设计

### 2.1 整体布局

```
┌──────────────────────────────────────────────────────────────────┐
│ ⚡ 变电站巡检仿真系统  │  👤 管理员  │ [仿真控制] [统计分析] [退出] │
├────────────┬─────────────────────────────────────┬───────────────┤
│  左侧筛选栏 │           中央分析区域               │  右侧数据面板  │
│            │                                    │               │
│  时间范围   │  ┌──────────────────────────────┐ │  关键指标      │
│  [开始]    │  │                              │ │  · 总步数     │
│  [结束]    │  │      图表 / 数据展示区         │ │  · 覆盖率     │
│            │  │      （具体实现留空）           │ │  · 探索效率   │
│  统计维度   │  │                              │ │  · 平均路径   │
│  □ 步数    │  │                              │ │               │
│  □ 覆盖率  │  └──────────────────────────────┘ │  排行榜        │
│  □ 耗时    │                                    │  1. Car001    │
│  □ 路径    │  ┌──────────────────────────────┐ │  2. Car002    │
│            │  │                              │ │  3. Car003    │
│  车辆选择   │  │      明细表格 / 日志区        │ │               │
│  ☑ Car001  │  │      （具体实现留空）          │ │               │
│  ☑ Car002  │  │                              │ │               │
│  ☑ Car003  │  │                              │ │               │
│            │  └──────────────────────────────┘ │               │
│            │                                    │               │
│  [应用筛选] │                                    │               │
│  [导出报表] │                                    │               │
│            │                                    │               │
└────────────┴─────────────────────────────────────┴───────────────┘
```

### 2.2 布局说明

沿用现有系统的三列布局模式，保持视觉一致性：

| 区域 | 宽度 | 说明 |
|------|------|------|
| 左侧筛选栏 | 260px | 时间范围选择、统计维度勾选、车辆选择、操作按钮 |
| 中央分析区 | flex:1 | 图表区（预留 ECharts/Canvas）+ 数据表格 |
| 右侧数据面板 | 280px | 关键指标卡片 + 排行榜 |

### 2.3 配色与风格

完全沿用 `style.css` 已有的 Design System：
- 暗色工业风背景 `#0d1117`
- 面板背景 `#161b22`，边框 `#30363d`
- 状态色：成功 `#3fb950`、警告 `#d2991d`、信息 `#79c0ff`
- 字体：系统默认 sans-serif + Noto Sans SC（中文）

---

## 三、分析维度定义

### 3.1 核心指标（Key Metrics）

| 指标 | 缩写 | 计算公式 | 优先级 |
|------|------|---------|--------|
| **总步数** | STEPS | Σ(所有小车 steps) | P0 |
| **探索覆盖率** | COV | (explored / explorable) × 100% | P0 |
| **探索效率** | EFF | exploredCount / Σ steps | P1 |
| **平均路径长度** | AVG_PATH | Σ pathLength / 任务分配次数 | P1 |
| **阻塞次数** | BLK_COUNT | Σ 每车 BLOCKED 状态次数 | P1 |
| **任务总耗时** | DURATION | elapsedSeconds | P0 |
| **空闲率** | IDLE_RATE | Σ(IDLE ticks) / total ticks | P2 |
| **重复探索率** | REEXPLORE | heatMap中 count>1 的格子数 / 总探索格数 | P2 |

### 3.2 统计维度（Dimensions）

| 维度 | 说明 | 实现方式 |
|------|------|---------|
| **按小车** | per-carId 统计 | 从 `CarID:History` 和 `CarID:Steps` 读取 |
| **按时间段** | 按 tick 区间分段 | 根据 History 中 tick 字段筛选 |
| **按区域** | 地图分区统计（如 3×3 块） | 根据 History 中 x,y 坐标分区 |
| **按算法** | BFS vs A* 对比 | 从 `TaskConfig.algorithm` 读取 |
| **按障碍物比例** | 不同 obstacleRatio 下效率对比 | 从 `TaskConfig.obstacleRatio` 读取 |

### 3.3 数据来源

分析数据主要从以下 Redis key 读取：

| 数据 | Redis Key | 说明 |
|------|-----------|------|
| 车辆步数 | `CarID:Steps` | 当前总步数 |
| 路径历史 | `CarID:History` | `[{x,y,tick}, ...]` 列表 |
| 阻塞记录 | `CarID:BlockedTick` | 最近一次阻塞 tick |
| 任务配置 | `TaskConfig` | 算法、障碍物比例等 |
| 地图视野 | `mapView` | bitmap，探索状态 |
| 地图障碍 | `mapBlock` | bitmap，障碍分布 |
| 热力图 | `mapHeat` | 访问频次 hash |

---

## 四、前端页面设计 (analysis.html)

### 4.1 HTML 结构（骨架）

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>统计分析 — 变电站巡检仿真系统</title>
  <link rel="stylesheet" href="css/style.css">
</head>
<body>
  <!-- ═══ 顶栏 ═══ -->
  <header class="header">
    <h1>⚡ 变电站巡检仿真系统</h1>
    <div class="global-info">
      <span id="info-user">👤 管理员(admin)</span>
      <a href="index.html" class="nav-link">仿真控制</a>
      <span class="nav-link active">统计分析</span>
      <button id="btn-logout" class="btn-small">退出</button>
    </div>
  </header>

  <div class="main-container">
    <!-- ═══ 左侧筛选栏 ═══ -->
    <aside class="sidebar" id="analysis-sidebar">
      <!-- 待填充 -->
    </aside>

    <!-- ═══ 中央分析区 ═══ -->
    <main class="analysis-main" id="analysis-main">
      <div class="analysis-placeholder">
        <p>请选择筛选条件后点击"应用筛选"</p>
      </div>
    </main>

    <!-- ═══ 右侧数据面板 ═══ -->
    <aside class="data-panel" id="analysis-data">
      <!-- 待填充 -->
    </aside>
  </div>

  <script src="js/auth.js"></script>
  <script src="js/analysis.js"></script>
</body>
</html>
```

### 4.2 分析 JS 骨架 (analysis.js)

```js
// ==================== 统计分析页面 — 空壳框架 ====================

// 全局状态
const state = {
  filters: {
    carIds: [],        // 选中的车辆
    timeRange: null,   // [startTick, endTick]
    metrics: [],       // 选中的指标
    algorithm: null,   // 筛选算法
    obstacleRatio: null // 筛选障碍物比例
  },
  data: null,          // 分析结果缓存
};

// ==================== 初始化 ====================
async function init() {
  await checkAuth();  // 来自 auth.js
  renderSidebar();
  renderDataPanel();
  // 具体实现留空
}

// ==================== 左侧筛选栏 ====================
function renderSidebar() {
  // TODO: 渲染筛选条件 UI
}

// ==================== 右侧数据面板 ====================
function renderDataPanel() {
  // TODO: 渲染指标卡片 + 排行榜
}

// ==================== 数据分析 API 调用 ====================
async function fetchAnalysis(filters) {
  // TODO: 调用后端 /api/analysis/query
  // 参数: {carIds, startTick, endTick, metrics}
  // 返回: {steps, coverage, efficiency, ...}
}

// ==================== 图表渲染 ====================
function renderChart(data) {
  // TODO: 使用 Canvas 或后续引入 ECharts 绘制
}

// ==================== 导出功能 ====================
async function exportReport() {
  // TODO: 调用后端 /api/analysis/export
  // 返回 CSV/JSON 文件下载
}

init();
```

---

## 五、后端接口设计

### 5.1 分析数据查询接口

路径前缀：`/api/analysis/`

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/analysis/summary` | 获取全局汇总指标 | 全部 |
| GET | `/api/analysis/car/{carId}` | 获取单个车辆详细统计 | 全部 |
| GET | `/api/analysis/leaderboard` | 获取排行数据 | 全部 |
| POST | `/api/analysis/query` | 按条件查询分析数据 | 全部 |
| GET | `/api/analysis/export` | 导出报表（当前仅 admin） | admin |

> 注：当前阶段仅定义接口签名，所有接口返回空 JSON（由后续开发填充）。

### 5.2 接口详细定义

**GET /api/analysis/summary**

响应 (200)：
```json
{
  "totalSteps": 0,
  "explorationRate": 0,
  "efficiency": 0,
  "duration": 0,
  "blockCount": 0,
  "idleRate": 0,
  "reExploreRate": 0,
  "activeCars": 3
}
```

**GET /api/analysis/car/{carId}**

响应 (200)：
```json
{
  "carId": "Car001",
  "steps": 0,
  "pathCount": 0,
  "avgPathLength": 0,
  "blockCount": 0,
  "idleRate": 0,
  "pathHistory": []
}
```

**GET /api/analysis/leaderboard**

响应 (200)：
```json
{
  "leaderboard": [
    {"carId": "Car001", "steps": 0, "coverage": 0},
    {"carId": "Car002", "steps": 0, "coverage": 0}
  ]
}
```

**POST /api/analysis/query**

请求体：
```json
{
  "carIds": ["Car001", "Car002"],
  "startTick": 0,
  "endTick": 100,
  "metrics": ["steps", "coverage", "efficiency"]
}
```

响应 (200)：
```json
{
  "data": {
    "Car001": {
      "steps": 0,
      "coverage": 0,
      "efficiency": 0
    },
    "Car002": {
      "steps": 0,
      "coverage": 0,
      "efficiency": 0
    }
  }
}
```

---

## 六、代码结构

### 6.1 已实现文件

```
common/src/main/java/com/substation/common/analysis/
├── model/
│   ├── AnalysisQuery.java        ← 查询参数 record ✓
│   ├── SummaryStatistics.java    ← 汇总统计 record ✓
│   └── CarStatistics.java        ← 单车统计 record ✓
├── AnalysisApiHandler.java       ← 分析 API (5个接口，返回空数据) ✓
└── AnalysisEngine.java           ← 分析引擎接口 (待实现逻辑)

display/src/main/resources/web/
├── analysis.html                 ← 统计分析页面 (基础框架) ✓
└── js/
    └── analysis.js               ← 分析页 JS (空壳) ✓
```

### 6.2 已修改文件

| 文件 | 修改内容 | 状态 |
|------|---------|------|
| `display/.../HttpFileServer.java` | 集成 `/api/analysis/` 路由 | ✅ |
| `display/.../DisplayMain.java` | 初始化 `AnalysisApiHandler` | ✅ |

---

## 七、图表技术选型建议

| 方案 | 优点 | 缺点 |
|------|------|------|
| **Canvas 原生绘制** | 零外部依赖，与现有地图 Canvas 一致 | 开发量大，复杂图表不便 |
| **ECharts (CDN 引入)** | 功能强大，图表美观，适合数据可视化 | 增加外部依赖 (~1MB CDN) |
| **Chart.js (CDN 引入)** | 轻量 (~60KB)，上手快 | 复杂分析能力不如 ECharts |

> **建议**：后续实际开发时引入 **ECharts CDN**。该项目本身就是数据可视化场景（Canvas 地图），分析界面使用专业图表库更合理。仅需在 `<head>` 中加一行：
> ```html
> <script src="https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.min.js"></script>
> ```

---

## 八、实施状态

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 创建 `analysis.html` + `analysis.js` 空壳 | ✅ 已完成 |
| 2 | 创建模型类 DTO（record） | ✅ 已完成 (SummaryStatistics, CarStatistics, AnalysisQuery) |
| 3 | 创建 `AnalysisApiHandler` 空壳 (5 个接口) | ✅ 已完成 |
| 4 | 创建 `AnalysisEngine` 接口 | ✅ 已完成 |
| 5 | 填充 `AnalysisEngine` 逻辑 (Redis 数据计算) | 待开发 |
| 6 | 前端图表渲染 (ECharts) | 待开发 |
| 7 | 导出功能 | 待开发 |
