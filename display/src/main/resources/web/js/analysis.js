/**
 * 统计分析 — 卡片网格 + 粒子背景 + 全屏详情（localStorage sim_records）
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'sim_records';
  var records = [];
  var $wrap, $fsd, $fsdBody, $fsdTitle;
  var colors = ['#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6'];

  async function init() {
    var user = await Auth.checkAuth();
    if (!user) { return; }
    Auth.renderNavBar(user);
    $wrap = document.getElementById('list-wrap');
    $fsd = document.getElementById('fsd');
    $fsdBody = document.getElementById('fsd-body');
    $fsdTitle = document.getElementById('fsd-title');
    document.getElementById('fsd-close').addEventListener('click', function () {
      $fsd.classList.remove('active');
    });
    loadList();
  }

  function fmtDate(ts) {
    var d = new Date(ts);
    return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate() + ' '
      + d.getHours() + ':' + String(d.getMinutes()).padStart(2, '0') + ':'
      + String(d.getSeconds()).padStart(2, '0');
  }

  function loadList() {
    try { records = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); }
    catch (e) { records = []; }
    if (!records.length) {
      $wrap.innerHTML = '<div class="empty"><div class="icon">📋</div><p>暂无仿真记录</p></div>';
      return;
    }
    var html = '<div class="a-grid">';
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      var eff = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : '--';
      html += '<div class="a-card" onclick="showDetail(' + i + ')">'
        + '<button class="a-del" onclick="event.stopPropagation();delRecord(' + i + ')">✕</button>'
        + '<h4>仿真记录 #' + (i + 1) + '</h4>'
        + '<p>' + (r.date || fmtDate(r.timestamp)) + '</p>'
        + '<div class="a-rate">' + r.explorationRate + '%</div>'
        + '<p>步数:' + r.totalSteps + ' | 有效:' + eff + ' | 耗时:' + (r.duration || 0)
        + 's | 车辆:' + r.carCount + '</p>'
        + '</div>';
    }
    html += '</div>';
    $wrap.innerHTML = html;
  }

  function delRecord(i) {
    records.splice(i, 1);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(records));
    loadList();
    $fsd.classList.remove('active');
  }
  window.delRecord = delRecord;

  function showDetail(i) {
    var r = records[i];
    if (!r) { return; }
    $fsdTitle.textContent = '仿真记录 #' + (i + 1) + ' - ' + (r.date || fmtDate(r.timestamp));
    var eff = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : '--';
    var html =
      '<div class="krow">'
      + '<div class="kcard"><h5>探索率</h5><div class="v" style="color:#3B82F6">' + r.explorationRate + '%</div></div>'
      + '<div class="kcard"><h5>总步数</h5><div class="v" style="color:#10B981">' + r.totalSteps + '</div></div>'
      + '<div class="kcard"><h5>有效步数</h5><div class="v" style="color:#059669">' + eff + '</div></div>'
      + '<div class="kcard"><h5>耗时</h5><div class="v" style="color:#F59E0B">' + (r.duration || 0) + 's</div></div>'
      + '</div>'
      + '<div class="krow"><div class="cbox" style="flex:1;min-height:300px" id="d1"></div>'
      + '<div class="cbox" style="flex:1;min-height:300px" id="d2"></div></div>'
      + '<div class="krow"><div class="cbox" style="flex:1;min-height:300px" id="d3"></div>'
      + '<div class="cbox" style="flex:1;min-height:300px" id="d4"></div></div>';
    $fsdBody.innerHTML = html;
    $fsd.classList.add('active');
    setTimeout(function () { renderCharts(r); }, 300);
  }
  window.showDetail = showDetail;

  function carLabelsAndSteps(r, field) {
    var cars = r.cars || [];
    if (!cars.length) {
      var count = r.carCount || 3;
      var labels = [], vals = [];
      for (var i = 0; i < count; i++) {
        labels.push('Car' + (i + 1));
        vals.push(Math.floor((r.totalSteps || 0) / count));
      }
      return { labels: labels, vals: vals };
    }
    var labels2 = [], vals2 = [];
    for (var j = 0; j < cars.length; j++) {
      labels2.push(cars[j].carId || ('Car' + (j + 1)));
      vals2.push(cars[j][field] || 0);
    }
    return { labels: labels2, vals: vals2 };
  }

  function renderCharts(r) {
    var stepData = carLabelsAndSteps(r, 'steps');
    var effData = carLabelsAndSteps(r, 'effectiveSteps');
    barC('d1', '各车总步数分布', stepData.labels, stepData.vals);
    barC('d2', '各车有效步数分布', effData.labels, effData.vals);
    var efficiency = r.totalSteps > 0 && r.totalEffectiveSteps != null
      ? Math.round(r.totalEffectiveSteps / r.totalSteps * 100) : 0;
    barC('d3', '关键指标对比', ['探索率', '有效率', '车辆数'],
      [r.explorationRate, efficiency, (r.carCount || 0) * 10]);
    progC('d4', '探索进度', r.explorationRate);
  }

  function barC(id, title, labels, vals) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = b.querySelector('canvas');
    if (!c) { c = document.createElement('canvas'); b.appendChild(c); }
    c.width = w; c.height = h;
    c.style.width = '100%'; c.style.height = '100%';
    var ctx = c.getContext('2d');
    var pad = { top: 36, right: 16, bottom: 32, left: 48 };
    var pw = w - pad.left - pad.right, ph = h - pad.top - pad.bottom;
    var max = Math.max.apply(null, vals.concat([5]));
    var bw = pw / labels.length * 0.55, gap = pw / labels.length * 0.45;
    ctx.fillStyle = '#1E293B'; ctx.font = '13px sans-serif';
    ctx.fillText(title, pad.left, 18);
    for (var i = 0; i < labels.length; i++) {
      var bh = (vals[i] || 0) / max * ph;
      var x = pad.left + i * (bw + gap) + gap / 2;
      var y = h - pad.bottom - bh;
      ctx.fillStyle = colors[i % 5];
      ctx.fillRect(x, y, bw, bh);
      ctx.fillStyle = '#64748B'; ctx.font = '11px sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(labels[i], x + bw / 2, h - pad.bottom + 14);
      ctx.fillText(vals[i] || 0, x + bw / 2, y - 6);
    }
  }

  function progC(id, title, rate) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = b.querySelector('canvas');
    if (!c) { c = document.createElement('canvas'); b.appendChild(c); }
    c.width = w; c.height = h;
    c.style.width = '100%'; c.style.height = '100%';
    var ctx = c.getContext('2d');
    ctx.fillStyle = '#1E293B'; ctx.font = '13px sans-serif';
    ctx.fillText(title, 8, 18);
    var barH = 50, barW = w - 80, barX = 40, barY = h / 2 - barH / 2;
    ctx.fillStyle = '#E2E8F0'; ctx.fillRect(barX, barY, barW, barH);
    var fillW = barW * rate / 100;
    ctx.fillStyle = rate > 80 ? '#10B981' : rate > 50 ? '#3B82F6' : '#F59E0B';
    ctx.fillRect(barX, barY, fillW, barH);
    ctx.fillStyle = '#FFF'; ctx.font = 'bold 20px sans-serif'; ctx.textAlign = 'center';
    ctx.fillText(rate + '%', barX + barW / 2, barY + barH / 2 + 7);
    ctx.fillStyle = '#64748B'; ctx.font = '11px sans-serif';
    ctx.fillText('0%', barX, barY + barH + 16);
    ctx.fillText('100%', barX + barW, barY + barH + 16);
  }

  init();
})();
