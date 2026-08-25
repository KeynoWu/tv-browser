(function () {
  'use strict';

  var API = {
    open: function (url) {
      return fetch('/api/open', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: url }) });
    },
    key: function (k) {
      return fetch('/api/key', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key: k }) });
    },
    input: function (text) {
      return fetch('/api/input', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: text }) });
    },
    status: function () {
      return fetch('/api/status').then(function (r) { return r.json(); });
    }
  };

  // 打开网址
  var urlInput = document.getElementById('urlInput');
  document.getElementById('btnOpen').addEventListener('click', function () {
    var v = urlInput.value.trim();
    if (v) { API.open(v); }
  });
  urlInput.addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { document.getElementById('btnOpen').click(); }
  });

  // 搜索：打开 Bing 搜索结果页（电视端）
  document.getElementById('btnSearch').addEventListener('click', function () {
    var v = urlInput.value.trim();
    if (!v) { return; }
    API.open('https://www.bing.com/search?q=' + encodeURIComponent(v));
  });

  // 快捷站点
  document.querySelectorAll('.site').forEach(function (b) {
    b.addEventListener('click', function () { API.open(b.getAttribute('data-url')); });
  });

  // 遥控按键：单击 + 长按连发
  var repeatTimer = null;
  function startRepeat(key) {
    stopRepeat();
    repeatTimer = setInterval(function () { API.key(key); }, 180);
  }
  function stopRepeat() {
    if (repeatTimer) { clearInterval(repeatTimer); repeatTimer = null; }
  }
  document.querySelectorAll('.key').forEach(function (b) {
    var key = b.getAttribute('data-key');
    b.addEventListener('click', function () { API.key(key); });
    b.addEventListener('touchstart', function (e) { e.preventDefault(); startRepeat(key); });
    b.addEventListener('touchend', stopRepeat);
    b.addEventListener('touchcancel', stopRepeat);
    b.addEventListener('mousedown', function () { startRepeat(key); });
    b.addEventListener('mouseup', stopRepeat);
    b.addEventListener('mouseleave', stopRepeat);
  });

  // 文字输入到电视
  document.getElementById('btnSendText').addEventListener('click', function () {
    var v = document.getElementById('textInput').value;
    if (v) { API.input(v); }
  });

  // 状态轮询
  var dot = document.getElementById('statusDot');
  var titleEl = document.getElementById('statusTitle');
  var urlEl = document.getElementById('statusUrl');

  function poll() {
    API.status().then(function (s) {
      dot.classList.add('online');
      titleEl.textContent = s.title || '电视浏览器';
      urlEl.textContent = s.url || '';
    }).catch(function () {
      dot.classList.remove('online');
      titleEl.textContent = '未连接电视';
      urlEl.textContent = '';
    });
  }
  poll();
  setInterval(poll, 3000);
})();
