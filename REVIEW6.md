# 第 4 轮全面审查报告（REVIEW6）

> 审查对象：最新代码（10 个提交，1685 行）
> 方法：全量精读 Kotlin/JS + 交叉验证（verify vs 真实代码、目录一致性、文档同步）+ 对抗扫描
> 基线：REVIEW5 修复后（a0b8002）

---

## 一、本轮新发现并修复

### N1 交互：工具栏显隐动画竞态（MainActivity）
- 问题：快速连按菜单键时，show/hide 的 ViewPropertyAnimator 互相取消，可能造成工具栏可见但 alpha=0（不可见）或 visibility 错乱。
- 修复：show/hide 均先 animate().cancel() 再重置状态（alpha/translationY/visibility）。

### N14 发布一致性：控制页缓存 1 小时过长（RemoteServer）
- 问题：Cache-Control max-age=3600，App 更新（新 assets）后手机浏览器 1 小时内仍用旧控制页（API 不匹配风险）。
- 修复：改为 300 秒。

### N17 竞态：搜索联想请求乱序（home_tv.html）
- 问题：快速输入时旧请求后返回覆盖新候选（显示与输入不匹配的联想）。
- 修复：请求序号 suggestSeq，响应仅当序号最新时应用。

### N21 交互：成功反馈 flashOk 快速连点竞态（app.js）
- 问题：快速连点按钮时 setTimeout 的还原值被"✓"覆盖，按钮永久显示 ✓。
- 修复：dataset.orig 保存原始文本 + 清理旧定时器。

### N9 文档：RemoteServer KDoc 缺 /api/suggest
- 修复：补充路由注释。

### N7 文档：README 未提及必应搜索/电视键盘
- 修复：README 功能清单补充两项新功能。

### N25/N26 体验：快捷站点无成功反馈；文字输入不支持手机键盘 Enter
- 修复：站点点击同样 flashOk；textInput 监听 Enter 触发发送。

---

## 二、本轮核对通过项

- REVIEW5 修复确认到位：TV_TOKEN 注入 + fetch 带 token、escapeHtml 渲染候选、数字键仅候选区选词、suggest q≤100
- verify.js 与真实代码端点完全对齐（含 /api/suggest）
- 协议白名单：javascript:/file:/ftp: 均被拦截（scheme 判断）
- 线程/生命周期：MainActivity 快照 + isDestroyed + post 内重取 webView 均正确
- httpGet：5s 超时、use 关闭流、JSON 容错、URL 固定 Bing 无 SSRF
- 无 TODO/FIXME；目录一致；零 Kotlin 警告

---

## 三、验证

- 内联 JS 语法：两文件 node 检查通过
- assembleDebug/Release：BUILD SUCCESSFUL 零警告
- verify.js：24/24
- release 已重打
