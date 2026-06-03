package com.ynk.virtualdisplay

import android.app.Application
import android.util.Log
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.data.repository.ShizukuDisplayRepository

class MyApplication : Application() {
    companion object {
        private const val TAG = "MyApplication"
    }

    lateinit var displayRepository: IDisplayRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MyApplication onCreate, initializing ShizukuDisplayRepository")
        displayRepository = ShizukuDisplayRepository(this)
    }
}
