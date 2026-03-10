package com.example.myapplication
import rikka.shizuku.SystemServiceHelper
import android.os.IBinder

fun test(): IBinder? {
    return SystemServiceHelper.getSystemService("display")
}
