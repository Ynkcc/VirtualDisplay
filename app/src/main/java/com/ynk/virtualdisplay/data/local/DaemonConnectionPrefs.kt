package com.ynk.virtualdisplay.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Daemon 连接配置（监听地址 / 端口 / 连接密码）。
 */
internal object DaemonConnectionPrefs {

    private const val DEFAULT_PORT = 27183

    /** 监听端口流（默认 27183）。 */
    fun serverPortFlow(context: Context): Flow<Int> =
        AppDataStore.store(context).data.map { it[AppDataStore.serverPortKey] ?: DEFAULT_PORT }

    /** 读取当前监听端口（默认 27183）。 */
    suspend fun getServerPort(context: Context): Int =
        AppDataStore.store(context).data.first()[AppDataStore.serverPortKey] ?: DEFAULT_PORT

    /** 持久化监听端口。 */
    suspend fun setServerPort(context: Context, port: Int) {
        AppDataStore.store(context).edit { it[AppDataStore.serverPortKey] = port }
    }

    /** 监听地址流（默认 127.0.0.1）。 */
    fun serverHostFlow(context: Context): Flow<String> =
        AppDataStore.store(context).data.map { it[AppDataStore.serverHostKey] ?: NetUtils.LOCAL_HOST }

    /** 读取当前监听地址（默认 127.0.0.1）。 */
    suspend fun getServerHost(context: Context): String =
        AppDataStore.store(context).data.first()[AppDataStore.serverHostKey] ?: NetUtils.LOCAL_HOST

    /** 持久化监听地址。 */
    suspend fun setServerHost(context: Context, host: String) {
        AppDataStore.store(context).edit { it[AppDataStore.serverHostKey] = host }
    }

    /** 连接密码流（默认为空）。 */
    fun serverPasswordFlow(context: Context): Flow<String> =
        AppDataStore.store(context).data.map { it[AppDataStore.serverPasswordKey] ?: "" }

    /** 读取当前连接密码（默认为空）。 */
    suspend fun getServerPassword(context: Context): String =
        AppDataStore.store(context).data.first()[AppDataStore.serverPasswordKey] ?: ""

    /** 持久化连接密码。 */
    suspend fun setServerPassword(context: Context, password: String) {
        AppDataStore.store(context).edit { it[AppDataStore.serverPasswordKey] = password }
    }
}
