# tv-browser UI/UX 审查报告（REVIEW4）

> 审查框架：ui-ux-pro-max（可访问性 CRITICAL > 触摸 HIGH > 布局/反馈 MEDIUM > 视觉 LOW）
> 审查对象：电视端主页 home_tv.html、电视端工具栏 activity_main.xml、手机控制页 index.html+style.css+app.js、应用主题
> 验证：assembleDebug 零警告；verify.js 21/21

---

## 一、问题清单与修复状态

### TV 端（10 英尺观看场景）

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| U1 | HIGH | 主页字号用纯 vh，720p 电视（1vh≈7.2px）下 .sub 2.6vh≈19px、.site 2.8vh≈20px，远距不可读 | 全部改用 clamp()：sub/site/qr-tip 下限 22px、上限 44px；h1 下限 44px；二维码最小 280px |
| U2 | HIGH | 页面加载无任何进度反馈（长加载被误认为卡死） | 布局顶部加 4dp 水平 ProgressBar（accent 色），onProgressChanged 驱动，完成隐藏 |
| U3 | MEDIUM | 工具栏地址栏不显示当前页面 URL（打开是空白/旧输入） | showToolbar 时若输入框为空则填充 currentUrl |
| U4 | MEDIUM | 焦点切换/工具栏显隐硬切无动画 | .site 加 0.2s 背景过渡；工具栏 alpha+translationY 显隐动画（200ms/150ms） |
| U5 | LOW | 无全屏浏览引导 | 保留（主页已含操作提示） |

### 手机控制页

| # | 级别 | 问题 | 修复 |
|---|---|---|---|
| U6 | MEDIUM | 文字输入行 input/button 高 40px，低于 44px 触摸标准 | 提升至 44px，字号 14→15px |
| U7 | MEDIUM | 打开/搜索/发送成功无反馈（仅失败有提示） | 按钮成功响应后短暂显示 ✓（500ms）还原 |
| U8 | LOW | 缺 theme-color；footer 文字 #555 对比度约 3.4:1 | 加 theme-color #0f0f0f；footer 提亮 #888（约 4.6:1） |
| U9 | LOW | 方向键用 Unicode 箭头（▲◀▶） | 保留（跨平台渲染一致，改动风险低） |
| U10 | LOW | user-scalable=no 禁缩放 | 保留（工具页防误操作权衡，已评估） |

---

## 二、核对通过项

- **触摸目标**：dpad 76px ✓、侧键 52px ✓、快捷站点 44px ✓、地址栏 44px ✓、状态点有文字伴随 ✓
- **对比度**：主文本（#fff/#eee on 深底）>12:1 ✓；次级 #888 约 4.6:1 ✓；#4caf50（绿）约 7.6:1 ✓；#ff4081（主色）约 5.4:1 ✓
- **按压反馈**：手机端所有按钮/站点/按键均有 :active 态 ✓
- **焦点可见性**：TV 主页 .site:focus 高亮 ✓（UI 必备 focus ring）
- **状态非仅靠颜色**：状态条有文字（未连接/标题）+ 圆点双通道 ✓
- **操作提示**：footer"长按方向键可连续移动"、主页"手机扫一扫"引导 ✓

---

## 三、未做项（有意保留）

| 项 | 原因 |
|---|---|
| 电视主页整体视觉重设计（品牌感） | 功能优先，保持克制深色风格 |
| 手机控制页动画（入场/按钮涟漪） | 控制页是工具型，追求低延迟响应 |
| TV 侧边栏/更多页面 | 当前主页+工具栏已覆盖核心路径 |

---

## 四、验证

- assembleDebug：BUILD SUCCESSFUL 零警告
- verify.js：21/21 通过
- 控制页 JS 语法检查通过；assets/control 与 web/control 同步一致
