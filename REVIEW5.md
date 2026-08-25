# 最近两次修改（TV 键盘 + 必应搜索）专项审查报告（REVIEW5）

> 审查对象：commit b6e8cdb（TV 虚拟键盘）+ dbd5608（必应搜索/网址快捷键）
> 覆盖：home_tv.html（379 行 JS）、RemoteServer.serveSuggest/fetchBingSuggest/httpGet、verify.js suggest 用例

---

## 一、发现并修复的问题

### P1-1 严重：搜索联想完全失效
- 问题：home_tv.html 的 loadSuggest() 用 fetch('/api/suggest') 未带访问令牌，而 /api/suggest 需 token 校验，电视主页调用恒 401，中文联想永远失败（显示"暂无联想"）。
- 修复：服务端注入 var TV_TOKEN = '{token}'（serveHome 替换占位符），fetch 携带 X-Auth-Token 头。

### P1-2 安全：候选 HTML 注入（XSS）
- 问题：renderSuggest 将 Bing 返回的词直接拼接 innerHTML，若联想词含 HTML/脚本可执行注入。
- 修复：新增 escapeHtml()（转义 & < > " '），候选按纯文本渲染。

### P2-1 交互：数字键冲突
- 问题：搜索模式数字键 1-8 无条件用于选候选，用户无法直接输入数字（如搜 2024）。
- 修复：数字键仅当焦点在候选区时选候选；网格焦点时数字键正常直输。

### P2-2 健壮性：suggest q 无长度限制
- 修复：serveSuggest 限制 q 不超过 100 字符，超长返回 400。

### P3 细节
- 失焦时方向键会从网格尾部绕行，改为失焦先 focusKey(0) 归位。
- 确认无问题：Enter 在控制键走默认 click；ArrowUp 在顶部行无越界；suggest fetch 有 catch 兜底。

---

## 二、核对通过项

- 焦点导航四区（快捷键/候选/网格/控制键）上下左右边界正确
- URL 拼接：doOk 的 search 模式用 encodeURIComponent；url 模式补 http://
- RemoteServer：httpGet 5s 超时 + use 关闭流 + JSONArray 容错（optJSONArray null 返回空列表）；URL 固定 Bing 主机无 SSRF
- verify.js suggest 用例（200 候选/401/400）与真实路由一致

---

## 三、验证

- 内联 JS 语法：node --check 通过
- assembleDebug/Release：BUILD SUCCESSFUL 零警告
- verify.js：24/24 通过
- release 包已重打（18:03），确认含 X-Auth-Token 修复，签名有效
