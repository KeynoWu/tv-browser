# tv-browser（电视浏览器）深度代码审查报告

> 审查对象：/Users/wuminxuan/Desktop/test/tv/tv-browser
> 审查范围：5 个 Kotlin 源文件、控制页 3 件套 + 电视主页、AndroidManifest、Gradle 配置、验证脚本
> 方法：逐文件精读 + 对抗性模式扫描 + 跨文件数据流/线程模型交叉验证

---

## 一、总体评价

项目结构清晰、职责划分合理（界面/服务/工具分层），控制链路设计正确，验证脚本（web/verify.js）完整覆盖了路由与 API 契约。初次审查发现 1 个崩溃级线程 bug、1 个移动端触摸交互 bug、若干安全与健壮性缺口；**全部 P1/P2 及主要 P3 问题已在修复阶段解决**（见第七节）。

---

## 二、高危问题（已修复）

### P1-1 崩溃级：currentStatus() 在后台线程调用 WebView 方法
- 位置：RemoteServer.serveStatus() 调 actions.currentStatus()；MainActivity.currentStatus()
- 问题：NanoHTTPD 的 serve() 在请求工作线程执行（非主线程），currentStatus() 直接调 webView.canGoBack()/canGoForward()，而 Android 文档明确 WebView 方法必须在 UI 线程调用。控制页每 3 秒轮询 /api/status，即每 3 秒后台线程触碰 WebView，可能抛异常或崩溃。
- **修复**：改为 @Volatile 主线程快照（onPageStarted/onPageFinished 回调更新 canGoBack/canGoForward/currentUrl/currentTitle），NanoHTTPD 线程只读快照，不再触碰 WebView。

### P1-2 交互级：手机触摸单击遥控键无响应
- 位置：app.js touchstart 里 e.preventDefault() 后 startRepeat(key)
- 问题：移动浏览器 touchstart preventDefault 会阻止合成 click；连发定时器 180ms 后才触发。快速单击（<180ms）一次都不会发送。
- **修复**：touchstart 里立即发送一次按键，再启动长按连发。

---

## 三、中危问题（已修复）

### P2-1 target=_blank 链接打不开
- 修复：覆写 onCreateWindow，复用当前 WebView 打开新窗口 URL。

### P2-2 openUrl 允许 file:// 协议 + allowFileAccess 未关
- 修复：openUrl 协议白名单（仅 http/https）；显式 s.allowFileAccess = false。

### P2-3 切后台 WebView 不暂停
- 修复：onPause/onResume 调用 webView.onPause()/onResume()。

### P2-4 端口 8080 硬编码 5 处 + 启动失败静默
- 修复：新增 ServerConfig 常量；RemoteServer 启动失败直接抛出，MainActivity catch 后 Toast 提示。

### P2-5 无鉴权 + POST body 无大小限制
- 修复：token 鉴权——二维码 URL 含 ?t=TOKEN；API 校验 X-Auth-Token 请求头；/qr.png 校验 ?t=；控制页 JS 从 URL 读 token 并附加到所有请求。body 限制 64KB；空 body/缺字段/非法 JSON 返回 400。

### P2-6 NetUtil 多网卡返回顺序不确定
- 修复：按 wlan(0) > eth(1) > en(2) > 其他(3) 优先级排序。

### P2-7 电视主页方向键导航无保障
- 修复：home_tv.html 加 tabindex + 方向键 JS 焦点管理；二维码 token 由服务端注入（{token} 占位符替换）。

---

## 四、低危 / 改进项（已修复主要项）

| # | 问题 | 状态 |
|---|---|---|
| 1 | androidx.webkit 死依赖 | ✅ 移除 |
| 2 | 二维码逐像素 setPixel 慢且无缓存 | ✅ int[]+setPixels，按 ip|token 缓存 |
| 3 | 无 onReceivedError 提示 | ✅ 新旧签名 Toast 提示 |
| 4 | hideSystemUi 仅 onCreate | ✅ onWindowFocusChanged 重复调用 |
| 5 | currentUrl/currentTitle 跨线程可见性 | ✅ 随 P1-1 快照解决 |
| 6 | 手机返回键无双击退出提示 | ✅ 不可后退时 Toast「已经是第一页」 |
| 7 | 搜索仅 Bing | ⏳ 待办（可扩展聚合搜索） |
| 8 | API 失败静默 | ✅ 状态条提示发送失败 |
| 9 | 未设 singleTask | ✅ Manifest 加 launchMode=singleTask |
| 10 | allowBackup=true | ✅ 改 false |
| 11 | 无签名配置 | ⏳ 发布前配置 |
| 12 | 无 onDownloadStart | ⏳ 待办 |
| 13 | 空 body 静默 ok | ✅ 400 校验 |
| 14 | 文档提交 | ✅ 已提交 |
| 15 | 电视工具栏 EditText 定位 | ⏳ 设计取舍，保留 |

---

## 五、验证过的正确点

- 线程切换：openUrl / handleRemoteKey / inputText 均正确 runOnUiThread ✓
- URL 规范化：无协议时补 http:// ✓
- HTML5 全屏状态机：去重、回调释放、toolbar 隐藏 ✓
- 按键连发清理：touchend/touchcancel/mouseup/mouseleave 全覆盖 ✓
- Base64 文字注入：UTF-8 正确，React/Vue 兼容（native setter）✓
- 路由与 404：/api/* 未知 404、静态兜底、token 401/400 语义正确 ✓
- 资源打包：assets/control 与 web/control 一致，APK 内文件完整 ✓
- 二维码内容：http://{IP}:8080/?t=TOKEN 与控制页路由匹配 ✓

---

## 六、修复优先级建议（已执行）

1. 立即：P1-1 线程崩溃、P1-2 触摸单击、P2-1 新窗口、P2-2 file:// 面 —— ✅ 全部修复
2. 尽快：P2-3 WebView 暂停、P2-4 端口常量、P2-7 主页焦点 —— ✅ 全部修复
3. 规划：P2-5 token 鉴权、P2-6 网卡优先、P3 清单 —— ✅ 已修复（签名/聚合搜索/下载处理留待发布）

---

## 七、修复验证结果

- 编译：gradle assembleDebug BUILD SUCCESSFUL（仅剩 systemUiVisibility 等无害弃用警告）
- 链路：node web/verify.js 17/17 通过（含 token 鉴权 401、缺字段 400、空 body 400、未知 API 404 用例）
- 打包：ServerConfig/MainActivity/RemoteServer 确认打入 dex；APK 3.4M

> 遗留待办：视频平台聚合搜索、release 签名配置、onDownloadStart 下载处理、电视工具栏入口取舍。
