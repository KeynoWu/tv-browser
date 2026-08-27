# 🍅 电视浏览器 TV Browser

> **给电视用的极简浏览器：Android TV 端 WebView 浏览器 + 手机扫码遥控（无需安装 App）**
> An open-source TV browser for Android TV - browse on TV, control on phone (scan QR, no app install needed).

Android · Kotlin · WebView · NanoHTTPD · ZXing   |   支持 Android 5.0+（API 21+）

---

## ✨ 功能特性

### 📺 电视端（Android TV App）

| 功能 | 说明 |
|---|---|
| 网页浏览 | 系统 WebView 加载任意网站，HTML5 视频全屏、多窗口处理 |
| 遥控器操作 | 方向键导航、返回后退/双击退出、菜单键唤出工具栏 |
| 🖥 TV 虚拟键盘 | 遥控器直接输网址 - 字母/数字/符号网格 + 一键快捷键（「https://」「.com」「.cn」等），不再依赖手机 |
| 🔍 必应搜索 | 遥控器输拼音到实时中文联想候选（方向键/数字键选择）再到打开搜索结果 |
| 快捷入口 | 主页二维码 + 快捷站点（与手机端共享同一份数据） |
| 体验细节 | 页面加载进度条、渲染进程崩溃保护、切后台自动暂停、加载失败提示 |

### 📱 手机端（扫码即用，纯网页）

| 功能 | 说明 |
|---|---|
| 扫码连接 | 电视主页二维码到手机浏览器直接打开控制页（iOS/Android 通用，无需安装） |
| 输网址 / 搜索 | 一键推送电视打开；Bing 搜索；快捷站点点击直达 |
| 遥控面板 | 圆形 D-pad（方向/OK）+ 返回/菜单/主页/刷新，单击即发、长按连发、触控震动反馈 |
| 文字输入 | 手机输入同步注入电视页面输入框（Base64 传输，兼容 React/Vue 框架） |
| 快捷站点管理 | 增删改快捷入口（名称/网址），保存后电视主页实时同步 |
| 状态同步 | 3 秒轮询显示电视当前页面标题与地址 |

---

## 🏗 架构

    ┌─ 电视端 (Android App, Kotlin) ─────────────────────────────┐
    │ MainActivity: 系统 WebView + 遥控按键 + 全屏视频 + 工具栏    │
    │ RemoteServer: NanoHTTPD :8080 本地控制服务                 │
    │   GET /            手机控制页（页面公开，API 需令牌）        │
    │   GET /home        电视主页（二维码 + 动态快捷入口）         │
    │   GET /qr.png      二维码（需令牌）                         │
    │   GET /api/status  电视状态（URL/标题/可前进后退）           │
    │   POST /api/open   {url} 打开网址                           │
    │   POST /api/key    {key} 模拟按键                           │
    │   POST /api/input  {text} 注入文字                          │
    │   GET  /api/suggest  搜索联想（Bing 转发）                  │
    │   GET/POST /api/sites  快捷站点读/存（sites.json）          │
    └──────────────┬─────────────────────────────────────────────┘
                   │ 同一 Wi-Fi，HTTP + 令牌鉴权
    ┌─ 手机端 (纯 HTML/CSS/JS，扫码后浏览器打开) ─────────────────┐
    │ 品牌头部 + 访问网址 + 快捷入口(可编辑) + 文字输入 + 遥控面板   │
    └─────────────────────────────────────────────────────────────┘

---

## 🚀 快速开始

1. **下载**：Release 获取最新 APK，或自行编译
2. **安装**到 Android TV / 盒子（Android 5.0+）：
    adb install -r app-release.apk
3. 打开 App，主页显示**二维码**（「http://{电视IP}:8080/?t=令牌」）
4. 手机连**同一 Wi-Fi**，扫码打开控制页
5. 输网址 / 用遥控面板 / 编辑快捷入口 / 电视主页用遥控器直接输网址或拼音搜索

---

## 🔒 安全模型

| 防护 | 说明 |
|---|---|
| 令牌鉴权 | 二维码携带随机令牌（持久化），所有控制 API 校验「X-Auth-Token」，防止局域网任意设备操控电视 |
| 协议白名单 | 仅 http/https，拒绝「file:」等本地文件读取 |
| Host 校验 | 防 DNS rebinding（仅接受本机 IP/localhost） |
| 请求限制 | body 小于等于 64KB（双重校验防 chunked 绕过）、suggest 参数长度限制 |
| WebView 收紧 | 禁 file/content 访问、禁定位、禁表单保存、无 JS 桥接口 |

为什么需要令牌？见 REVIEW5 讨论：浏览器功能本身开放，令牌保护的是「局域网控制服务」——扫码的设备才有权操控电视。

---

## 🛠 技术栈

- **Android 端**：Kotlin · AGP 8.6.0 · Gradle 8.7（JDK 17）· 系统 WebView · NanoHTTPD 2.3.1（本地控制服务）· ZXing 3.5.3（二维码）
- **控制页**：纯 HTML/CSS/JS（SVG 图标、无构建步骤、随 APK 打包）

## 📁 项目结构

    tv-browser/
    ├── app/src/main/
    │   ├── java/com/tvbrowser/app/
    │   │   ├── MainActivity.kt    # 浏览器主界面 + 遥控 + 全屏 + 快捷站点存储
    │   │   ├── RemoteServer.kt    # NanoHTTPD 控制服务（路由/鉴权/联想/站点）
    │   │   ├── RemoteActions.kt   # 服务到 Activity 接口
    │   │   ├── ServerConfig.kt    # 端口/令牌配置
    │   │   ├── QrUtil.kt          # ZXing 二维码
    │   │   └── NetUtil.kt         # 局域网 IP（缓存/优先级）
    │   ├── assets/control/        # 手机控制页 + 电视主页（随 APK 打包）
    │   └── res/                   # 布局/图标/主题
    ├── web/control/               # 控制页源目录（与 assets 同步）
    ├── web/verify.js              # 链路验证脚本（29 项）
    └── REVIEW*.md                 # 开发审查记录（1-9）

---

## 🔨 构建

环境：JDK 17 + Android SDK（platform 34）

    ./gradlew assembleDebug    # 调试包
    ./gradlew assembleRelease  # 发布包（需签名配置）

签名：local.properties 配置 RELEASE_STORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD（不入库）；keystore（*.jks）同样被 .gitignore 忽略。

## ✅ 验证

    node web/verify.js   # 29 项链路验证：路由/token/参数/suggest/sites

---

## 📄 开发记录

项目经历 9 轮审查迭代（REVIEW.md 到 REVIEW9.md）：架构、安全/稳定/性能、UI/图标、资源生命周期、构建发布、真机反馈修复——每轮均有实际发现与修复闭环。

## License

MIT © KeynoWu
