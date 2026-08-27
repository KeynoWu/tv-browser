# 今日修改全面审查报告（REVIEW7）

> 审查范围：今日 6 个提交——TV 键盘(b6e8cdb)、必应搜索(dbd5608)、REVIEW5 修复(a0b8002)、REVIEW6 修复(9589d76)、PC UA+震动(64714df)、快捷站点管理(b0b8dfb)
> 重点：sites 管理（首审）与 UA/震动（首审）；键盘/搜索为复查（REVIEW5/6 已审）

---

## 一、本轮发现并修复

### P2-1 站点列表两端不一致
- 问题：手机控制页默认 6 站（含芒果），电视主页硬编码 5 站（无芒果、名称各异：B站 vs 哔哩哔哩）——同一产品两套入口数据。
- 修复：**电视主页站点区改为动态渲染 /api/sites**（复用 TV_TOKEN 鉴权），与手机控制页共享同一份数据；手机端增删改后电视主页实时同步。

### P2-2 编辑态布局挤压
- 问题：编辑快捷站点时快捷区仍显示在面板上方，双区挤压。
- 修复：进入编辑隐藏快捷区，保存/取消后恢复。

### P3-1 saveSites 元素结构未校验
- 问题：saveSites 仅校验存在 sites 数组，未校验每项含 name/url——非法元素会被存储并在渲染时产生 undefined 按钮。
- 修复：服务端校验每项为对象且含 name/url 字段，否则拒绝。

---

## 二、核对通过项（今日代码）

- **MainActivity**：UA 改为 PC 桌面（无 Mobile 标记）✓；sites 存储 @Synchronized + 原子替换 + 损坏回退默认 ✓；getSites 返回前 JSON 校验 ✓
- **RemoteServer**：/api/sites GET/POST 区分与 token 校验 ✓；handleSitesPost 复用 64KB 限制与空/格式校验 ✓；路由顺序正确（/api/sites 在 startsWith("/api/") 前）✓
- **app.js**：站点编辑行 value 赋值渲染（无注入面）✓；saveSites 发送 {sites:[...]} 与服务器契约一致 ✓；vibrate 有 navigator.vibrate 存在性判断（iOS 自动跳过）✓
- **home_tv.html**：动态站点插入位置在功能按钮前、焦点数组重建、空列表时保留搜索/网址按钮 ✓；REVIEW5/6 的 token/escapeHtml/序号守卫/数字键限定复查确认到位 ✓
- **verify.js**：sites 5 用例（读/存/反映/401/400）与真实路由一致 ✓

---

## 三、验证

- 内联 JS 语法：home_tv.html、app.js 均通过
- assembleDebug/Release：BUILD SUCCESSFUL 零警告
- verify.js：29/29 通过
- release 已重打（09:17 → 本轮重打）
