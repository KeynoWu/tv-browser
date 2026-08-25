(function () {
  'use strict';

  // 从 URL 读取访问令牌（二维码 URL 形如 /?t=TOKEN）
  var AUTH_TOKEN = '';
  (function () {
    var m = location.search.match(/[?&]t=([^&]+)/);
    if (m) { AUTH_TOKEN = decodeURIComponent(m[1]); }
  })();

  var statusDot = document.getElementById('statusDot');
  var statusTitle = document.getElementById('statusTitle');
  var statusUrl = document.getElementById('statusUrl');

  function setStatus(text, online) {
    statusTitle.textContent = text;
    if (online) { statusDot.classList.add('online'); }
    else { statusDot.classList.remove('online'); }
  }

  function api(url, body) {
    var opts = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    };
    if (AUTH_TOKEN) { opts.headers['X-Auth-Token'] = AUTH_TOKEN; }
    if (body) { opts.body = JSON.stringify(body); }
    return fetch(url, opts).then(function (r) {
      if (r.status === 401) { throw new Error('unauthorized'); }
      if (!r.ok) { throw new Error('http ' + r.status); }
      return r;
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
    b.addEventListener('click', function () { api('/api/key', { key: key }); });
    b.addEventListener('touchstart', function (e) {
      e.preventDefault();
      api('/api/key', { key: key }); // 立即发送一次，单击即有响应
      startRepeat(key);              // 长按进入连发
    });
    b.addEventListener('touchend', stopRepeat);
    b.addEventListener('touchcancel', stopRepeat);
    b.addEventListener('mousedown', function () { startRepeat(key); });
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
    var opts = { headers: {} };
    if (AUTH_TOKEN) { opts.headers['X-Auth-Token'] = AUTH_TOKEN; }
    fetch('/api/status', opts).then(function (r) {
      if (r.status === 401) { throw new Error('unauthorized'); }
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
