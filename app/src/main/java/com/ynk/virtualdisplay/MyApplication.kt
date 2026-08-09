package com.ynk.virtualdisplay

import android.app.Application
import android.util.Log
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.DaemonDisplayRepository
import com.ynk.virtualdisplay.util.ExceptionUtils

class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"
    }

    lateinit var displayRepository: IDisplayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        ExceptionUtils.setupGlobalCrashHandler()
        Log.i(TAG, "MyApplication onCreate, initializing DaemonDisplayRepository")
        displayRepository = DaemonDisplayRepository(this)
    }
}
