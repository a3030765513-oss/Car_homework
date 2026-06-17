/**
 * 变电站巡检仿真系统 — 统计分析页面。
 *
 * 职责：
 *   1. 筛选条件收集
 *   2. 调用分析 API
 *   3. 渲染图表和数据
 *   4. 导出报表
 *
 * 状态：框架空壳，具体分析逻辑后续填充
 */

(function () {
  'use strict';

  var $btnApply  = document.getElementById('btn-apply');
  var $btnExport = document.getElementById('btn-export');
  var $chartArea = document.getElementById('chart-area');
  var $tableArea = document.getElementById('table-area');
  var $metrics   = document.getElementById('metrics-display');
  var $lb        = document.getElementById('lb');

  // ═══════════════════════════════════════════════════════════
  // 初始化
  // ═══════════════════════════════════════════════════════════

  async function init() {
    var user = await Auth.checkAuth();
    if (!user) { return; }
    Auth.renderNavBar(user);
    Auth.applyPermissions(user.role);
  }

  // ═══════════════════════════════════════════════════════════
  // 筛选条件收集
  // ═══════════════════════════════════════════════════════════

  function collectFilters() {
    var start = parseInt(document.getElementById('filter-start').value, 10) || 0;
    var end   = parseInt(document.getElementById('filter-end').value, 10) || 100;

    var cars = [];
    var checkboxes = document.querySelectorAll('.filter-car:checked');
    for (var i = 0; i < checkboxes.length; i++) {
      cars.push(checkboxes[i].value);
    }

    var metrics = [];
    var mboxes = document.querySelectorAll('.filter-metric:checked');
    for (var j = 0; j < mboxes.length; j++) {
      metrics.push(mboxes[j].value);
    }

    return { carIds: cars, startTick: start, endTick: end, metrics: metrics };
  }

  // ═══════════════════════════════════════════════════════════
  // API 调用（空壳）
  // ═══════════════════════════════════════════════════════════

  async function fetchAnalysis(filters) {
    try {
      var token = Auth.getToken();
      var resp = await fetch('/api/analysis/query', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + (token || '')
        },
        body: JSON.stringify(filters)
      });
      return resp.json();
    } catch (e) {
      return null;
    }
  }

  // ═══════════════════════════════════════════════════════════
  // 渲染
  // ═══════════════════════════════════════════════════════════

  function updateMetrics(data) {
    if (!data) { return; }
    var html = '';
    html += '<div>总步数: ' + (data.totalSteps || '--') + '</div>';
    html += '<div>覆盖率: ' + (data.explorationRate || '--') + '%</div>';
    html += '<div>探索效率: ' + (data.efficiency || '--') + '</div>';
    html += '<div>总耗时: ' + (data.duration || '--') + 's</div>';
    $metrics.innerHTML = html;
  }

  function updateLeaderboard(data) {
    if (!data || !data.leaderboard) { return; }
    var html = '';
    for (var i = 0; i < data.leaderboard.length; i++) {
      var item = data.leaderboard[i];
      html += '<li>' + item.carId + ': ' + (item.steps || 0) + ' 步</li>';
    }
    $lb.innerHTML = html || '<li>暂无数据</li>';
  }

  // ═══════════════════════════════════════════════════════════
  // 事件
  // ═══════════════════════════════════════════════════════════

  $btnApply.addEventListener('click', async function () {
    var filters = collectFilters();
    $chartArea.innerHTML = '<p>加载中...</p>';
    var data = await fetchAnalysis(filters);
    if (data) {
      $chartArea.innerHTML = '<p>数据已加载（图表待实现）</p>';
      updateMetrics(data.data ? data.data.summary : data);
      updateLeaderboard(data);
    } else {
      $chartArea.innerHTML = '<p>数据加载失败</p>';
    }
  });

  $btnExport.addEventListener('click', async function () {
    var token = Auth.getToken();
    try {
      var resp = await fetch('/api/analysis/export', {
        headers: { 'Authorization': 'Bearer ' + (token || '') }
      });
      if (resp.ok) {
        alert('导出成功');
      } else {
        alert('导出失败，请检查权限');
      }
    } catch (e) {
      alert('导出失败: 网络错误');
    }
  });

  init();
})();
