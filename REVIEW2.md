# tv-browser 二次独立审查报告（REVIEW2）

> 审查方式：**全新独立审查**——不参考 REVIEW.md 结论，从零精读全部 6 个 Kotlin 文件、4 个控制页文件、verify.js 与构建配置
> 构建验证：gradlew clean assembleDebug 全量重编译（38 任务全执行，无缓存复用）→ BUILD SUCCESSFUL，APK 3.4M
> 链路验证：node web/verify.js 17/17 通过
> 审查对象 commit：3b4597b（修复后）

---

## 一、总体结论

修复阶段的工作在代码中得到确认（P1-1 线程快照、P1-2 触摸发送、token 鉴权、协议白名单等均已落地且通过干净构建）。本次独立审查未发现**新的崩溃级或高危安全漏洞**，但确认了 **1 个安全权衡点、1 个验证脚本与真实代码的语义偏差、若干健壮性与体验问题**，以及 3 项**必须实机验证**的行为（无法静态确认）。

---

## 二、修复确认（独立核验，非引用旧报告）

| 修复项 | 代码核验结果 |
|---|---|
| P1-1 线程快照 | MainActivity:31-35 @Volatile 快照 + :97-105 回调更新 + :370-380 只读 → 确认生效 |
| P1-2 触摸单击 | app.js:75-79 touchstart 立即发送 → 确认生效 |
| P2-1 onCreateWindow | MainActivity:136-143 复用 WebView → 确认生效 |
| P2-2 file:// 面 | MainActivity:91 allowFileAccess=false + :214-220 协议白名单 → 确认生效 |
| P2-3 暂停 | MainActivity:153-162 onPause/onResume → 确认生效 |
| P2-4 端口常量 | ServerConfig:8 PORT + :240 启动失败 Toast → 确认生效 |
| P2-5 token 鉴权 | RemoteServer:41-56 校验 + :69-74 authorized + :131-133 body 限制 → 确认生效 |
| P2-6 网卡优先 | NetUtil:13-21 priority 排序 → 确认生效 |
| P2-7 主页焦点 | home_tv.html:47-54 方向键 JS + tabindex → 代码生效，**行为待实机验证** |
| P3 各项 | 依赖/二维码/错误提示/singleTask/allowBackup → 确认生效 |

---

## 三、本轮独立审查的新发现

### A. 安全权衡（中危，建议评估）

**A-1 mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW（MainActivity:89）**
- HTTPS 页面可加载任意 HTTP 子资源（脚本/图片/媒体）。攻击者若劫持 HTTP 请求，可在 HTTPS 页面注入内容。
- 权衡：很多视频站（尤其国内）存在混合内容，COMPATIBILITY_MODE 可能破坏播放。当前 TV 浏览器定位下为**有意的兼容性取舍**，但应知晓：它抵消了 HTTPS 页面本身的部分防护。
- 建议：如可接受媒体类混合（图片/音视频）但拒绝脚本，改为 MIXED_CONTENT_COMPATIBILITY_MODE 并实机验证主流站点播放；若均正常则收紧。

**A-2 token 出现在 URL query（二维码 / 控制页地址含 ?t=）**
- 手机浏览器历史记录、代理日志、电视端 WebView 日志可能记录完整 URL（含 token）。
- 权衡：局域网 + 持久 token 场景下风险可控；token 泄露后攻击者仍可控制电视，直至卸载应用。
- 建议（可选）：token 改为每次启动轮换（电视主页二维码随之更新），降低泄露窗口；代价是二维码每次启动变化。

### B. 验证脚本与真实代码的语义偏差（中危，测试保真度）

**B-1 verify.js 的 /qr.png 鉴权比真实代码宽松**
- 真实代码（RemoteServer:42）：/qr.png 只校验 query 参数 ?t=（不认 header）。
- verify.js:39-40：tokenOk 同时认 query 和 header，/qr.png 复用了它。
- 影响：verify 无法发现"真实代码 /qr.png 只认 query"这一差异；如果未来有人给 /qr.png 加 header 校验，verify 仍通过而真实行为已变。
- 建议：verify.js 的 /qr.png 分支单独校验 query，与真实代码逐行对齐。

