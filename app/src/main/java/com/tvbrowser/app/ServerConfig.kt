package com.tvbrowser.app

import android.content.Context
import java.security.SecureRandom

/** 服务器配置与访问令牌 */
object ServerConfig {
    const val PORT = 8080

    /** 电视端主页（本地访问，无需 token） */
    fun homeUrl(): String = "http://127.0.0.1:$PORT/home"

    /** 手机控制页地址（含 token，扫码用） */
    fun controlUrl(ip: String, token: String): String = "http://$ip:$PORT/?t=$token"

    private const val PREF = "tv_browser_pref"
    private const val KEY_TOKEN = "auth_token"

    /** 获取（或首次生成并持久化）访问令牌 */
    fun getToken(context: Context): String {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = sp.getString(KEY_TOKEN, null)
        if (!existing.isNullOrEmpty()) return existing
        val newToken = generateToken()
        sp.edit().putString(KEY_TOKEN, newToken).apply()
        return newToken
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
