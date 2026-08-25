package com.tvbrowser.app

import android.graphics.Bitmap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 本地控制服务（端口 8080）。
 * - GET  /                手机控制页（扫码后打开；页面本身公开，API 需 token）
 * - GET  /control/...     控制页静态资源（公开）
 * - GET  /home            电视端主页（公开，内含注入 token 的二维码 URL）
 * - GET  /qr.png?t=TOKEN  二维码图片（需 token）
 * - GET  /api/status      电视状态（需 token）
 * - POST /api/open        {url} 打开网址（需 token）
 * - POST /api/key         {key} 模拟按键（需 token）
 * - POST /api/input       {text} 注入文字（需 token）
 */
class RemoteServer(port: Int, private val actions: RemoteActions, private val token: String) : NanoHTTPD(port) {

    companion object {
        private const val MAX_BODY_SIZE = 64 * 1024L
    }

    init {
        // 启动失败（端口占用等）直接抛出，由调用方感知并提示
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @Suppress("DEPRECATION")
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/" || uri == "/control" || uri == "/control/" ->
                    serveAsset("control/index.html")
                uri.startsWith("/control/") ->
                    serveAsset(uri.removePrefix("/control/"))
                uri == "/home" ->
                    serveHome()
                uri == "/qr.png" ->
                    if (session.parms["t"] == token) serveQr() else unauthorized()
                uri == "/api/status" ->
                    if (authorized(session)) serveStatus() else unauthorized()
                uri == "/api/open" ->
                    if (authorized(session)) handlePost(session, "url") { body ->
                        actions.openUrl(body.optString("url"))
                    } else unauthorized()
                uri == "/api/key" ->
                    if (authorized(session)) handlePost(session, "key") { body ->
                        actions.handleRemoteKey(body.optString("key"))
                    } else unauthorized()
                uri == "/api/input" ->
                    if (authorized(session)) handlePost(session, "text") { body ->
                        actions.inputText(body.optString("text"))
                    } else unauthorized()
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

    /** token 校验：请求头 X-Auth-Token 或查询参数 t */
    @Suppress("DEPRECATION")
    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers["x-auth-token"]
        if (header == token) return true
        val parm = session.parms["t"]
        return parm == token
    }

    private fun unauthorized(): Response =
        newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")

    private fun serveHome(): Response {
        val html = readAssetText("control/home_tv.html")
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "asset not found")
        val injected = html.replace("{token}", token)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", injected)
    }

    private fun readAssetText(path: String): String? {
        return try {
            actions.assets().open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
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

    @Suppress("DEPRECATION")
    private fun handlePost(session: IHTTPSession, required: String, handler: (JSONObject) -> Unit): Response {
        // body 大小限制，防恶意大请求（Content-Length 声明与实际字节数双重校验，防 chunked 绕过）
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_BODY_SIZE) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "body too large")
        }
        val body = readBody(session)
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_SIZE) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "body too large")
        }
        if (body.isBlank()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "empty body")
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid json")
        }
        if (!json.has(required) || json.optString(required).isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "missing field: " + required
            )
        }
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