**B-2 verify.js 未覆盖 413（body 超限）与 /control/ 前缀路由**
- 真实代码有 64KB 限制（RemoteServer:131-133）和 /control/* 路由（:37-38），verify 均未测试。
- 建议：补充 413 用例（发大于 64KB body）与 /control/style.css 可达性用例。

### C. 健壮性 / 体验（低危）

| # | 问题 | 位置 | 说明与建议 |
|---|---|---|---|
| C-1 | Content-Length 检查可被绕过 | RemoteServer:131 | 客户端不发 Content-Length（chunked）时按 0 处理，64KB 限制失效；建议 readBody 后校验实际字节数 |
| C-2 | 手机"返回"键不关闭已打开的工具栏 | MainActivity:316-321 | TV 遥控 BACK 会先 hideToolbar（:273-276），手机 back 只 goBack；建议 handleRemoteKey 的 back 分支也先检查 toolbarVisible |
| C-3 | 状态轮询每次调 getLocalIpAddress | MainActivity:377 | 每 3 秒遍历 NetworkInterface；建议缓存 IP（切网时失效） |
| C-4 | 鼠标按住大于 180ms 后 click 多发一次按键 | app.js:74,82 | mousedown 连发期间，mouseup 后的 click 再补发一次；触摸不受影响，仅桌面调试场景 |
| C-5 | openUrl 对 ftp/data 等 scheme 拼 http:// 前缀 | MainActivity:216-220 | ftp://x 会变成 http://ftp://x（无害但不优雅）；建议明确拒绝非 http(s) 前缀输入 |
| C-6 | app.js 中状态轮询的手写 fetch 与 api() 重复 | app.js:103-105 vs 21-33 | 轮询未复用 api()（因它是 POST）；可抽公共 fetch 包装 |

### D. 代码质量（低危）

| # | 问题 | 位置 |
|---|---|---|
| D-1 | session.parms 为 deprecated API | RemoteServer:42,72（NanoHTTPD 建议 getParms()） |
| D-2 | systemUiVisibility 系列弃用（7 处） | MainActivity:67-74（minSdk 21 兼容需要，可保留但建议注释说明） |
| D-3 | MainActivity 393 行偏长 | 可拆 BrowserController / KeyDispatcher |
| D-4 | Charsets.UTF_8 引用无显式 import（依赖 kotlin.text 隐式） | MainActivity:346（无碍但可读性） |

### E. 必须实机验证的行为（静态无法确认）

| # | 行为 | 风险点 |
|---|---|---|
| E-1 | home_tv.html 方向键焦点导航 | Chromium TV 焦点导航可能先消费 DPAD（tabindex 元素），页面 keydown 是否触发不确定；可能仍无法用遥控移动焦点 |
| E-2 | onCreateWindow 复用当前 WebView | target=_blank 在新 URL 加载前，原页面是否保留后退历史；个别站点 window.open 行为差异 |
| E-3 | 触摸单击/连发真实手感 | preventDefault 后 iOS/Android 浏览器事件链差异；连发 180ms 间隔是否跟手 |
| E-4 | onPause/onResume 与全屏视频交互 | 全屏（customView）时切后台再回来，视频是否正常恢复 |

---

## 四、已确认无问题项（独立核验）

- 线程边界：openUrl/handleRemoteKey/inputText 三处均 runOnUiThread；currentStatus 只读 volatile 快照 → 无跨线程 WebView 调用 ✓
- token 生命周期：lazy 初始化（synchronized）+ SharedPreferences 持久化 + allowBackup=false ✓
- 字符串安全：控制页全部 textContent 渲染，无 innerHTML 注入点；JSON body 传输无拼接 ✓
- 协议注入：javascript:/data: 等 scheme 会被 http:// 前缀中和，不产生代码执行 ✓
- URL 路径穿越：serveAsset 的 ../ 会被 AssetManager 拒绝返回 404 ✓
- 资源一致性：assets/control 与 web/control 完全一致 ✓
- 依赖最小化：仅 core-ktx/appcompat/nanohttpd/zxing，无死依赖 ✓

---

## 五、建议动作（按优先级）

1. **立即**：B-1/B-2 修 verify.js 保真度（对齐 /qr.png 语义、补 413 与 /control/ 用例）
2. **评估**：A-1 mixedContentMode 收紧为 COMPATIBILITY_MODE（需实机验证播放）；A-2 token 轮换（可选）
3. **顺手**：C-1 body 实际大小校验、C-2 手机 back 关工具栏、C-3 IP 缓存、D-1 getParms()
4. **实机必测**：E-1 主页焦点、E-2 新窗口、E-3 触摸手感、E-4 全屏恢复

---

## 六、修复状态（2025-08-25 已执行）

| 项 | 状态 | 修复内容 |
|---|---|---|
| B-1 verify /qr.png 语义 | ✅ | verify.js /qr.png 分支改为只校验 query（与 RemoteServer:42 一致） |
| B-2 verify 覆盖 413 与 /control/ | ✅ | 新增 body>64KB→413 用例与 /control/style.css 用例；模拟服务器实现 413 检查（19/19 通过） |
| C-1 CL 绕过 | ✅ | handlePost 在 readBody 后按实际字节数二次校验（>64KB→413），防 chunked 绕过 |
| C-2 手机 back 关工具栏 | ✅ | handleRemoteKey("back") 先检查 toolbarVisible→hideToolbar |
| C-3 IP 高频遍历 | ✅ | NetUtil 加 30 秒缓存（@Volatile 时间戳失效） |
| C-4 鼠标长按 click 多发 | ✅ | mousedown 立即发送 + 连发；click 仅处理键盘激活（e.detail===0），触摸走 touchstart 路径 |
| C-5 非 http(s) scheme | ✅ | openUrl 对含 :// 的非 http/https scheme 直接拒绝 |
| C-6 fetch 重复 | ✅ | 抽公共 authFetch（自动 token + 统一错误），poll 复用 |
| D-1 parms deprecated | ✅ | serve/authorized/handlePost 加 @Suppress("DEPRECATION") |
| D-2 systemUiVisibility | ✅ | hideSystemUi 加 @Suppress + minSdk 21 兼容注释 |
| 编译警告清理 | ✅ | onReceivedError 加 OVERRIDE_DEPRECATION；onCreateWindow 消除变量遮蔽/安全调用；**assembleDebug 零 Kotlin 警告** |
| A-1 mixedContentMode | ⏳ | 保留 ALWAYS_ALLOW（视频站兼容权衡），建议实机验证后评估收紧 |
| A-2 token 轮换 | ⏳ | 保留持久 token，可选改为启动轮换 |
| D-3 MainActivity 拆分 | ⏳ | 低优先级，重构风险高，留后续 |
