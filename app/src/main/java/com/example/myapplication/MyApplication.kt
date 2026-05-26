package com.example.myapplication

import android.app.Application
import android.util.Log

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
