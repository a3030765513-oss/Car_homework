# Unity 3D 可视化方案——新手完整指南

> 目标：在现有 Web 页面加一个按钮，切换到 Unity 3D 地图，实时显示小车、障碍物和树墙  
> 方案：Unity WebGL + WebSocket + 现有系统零改动

---

## 一、整体思路

```
                现有 Java 后端（不改）
                      │
              WebSocket (ws://localhost:8888)
                      │
        ┌─────────────┼─────────────┐
        ▼                           ▼
   现有 2D Canvas               Unity 3D WebGL
   (不改)                       (新增)
```

Unity 和现有的 2D 页面**共用一个 WebSocket**，收到同一份 `SimulationState` JSON 数据，各自用自己的方式渲染。

---

## 二、你需要安装的东西

| 软件 | 下载 | 说明 |
|------|------|------|
| Unity Hub | https://unity.com/download | Unity 版本管理器 |
| Unity 2022.3 LTS | 在 Unity Hub 里安装 | 选 WebGL Build Support 模块 |
| Visual Studio Code | 可选，写 C# 脚本用 | Unity 自带编辑器也行 |

安装 Unity Hub → 点击左侧 "Installs" → "Install Editor" → 选 **2022.3 LTS** → 勾上 **WebGL Build Support** → 等安装完成。

---

## 三、创建 Unity 项目

1. 打开 Unity Hub → 右上角 "New Project"
2. 模板选 **3D Core**
3. 项目名填 `Substation3D`
4. Location 选一个方便找的文件夹
5. 点 "Create Project"

等 Unity 编辑器打开（第一次可能几分钟）。

---

## 四、安装 WebSocket 插件

Unity 不自带 WebSocket，需要装一个。

1. 浏览器打开 https://github.com/endel/NativeWebSocket/releases
2. 下载 `NativeWebSocket.unitypackage`
3. 双击下载的文件 → Unity 会弹出 Import 窗口 → 全选 → Import

这是最轻量的 Unity WebSocket 库，零依赖。

---

## 五、搭建 3D 场景

### 5.1 地面（初始随便放，代码会自动调）

1. Unity 顶部菜单 → **GameObject → 3D Object → Plane**
2. 在右侧 Inspector 面板：
   - Position：`X=0, Y=0, Z=0`
   - Scale：`X=1, Y=1, Z=1`
3. 拖到 Hierarchy → 改名 `GroundPlane`

> 不用管尺寸——后面的 `RebuildScene()` 代码会根据地图大小自动缩放和位移。默认 30×30 还是 20×20，代码全自动处理。

### 5.2 添加光源

1. 顶部菜单 → **GameObject → Light → Directional Light**
2. Rotation 设 `X=50, Y=-30, Z=0`

### 5.3 摄像机（不用调，代码自动摆）

选中 Main Camera，**不需要改 Position**——`RebuildScene()` 会根据地图尺寸自动计算摄像机位置。

### 5.4 创建树模型预制体（Prefab）

树墙由代码自动生成，你只需要做**一个**树的模板：

1. **GameObject → 3D Object → Capsule**
2. Scale 设 `X=0.2, Y=1.5, Z=0.2`
3. 材质颜色绿色
4. 拖进 Prefabs 文件夹 → 改名 `Tree`

> 后面代码会自动在地图四条边上 Instantiate，不用手动复制。

### 5.5 创建障碍物和小车的预制体（Prefab）

**障碍物**：
1. **GameObject → 3D Object → Cube**
2. Scale 设 `X=1, Y=1.5, Z=1`
3. 材质颜色红色
4. 在 Project 窗口创建文件夹 `Prefabs`
5. 把 Cube 拖进 Prefabs 文件夹 → 改名 `Obstacle`

**小车**：
1. **GameObject → 3D Object → Capsule**
2. Scale 设 `X=0.5, Y=0.5, Z=0.5`
3. 材质颜色蓝色
4. 拖进 Prefabs → 改名 `Car`

> 后面用代码动态生成，不用手动摆放

---

## 六、核心 C# 脚本

在 Project 窗口创建一个文件夹 `Scripts`，右键 → **Create → C# Script**，创建以下三个脚本。

### 6.1 WebSocketClient.cs —— 连接后端

```csharp
using UnityEngine;
using NativeWebSocket;
using System;

public class WebSocketClient : MonoBehaviour
{
    private WebSocket ws;

    public event Action<string> OnMessage;

    async void Start()
    {
        ws = new WebSocket("ws://localhost:8888");

        ws.OnMessage += (bytes) =>
        {
            string json = System.Text.Encoding.UTF8.GetString(bytes);
            OnMessage?.Invoke(json);
        };

        ws.OnError += (msg) => Debug.LogError("WS Error: " + msg);
        ws.OnClose += (code) => Debug.Log("WS 断开");

        await ws.Connect();
        Debug.Log("WebSocket 已连接");
    }

    async void OnDestroy()
    {
        if (ws != null)
            await ws.Close();
    }
}
```

