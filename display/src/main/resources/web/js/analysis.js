/**
 * 统计分析 — 卡片网格 + 粒子背景 + 全屏详情（localStorage sim_records）
 * 改进版：环图 + 堆叠柱 + KPI 指标卡
 */
(function () {
  'use strict';

  var STORAGE_KEY = 'sim_records';
  var records = [];
  var $wrap, $fsd, $fsdBody, $fsdTitle;

  var EFF_GREEN = '#10B981';
  var WASTED_ORANGE = '#F97316';

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

  function getEfficiency(r) {
    if (r.efficiencyPercent != null) { return r.efficiencyPercent; }
    if (r.totalSteps > 0 && r.totalEffectiveSteps != null) {
      return Math.round(r.totalEffectiveSteps / r.totalSteps * 100);
    }
    return null;
  }

  function getWasted(r) {
    if (r.wastedSteps != null) { return r.wastedSteps; }
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : 0;
    return (r.totalSteps || 0) - effective;
  }

  function effColor(pct) {
    if (pct == null) { return '#94A3B8'; }
    if (pct > 60) { return EFF_GREEN; }
    if (pct >= 40) { return '#F59E0B'; }
    return '#EF4444';
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
      var eff = getEfficiency(r);
      var effDisplay = eff != null ? eff + '%' : '--';
      var effClr = effColor(eff);
      var algo = r.algorithm || '--';
      var obsRatio = r.obstacleRatio != null ? Math.round(r.obstacleRatio * 100) + '%' : '--';
      html += '<div class="a-card" onclick="showDetail(' + i + ')">'
        + '<button class="a-del" onclick="event.stopPropagation();delRecord(' + i + ')">✕</button>'
        + '<h4>仿真记录 #' + (i + 1) + '</h4>'
        + '<p>' + (r.date || fmtDate(r.timestamp)) + '</p>'
        + '<div class="a-rate" style="color:' + effClr + '">' + effDisplay + '</div>'
        + '<div class="a-rate-label">探索效率</div>'
        + '<p>' + (r.carCount || 0) + ' 车 · ' + (r.duration || 0) + 's · ' + algo + ' · 障碍 ' + obsRatio + '</p>'
        + '</div>';
    }
    html += '</div>';
    $wrap.innerHTML = html;
  }

  function delRecord(i) {
    if (!confirm('确定要删除该仿真记录吗？此操作不可恢复。')) {
      return;
    }
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
    var eff = getEfficiency(r);
    var effDisplay = eff != null ? eff + '%' : '--';
    var wasted = getWasted(r);
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : '--';
    var algo = r.algorithm || '--';
    var obsRatio = r.obstacleRatio != null ? Math.round(r.obstacleRatio * 100) + '%' : '--';
    var balance = r.balanceScore != null ? Math.round(r.balanceScore * 100) + '%' : '--';

    var html =
      '<div class="krow">'
      + '<div class="kcard"><h5>探索效率</h5><div class="v" style="color:' + effColor(eff) + '">' + effDisplay + '</div></div>'
      + '<div class="kcard"><h5>总步数</h5><div class="v" style="color:#3B82F6">' + (r.totalSteps || 0) + '</div></div>'
      + '<div class="kcard"><h5>有效步数</h5><div class="v" style="color:#10B981">' + effective + '</div></div>'
      + '<div class="kcard"><h5>无效步数</h5><div class="v" style="color:#F97316">' + wasted + '</div></div>'
      + '<div class="kcard"><h5>耗时</h5><div class="v" style="color:#8B5CF6">' + (r.duration || 0) + 's</div></div>'
      + '</div>'
      + '<div class="info-row">'
      + '<span>节拍: ' + (r.tick || 0) + '</span>'
      + '<span>车辆: ' + (r.carCount || 0) + '</span>'
      + '<span>算法: ' + algo + '</span>'
      + '<span>障碍率: ' + obsRatio + '</span>'
      + '<span>负载均衡: ' + balance + '</span>'
      + '</div>'
      + '<div class="krow"><div class="cbox" style="flex:1;min-height:320px" id="d1"></div>'
      + '<div class="cbox" style="flex:1;min-height:320px" id="d2"></div></div>';
    $fsdBody.innerHTML = html;
    $fsd.classList.add('active');
    setTimeout(function () { renderCharts(r); }, 300);
  }
  window.showDetail = showDetail;

  function getCarData(r) {
    var cars = r.cars || [];
    if (!cars.length) {
      var count = r.carCount || 3;
      var labels = [], effVals = [], wastedVals = [];
      var perCarSteps = Math.floor((r.totalSteps || 0) / count);
      var perCarEff = r.totalEffectiveSteps != null ? Math.floor(r.totalEffectiveSteps / count) : 0;
      for (var i = 0; i < count; i++) {
        labels.push('Car' + (i + 1));
        effVals.push(perCarEff);
        wastedVals.push(Math.max(0, perCarSteps - perCarEff));
      }
      return { labels: labels, effective: effVals, wasted: wastedVals };
    }
    var labels2 = [], effVals2 = [], wastedVals2 = [];
    for (var j = 0; j < cars.length; j++) {
      labels2.push(cars[j].carId || ('Car' + (j + 1)));
      var carEff = cars[j].effectiveSteps || 0;
      var carSteps = cars[j].steps || 0;
      effVals2.push(carEff);
      wastedVals2.push(Math.max(0, carSteps - carEff));
    }
    return { labels: labels2, effective: effVals2, wasted: wastedVals2 };
  }

  function renderCharts(r) {
    var carData = getCarData(r);
    var effective = r.totalEffectiveSteps != null ? r.totalEffectiveSteps : 0;
    var wasted = getWasted(r);
    donutC('d1', '有效 vs 无效步数', effective, wasted);
    stackedBarC('d2', '各车有效 / 无效步数', carData.labels, carData.effective, carData.wasted);
  }

  // ══════════════ 环形图：有效 vs 无效
  function donutC(id, title, effective, wasted) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = b.querySelector('canvas');
    if (!c) { c = document.createElement('canvas'); b.appendChild(c); }
    c.width = w; c.height = h;
    c.style.width = '100%'; c.style.height = '100%';
    var ctx = c.getContext('2d');

    var total = effective + wasted;
    var pct = total > 0 ? Math.round(effective / total * 100) : 0;
    var clr = effColor(pct);

    var cx = w / 2, cy = h / 2 + 6;
    var outerR = Math.min(w, h) / 2 - 34;
    var innerR = outerR * 0.62;
    var startAngle = -Math.PI / 2;

    // 标题
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 13px sans-serif'; ctx.textAlign = 'left';
    ctx.fillText(title, 10, 18);

    // 灰色底环（无效）
    ctx.beginPath();
    ctx.arc(cx, cy, outerR, 0, Math.PI * 2);
    ctx.arc(cx, cy, innerR, Math.PI * 2, 0, true);
    ctx.fillStyle = '#F1F5F9';
    ctx.fill();

    // 有效弧线
    if (total > 0) {
      var sweep = (effective / total) * Math.PI * 2;
      ctx.beginPath();
      ctx.arc(cx, cy, outerR, startAngle, startAngle + sweep);
      ctx.arc(cx, cy, innerR, startAngle + sweep, startAngle, true);
      ctx.fillStyle = clr;
      ctx.fill();
    }

    // 中心数字
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 38px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText(pct + '%', cx, cy - 2);
    ctx.fillStyle = '#64748B'; ctx.font = '12px sans-serif';
    ctx.fillText('探索效率', cx, cy + 24);

    // 图例
    var legendY = h - 14;
    ctx.fillStyle = clr;
    ctx.fillRect(12, legendY - 7, 12, 12);
    ctx.fillStyle = '#475569'; ctx.font = '12px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    ctx.fillText('有效 ' + effective, 28, legendY);

    ctx.fillStyle = '#F1F5F9';
    ctx.fillRect(120, legendY - 7, 12, 12);
    ctx.strokeStyle = '#CBD5E1'; ctx.lineWidth = 1;
    ctx.strokeRect(120, legendY - 7, 12, 12);
    ctx.fillStyle = '#475569';
    ctx.fillText('无效 ' + wasted, 136, legendY);
  }

  // ══════════════ 堆叠柱状图：各车 有效 + 无效
  function stackedBarC(id, title, labels, effectiveVals, wastedVals) {
    var b = document.getElementById(id);
    if (!b) { return; }
    var w = b.clientWidth - 20, h = b.clientHeight - 12;
    if (w < 80 || h < 80) { return; }
    var c = b.querySelector('canvas');
    if (!c) { c = document.createElement('canvas'); b.appendChild(c); }
    c.width = w; c.height = h;
    c.style.width = '100%'; c.style.height = '100%';
    var ctx = c.getContext('2d');
    var pad = { top: 36, right: 24, bottom: 36, left: 52 };
    var pw = w - pad.left - pad.right, ph = h - pad.top - pad.bottom;
    var n = labels.length;

    var maxTotal = 5;
    for (var i = 0; i < n; i++) {
      maxTotal = Math.max(maxTotal, (effectiveVals[i] || 0) + (wastedVals[i] || 0));
    }

    var bw = Math.min(40, pw / n * 0.55);
    var gap = pw / n;

    // 标题
    ctx.fillStyle = '#1E293B'; ctx.font = 'bold 13px sans-serif'; ctx.textAlign = 'left';
    ctx.fillText(title, pad.left, 18);

    for (var i = 0; i < n; i++) {
      var eff = effectiveVals[i] || 0;
      var wst = wastedVals[i] || 0;
      var total = eff + wst;
      var totalH = total / maxTotal * ph;
      var effH = total > 0 ? (eff / total) * totalH : 0;
      var wstH = totalH - effH;

      var x = pad.left + i * gap + gap / 2 - bw / 2;
      var bottomY = h - pad.bottom;

      if (wstH > 0.5) {
        ctx.fillStyle = WASTED_ORANGE;
        ctx.fillRect(x, bottomY - totalH, bw, wstH);
      }
      if (effH > 0.5) {
        ctx.fillStyle = EFF_GREEN;
        ctx.fillRect(x, bottomY - effH, bw, effH);
      }

      ctx.fillStyle = '#475569'; ctx.font = '11px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'bottom';
      ctx.fillText(total, x + bw / 2, bottomY - totalH - 4);

      ctx.fillStyle = '#64748B'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top';
      ctx.fillText(labels[i], x + bw / 2, bottomY + 6);
    }

    // 图例
    var legendY = 14;
    var legendX = w - pad.right - 140;
    ctx.fillStyle = EFF_GREEN;
    ctx.fillRect(legendX, legendY, 10, 10);
    ctx.fillStyle = '#475569'; ctx.font = '11px sans-serif'; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    ctx.fillText('有效步数', legendX + 14, legendY + 5);

    ctx.fillStyle = WASTED_ORANGE;
    ctx.fillRect(legendX + 70, legendY, 10, 10);
    ctx.fillStyle = '#475569';
    ctx.fillText('无效步数', legendX + 84, legendY + 5);
  }

  init();
})();
