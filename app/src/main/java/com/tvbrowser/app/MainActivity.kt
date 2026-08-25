package com.tvbrowser.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.tvbrowser.app.databinding.ActivityMainBinding
import java.nio.charset.Charset

class MainActivity : Activity(), RemoteActions {

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null
    private var remoteServer: RemoteServer? = null
    private var toolbarVisible = false
    private var currentTitle = ""
    private var currentUrl = ""
    private var backKeyTime = 0L

    // HTML5 视频全屏
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val wv = binding.webView
        webView = wv
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = s.userAgentString + " TvBrowser/0.1"

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = url ?: ""
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url ?: ""
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = title ?: ""
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                showCustomView(view, callback)
            }
            override fun onHideCustomView() {
                hideCustomView()
            }
        }
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
        val finalUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") &&
            !trimmed.startsWith("file://") && !trimmed.startsWith("about:")
        ) {
            "http://" + trimmed
        } else {
            trimmed
        }
        val wv = webView ?: return
        runOnUiThread { wv.loadUrl(finalUrl) }
    }

    private fun loadHome() {
        openUrl("http://127.0.0.1:8080/home")
    }

    private fun showRemoteHint() {
        val ip = NetUtil.getLocalIpAddress()
        if (ip == null) {
            Toast.makeText(this, "未连接网络，无法生成二维码", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "手机浏览器打开 http://$ip:8080 扫码遥控", Toast.LENGTH_LONG).show()
        }
    }

    private fun startServer() {
        try {
            remoteServer = RemoteServer(8080, this)
        } catch (e: Exception) {
            Toast.makeText(this, "控制服务启动失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        remoteServer?.stop()
        try {
            binding.root.removeView(binding.webView)
        } catch (_: Exception) {
            // 视图可能已被移除，忽略
        }
        webView?.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
        toolbarVisible = true
        binding.toolbar.visibility = View.VISIBLE
        binding.urlInput.requestFocus()
    }

    private fun hideToolbar() {
        toolbarVisible = false
        binding.toolbar.visibility = View.GONE
        binding.urlInput.clearFocus()
    }

    // ---- RemoteActions ----
    override fun handleRemoteKey(key: String) {
        runOnUiThread {
            val wv = webView ?: return@runOnUiThread
            when (key) {
                "back" -> if (wv.canGoBack()) wv.goBack()
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
            "canGoBack" to (webView?.canGoBack() ?: false),
            "canGoForward" to (webView?.canGoForward() ?: false),
            "online" to true,
            "ip" to (NetUtil.getLocalIpAddress() ?: ""),
            "port" to 8080
        )
    }

    override fun qrBitmap(): Bitmap? {
        val ip = NetUtil.getLocalIpAddress() ?: return null
        return QrUtil.generate("http://$ip:8080/", 512)
    }

    override fun assets(): AssetManager = assets
}
