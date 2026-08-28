(function () {
  'use strict';

  // 访问令牌：优先 URL（二维码 /?t=TOKEN），其次 sessionStorage（刷新后恢复）
  // 取出后立即用 history.replaceState 清除地址栏与历史记录中的 token
  var AUTH_TOKEN = sessionStorage.getItem('tv_token') || '';
  (function () {
    var m = location.search.match(/[?&]t=([^&]+)/);
    if (m) {
      AUTH_TOKEN = decodeURIComponent(m[1]);
      sessionStorage.setItem('tv_token', AUTH_TOKEN);
      history.replaceState(null, '', location.pathname);
    }
  })();

  var statusDot = document.getElementById('statusDot');
  var statusTitle = document.getElementById('statusTitle');
  var statusUrl = document.getElementById('statusUrl');

  function setStatus(text, online) {
    statusTitle.textContent = text;
    if (online) { statusDot.classList.add('online'); }
    else { statusDot.classList.remove('online'); }
  }

  // 公共请求包装：自动附加 token、统一错误语义（401/非 2xx 抛错）
  function authFetch(url, opts) {
    var finalOpts = Object.assign({}, opts);
    var headers = Object.assign({}, (opts && opts.headers) || {});
    if (AUTH_TOKEN) { headers['X-Auth-Token'] = AUTH_TOKEN; }
    finalOpts.headers = headers;
    return fetch(url, finalOpts).then(function (r) {
      if (r.status === 401) { throw new Error('unauthorized'); }
      if (!r.ok) { throw new Error('http ' + r.status); }
      return r;
    });
  }

  function api(url, body) {
    return authFetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  }

  // 成功反馈：按钮短暂显示对勾（500ms 后还原；防快速连点竞态——dataset 保存原文 + 清理定时器）
  // 兼容带 SVG 图标的按钮：优先改 <span> 文案，避免 textContent 赋值删掉图标
  function flashOk(btn) {
    var span = btn.querySelector('span');
    var original = btn.dataset.orig;
    if (!original) {
      original = span ? span.textContent : btn.textContent;
      btn.dataset.orig = original;
    }
    if (span) { span.textContent = '✓'; } else { btn.textContent = '✓'; }
    if (btn._flashTimer) { clearTimeout(btn._flashTimer); }
    btn._flashTimer = setTimeout(function () {
      if (span) { span.textContent = original; } else { btn.textContent = original; }
    }, 500);
  }

  // 打开网址
  var urlInput = document.getElementById('urlInput');
  document.getElementById('btnOpen').addEventListener('click', function () {
    var v = urlInput.value.trim();
    if (!v) { return; }
    api('/api/open', { url: v }).then(function () {
      flashOk(document.getElementById('btnOpen'));
    }).catch(function () {
      setStatus('发送失败（未连接或令牌失效）', false);
    });
  });
  urlInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { document.getElementById('btnOpen').click(); }
  });

  // 搜索：打开 Bing 搜索结果页（电视端）
  document.getElementById('btnSearch').addEventListener('click', function () {
    var v = urlInput.value.trim();
    if (!v) { return; }
    api('/api/open', { url: 'https://www.bing.com/search?q=' + encodeURIComponent(v) })
      .then(function () { flashOk(document.getElementById('btnSearch')); })
      .catch(function () { setStatus('发送失败（未连接或令牌失效）', false); });
  });

  // ---- 快捷站点管理（增删改，存电视端 /api/sites） ----
  var sites = [];
  var quickBox = document.getElementById('quickSites');
  var editPanel = document.getElementById('editPanel');
  var editList = document.getElementById('editList');

  function renderSites() {
    quickBox.innerHTML = '';
    sites.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'site';
      b.textContent = s.name;
      b.addEventListener('click', function () {
        api('/api/open', { url: s.url }).then(function () { flashOk(b); });
      });
      quickBox.appendChild(b);
    });
    // 编辑入口
    var edit = document.createElement('button');
    edit.className = 'site edit';
    edit.textContent = '编辑';
    edit.addEventListener('click', openEdit);
    quickBox.appendChild(edit);
  }

  function openEdit() {
    editList.innerHTML = '';
    (sites.length ? sites : [{ name: '', url: '' }]).forEach(function (s) { addEditRow(s.name, s.url); });
    quickBox.style.display = 'none'; // 编辑时隐藏快捷区，避免双区挤压
    editPanel.hidden = false;
  }
  function addEditRow(name, url) {
    var row = document.createElement('div');
    row.className = 'edit-row';
    row.innerHTML = '<input class="edit-name" placeholder="名称，如 B站">' +
      '<input class="edit-url" placeholder="网址，如 https://www.bilibili.com">' +
      '<button class="edit-del">删除</button>';
    row.querySelector('.edit-name').value = name || '';
    row.querySelector('.edit-url').value = url || '';
    row.querySelector('.edit-del').addEventListener('click', function () { row.remove(); });
    editList.appendChild(row);
  }
  function collectSites() {
    var list = [];
    document.querySelectorAll('.edit-row').forEach(function (row) {
      var name = row.querySelector('.edit-name').value.trim();
      var url = row.querySelector('.edit-url').value.trim();
      if (name || url) { list.push({ name: name || url, url: url }); }
    });
    return list;
  }
  function saveSites() {
    var list = collectSites();
    api('/api/sites', { sites: list })
      .then(function () {
        sites = list;
        editPanel.hidden = true;
        quickBox.style.display = '';
        renderSites();
      })
      .catch(function () { setStatus('保存失败（未连接或令牌失效）', false); });
  }
  document.getElementById('editAdd').addEventListener('click', function () { addEditRow('', ''); });
  document.getElementById('editSave').addEventListener('click', saveSites);
  document.getElementById('editCancel').addEventListener('click', function () {
    editPanel.hidden = true;
    quickBox.style.display = '';
  });

  function loadSites() {
    authFetch('/api/sites').then(function (r) { return r.json(); })
      .then(function (d) { sites = d.sites || []; renderSites(); })
      .catch(function () { /* 未连接时保留空 */ });
  }
  loadSites();

  // 触控震动反馈（Android Chrome 支持；用户手势内调用有效）
  function vibrate(ms) {
    if (navigator.vibrate) {
      try { navigator.vibrate(ms); } catch (e) { /* 忽略不支持 */ }
    }
  }

  // 遥控按键：单击立即发送 + 长按连发
  var repeatTimer = null;
  function startRepeat(key) {
    stopRepeat();
    repeatTimer = setInterval(function () { api('/api/key', { key: key }); }, 180);
  }
  function stopRepeat() {
    if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null; }
  }
  document.querySelectorAll('.key').forEach(function (b) {
    var key = b.getAttribute('data-key');
    // 鼠标：mousedown 已立即发送，click（detail>0）跳过避免重复；键盘激活 click（detail===0）发送
    b.addEventListener('click', function (e) {
      if (e.detail === 0) { api('/api/key', { key: key }); }
    });
    // 触摸：立即发送一次 + 长按连发（preventDefault 抑制合成 click）+ 震动反馈
    b.addEventListener('touchstart', function (e) {
      e.preventDefault();
      vibrate(15); // 按键触感反馈（短促 15ms）
      api('/api/key', { key: key });
      startRepeat(key);
    });
    b.addEventListener('touchend', stopRepeat);
    b.addEventListener('touchcancel', stopRepeat);
    // 鼠标：立即发送一次 + 长按连发（click 不再补发）
    b.addEventListener('mousedown', function () {
      api('/api/key', { key: key });
      startRepeat(key);
    });
    b.addEventListener('mouseup', stopRepeat);
    b.addEventListener('mouseleave', stopRepeat);
  });

  // 文字输入到电视（支持手机键盘 Enter 直接发送）
  var textInput = document.getElementById('textInput');
  textInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { document.getElementById('btnSendText').click(); }
  });
  document.getElementById('btnSendText').addEventListener('click', function () {
    var v = document.getElementById('textInput').value;
    if (v) {
      api('/api/input', { text: v }).then(function () {
        flashOk(document.getElementById('btnSendText'));
      }).catch(function () {
        setStatus('发送失败（未连接或令牌失效）', false);
      });
    }
  });

  // ---- 鼠标模式（触摸板） ----
  var remotePanel = document.getElementById('remotePanel');
  var mousePanel = document.getElementById('mousePanel');
  var mouseOn = false;

  function enterMouse(persist) {
    mouseOn = true;
    remotePanel.style.display = 'none';
    mousePanel.hidden = false;
    if (persist !== false) { sessionStorage.setItem('tv_mouse', '1'); }
    api('/api/mouseMode', { on: true });
  }
  function exitMouse() {
    mouseOn = false;
    remotePanel.style.display = '';
    mousePanel.hidden = true;
    sessionStorage.removeItem('tv_mouse');
    api('/api/mouseMode', { on: false });
  }
  document.getElementById('btnMouseMode').addEventListener('click', function () { enterMouse(); });
  document.getElementById('mouseExit').addEventListener('click', exitMouse);
  // 刷新控制页后恢复鼠标模式（电视端光标仍在）
  if (sessionStorage.getItem('tv_mouse') === '1') { enterMouse(false); }

  // 触摸板：滑动移光标（系数 2.2 适配电视分辨率）、短按=点击
  var pad = document.getElementById('touchpad');
  var padX = 0, padY = 0, padT = 0, padMoved = false;
  pad.addEventListener('touchstart', function (e) {
    e.preventDefault();
    padX = e.touches[0].clientX;
    padY = e.touches[0].clientY;
    padT = Date.now();
    padMoved = false;
  });
  var pendX = 0, pendY = 0, moveTimer = null;
  function flushMove() {
    moveTimer = null;
    if (pendX || pendY) {
      api('/api/mouse', { type: 'move', dx: Math.round(pendX * 2.2), dy: Math.round(pendY * 2.2) });
      pendX = 0; pendY = 0;
    }
  }
  pad.addEventListener('touchmove', function (e) {
    e.preventDefault();
    var dx = e.touches[0].clientX - padX;
    var dy = e.touches[0].clientY - padY;
    padX = e.touches[0].clientX;
    padY = e.touches[0].clientY;
    if (Math.abs(dx) + Math.abs(dy) > 2) padMoved = true;
    pendX += dx; pendY += dy;
    if (!moveTimer) { moveTimer = setTimeout(flushMove, 50); } // 50ms 批量合并，避免请求风暴
  });
  pad.addEventListener('touchend', function () {
    if (!padMoved && Date.now() - padT < 250) {
      api('/api/mouse', { type: 'click' });
    }
  });

  // 滚动条：上下滑动翻页
  var sb = document.getElementById('scrollbar');
  var sbY = 0;
  sb.addEventListener('touchstart', function (e) { e.preventDefault(); sbY = e.touches[0].clientY; });
  sb.addEventListener('touchmove', function (e) {
    e.preventDefault();
    var dy = e.touches[0].clientY - sbY;
    sbY = e.touches[0].clientY;
    api('/api/mouse', { type: 'scroll', dy: Math.round(dy * 3) });
  });


  // 状态轮询
  function poll() {
    if (!AUTH_TOKEN) {
      setStatus('未连接（缺少令牌，请通过二维码打开）', false);
      return;
    }
    authFetch('/api/status').then(function (r) {
      return r.json();
    }).then(function (s) {
      setStatus(s.title || '电视浏览器', true);
      statusUrl.textContent = s.url || '';
    }).catch(function () {
      setStatus('未连接电视', false);
      statusUrl.textContent = '';
    });
  }
  poll();
  setInterval(poll, 3000);
})();
