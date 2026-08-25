# tv-browser 安全 / 稳定 / 性能 审查与优化报告（REVIEW3）

> 审查维度：安全性、稳定性、性能（三维度专项）
> 基线：REVIEW2 修复后（commit 9ba11ea）；本轮优化 commit：见文末
> 验证：gradlew assembleDebug BUILD SUCCESSFUL（零 Kotlin 警告）；node web/verify.js 21/21 通过（新增 Host 校验 2 例）

---

## 一、安全性优化

### S1 ✅ Host 校验（防 DNS rebinding）— 新增
- **风险**：恶意网页/域名（如广告域名）DNS 解析到 127.0.0.1 后，其 JS 可 fetch 电视的 :8080 API（同源策略被 rebinding 绕过），无 token 时虽 401，但 token 已知时（同网段泄露）可控制电视。
- **修复**：RemoteServer.serve() 开头校验 Host header，仅允许 127.0.0.1 / localhost / 本机局域网 IP，否则 403。
- **验证**：verify.js 新增伪造 Host=evil.com → 403 用例（Node fetch 禁止 Host header，用 http.request 原始请求实现）。

### S2 ✅ 控制页 token 清理 — 新增
- **风险**：二维码 URL 含 token（/?t=TOKEN），会出现在手机浏览器地址栏与历史记录。
- **修复**：app.js 取出 token 后立即 sessionStorage 持久化 + history.replaceState 清除 URL 中的 token；刷新页面从 sessionStorage 恢复（不丢失连接），关闭标签页后 sessionStorage 清空（重新扫码）。
- **权衡**：清除后地址栏不再显示 token；QR 码图片本身仍含 token（无法避免，二维码内容即连接凭证）。

### S3 ✅ WebView 能力收紧 — 新增
- 新增禁用项：content:// 内容访问（setAllowContentAccess=false）、定位（setGeolocationEnabled=false）、表单保存（setSaveFormData=false）、页面缩放（setSupportZoom=false，TV 遥控无意义）。
- 与既有防护叠加：协议白名单（仅 http/https）、allowFileAccess=false、无 addJavascriptInterface（控制走 HTTP API）、混合内容 ALWAYS_ALLOW 保留（视频站兼容权衡，见 REVIEW2 A-1）。

---

## 二、稳定性优化

### St1 ✅ Activity 销毁后调用保护 — 新增
- **风险**：NanoHTTPD 后台线程在 Activity onDestroy 后仍可能触发 RemoteActions（如排队中的 /api/open），runOnUiThread post 的任务访问已 destroy 的 WebView 会崩溃。
- **修复**：① onDestroy 中 destroy 后置 webView = null；② openUrl / handleRemoteKey / inputText 在 post 前检查 isDestroyed || isFinishing。

### St2 ✅ 下载行为拦截 — 新增
- **风险**：网页触发下载时 WebView 无 DownloadListener，行为未定义（可能无响应或走系统默认）。
- **修复**：setDownloadListener 拦截并 Toast 提示"暂不支持文件下载"（当前版本不提供下载能力）。

### St3 ✅ 低内存释放 — 新增
- onLowMemory() 调用 webView.freeMemory()，降低长时浏览被系统回收的概率。

---

## 三、性能优化

### P1 ✅ 控制页静态资源内存缓存 — 新增
- **问题**：每次请求从 assets 重新读取 + 解压（index.html / style.css / app.js / 注入后主页）。
- **修复**：ConcurrentHashMap 缓存资源字节（<1MB 总量）；serveHome 注入 token 结果也缓存（token 不变即复用）。
- **效果**：控制页/主页资源加载从"每次读 APK"降为"内存命中"。

### P2 ✅ 响应缓存头 — 新增
- serveAsset 响应加 Cache-Control: public, max-age=3600，手机浏览器二次打开控制页走本地缓存。

### P3 ✅ WebView 低内存释放 — 见 St3
- 长时浏览内存增长场景下主动释放。

### 保留项（收益低/有风险，未做）
- QR PNG 字节缓存：bitmap 已按 ip|token 缓存，PNG 压缩仅主页加载时一次（~几十 ms），收益低。
- NetUtil 接口变化即时失效：30s TTL 已覆盖切网场景，复杂监听收益低。
- MainActivity 拆分：重构风险高，当前 400+ 行可维护。

