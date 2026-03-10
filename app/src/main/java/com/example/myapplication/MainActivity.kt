package com.example.myapplication

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.models.ShizukuState
import com.example.myapplication.ui.screens.ShizukuPermissionScreen
import com.example.myapplication.ui.screens.VirtualDisplayScreen
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            if (Sui.init(packageName)) {
                Log.i("MainActivity", "Sui initialized successfully")
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "Sui init failed", e)
        }

        viewModel.checkShizukuStatus(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ShizukuDisplayBridge.bindService(this@MainActivity)
            }
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsState()
                
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (val state = uiState.shizukuState) {
                            is ShizukuState.Checking -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            is ShizukuState.Ready -> {
                                VirtualDisplayScreen(viewModel = viewModel)
                            }
                            else -> {
                                ShizukuPermissionScreen(
                                    state = state,
                                    onRetry = { viewModel.checkShizukuStatus(this@MainActivity) },
                                    onRequestPermission = {
                                        try {
                                            Shizuku.requestPermission(ShizukuDisplayBridge.REQUEST_CODE)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Failed to request permission", e)
                                        }
                                    }
                                )
                            }
                        }
                        
                        if (uiState.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除手动解绑，交给 repeatOnLifecycle 自动管理
    }
}
