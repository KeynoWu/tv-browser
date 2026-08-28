# 鼠标模式专项审查报告（REVIEW10）

> 审查对象：鼠标模式功能（4e210fd）——MainActivity 注入/按键/桥、RemoteServer 路由、控制页触摸板

---

## 一、发现并修复

### P1-1 性能/稳定：触摸板请求风暴
- 问题：touchmove 以浏览器事件频率（约 60Hz）逐次 fetch /api/mouse——每秒约 60 个请求打满 NanoHTTPD（每连接一线程）并形成主线程 evaluateJavascript 风暴。
- 修复：50ms 批量合并（touchmove 累积 delta，定时器统一 flush），请求量降约 3-5 倍且移动更平滑。

### P1-2 功能缺陷：点击双触发
- 问题：click() 同时 dispatchEvent(click) 与 el.click()——元素的 click 监听器收到两次（点播放按钮=开又关）。
- 修复：移除 el.click()，仅保留一次合成 click 派发。

### P2-1 体验：控制页刷新后鼠标模式状态丢失
- 问题：电视端 mouseMode 仍开启（光标在），控制页刷新回遥控面板——两端状态不同步。
- 修复：enterMouse 时 sessionStorage 记忆（tv_mouse），控制页加载时恢复触摸板视图并重新开启电视端（幂等）；exitMouse 清除。

### P2-2 稳定：mouseMode 下 ACTION_UP 泄漏到 WebView
- 问题：DOWN 被 Activity 拦截后，UP 落到 super（WebView 收到孤立 UP）。
- 修复：mouseMode 时 UP 对已拦截按键一并 return true。

### P3 文档
- RemoteServer KDoc 补 /api/mouseMode、/api/mouse
- README 功能表补鼠标模式（飞鼠）

---

## 二、核对通过项

- mouseJs：幂等注入（__tvMouse 存在即 return）、pointer-events:none、z-index 顶层、光标 clamp 屏幕内、边缘滚动、destroy 清理
- 线程：@Volatile mouseMode、evalPageJs（isDestroyed 前置 + runOnUiThread 内判空）、setMouseMode 幂等（mouseMode == on 早退）
- 按键：方向键长按靠系统重复连发；BACK 退出鼠标、MENU 退出+工具栏；音量键不拦截
- 页面导航：onPageFinished 重注入（mouseJs 幂等，光标位置在新文档重置为中心，合理）
- API：/api/mouseMode、/api/mouse token 校验 + handleJsonPost（64KB/空/格式）+ dx/dy 超大值被 JS clamp 兜底
- 控制页：触摸板 value/事件无注入面；sessionStorage 标签页级

---

## 三、验证

- app.js 语法 OK；assembleDebug/Release 零警告；verify.js 33/33；release 已重打