### 6.2 MapRenderer.cs —— 动态渲染地图（自适应尺寸）

```csharp
using UnityEngine;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;

public class MapRenderer : MonoBehaviour
{
    public GameObject obstaclePrefab;
    public GameObject carPrefab;
    public GameObject treePrefab;
    public GameObject groundPlane;   // 地面 Plane，拖入 Inspector

    private Dictionary<string, GameObject> cars = new();
    private List<GameObject> obstacles = new();
    private List<GameObject> trees = new();

    private int lastWidth = -1;
    private int lastHeight = -1;

    public void UpdateFromJson(string json)
    {
        JObject root = JObject.Parse(json);

        // 从 mapBlock 数组推导地图尺寸
        var mapBlock = root["mapBlock"] as JArray;
        if (mapBlock == null || mapBlock.Count == 0) return;
        int mapHeight = mapBlock.Count;
        int mapWidth  = (mapBlock[0] as JArray).Count;

        // 尺寸变了 → 重建场景
        if (mapWidth != lastWidth || mapHeight != lastHeight)
        {
            RebuildScene(mapWidth, mapHeight);
            lastWidth  = mapWidth;
            lastHeight = mapHeight;
        }

        // ── 更新障碍物 ──
        foreach (var obs in obstacles) Destroy(obs);
        obstacles.Clear();

        for (int r = 0; r < mapHeight; r++)
        {
            var row = mapBlock[r] as JArray;
            if (row == null) continue;
            for (int c = 0; c < mapWidth; c++)
            {
                if (row[c].Value<bool>())
                {
                    var obj = Instantiate(obstaclePrefab,
                        new Vector3(c, 0.75f, r), Quaternion.identity);
                    obstacles.Add(obj);
                }
            }
        }

        // ── 更新小车 ──
        var carList = root["cars"] as JArray;
        // 如果 cars 不在顶层，尝试从 data 子对象取
        if (carList == null)
        {
            var inner = root["data"] as JObject;
            if (inner != null) carList = inner["cars"] as JArray;
        }
        if (carList == null) return;
        var activeIds = new HashSet<string>();

        foreach (var carToken in carList)
        {
            string carId = carToken["carId"].Value<string>();
            int x = carToken["position"]["x"].Value<int>();
            int y = carToken["position"]["y"].Value<int>();
            activeIds.Add(carId);

            if (!cars.ContainsKey(carId))
                cars[carId] = Instantiate(carPrefab);

            cars[carId].transform.position = new Vector3(x, 0.5f, y);
        }

        // 删除不再出现的车辆
        var toRemove = new List<string>();
        foreach (var kv in cars)
            if (!activeIds.Contains(kv.Key)) toRemove.Add(kv.Key);
        foreach (var id in toRemove)
        {
            Destroy(cars[id]);
            cars.Remove(id);
        }
    }

    void RebuildScene(int w, int h)
    {
        if (groundPlane != null)
        {
            groundPlane.transform.position = new Vector3(w / 2f, 0, h / 2f);
            groundPlane.transform.localScale = new Vector3(w / 10f, 1, h / 10f);
        }

        foreach (var t in trees) Destroy(t);
        trees.Clear();

        // 四条边种树
        for (int x = 0; x < w; x++)
        {
            trees.Add(Instantiate(treePrefab, new Vector3(x, 0, -1), Quaternion.identity));
            trees.Add(Instantiate(treePrefab, new Vector3(x, 0, h),  Quaternion.identity));
        }
        for (int y = 0; y < h; y++)
        {
            trees.Add(Instantiate(treePrefab, new Vector3(-1, 0, y), Quaternion.identity));
            trees.Add(Instantiate(treePrefab, new Vector3(w,  0, y), Quaternion.identity));
        }

        Camera.main.transform.position = new Vector3(w / 2f, Mathf.Max(w, h) * 1.2f, -5);
    }
}
```

### 6.3 GameController.cs —— 串联

创建一个空物体挂载所有脚本：

1. **GameObject → Create Empty** → 改名 `GameController`
2. Add Component → 把 `WebSocketClient.cs`、`MapRenderer.cs` 都拖上去
3. 把 Prefabs 里的 Obstacle、Car、Capsule树 拖到 MapRenderer 的槽位
4. 场景里的 Ground Plane 也拖到 MapRenderer 的 `groundPlane` 槽位

```csharp
using UnityEngine;
using Newtonsoft.Json.Linq;

public class GameController : MonoBehaviour
{
    public WebSocketClient wsClient;
    public MapRenderer mapRenderer;

    void Start()
    {
        wsClient.OnMessage += (json) =>
        {
            var root = JObject.Parse(json);
            if (root["type"]?.Value<string>() == "REFRESH_ALL")
            {
                // 把嵌套的 data 子对象传给 MapRenderer
                mapRenderer.UpdateFromJson(root["data"].ToString());
            }
        };
    }
}
```

---

