package com.tvbrowser.app

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网 IP 获取（优先 Wi-Fi/以太网，跳过虚拟接口）。
 * 带 30 秒缓存：控制页 3 秒轮询 /api/status 时避免高频遍历 NetworkInterface。
 */
object NetUtil {

    private const val CACHE_TTL_MS = 30_000L

    @Volatile private var cachedIp: String? = null
    @Volatile private var cachedAt: Long = 0L

    fun getLocalIpAddress(): String? {
        val now = System.currentTimeMillis()
        val cached = cachedIp
        if (cached != null && now - cachedAt < CACHE_TTL_MS) {
            return cached
        }
        val fresh = resolve()
        if (fresh != null) {
            cachedIp = fresh
            cachedAt = now
        }
        return fresh
    }

    private fun priority(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("wlan") -> 0   // Wi-Fi
            n.startsWith("eth") -> 1    // 有线
            n.startsWith("en") -> 2     // macOS 风格以太网
            else -> 3
        }
    }

    private fun resolve(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.filter { it.isUp && !it.isLoopback }
                ?.filter { nif ->
                    val name = nif.name.lowercase()
                    !name.startsWith("dummy") && !name.startsWith("tun") &&
                        !name.startsWith("ppp") && !name.startsWith("virbr")
                }
                ?.sortedBy { priority(it.name) }
                ?: return null
            for (nif in interfaces) {
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
