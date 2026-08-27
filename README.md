# 电视浏览器（TV Browser）

给电视用的极简浏览器：**电视端 Android App 内置 WebView 浏览器 + 手机浏览器扫码遥控**。
手机与电视同一 Wi-Fi 下，扫码即可打开遥控页——输网址、搜索、当遥控器（方向键/确认/返回/菜单）。

## 功能

**电视端**
- 系统 WebView 浏览器，支持任意网站、HTML5 视频全屏
- 遥控器操作：方向键导航、返回键后退、菜单键唤出工具栏
- 工具栏：地址输入 / 刷新 / 主页 / 扫码提示
- 主页内置二维码 + 常用网站入口（遥控器可导航）
- **电视端虚拟键盘**：遥控器方向键在字母/数字/符号网格输入网址（含 https://、.com、.cn 等快捷键）
- **快捷站点共享**：电视主页与手机控制页共用同一份快捷站点（电视端 filesDir/sites.json），手机端可增删改
- **必应搜索**：遥控器输拼音 → 实时中文联想候选（数字键/方向键选择）→ 打开搜索结果
- 局域网 IP 自动发现，内置本地控制服务（端口 8080）

**手机端（扫码后浏览器打开，无需安装 App）**
- 输入网址一键推送电视打开
- 搜索（Bing 搜索页推送到电视）
- 遥控按键面板：方向键 / OK / 返回 / 菜单 / 主页 / 刷新（长按连发）
- 文字输入：电视页面聚焦输入框后，手机输入内容同步到电视（支持 React/Vue 等框架）
- 实时显示电视当前页面标题与地址

## 架构

```
┌─ 电视端 (Android App, Kotlin) ──────────────────────────────┐
│ MainActivity: 系统 WebView + 全屏视频 + 遥控按键 + 工具栏    │
│ RemoteServer: NanoHTTPD :8080                               │
│   GET /          手机控制页 (assets/control/index.html)      │
│   GET /home      电视端主页（含二维码）                      │
│   GET /qr.png    二维码图片（ZXing 生成）                    │
│   GET /api/status  电视状态（URL/标题/可前进后退）           │
│   POST /api/open  {url}   打开网址                           │
│   POST /api/key   {key}   模拟按键                          │
│   POST /api/input {text}  注入文字到聚焦输入框               │
└──────────────┬──────────────────────────────────────────────┘
               │ 同一 Wi-Fi，HTTP
┌─ 手机端 (纯 HTML/JS/CSS, 扫码后浏览器打开) ─────────────────┐
│ 地址栏 + 快捷站点 + 遥控面板 + 文字输入 + 3s 状态轮询        │
└─────────────────────────────────────────────────────────────┘
```

## 使用

1. 编译安装 APK 到电视/盒子：
   ```bash
   cd tv-browser
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. 打开 App：主页显示二维码（`http://{电视IP}:8080/?t={令牌}`，令牌由 App 首次启动自动生成）
3. 手机连同一 Wi-Fi，扫二维码 → 浏览器打开遥控页
4. 输网址 / 点搜索 / 用遥控面板控制电视

## 项目结构

```
tv-browser/
├── app/src/main/
│   ├── java/com/tvbrowser/app/
│   │   ├── MainActivity.kt    # 浏览器主界面 + 遥控 + 全屏视频
│   │   ├── RemoteServer.kt    # NanoHTTPD 控制服务
│   │   ├── RemoteActions.kt   # 服务→Activity 动作接口
│   │   ├── QrUtil.kt          # ZXing 二维码
│   │   └── NetUtil.kt         # 局域网 IP
│   ├── assets/control/        # 手机控制页 + 电视主页（随 APK 打包）
│   └── res/                   # 布局/图标/主题
├── web/control/               # 控制页源目录（与 assets/control 同步）
├── build.gradle.kts           # AGP 8.6 + Kotlin 1.9
└── settings.gradle.kts
```

## 技术栈

- Kotlin + AGP 8.6.0 + Gradle 8.7（JDK 17）
- 系统 WebView（无 X5 依赖，minSdk 21 / targetSdk 34）
- NanoHTTPD 2.3.1（本地控制服务）
- ZXing core 3.5.3（二维码生成）
- 手机控制页：原生 HTML/CSS/JS（无构建步骤）

## 链路验证

不依赖真机即可验证控制链路（模拟 RemoteServer 路由 + 真实控制页文件 + app.js 调用格式）：

```bash
node web/verify.js   # 16 项检查：静态资源路由 / API 参数 / 页面资源引用完整性
```

## 待办 / 可扩展

- [ ] 遥控方向键的页面级焦点导航增强（自定义 JS 焦点系统）
- [ ] 多手机连接管理 / 简单鉴权
- [ ] 视频平台聚合搜索（替代 Bing 搜索）
- [ ] 播放控制（进度/音量/全屏）
- [ ] 退出确认对话框
