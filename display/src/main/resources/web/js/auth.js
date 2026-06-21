/**
 * 变电站巡检仿真系统 — 认证模块。
 *
 * 职责：
 *   1. 登录/登出
 *   2. Token 管理（localStorage）
 *   3. 未登录时自动跳转 login.html
 *   4. 权限控制（admin vs user）
 */

(function () {
  'use strict';

  var TOKEN_KEY = 'auth_token';
  var LOGIN_URL = '/login.html';

  // ═══════════════════════════════════════════════════════════
  // Token 管理
  // ═══════════════════════════════════════════════════════════

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
  }

  function setToken(token) {
    localStorage.setItem(TOKEN_KEY, token);
  }

  function clearToken() {
    localStorage.removeItem(TOKEN_KEY);
  }

  // ═══════════════════════════════════════════════════════════
  // API 调用
  // ═══════════════════════════════════════════════════════════

  async function apiCall(method, path, body) {
    var headers = { 'Content-Type': 'application/json' };
    var token = getToken();
    if (token) {
      headers['Authorization'] = 'Bearer ' + token;
    }
    var opts = { method: method, headers: headers };
    if (body) { opts.body = JSON.stringify(body); }
    var resp = await fetch(path, opts);
    if (resp.status === 401) {
      clearToken();
      if (window.location.pathname.indexOf('login.html') === -1) {
        window.location.href = LOGIN_URL;
      }
      return null;
    }
    return resp.json();
  }

  async function login(username, password) {
    return apiCall('POST', '/api/auth/login', { username: username, password: password });
  }

  async function logout() {
    await apiCall('POST', '/api/auth/logout');
    clearToken();
    window.location.href = LOGIN_URL;
  }

  async function register(username, password, displayName, role) {
    return apiCall('POST', '/api/auth/register', {
      username: username,
      password: password,
      displayName: displayName,
      role: role
    });
  }

  async function changePassword(oldPw, newPw) {
    return apiCall('POST', '/api/auth/change-password', {
      oldPassword: oldPw,
      newPassword: newPw
    });
  }

  async function getCurrentUser() {
    return apiCall('GET', '/api/auth/me');
  }

  // ═══════════════════════════════════════════════════════════
  // 页面加载检查（所有非登录页调用）
  // ═══════════════════════════════════════════════════════════

  async function checkAuth() {
    if (window.location.pathname.indexOf('login.html') !== -1) {
      // 已登录则直接跳主页面
      if (getToken()) {
        window.location.href = '/dashboard.html';
      }
      return null;
    }
    if (!getToken()) {
      window.location.href = LOGIN_URL;
      return null;
    }
    try {
      var user = await getCurrentUser();
      if (!user || !user.success) {
        clearToken();
        window.location.href = LOGIN_URL;
        return null;
      }
      return user;
    } catch (e) {
      return null;
    }
  }

  // ═══════════════════════════════════════════════════════════
  // 权限控制 UI
  // ═══════════════════════════════════════════════════════════

  var ROLE_NAMES = { admin: '管理员', simulator: '仿真员', analyst: '统计分析员' };

  function applyPermissions(role) {
    if (role === 'admin') { return; } // 管理员全部权限

    var isSimulator = (role === 'simulator');
    var isAnalyst  = (role === 'analyst');

    // 统计分析员：隐藏操作按钮
    if (isAnalyst) {
      var ids = ['btn-start', 'btn-pause', 'btn-reset', 'btn-addcar'];
      for (var i = 0; i < ids.length; i++) {
        var el = document.getElementById(ids[i]);
        if (el) { el.style.display = 'none'; }
      }
      var cfgIds = ['cfg-obstacleRatio', 'cfg-algorithm', 'cfg-tickInterval'];
      for (var j = 0; j < cfgIds.length; j++) {
        var cel = document.getElementById(cfgIds[j]);
        if (cel) { cel.disabled = true; }
      }
    }

    // 统计分析员隐藏统计分析链接
    if (isSimulator) {
      var navLink = document.querySelector('.nav-link[href="analysis.html"]');
      if (navLink) { navLink.style.display = 'none'; }
    }
  }

  function renderNavBar(user) {
    var userEl = document.getElementById('info-user');
    if (userEl && user) {
      var label = user.displayName || ROLE_NAMES[user.role] || user.username || '用户';
      userEl.textContent = '\uD83D\uDC64 ' + label;
    }
    var logoutBtn = document.getElementById('btn-logout');
    if (logoutBtn) {
      logoutBtn.addEventListener('click', logout);
    }
    // 修改密码弹窗
    var cpwBtn  = document.getElementById('btn-changepw');
    var cpwModal = document.getElementById('changepw-modal');
    if (cpwBtn && cpwModal) {
      cpwBtn.addEventListener('click', function(e){ e.preventDefault(); cpwModal.style.display='flex'; });
      document.getElementById('cpw-cancel').addEventListener('click', function(){ cpwModal.style.display='none'; });
      document.getElementById('cpw-submit').addEventListener('click', async function(){
        var oldPw = document.getElementById('cpw-old').value;
        var newPw = document.getElementById('cpw-new').value;
        var cfmPw = document.getElementById('cpw-confirm').value;
        var errEl = document.getElementById('cpw-err');
        if (!oldPw || !newPw || !cfmPw) { errEl.textContent = '请填写所有字段'; return; }
        if (newPw.length < 6) { errEl.textContent = '新密码至少6位'; return; }
        if (newPw !== cfmPw) { errEl.textContent = '两次新密码不一致'; return; }
        try {
          var resp = await changePassword(oldPw, newPw);
          if (resp && resp.success) { alert('密码修改成功'); cpwModal.style.display='none'; }
          else { errEl.textContent = (resp && resp.error) || '修改失败'; }
        } catch(e) { errEl.textContent = '网络错误'; }
      });
    }
  }

  // 暴露到全局
  window.Auth = {
    login: login,
    logout: logout,
    register: register,
    changePassword: changePassword,
    checkAuth: checkAuth,
    getCurrentUser: getCurrentUser,
    getToken: getToken,
    applyPermissions: applyPermissions,
    renderNavBar: renderNavBar
  };

})();
