# UI + 图标深度审查优化报告（REVIEW8）

> 框架：ui-ux-pro-max（图标/色板/可访问性/一致性）
> 重点：icon 系统化——替换 Unicode 字符图标，统一 SVG

---

## 一、优化内容

### 1. SVG 图标系统（替换 Unicode 字符）
- **问题**：控制页方向键用 Unicode 箭头（▲◀▶▼）、按钮纯文字——跨平台渲染不一致、无法统一风格与尺寸、不可着色。
- **优化**：内联 SVG symbol 库（15 个图标，stroke 2px / round 圆角 / Lucide 风格）：
  方向(up/down/left/right)、确认(ok)、返回、菜单、主页、刷新、搜索、链接(打开)、编辑、加号、删除、发送(纸飞机)。
  - 通过 <use href="#i-xxx"> 引用，currentColor 跟随按钮颜色
  - 尺寸 token：.icon(20px) / .icon.lg(26px) / .icon.xl(38px)
- 方向键/侧键/打开/搜索/发送按钮全部图标化（保留文字标签：图标+文字，双重可识别）。

### 2. 色板 token 化（CSS 变量）
- 问题：style.css 散落 20+ 个 hex（#0f0f0f/#1a1a1a/#ff4081/#4caf50...），无法统一调色。
- 优化：:root 定义语义 token（--bg/--surface/--primary/--success/--danger/--text/--text-secondary/--radius/--gap），全部引用替换。

### 3. 可访问性
- 所有图标按钮加 aria-label（上/左/确认/打开/搜索/发送等）
- .key 加 :focus-visible 焦点环（手机浏览器键盘导航可见）
- 对比度保持：图标 currentColor 继承文字色（主文本 12:1+）

### 4. 布局细节
- 侧键按钮图标改为横排（图标+文字同行），宽度 96px 更舒适
- 编辑区删除按钮图标化（垃圾桶）+ 添加按钮（加号）
- 色板替换后 UI 视觉无回归（token 值与原 hex 一致）

---

## 二、修复的连带问题

### flashOk 兼容图标按钮
- 问题：打开/搜索/发送按钮加入 SVG 后，原 flashOk 用 btn.textContent='✓' 会移除 SVG 子节点（图标丢失）。
- 修复：flashOk 优先操作按钮内 <span> 文案，无 span（动态站点按钮）时回退 textContent。

---

## 三、验证

- JS 语法：app.js/index.html 均通过
- assembleDebug/Release：BUILD SUCCESSFUL 零警告
- verify.js：29/29
- release 已重打（09:25），包内含 15 个 SVG symbol + 色板 token，签名有效

## 四、未做（记录）
- TV 主页文字按钮保留（TV 10 英尺文字更清晰，图标点缀收益低）
- 应用图标 adaptive icon 保持（功能优先，品牌化后续）
