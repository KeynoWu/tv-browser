# tv-browser（电视浏览器）深度代码审查报告

> 审查对象：/Users/wuminxuan/Desktop/test/tv/tv-browser（commit cba0a30 + 工作区改动）
> 审查范围：5 个 Kotlin 源文件、控制页 3 件套 + 电视主页、AndroidManifest、Gradle 配置、验证脚本
> 方法：逐文件精读 + 对抗性模式扫描 + 跨文件数据流/线程模型交叉验证

---

## 一、总体评价

项目结构清晰、职责划分合理（界面/服务/工具分层），控制链路设计正确，验证脚本（web/verify.js）完整覆盖了路由与 API 契约。作为 0.1 版本可用，但存在 **1 个崩溃级线程 bug、1 个移动端触摸交互 bug、若干安全与健壮性缺口**，建议按本报告优先级修复后再正式使用。

---

## 二、高危问题（建议立即修复）

### P1-1 崩溃级：currentStatus() 在后台线程调用 WebView 方法
- **位置**：RemoteServer.kt:92 serveStatus() 调 actions.currentStatus()；MainActivity.kt:295-305
- **问题**：NanoHTTPD 的 serve() 在**请求工作线程**执行（非主线程）。currentStatus() 直接调用 webView?.canGoBack()/canGoForward()，而 Android 文档明确 **WebView 方法必须在 UI 线程调用**。手机控制页每 3 秒轮询一次 /api/status，意味着**每 3 秒一次后台线程触碰 WebView**——在多数设备上会抛 "WebView methods must be called on the UI thread" 或产生未定义行为，最终可能使控制服务 500 或崩溃。
- **佐证**：RemoteActions.kt 接口注释明确要求"实现方需自行切主线程"，openUrl/handleRemoteKey/inputText 都正确 runOnUiThread 了，唯独 currentStatus 漏了——接口契约未被完整实现。
- **修复建议**：
    // 方案A（推荐）：维护主线程快照，NanoHTTPD 线程只读快照
    @Volatile private var canGoBack = false
    @Volatile private var canGoForward = false
    // 在 onPageStarted / onPageFinished 回调（主线程）里更新这两个字段
    // currentStatus() 直接读快照，不再触碰 WebView
    
    // 方案B：CountDownLatch 同步切主线程（简单但每 3s 阻塞后台线程 0-500ms）

### P1-2 交互级：手机触摸单击遥控键无响应（控制页 app.js）
- **位置**：app.js:53 touchstart 里 e.preventDefault() 后 startRepeat(key)
- **问题**：移动浏览器中 touchstart 调用 preventDefault() 会**阻止浏览器合成后续 click 事件**。而 startRepeat 用 setInterval(180ms) 连发，若手指快速点按（按住时长 <180ms），定时器一次都不会触发。结果：**手机上单击方向键/OK 等没有任何按键发送**；只有按住超过 180ms 才开始连发。鼠标（桌面调试）因有 click 兜底不受影响，更凸显这是触摸特有 bug。
- **修复建议**：
    b.addEventListener('touchstart', function (e) {
      e.preventDefault();
      API.key(key);        // 立即发送一次（单击即有响应）
      startRepeat(key);    // 再启动长按连发
    });

---

## 三、中危问题（建议尽快修复）

### P2-1 target=_blank 链接打不开（新窗口无处理）
- **位置**：MainActivity.kt:69 javaScriptCanOpenWindowsAutomatically = true，但 WebChromeClient 未覆写 onCreateWindow
- **问题**：网页中 target="_blank" 或 window.open() 的链接（B站/搜索引擎结果页等大量使用）在 WebView 中默认被丢弃，点击无响应。
- **修复**：覆写 onCreateWindow，拦截后在同一 WebView loadUrl：
    override fun onCreateWindow(view, isDialog, isUserGesture, resultMsg): Boolean {
      val href = resultMsg?.obj as? WebView.WebViewTransport
      href?.webView = webView   // 复用当前 WebView 打开新窗口 URL
      resultMsg?.sendToTarget()
      return true
    }

### P2-2 openUrl 允许 file:// 协议 + allowFileAccess 未显式关闭
- **位置**：MainActivity.kt:149-155（URL 规范化保留 file://）；WebView settings 未设 allowFileAccess
- **问题**：局域网内任意设备可 POST /api/open 发送 {"url":"file:///sdcard/xxx"} 或 file:///data/data/...，WebView 默认允许 file 访问（Android 12+ 默认仍为 true），等于**把电视上的本地文件暴露给局域网可读**。
- **修复**：openUrl 白名单化——拒绝 file:// 与 about:（内置主页除外）；并显式 s.allowFileAccess = false。

### P2-3 切后台 WebView 不暂停（视频/音频继续播放）
- **位置**：MainActivity 未覆写 onPause/onResume 调用 webView.onPause()/onResume()
- **问题**：按 HOME 键或切到其他应用后，网页视频/音频继续播放，耗电且用户不可见。
- **修复**：onPause { webView?.onPause() }、onResume { webView?.onResume() }（注意与 customView 全屏状态交互）。

### P2-4 端口 8080 硬编码 5 处 + 端口冲突静默失败
- **位置**：MainActivity.kt:161/169/175/303/309；RemoteServer.kt init 中 start 失败仅 Log.e
- **问题**：① 端口号魔法数字散落 5 处，改端口极易漏改；② 若 8080 被占用，RemoteServer 构造内 start 抛异常被吞掉只打日志，MainActivity 无法感知，主页（依赖 :8080/home）加载失败且无任何提示。
- **修复**：抽 object ServerConfig { const val PORT = 8080; HOME_URL 引用 }；RemoteServer 改为可感知失败（返回 null 或暴露 wasStarted()）。

