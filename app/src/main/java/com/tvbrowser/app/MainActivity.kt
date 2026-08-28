package com.tvbrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.tvbrowser.app.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File

class MainActivity : Activity(), RemoteActions {

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var remoteServer: RemoteServer? = null
    private var toolbarVisible = false

    // 主线程维护的状态快照（NanoHTTPD 后台线程只读，避免跨线程调用 WebView）
    @Volatile private var currentTitle = ""
    @Volatile private var currentUrl = ""
    @Volatile private var canGoBack = false
    @Volatile private var canGoForward = false
    private var backKeyTime = 0L

    // HTML5 视频全屏
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val authToken: String by lazy { ServerConfig.getToken(applicationContext) }

    // 鼠标模式（飞鼠）：光标注入页面，方向键移动、OK 点击、返回退出
    @Volatile private var mouseMode = false
    private val MOUSE_STEP = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initWebView()
        initToolbar()
        startServer()
        loadHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION") // minSdk 21 兼容需要，WindowInsetsController 需 API 30+
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // 注入到当前页面的鼠标控制 JS（光标 + elementFromPoint 点击 + 边缘滚动）
    private val mouseJs = """
        (function(){
          if(window.__tvMouse) return;
          window.__tvMouse = {
            x: Math.round(window.innerWidth / 2),
            y: Math.round(window.innerHeight / 2),
            el: null,
            init: function(){
              if(this.el) return;
              var d = document.createElement("div");
              d.style.cssText = "position:fixed;z-index:2147483647;pointer-events:none;width:36px;height:36px;" +
                "left:0;top:0;transform:translate(-50%,-50%);" +
                "border-radius:50%;background:rgba(255,61,127,0.45);border:3px solid #ff3d7f;" +
                "box-shadow:0 0 12px rgba(255,61,127,0.6);";
              document.documentElement.appendChild(d);
              this.el = d;
              this.render();
            },
            render: function(){ if(this.el) this.el.style.transform = "translate(" + this.x + "px," + this.y + "px) translate(-50%,-50%)"; },
            move: function(dx,dy){
              var w = window.innerWidth, h = window.innerHeight;
              this.x = Math.max(0, Math.min(w, this.x + dx));
              this.y = Math.max(0, Math.min(h, this.y + dy));
              this.render();
              if(this.y < h * 0.08) window.scrollBy(0, -24);
              else if(this.y > h * 0.92) window.scrollBy(0, 24);
              if(this.x < w * 0.04) window.scrollBy(-20, 0);
              else if(this.x > w * 0.96) window.scrollBy(20, 0);
            },
            click: function(){
              var el = document.elementFromPoint(this.x, this.y);
              if(!el) return;
              try{
                el.dispatchEvent(new MouseEvent("mousedown", {clientX:this.x, clientY:this.y, bubbles:true, view:window}));
                el.dispatchEvent(new MouseEvent("mouseup", {clientX:this.x, clientY:this.y, bubbles:true, view:window}));
                // 只派发一次合成 click；不再调用 el.click()（否则监听器收到两次，播放按钮会出现开又关）
                el.dispatchEvent(new MouseEvent("click", {clientX:this.x, clientY:this.y, bubbles:true, view:window}));
              }catch(e){}
            },
            scroll: function(dy){ window.scrollBy(0, dy); },
            destroy: function(){ if(this.el){ this.el.parentNode.removeChild(this.el); this.el = null; } }
          };
          window.__tvMouse.init();
        })();
    """.trimIndent()