## 七、JSON 格式适配 + 动态尺寸原理

### 7.1 后端发来的数据结构

后端 WebSocket 推给前端的 REFRESH_ALL 消息：

```json
{
  "type": "REFRESH_ALL",
  "tick": 15,
  "data": {
    "mapBlock": [[false, true, ...], ...],
    "mapView":  [[true,  false, ...], ...],
    "cars": [
      {"carId":"Car001", "position":{"x":5, "y":10}, "status":"READY", ...},
      {"carId":"Car002", ...}
    ]
  }
}
```

### 7.2 地图尺寸从哪来

**不硬编码 30×30**——直接从 `mapBlock` 数组的长度推导：

```
mapBlock 是 JSON 数组 → 数组有几行 = mapHeight
每行有几个元素    = mapWidth
```

如果 Web 端改了配置（比如 20×20 或 40×40），后端发的 `mapBlock` 尺寸就自动跟着变。Unity 收到新尺寸后：

1. 对比 `lastWidth` / `lastHeight`
2. 如果变了 → `RebuildScene()`：
   - 地面 Plane 缩放适配新尺寸
   - 树墙清除后按新尺寸重建
   - 摄像机位置重新计算
3. 之后每帧按新尺寸遍历障碍物和小车

### 7.3 车辆数量自适应

小车的 `cars` 数组包含多少辆车就渲染多少辆：
- 新 Array 里多了一辆 → 自动 Instantiate 新 Capsule
- 新 Array 里少了一辆 → 自动 Destroy 旧的
- **不需要改任何代码**，全自动

### 7.4 安装 Newtonsoft.Json（必装）

Unity 自带的 JsonUtility 不支持嵌套数组（bool[][]）。用 Newtonsoft.Json 代替：

1. Unity 菜单 → **Window → Package Manager**
2. 左上角 **+** → **Add package by name**
3. 输入：`com.unity.nuget.newtonsoft-json`
4. 点 Add → 等安装完成

---

## 八、在现有网页中嵌入 Unity

### 8.1 构建 WebGL

1. Unity 菜单 → File → Build Settings
2. Platform 选 **WebGL** → Switch Platform
3. 点 Build → 选一个文件夹（如 `E:/IdeaProjects/new/display/src/main/resources/web/unity/`）
4. 等待构建完成（首次可能 10-20 分钟）

### 8.2 在 HTML 中添加入口

在现有 `index.html` 中添加按钮和 Unity 容器：

```html
<!-- 顶部加按钮 -->
<button id="btn-unity" onclick="toggleUnity()">🎮 3D视图</button>

<!-- Canvas 和 Unity 切换 -->
<div id="view-2d">
    <canvas id="mapCanvas" width="600" height="600"></canvas>
</div>
<div id="view-3d" style="display:none; width:600px; height:600px;">
    <iframe id="unity-frame" src="unity/index.html" 
            style="width:100%; height:100%; border:none;"></iframe>
</div>

<script>
function toggleUnity() {
    var view2d = document.getElementById('view-2d');
    var view3d = document.getElementById('view-3d');
    var btn = document.getElementById('btn-unity');
    
    if (view3d.style.display === 'none') {
        view2d.style.display = 'none';
        view3d.style.display = 'block';
        btn.textContent = '📏 2D视图';
    } else {
        view2d.style.display = 'block';
        view3d.style.display = 'none';
        btn.textContent = '🎮 3D视图';
    }
}
</script>
```

### 8.3 Unity 里连接 WebSocket 的地址

Unity WebGL 里 `ws://localhost:8888` 不需要改——Unity WebGL 运行在你的浏览器中，WebSocket 从浏览器发起，跟你现有 2D 前端一模一样。

---

## 九、开发顺序建议

```
第 1 步：安装 Unity + 创建空项目
第 2 步：搭建静态场景（地面 + 树 + 摄像机）
第 3 步：安装 NativeWebSocket 插件
第 4 步：写 WebSocketClient.cs → 测试能收到消息
第 5 步：写 MapRenderer.cs → 解析 JSON → 动态生成方块
第 6 步：写 GameController.cs → 串联
第 7 步：构建 WebGL
第 8 步：嵌入 HTML → 测试切换按钮
```

每完成一步都运行一下验证，不要全部写完了再测。

---

## 十、常见坑

| 问题 | 解决 |
|------|------|
| Unity 连不上 ws://localhost | 检查防火墙；Unity WebGL 的 WebSocket 从浏览器发起，端口和 2D 前端一致 |
| bool[][] 解析报错 | 用 Newtonsoft.Json 替代 JsonUtility |
| WebGL 构建后空白 | 确认 Build 选的是 WebGL 平台；检查浏览器 Console 报错 |
| 小车不更新 | 检查 WebSocket 消息格式是否解析正确；先 Console.Log 看收到什么 |
| 3D 坐标和 2D 不对齐 | Unity 坐标 (X, Y, Z) 对应地图 (col, height, row)，Y 是高度轴 |