### P2-5 无鉴权 + POST body 无大小限制
- **位置**：RemoteServer 全部端点；readBody 无限制
- **问题**：① 局域网内**任何设备**（不只扫码者）都可调用全部 API——控制电视开任意网址/模拟按键；② parseBody 会把整个请求体读入内存，恶意客户端可发超大 body 造成内存压力。
- **修复**：控制页 URL 带随机 token（QR 码含 token，API 校验）；readBody 限制 body 不超过 64KB。

### P2-6 NetUtil 多网卡返回顺序不确定
- **位置**：NetUtil.kt:10-20
- **问题**：遍历 NetworkInterface 返回**第一个**非回环 IPv4，未按"Wi-Fi/以太网优先"排序。盒子同时插网线+Wi-Fi 或启用热点时，可能返回手机无法访问的地址，二维码失效。
- **修复**：优先 wlan0/eth0/en0 等常规名；或枚举全部 IPv4。

### P2-7 电视主页（home_tv.html）方向键导航无保障
- **位置**：home_tv.html（纯静态，无焦点 JS）
- **问题**：Android WebView 对网页链接的 DPAD 焦点导航支持不稳定，遥控器可能无法在"哔哩哔哩/爱奇艺/…"之间移动焦点。
- **修复**：主页元素加 tabindex + 轻量 JS 焦点管理。

---

## 四、低危 / 改进项（P3）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 1 | androidx.webkit:webkit:1.11.0 引入但代码未使用（死依赖） | app/build.gradle.kts | 移除，减小体积 |
| 2 | QrUtil.generate 512×512 逐像素 setPixel（约 26 万次 native 调用）且无缓存，每次 /qr.png 全量重算 | QrUtil.kt | 改 int[] + setPixels；结果缓存 |
| 3 | 无 onReceivedError 处理，网页加载失败无提示 | MainActivity WebViewClient | 加失败 Toast/错误页 |
| 4 | hideSystemUi() 仅 onCreate 调用，部分设备返回后系统栏重现 | MainActivity | 在 onWindowFocusChanged 重复调用 |
| 5 | currentUrl/currentTitle 非 volatile，UI 线程写 / 后台线程读存在可见性风险 | MainActivity | 随 P1-1 改为主线程快照 |
| 6 | 手机端"返回"键与电视端 BACK 语义不一致（无双击退出） | handleRemoteKey | 手机返回不可 goBack 时 Toast 提示 |
| 7 | 搜索仅 Bing 搜索页，无视频平台聚合 | app.js | 可扩展 360kan/豆瓣聚合（utao 方案） |
| 8 | app.js 的 open/key/input 无失败反馈（网络断时静默） | app.js | fetch catch 后状态提示 |
| 9 | Manifest 未设 launchMode，重复启动可能多实例争端口 | AndroidManifest | 加 singleTask |
| 10 | allowBackup="true"，备份可能带出浏览历史 | AndroidManifest | 视需求改 false |
| 11 | versionCode=1 / versionName=0.1.0，无签名配置 | build.gradle.kts | 发布前配签名 |
| 12 | 无 onDownloadStart，网页下载无响应 | MainActivity | 拦截下载提示 |
| 13 | readBody 空 body 时静默 ok（POST 无 body 也返回 ok:true） | RemoteServer | 校验必填字段 |
| 14 | README 改动未提交 git | git status | 提交工作区改动 |
| 15 | 电视工具栏 EditText 在 TV 无软键盘，与"手机输网址"定位重叠 | activity_main.xml | 可考虑弱化该入口 |

---

## 五、验证过的正确点（未发现问题的部分）

- **线程切换**：openUrl / handleRemoteKey / inputText 三处均正确 runOnUiThread ✓
- **URL 规范化**：无协议时补 http:// 逻辑正确 ✓
- **HTML5 全屏**：showCustomView/hideCustomView 状态机正确（去重、回调释放、toolbar 隐藏）✓
- **按键连发清理**：stopRepeat 在 touchend/touchcancel/mouseup/mouseleave 全覆盖 ✓
- **Base64 文字注入**：escape/atob 处理 UTF-8 正确，React/Vue 兼容（native setter）✓
- **路由与 404**：/api/* 未知端点 404、静态资源兜底、控制页/主页/二维码路由均正确（verify.js 16 项通过佐证）✓
- **资源打包**：assets/control 与 web/control 完全一致，APK 内文件完整 ✓
- **二维码内容**：http://{IP}:8080/ 与控制页路由匹配 ✓

---

## 六、修复优先级建议

1. **立即**（上线前）：P1-1 线程崩溃、P1-2 触摸单击、P2-1 新窗口、P2-2 file:// 面
2. **尽快**（体验/健壮性）：P2-3 WebView 暂停、P2-4 端口常量与冲突检测、P2-7 主页焦点
3. **规划**（安全加固/发布）：P2-5 token 鉴权、P2-6 网卡优先、P3 清单（签名、singleTask、去死依赖等）

> 说明：P1-1 与 P1-2 均通过代码路径推演确认（NanoHTTPD serve 线程模型、移动浏览器事件合成规则均为平台既定行为）；其余问题为静态审查结论。所有修复建议均未实际改动代码，待你确认后执行。
