// 电视浏览器控制链路验证脚本
// 用法: node web/verify.js
// 模拟 RemoteServer(RemoteServer.kt) 的路由与 token 鉴权行为，加载真实的 assets/control 文件，
// 并按手机控制页 app.js 的调用格式发起完整请求序列，验证链路。
const http = require('http');
const fs = require('fs');
const path = require('path');

const CONTROL_DIR = path.join(__dirname, '../app/src/main/assets/control');
const PORT = 18080;
const TOKEN = 'TESTTOKEN1234567890';

// 内存态快捷站点（模拟电视端 sites.json）
let memSites = { sites: [{ name: 'B站', url: 'https://www.bilibili.com' }] };

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
};

function send(res, code, ct, body) {
  res.writeHead(code, { 'Content-Type': ct });
  res.end(body);
}

function sendFile(res, name) {
  const file = path.join(CONTROL_DIR, name);
  if (!fs.existsSync(file)) {
    send(res, 404, 'text/plain', 'asset not found: ' + name);
    return;
  }
  const ext = path.extname(file);
  send(res, 200, MIME[ext] || 'application/octet-stream', fs.readFileSync(file));
}

// 与 RemoteServer.kt 的 serve() 路由 + token 鉴权保持一致的模拟实现
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const uri = url.pathname;
  // 与 RemoteServer.kt 的 hostAllowed 一致：Host 必须是本机（127.0.0.1/localhost）
  const hostname = (req.headers['host'] || '').split(':')[0].toLowerCase();
  const hostOk = hostname === '127.0.0.1' || hostname === 'localhost';
  if (!hostOk) return send(res, 403, 'text/plain', 'forbidden host');
  const tokenOk = (url.searchParams.get('t') === TOKEN) ||
    (req.headers['x-auth-token'] === TOKEN);
  const unauthorized = () => send(res, 401, 'text/plain', 'unauthorized');

  if (uri === '/' || uri === '/control' || uri === '/control/') return sendFile(res, 'index.html');
  if (uri.startsWith('/control/')) return sendFile(res, uri.slice('/control/'.length));
  if (uri === '/home') {
    const html = fs.readFileSync(path.join(CONTROL_DIR, 'home_tv.html'), 'utf8');
    return send(res, 200, 'text/html; charset=utf-8', html.replace('{token}', TOKEN));
  }
  if (uri === '/qr.png') {
    // 与 RemoteServer.kt 一致：/qr.png 只认 query 参数 t（不认 header）
    if (url.searchParams.get('t') !== TOKEN) return unauthorized();
    res.writeHead(200, { 'Content-Type': 'image/png' });
    res.end(Buffer.from([0x89, 0x50, 0x4e, 0x47])); // PNG 魔数
    return;
  }
  if (uri === '/api/status') {
    if (!tokenOk) return unauthorized();
    return send(res, 200, 'application/json', JSON.stringify({
      url: 'https://www.bilibili.com', title: '哔哩哔哩',
      canGoBack: true, canGoForward: false, online: true,
      ip: '192.168.1.100', port: 8080
    }));
  }
  if (uri === '/api/suggest') {
    if (!tokenOk) return unauthorized();
    const q = url.searchParams.get('q') || '';
    if (!q) return send(res, 400, 'text/plain', 'missing q');
    return send(res, 200, 'application/json', JSON.stringify({
      q: q, suggestions: ['西游记', '西游记全集', '西游记女儿国', '西游伏妖篇']
    }));
  }
  if (uri === '/api/sites') {
    if (!tokenOk) return unauthorized();
    if (req.method === 'GET') {
      return send(res, 200, 'application/json', JSON.stringify(memSites));
    }
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      let json;
      try { json = JSON.parse(body); } catch (e) { return send(res, 400, 'text/plain', 'invalid json'); }
      if (!Array.isArray(json.sites)) return send(res, 400, 'text/plain', 'invalid sites json');
      memSites = json; // 保存到内存
      send(res, 200, 'application/json', '{"ok":true}');
    });
    return;
  }
  if (uri === '/api/open' || uri === '/api/key' || uri === '/api/input') {
    if (!tokenOk) return unauthorized();
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      console.log('  [API] ' + req.method + ' ' + uri + '  body=' + (body.length > 100 ? body.slice(0, 100) + '...(' + body.length + 'B)' : body));
      if (Buffer.byteLength(body, 'utf8') > 64 * 1024) return send(res, 413, 'text/plain', 'body too large');
      if (!body.trim()) return send(res, 400, 'text/plain', 'empty body');
      let json;
      try { json = JSON.parse(body); } catch (e) { return send(res, 400, 'text/plain', 'invalid json'); }
      const required = uri === '/api/open' ? 'url' : (uri === '/api/key' ? 'key' : 'text');
      if (!json[required] || String(json[required]).trim() === '') {
        return send(res, 400, 'text/plain', 'missing field: ' + required);
      }
      send(res, 200, 'application/json', '{"ok":true}');
    });
    return;
  }
  if (uri.startsWith('/api/')) return send(res, 404, 'text/plain', '404 not found');
  sendFile(res, uri.slice(1)); // 兜底静态资源
});

let failures = 0;
function check(name, cond) {
  const mark = cond ? 'PASS' : 'FAIL';
  if (!cond) failures++;
  console.log('  [' + mark + '] ' + name);
}

