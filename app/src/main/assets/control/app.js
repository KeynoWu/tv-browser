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

  // 打开网址
  var urlInput = document.getElementById('urlInput');
  document.getElementById('btnOpen').addEventListener('click', function () {
    var v = urlInput.value.trim();
    if (!v) { return; }
    api('/api/open', { url: v }).catch(function () {
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
      .catch(function () { setStatus('发送失败（未连接或令牌失效）', false); });
  });

  // 快捷站点
  document.querySelectorAll('.site').forEach(function (b) {
    b.addEventListener('click', function () {
      api('/api/open', { url: b.getAttribute('data-url') });
    });
  });

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
    // 触摸：立即发送一次 + 长按连发（preventDefault 抑制合成 click）
    b.addEventListener('touchstart', function (e) {
      e.preventDefault();
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

  // 文字输入到电视
  document.getElementById('btnSendText').addEventListener('click', function () {
    var v = document.getElementById('textInput').value;
    if (v) {
      api('/api/input', { text: v }).catch(function () {
        setStatus('发送失败（未连接或令牌失效）', false);
      });
    }
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
