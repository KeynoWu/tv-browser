package com.tvbrowser.app

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 本地控制服务（端口 8080）。
 * - GET  /                手机控制页（扫码后打开）
 * - GET  /control/...    控制页静态资源
 * - GET  /home            电视端主页（含二维码）
 * - GET  /qr.png          二维码图片
 * - GET  /api/status      电视状态
 * - POST /api/open        {url} 打开网址
 * - POST /api/key         {key} 模拟按键
 * - POST /api/input       {text} 注入文字
 */
class RemoteServer(port: Int, private val actions: RemoteActions) : NanoHTTPD(port) {

    init {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            android.util.Log.e("RemoteServer", "start failed", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/control" || uri == "/control/" ->
                    serveAsset("control/index.html")
                uri.startsWith("/control/") ->
                    serveAsset(uri.removePrefix("/control/"))
                uri == "/home" ->
                    serveAsset("control/home_tv.html")
                uri == "/qr.png" ->
                    serveQr()
                uri == "/api/status" ->
                    serveStatus()
                uri == "/api/open" ->
                    handlePost(session) { body -> actions.openUrl(body.optString("url")) }
                uri == "/api/key" ->
                    handlePost(session) { body -> actions.handleRemoteKey(body.optString("key")) }
                uri == "/api/input" ->
                    handlePost(session) { body -> actions.inputText(body.optString("text")) }
                uri.startsWith("/api/") ->
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 not found")
                // 控制页的静态资源（style.css/app.js 等）以根路径请求，从 control/ 兜底读取
                else ->
                    serveAsset("control/" + uri.removePrefix("/"))
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: " + e.message)
        }
    }

    private fun serveAsset(path: String): Response {
        val normalized = path.removePrefix("/")
        val mime = when {
            normalized.endsWith(".html") -> "text/html; charset=utf-8"
            normalized.endsWith(".js") -> "application/javascript; charset=utf-8"
            normalized.endsWith(".css") -> "text/css; charset=utf-8"
            normalized.endsWith(".png") -> "image/png"
            normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") -> "image/jpeg"
            normalized.endsWith(".svg") -> "image/svg+xml"
            normalized.endsWith(".ico") -> "image/x-icon"
            else -> "application/octet-stream"
        }
        return try {
            val stream: InputStream = actions.assets().open(normalized)
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "asset not found: " + normalized)
        }
    }

    private fun serveQr(): Response {
        val bmp = actions.qrBitmap()
        if (bmp == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "no ip available")
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return newChunkedResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bos.toByteArray()))
    }

    private fun serveStatus(): Response {
        val json = JSONObject(actions.currentStatus())
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json.toString())
    }

    private fun handlePost(session: IHTTPSession, handler: (JSONObject) -> Unit): Response {
        val body = readBody(session)
        val json = if (body.isBlank()) JSONObject() else JSONObject(body)
        handler(json)
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"ok\":true}")
    }

    private fun readBody(session: IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