---

## 四、验证结果

| 项 | 结果 |
|---|---|
| gradlew assembleDebug | ✅ BUILD SUCCESSFUL，零 Kotlin 警告 |
| node web/verify.js | ✅ 21/21（新增：伪造 Host 403 / 正常 Host 200） |
| APK | ✅ 3.4M |
| 控制页同步 | ✅ assets/control 与 web/control 一致 |

---

## 五、仍建议实机验证

1. Host 校验不影响正常扫码（手机浏览器 Host 为本机 IP，放行）
2. 控制页刷新后从 sessionStorage 恢复连接（无需重新扫码）
3. S3 禁用缩放/定位对目标网站无副作用
4. P1 缓存后资源更新需重装 APK（assets 打包内不变）

### P4 ✅ QR 图片用后即弃（内存清理）— 补充
- **问题**：qrBitmap 曾按 ip|token 永久缓存（512KB Bitmap 常驻、永不释放）。
- **修复**：移除缓存字段，改为按需实时生成、用后即弃（Bitmap 随方法结束即可被 GC）；serveQr 中的临时 Bitmap/ByteArrayOutputStream 均为局部变量，请求结束即回收。
- **成本**：仅电视主页加载 /qr.png 时生成，单次约几毫秒（setPixels 已优化），可忽略。
- **验证**：grep 确认 qrCache 无残留；assembleDebug 零警告；verify.js 21/21。

---

## 六、资源生命周期专项排查（内存/泄漏/竞态）

> 排查范围：全部缓存、静态/单例持有、回调注册、流/IO、Handler/Runnable、Bitmap/byte[]、Activity 引用、WebView 生命周期、NanoHTTPD 线程、JS 桥

### 新修复

**St4 竞态修复：openUrl 的 post 时序 — 新增**
- 风险：openUrl 在 runOnUiThread 前捕获 wv 引用；若主线程先执行 onDestroy（webView destroy 后置空）再执行 post 任务，wv.loadUrl 会调用已销毁的 WebView 导致崩溃（NanoHTTPD 后台线程与 onDestroy 的竞态窗口）。
- 修复：post 执行体内重新取 webView（取空则安全跳过）。handleRemoteKey/inputText 原本就在 post 内取用，无此问题。

**St5 渲染进程崩溃处理 — 新增**
- 风险：WebView 渲染进程崩溃（复杂页面/内存不足，Android 8+）默认拖垮整个应用。
- 修复：覆写 onRenderProcessGone，区分崩溃/内存过高提示，复位 about:blank，返回 true 阻止应用崩溃。

### 排查确认无问题项

| 类别 | 结论 |
|---|---|
| 缓存 | assetCache（控制页 4 文件）与 homeHtmlCached（主页注入结果）为固定小集合（总量小于 50KB、key 集合不变），不增长、无泄漏，可接受常驻 |
| 静态/单例 | NetUtil 缓存 30s TTL 自动过期；ServerConfig/QrUtil 无状态字段 |
| 流/IO | assets 流全部 use 自动关闭；ByteArrayInputStream 由 NanoHTTPD 发送后关闭（close 为 no-op）；无文件 IO |
| 回调注册 | 无 BroadcastReceiver/传感器/NetworkCallback/ContentObserver 注册 |
| Handler/Runnable | 无延时任务；qrBitmap 用时间戳方案（无 Runnable 泄漏） |
| Bitmap/byte[] | 二维码实时生成用后即弃；favicon 参数不持有；assetCache 仅小字节数组 |
| Activity 引用 | webView destroy+置空；remoteServer onDestroy stop；WebViewClient/ChromeClient 匿名对象随 WebView 释放 |
| JS 桥 | 无 addJavascriptInterface（控制走 HTTP API，无桥对象泄漏） |
| Web 端 | setInterval 随页面关闭清理；repeatTimer 事件全覆盖停止；sessionStorage 标签页级自动失效 |
| NanoHTTPD | 每连接一线程（局域网低并发）；Host 校验已挡外部滥用 |

### 低风险接受项
- RemoteServer 持有 Activity（actions）引用：onDestroy stop 后，处理中的慢请求短时持有（请求级，秒级完成）
- NanoHTTPD 无连接数上限：同网段恶意客户端可占线程，Host 校验已挡外网，局域网内风险可接受
