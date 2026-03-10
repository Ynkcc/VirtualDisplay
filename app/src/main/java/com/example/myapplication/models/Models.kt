package com.example.myapplication.models

sealed class ShizukuState {
    data object Checking : ShizukuState()
    data object NotRunning : ShizukuState()
    data object PermissionDenied : ShizukuState()
    data object Ready : ShizukuState()
}

data class AppInfo(val name: String, val packageName: String)
