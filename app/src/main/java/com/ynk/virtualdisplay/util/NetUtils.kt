package com.ynk.virtualdisplay.util

import android.util.Log
import java.net.*

object NetUtils {
    private const val TAG = "NetUtils"

    const val LOCAL_HOST = "127.0.0.1"
    const val ANY_HOST = "0.0.0.0"
    const val IPV6_ANY = "::"
    const val IPV6_LOOPBACK = "::1"

    private val IPV4_REGEX = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
    private val HOSTNAME_REGEX = Regex("^([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])(\\.[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])*$")

    fun getDefaultLoopback(): String {
        return try {
            InetAddress.getLoopbackAddress().hostAddress ?: LOCAL_HOST
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get system loopback address, using $LOCAL_HOST", e)
            LOCAL_HOST
        }
    }

    fun getFriendlyErrorMessage(t: Throwable): String {
        return when (t) {
            is ConnectException -> {
                val msg = t.message ?: ""
                when {
                    msg.contains("ECONNREFUSED") -> "连接被拒绝：服务端可能未启动，或者防火墙阻止了端口 27183。"
                    msg.contains("ETIMEDOUT") -> "连接超时：请检查网络是否稳定，或者目标 IP 是否正确。"
                    else -> "连接失败：请确保两台设备处于同一局域网内。"
                }
            }
            is NoRouteToHostException -> "网络不可达：请检查局域网连接，确保目标设备在当前网段。"
            is SocketTimeoutException -> "连接超时：网络响应过慢，请稍后重试。"
            is UnknownHostException -> "未知的主机地址，请检查 IP 格式是否正确。"
            else -> "网络错误: ${t.localizedMessage ?: "未知异常"}"
        }
    }

    fun resolveConnectHost(host: String): String {
        return when (val trimmed = host.trim()) {
            ANY_HOST -> LOCAL_HOST
            IPV6_ANY -> IPV6_LOOPBACK
            else -> trimmed
        }
    }

    fun isLocalHost(host: String): Boolean {
        val trimmed = host.trim()
        val defaultLoopback = getDefaultLoopback()
        return trimmed == LOCAL_HOST ||
                trimmed == IPV6_LOOPBACK ||
                trimmed == "localhost" ||
                trimmed == ANY_HOST ||
                trimmed == IPV6_ANY ||
                trimmed.equals(defaultLoopback, ignoreCase = true)
    }

    fun isValidHostFormat(host: String): Boolean {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == IPV6_ANY || trimmed == IPV6_LOOPBACK) return true
        if (IPV4_REGEX.matches(trimmed)) return true
        if (isValidIpv6Format(trimmed)) return true
        if (HOSTNAME_REGEX.matches(trimmed)) return true
        return false
    }

    private fun isValidIpv6Format(ip: String): Boolean {
        if (!ip.contains(':')) return false
        if (ip.count { it == ':' } < 2) return false
        val doubleColonIndex = ip.indexOf("::")
        if (doubleColonIndex != -1 && ip.indexOf("::", doubleColonIndex + 2) != -1) {
            return false
        }
        val parts = ip.split(":")
        if (doubleColonIndex == -1 && parts.size != 8) {
            return false
        }
        for (part in parts) {
            if (part.length > 4) return false
            if (part.isNotEmpty() && !part.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return false
            }
        }
        return true
    }

    fun getAvailableNetworkAddresses(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        list.add("本机环回 (${getDefaultLoopback()})" to getDefaultLoopback())
        list.add("双栈全部接口 (IPv4 + IPv6: ::)" to IPV6_ANY)
        list.add("IPv4 全部接口 (0.0.0.0)" to ANY_HOST)

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    val hostAddress = addr.hostAddress ?: continue
                    val cleanAddress = hostAddress.substringBefore('%')
                    val label = "${intf.displayName ?: intf.name} ($cleanAddress)"
                    list.add(label to cleanAddress)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get network interfaces", e)
        }
        return list
    }

    fun testConnection(host: String, port: Int, timeoutMs: Int = 3000): Result<Unit> {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
