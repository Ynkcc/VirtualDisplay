package com.ynk.virtualdisplay.util

import java.net.*

object NetUtils {
    const val LOCAL_HOST = "127.0.0.1"
    const val ANY_HOST = "0.0.0.0"

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
}
