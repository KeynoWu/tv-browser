// 电视浏览器控制链路验证脚本
// 用法: node web/verify.js
// 模拟 RemoteServer(RemoteServer.kt) 的路由行为，加载真实的 assets/control 文件，
// 并按手机控制页 app.js 的调用格式发起完整请求序列，验证链路。
const http = require('http');
const fs = require('fs');
const path = require('path');

const CONTROL_DIR = path.join(__dirname, '../app/src/main/assets/control');
const PORT = 18080;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
};

function sendFile(res, name) {
  const file = path.join(CONTROL_DIR, name);
  if (!fs.existsSync(file)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('asset not found: ' + name);
    return false;
  }
  const ext = path.extname(file);
  res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
  res.end(fs.readFileSync(file));
  return true;
}

// 与 RemoteServer.kt 的 serve() 路由保持一致的模拟实现
const server = http.createServer((req, res) => {
  const uri = new URL(req.url, 'http://x').pathname;

  if (uri === '/' || uri === '/control' || uri === '/control/') return sendFile(res, 'index.html');
  if (uri.startsWith('/control/')) return sendFile(res, uri.slice('/control/'.length));
  if (uri === '/home') return sendFile(res, 'home_tv.html');
  if (uri === '/qr.png') {
    res.writeHead(200, { 'Content-Type': 'image/png' });
    res.end(Buffer.from([0x89, 0x50, 0x4e, 0x47])); // PNG 魔数
    return;
  }
  if (uri === '/api/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ url: 'https://www.bilibili.com', title: '哔哩哔哩', canGoBack: true, canGoForward: false, online: true, ip: '192.168.1.100', port: 8080 }));
    return;
  }
  if (uri === '/api/open' || uri === '/api/key' || uri === '/api/input') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      console.log('  [API] ' + req.method + ' ' + uri + '  body=' + body);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    });
    return;
  }
  if (uri.startsWith('/api/')) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('404 not found');
    return;
  }
  // 兜底：控制页静态资源（/style.css /app.js 等）
  sendFile(res, uri.slice(1));
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
  console.log('=== 电视浏览器控制链路验证 ===');

  console.log('\n[1] 静态资源路由');
  let r = await fetch(base + '/');
  check('GET / -> 200 控制页', r.status === 200 && (await r.text()).includes('电视遥控'));
  r = await fetch(base + '/style.css');
  check('GET /style.css -> 200 (兜底路由)', r.status === 200);
  r = await fetch(base + '/app.js');
  check('GET /app.js -> 200 (兜底路由)', r.status === 200);
  r = await fetch(base + '/home');
  check('GET /home -> 200 电视主页', r.status === 200 && (await r.text()).includes('电视浏览器'));
  r = await fetch(base + '/qr.png');
  check('GET /qr.png -> 200 图片', r.status === 200 && (await r.arrayBuffer()).byteLength > 0);
  r = await fetch(base + '/favicon.ico');
  check('GET /favicon.ico -> 404（不存在资源正确 404）', r.status === 404);

  console.log('\n[2] API 调用（模拟 app.js 的 fetch 格式）');
  r = await fetch(base + '/api/open', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: 'https://www.iqiyi.com' }) });
  check('POST /api/open {url} -> ok', r.status === 200);
  r = await fetch(base + '/api/key', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key: 'up' }) });
  check('POST /api/key {key} -> ok', r.status === 200);
  r = await fetch(base + '/api/input', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: '你好电视' }) });
  check('POST /api/input {text} -> ok', r.status === 200);
  r = await fetch(base + '/api/status');
  const st = await r.json();
  check('GET /api/status -> 200 JSON(url/title)', r.status === 200 && !!st.url && !!st.title);
  r = await fetch(base + '/api/nope');
  check('GET /api/nope -> 404', r.status === 404);

  console.log('\n[3] 控制页 HTML 引用的资源完整性');
  const html = await (await fetch(base + '/')).text();
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]).filter((u) => !u.startsWith('http'));
  for (const ref of refs) {
    const rr = await fetch(base + '/' + ref);
    check('资源可达: ' + ref + ' -> ' + rr.status, rr.status === 200);
  }
  const homeHtml = await (await fetch(base + '/home')).text();
  const homeRefs = [...homeHtml.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1]).filter((u) => !u.startsWith('http'));
  for (const ref of homeRefs) {
    const rr = await fetch(base + '/' + ref);
    check('主页资源可达: ' + ref + ' -> ' + rr.status, rr.status === 200);
  }

  console.log('\n=== 结果: ' + (failures === 0 ? '全部通过 ✔' : failures + ' 项失败 ✘') + ' ===');
  server.close();
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
