package com.ynk.virtualdisplay.util

import java.net.*

/** 网络相关工具：地址常量、错误信息映射与 TCP 连通性测试。 */
object NetUtils {
    /** 本地环路地址。 */
    const val LOCAL_HOST = "127.0.0.1"

    /** 任意地址（监听所有网卡）标识。 */
    const val ANY_HOST = "0.0.0.0"

    /**
     * 将网络异常映射为用户可读的中文提示，按常见错误类型给出排查建议。
     *
     * @param t 捕获的网络异常。
     * @return 友好的中文错误描述。
     */
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

    /**
     * 如果为 0.0.0.0 返回本地环路地址，否则返回实际ip
     */
    fun resolveConnectHost(host: String): String {
        return if (host == ANY_HOST) LOCAL_HOST else host
    }

    /**
     * 测试 host:port 是否能建立 TCP 连接（需在 IO 线程调用）
     */
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
