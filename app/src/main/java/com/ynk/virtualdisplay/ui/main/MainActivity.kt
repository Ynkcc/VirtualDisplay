package com.ynk.virtualdisplay.ui.main

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
import com.ynk.virtualdisplay.MyApplication
import com.ynk.virtualdisplay.data.model.ShizukuState
import com.ynk.virtualdisplay.data.repository.ShizukuDisplayRepository
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import com.ynk.virtualdisplay.ui.screens.SettingsScreen
import com.ynk.virtualdisplay.ui.screens.ShizukuPermissionScreen
import com.ynk.virtualdisplay.ui.screens.VirtualDisplayScreen
import com.ynk.virtualdisplay.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as MyApplication).displayRepository, applicationContext)
    }

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

        val repository = (application as MyApplication).displayRepository
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.bindService(this@MainActivity)
            }
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsState()
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (uiState.shizukuState is ShizukuState.Ready) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = uiState.currentTab == ScreenTab.CONSOLE,
                                    onClick = { viewModel.switchTab(ScreenTab.CONSOLE) },
                                    icon = { Icon(Icons.Default.Home, contentDescription = "控制台") },
                                    label = { Text("控制台") }
                                )
                                NavigationBarItem(
                                    selected = uiState.currentTab == ScreenTab.SETTINGS,
                                    onClick = { viewModel.switchTab(ScreenTab.SETTINGS) },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                                    label = { Text("设置") }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (val state = uiState.shizukuState) {
                            is ShizukuState.Checking -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            is ShizukuState.Ready -> {
                                when (uiState.currentTab) {
                                    ScreenTab.CONSOLE -> VirtualDisplayScreen(viewModel = viewModel)
                                    ScreenTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
                                }
                            }
                            else -> {
                                ShizukuPermissionScreen(
                                    state = state,
                                    onRetry = { viewModel.checkShizukuStatus(this@MainActivity) },
                                    onRequestPermission = {
                                        try {
                                            Shizuku.requestPermission(ShizukuDisplayRepository.REQUEST_CODE)
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
        Log.d("MainActivity", "onDestroy: unbinding service")
        (application as MyApplication).displayRepository.unbindService()
    }
}