async function main() {
  await new Promise((r) => server.listen(PORT, r));
  const base = 'http://127.0.0.1:' + PORT;
  const authHeader = { 'X-Auth-Token': TOKEN };
  console.log('=== 电视浏览器控制链路验证（含 token 鉴权） ===');

  // fetch 禁止设置 Host header，用 http.request 原始请求伪造
  function rawGet(hostHeader) {
    return new Promise(function (resolve, reject) {
      var req = http.request({
        host: '127.0.0.1', port: PORT, path: '/', method: 'GET',
        headers: { Host: hostHeader }
      }, function (res) { res.resume(); resolve(res.statusCode); });
      req.on('error', reject);
      req.end();
    });
  }

  console.log('\n[0] Host 校验（防 DNS rebinding）');
  let r = await rawGet('evil.com');
  check('GET / (伪造 Host: evil.com) -> 403', r === 403);
  r = await rawGet('127.0.0.1:' + PORT);
  check('GET / (正常 Host) -> 200', r === 200);

  console.log('\n[1] 静态资源路由');
  r = await fetch(base + '/');
  check('GET / -> 200 控制页', r.status === 200 && (await r.text()).includes('电视遥控'));
  r = await fetch(base + '/style.css');
  check('GET /style.css -> 200 (兜底路由)', r.status === 200);
  r = await fetch(base + '/app.js');
  check('GET /app.js -> 200 (兜底路由)', r.status === 200);
  r = await fetch(base + '/control/style.css');
  check('GET /control/style.css -> 200 (前缀路由)', r.status === 200);
  r = await fetch(base + '/home');
  const home = await r.text();
  check('GET /home -> 200 且注入 token', r.status === 200 && home.includes('qr.png?t=' + TOKEN));
  r = await fetch(base + '/favicon.ico');
  check('GET /favicon.ico -> 404', r.status === 404);

  console.log('\n[2] token 鉴权');
  r = await fetch(base + '/qr.png');
  check('GET /qr.png (无token) -> 401', r.status === 401);
  r = await fetch(base + '/qr.png?t=' + TOKEN);
  check('GET /qr.png?t=TOKEN -> 200 图片', r.status === 200);
  r = await fetch(base + '/api/status', { headers: {} });
  check('GET /api/status (无token) -> 401', r.status === 401);
  r = await fetch(base + '/api/status', { headers: authHeader });
  const st = await r.json();
  check('GET /api/status (header token) -> 200 JSON', r.status === 200 && !!st.url && !!st.title);
  r = await fetch(base + '/api/suggest?q=xiyouji', { headers: authHeader });
  const sug = await r.json();
  check('GET /api/suggest?q=xiyouji -> 200 含中文候选', r.status === 200 && Array.isArray(sug.suggestions) && sug.suggestions.length > 0 && typeof sug.suggestions[0] === 'string');
  r = await fetch(base + '/api/suggest?q=xiyouji');
  check('GET /api/suggest (无token) -> 401', r.status === 401);
  r = await fetch(base + '/api/suggest', { headers: authHeader });
  check('GET /api/suggest (缺q) -> 400', r.status === 400);
  r = await fetch(base + '/api/sites', { headers: authHeader });
  const sites1 = await r.json();
  check('GET /api/sites -> 200 含站点', r.status === 200 && Array.isArray(sites1.sites));
  r = await fetch(base + '/api/sites', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({ sites: [{ name: '知乎', url: 'https://www.zhihu.com' }] }) });
  check('POST /api/sites (增删改保存) -> ok', r.status === 200);
  r = await fetch(base + '/api/sites', { headers: authHeader });
  const sites2 = await r.json();
  check('GET /api/sites 反映新列表', sites2.sites.length === 1 && sites2.sites[0].name === '知乎');
  r = await fetch(base + '/api/sites');
  check('GET /api/sites (无token) -> 401', r.status === 401);
  r = await fetch(base + '/api/sites', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({}) });
  check('POST /api/sites (缺sites字段) -> 400', r.status === 400);

  console.log('\n[3] API 调用（模拟 app.js 的 fetch 格式，带 token）');
  r = await fetch(base + '/api/open', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({ url: 'https://www.iqiyi.com' }) });
  check('POST /api/open {url} -> ok', r.status === 200);
  r = await fetch(base + '/api/key', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({ key: 'up' }) });
  check('POST /api/key {key} -> ok', r.status === 200);
  r = await fetch(base + '/api/input', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({ text: '你好电视' }) });
  check('POST /api/input {text} -> ok', r.status === 200);
  r = await fetch(base + '/api/open', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: 'x' }) });
  check('POST /api/open (无token) -> 401', r.status === 401);
  r = await fetch(base + '/api/open', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({}) });
  check('POST /api/open (缺字段) -> 400', r.status === 400);
  r = await fetch(base + '/api/open', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: '' });
  check('POST /api/open (空body) -> 400', r.status === 400);
  r = await fetch(base + '/api/nope', { headers: authHeader });
  check('GET /api/nope -> 404', r.status === 404);
  r = await fetch(base + '/api/open', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, authHeader), body: JSON.stringify({ url: 'x'.repeat(70 * 1024) }) });
  check('POST /api/open (body > 64KB) -> 413', r.status === 413);

  console.log('\n[4] 控制页 HTML 引用的资源完整性');
  const html = await (await fetch(base + '/')).text();
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]).filter((u) => !u.startsWith('http'));
  for (const ref of refs) {
    const rr = await fetch(base + '/' + ref);
    check('资源可达: ' + ref + ' -> ' + rr.status, rr.status === 200);
  }

  console.log('\n=== 结果: ' + (failures === 0 ? '全部通过 ✔' : failures + ' 项失败 ✘') + ' ===');
  server.close();
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