    /** 主线程执行页面 JS（WebView 未就绪/已销毁时安全跳过） */
    private fun evalPageJs(js: String) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            val wv = webView ?: return@runOnUiThread
            wv.evaluateJavascript(js, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val wv = binding.webView
        webView = wv
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // 视频站兼容权衡（见 REVIEW2 A-1）
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = false // 禁止 file:// 本地文件读取（防局域网利用）
        s.setAllowContentAccess(false) // 禁止 content:// 内容访问
        s.setGeolocationEnabled(false) // 禁用定位
        s.setSupportZoom(false) // 禁用缩放（TV 遥控场景无意义）
        // PC 桌面 UA（去掉 Mobile 标记）：视频/新闻类网站按桌面版布局呈现
        s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 TvBrowser/0.1"

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = url ?: ""
                refreshNavState(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url ?: ""
                refreshNavState(view)
                // 鼠标模式开启时，页面跳转后重新注入光标
                if (mouseMode) {
                    view?.evaluateJavascript(mouseJs, null)
                }
            }

            // 兼容 API 21-22（旧签名）
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (view?.url == failingUrl) {
                    Toast.makeText(this@MainActivity, "加载失败: " + (description ?: ""), Toast.LENGTH_SHORT).show()
                }
            }

            // API 23+ 新签名
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Toast.makeText(this@MainActivity, "加载失败: " + error?.description, Toast.LENGTH_SHORT).show()
                }
            }

            // 渲染进程崩溃（API 26+）：返回 true 阻止整个应用崩溃，提示并复位页面
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                val msg = if (detail?.didCrash() == true) {
                    "网页渲染进程崩溃，正在恢复"
                } else {
                    "网页占用内存过高，正在恢复"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                view?.loadUrl("about:blank")
                return true // 已处理，应用不崩溃
            }
        }

        // 拦截下载：当前版本不做文件下载，提示用户
        wv.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "暂不支持文件下载", Toast.LENGTH_SHORT).show()
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = title ?: ""
            }

            // 页面加载进度（顶部细进度条，finished 后隐藏）
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    binding.pageProgress.visibility = View.INVISIBLE
                } else {
                    binding.pageProgress.visibility = View.VISIBLE
                    binding.pageProgress.progress = newProgress
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // target=_blank / window.open：复用当前 WebView 打开
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val current = webView ?: return false
                transport.webView = current
                msg.sendToTarget()
                return true
            }
        }
    }

    private fun refreshNavState(view: WebView?) {
        val v = view ?: return
        canGoBack = v.canGoBack()
        canGoForward = v.canGoForward()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemUi()
    }

    private fun showCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.webView.visibility = View.GONE
        binding.customViewContainer.visibility = View.VISIBLE
        view?.let { binding.customViewContainer.addView(it) }
        hideToolbar()
    }

    private fun hideCustomView() {
        val cv = customView ?: return
        binding.customViewContainer.removeView(cv)
        binding.customViewContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    private fun initToolbar() {
        binding.btnOpen.setOnClickListener { openUrlFromInput() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                openUrlFromInput()
                true
            } else {
                false
            }
        }
        binding.btnRefresh.setOnClickListener { webView?.reload() }
        binding.btnHome.setOnClickListener { loadHome() }
        binding.btnRemote.setOnClickListener { showRemoteHint() }
        binding.btnMouse.setOnClickListener { toggleMouseMode() }
    }

    private fun openUrlFromInput() {
        val text = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        openUrl(text)
        hideToolbar()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlInput.windowToken, 0)
    }

    override fun openUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        // 协议白名单：仅 http/https（拒绝 file/ftp/data/javascript 等）
        val lower = trimmed.lowercase()
        val schemeEnd = lower.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = lower.substring(0, schemeEnd)
            if (scheme != "http" && scheme != "https") return
        }
        val finalUrl = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "http://" + trimmed
        }
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            // post 执行时重新取 webView：防止与 onDestroy 竞态（取到已销毁实例导致崩溃）
            val wv = webView ?: return@runOnUiThread
            wv.loadUrl(finalUrl)
        }
    }

    private fun loadHome() {
        openUrl(ServerConfig.homeUrl())
    }

    private fun showRemoteHint() {
        val ip = NetUtil.getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(this, "未连接网络，无法生成二维码", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "手机浏览器打开 " + ServerConfig.controlUrl(ip, authToken), Toast.LENGTH_LONG).show()
        }
    }

    private fun startServer() {
        try {
            remoteServer = RemoteServer(ServerConfig.PORT, this, authToken)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "控制服务启动失败（端口 " + ServerConfig.PORT + " 可能被占用）: " + e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Suppress("DEPRECATION") // freeMemory 在新 SDK 标记废弃但仍是官方建议的低内存释放方式
    override fun onLowMemory() {
        super.onLowMemory()
        webView?.freeMemory()
    }

    override fun onDestroy() {
        remoteServer?.stop()
        try {
            binding.root.removeView(binding.webView)
        } catch (_: Exception) {
            // 视图可能已被移除，忽略
        }
        webView?.destroy()
        webView = null // 销毁后置空，防止残留引用被调用崩溃
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 鼠标模式：方向键移动光标、OK 点击、返回/菜单退出（UP 一并拦截，避免孤立 UP 泄漏到 WebView）
        if (mouseMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TAB -> return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && mouseMode) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { mouseAction("move", 0f, -MOUSE_STEP.toFloat()); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { mouseAction("move", 0f, MOUSE_STEP.toFloat()); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { mouseAction("move", -MOUSE_STEP.toFloat(), 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { mouseAction("move", MOUSE_STEP.toFloat(), 0f); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { mouseAction("click", 0f, 0f); return true }
                KeyEvent.KEYCODE_BACK -> { setMouseMode(false); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TAB -> {
                    setMouseMode(false)
                    toggleToolbar()
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TAB -> {
                    toggleToolbar()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (customView != null) {
                        hideCustomView()
                        return true
                    }
                    if (toolbarVisible) {
                        hideToolbar()
                        return true
                    }
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                        return true
                    }
                    val now = System.currentTimeMillis()
                    if (now - backKeyTime < 3000) {
                        finish()
                    } else {
                        backKeyTime = now
                        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleToolbar() {
        if (toolbarVisible) hideToolbar() else showToolbar()
    }

    private fun showToolbar() {
        if (toolbarVisible) return
        toolbarVisible = true
        // 显示当前页面 URL（仅填充一次，避免覆盖用户输入）
        if (currentUrl.isNotEmpty() && binding.urlInput.text.isNullOrEmpty()) {
            binding.urlInput.setText(currentUrl)
        }
        // 先 cancel 旧动画再重置状态，避免快速连按菜单键时动画互相取消导致状态错乱
        binding.toolbar.animate().cancel()
        binding.toolbar.visibility = View.VISIBLE
        binding.toolbar.alpha = 0f
        binding.toolbar.translationY = binding.toolbar.height.toFloat()
        binding.toolbar.animate().alpha(1f).translationY(0f).setDuration(200).start()
        binding.urlInput.requestFocus()
    }

    private fun hideToolbar() {
        if (!toolbarVisible) return
        toolbarVisible = false
        binding.urlInput.clearFocus()
        binding.toolbar.animate().cancel() // 防止与 show 动画交错
        binding.toolbar.animate().alpha(0f).translationY(binding.toolbar.height.toFloat())
            .setDuration(150)
            .withEndAction {
                binding.toolbar.visibility = View.GONE
            }
            .start()
    }

    // ---- 鼠标模式（飞鼠） ----
    private fun toggleMouseMode() {
        setMouseMode(!mouseMode)
    }

    override fun setMouseMode(on: Boolean) {
        if (isDestroyed || isFinishing || mouseMode == on) return
        mouseMode = on
        evalPageJs(if (on) mouseJs else "window.__tvMouse && window.__tvMouse.destroy();")
        if (on) {
            hideToolbar()
            Toast.makeText(this, "鼠标模式：方向键移动 · OK 点击 · 返回退出", Toast.LENGTH_SHORT).show()
        }
    }

    override fun mouseAction(type: String, p1: Float, p2: Float) {
        val js = when (type) {
            "click" -> "window.__tvMouse && window.__tvMouse.click();"
            "scroll" -> "window.__tvMouse && window.__tvMouse.scroll(" + p2.toInt() + ");"
            else -> "window.__tvMouse && window.__tvMouse.move(" + p1.toInt() + "," + p2.toInt() + ");"
        }
        evalPageJs(js)
    }

    // ---- RemoteActions ----
    override fun handleRemoteKey(key: String) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            val wv = webView ?: return@runOnUiThread
            when (key) {
                "back" -> {
                    if (toolbarVisible) {
                        hideToolbar()
                    } else if (wv.canGoBack()) {
                        wv.goBack()
                    } else {
                        Toast.makeText(this, "已经是第一页", Toast.LENGTH_SHORT).show()
                    }
                }
                "menu" -> toggleToolbar()
                "home" -> loadHome()
                "refresh" -> wv.reload()
                "forward" -> if (wv.canGoForward()) wv.goForward()
                "up" -> sendKey(wv, KeyEvent.KEYCODE_DPAD_UP)
                "down" -> sendKey(wv, KeyEvent.KEYCODE_DPAD_DOWN)
                "left" -> sendKey(wv, KeyEvent.KEYCODE_DPAD_LEFT)
                "right" -> sendKey(wv, KeyEvent.KEYCODE_DPAD_RIGHT)
                "ok" -> sendKey(wv, KeyEvent.KEYCODE_DPAD_CENTER)
            }
        }
    }

    private fun sendKey(wv: WebView, keyCode: Int) {
        if (!wv.hasFocus()) {
            wv.requestFocus()
        }
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun inputText(text: String) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val js = """
                (function(){
                  var el = document.activeElement;
                  if(!el) return;
                  var tag = el.tagName ? el.tagName.toUpperCase() : '';
                  var isInput = tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable;
                  if(!isInput) return;
                  var txt = decodeURIComponent(escape(atob('$encoded')));
                  if(el.isContentEditable){
                    el.textContent = (el.textContent || '') + txt;
                  } else {
                    var proto = tag === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(el, (el.value || '') + txt);
                  }
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                  el.dispatchEvent(new Event('change', {bubbles:true}));
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }
    }

    override fun currentStatus(): Map<String, Any> {
        return mapOf(
            "url" to currentUrl,
            "title" to currentTitle,
            "canGoBack" to canGoBack,
            "canGoForward" to canGoForward,
            "online" to (webView != null),
            "ip" to (NetUtil.getLocalIpAddress() ?: ""),
            "port" to ServerConfig.PORT
        )
    }

    override fun qrBitmap(): Bitmap? {
        // 二维码按需实时生成、用后即弃（Bitmap 随方法结束即可被 GC，不常驻内存）
        // 仅在电视主页加载 /qr.png 时触发，单次生成约几毫秒，成本可忽略
        val ip = NetUtil.getLocalIpAddress() ?: return null
        return QrUtil.generate(ServerConfig.controlUrl(ip, authToken), 512)
    }

    override fun assets(): AssetManager = assets

    // ---- 快捷站点管理（存 filesDir/sites.json，多手机共享） ----
    private val sitesFile: File get() = File(filesDir, "sites.json")

    private val defaultSitesJson = "{\"sites\":[{\"name\":\"B站\",\"url\":\"https://www.bilibili.com\"},{\"name\":\"爱奇艺\",\"url\":\"https://www.iqiyi.com\"},{\"name\":\"腾讯\",\"url\":\"https://v.qq.com\"},{\"name\":\"优酷\",\"url\":\"https://www.youku.com\"},{\"name\":\"央视直播\",\"url\":\"https://tv.cctv.com/live\"},{\"name\":\"芒果\",\"url\":\"https://www.mgtv.com\"}]}"

    @Synchronized
    override fun getSites(): String {
        return try {
            if (sitesFile.exists()) {
                val text = sitesFile.readText(Charsets.UTF_8)
                JSONObject(text) // 校验合法，损坏则回退默认
                text
            } else {
                defaultSitesJson
            }
        } catch (e: Exception) {
            defaultSitesJson
        }
    }

    @Synchronized
    override fun saveSites(json: String): Boolean {
        return try {
            val arr = JSONObject(json).optJSONArray("sites") ?: return false // 必须含 sites 数组
            // 校验每项为对象且含 name/url 字段
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: return false
                if (!item.has("name") || !item.has("url")) return false
            }
            val tmp = File(filesDir, "sites.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(sitesFile) // 原子替换，避免写一半损坏
            true
        } catch (e: Exception) {
            false
        }
    }
}
