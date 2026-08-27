package com.tvbrowser.app

import android.content.res.AssetManager
import android.graphics.Bitmap

/**
 * 控制服务（NanoHTTPD）回调宿主 Activity 的动作接口。
 * 所有方法都可能在 NanoHTTPD 的后台线程被调用，实现方需自行切主线程。
 */
interface RemoteActions {
    /** 打开一个网址（主线程执行） */
    fun openUrl(url: String)

    /** 模拟遥控按键：up/down/left/right/ok/back/menu/home/refresh/forward */
    fun handleRemoteKey(key: String)

    /** 向当前页面聚焦的输入框注入文字 */
    fun inputText(text: String)

    /** 当前电视状态（用于 /api/status） */
    fun currentStatus(): Map<String, Any>

    /** 二维码图片（控制页地址） */
    fun qrBitmap(): Bitmap?

    /** 获取快捷站点列表（JSON：{"sites":[{"name","url"}]}），返回合法 JSON */
    fun getSites(): String

    /** 保存快捷站点列表（全量替换），成功返回 true */
    fun saveSites(json: String): Boolean

    /** assets 访问器（控制页静态资源） */
    fun assets(): AssetManager
}
