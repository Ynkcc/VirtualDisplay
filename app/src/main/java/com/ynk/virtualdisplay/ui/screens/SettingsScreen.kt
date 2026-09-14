package com.ynk.virtualdisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ynk.virtualdisplay.data.local.DaemonConnectionPrefs
import com.ynk.virtualdisplay.data.local.DisplayFlagPrefs
import com.ynk.virtualdisplay.data.local.FeatureTogglePrefs
import com.ynk.virtualdisplay.data.model.ALL_DISPLAY_FLAGS
import com.ynk.virtualdisplay.ui.main.MainViewModel
import com.ynk.virtualdisplay.util.NetUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统设置页：负责各配置项的订阅与展开状态，具体区块见
 * [FlagsSettingsSection] / [GesturesSettingsSection] / [ServerStartSettingsSection] / [SystemInfoSection]。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val serverPort by DaemonConnectionPrefs.serverPortFlow(context).collectAsState(initial = 27183)
    val serverHost by DaemonConnectionPrefs.serverHostFlow(context).collectAsState(initial = NetUtils.LOCAL_HOST)
    val serverPassword by DaemonConnectionPrefs.serverPasswordFlow(context).collectAsState(initial = "")
    val showPerformanceStats by FeatureTogglePrefs.showPerformanceStatsFlow(context).collectAsState(initial = true)
    val captureBack by FeatureTogglePrefs.captureBackFlow(context).collectAsState(initial = false)
    val ultraLowLatency by FeatureTogglePrefs.ultraLowLatencyFlow(context).collectAsState(initial = false)
    val moveTasksOnDestroy by FeatureTogglePrefs.moveTasksOnDestroyFlow(context).collectAsState(initial = true)

    val flagStates = remember {
        mutableStateMapOf<String, Boolean>()
    }

    LaunchedEffect(uiState.currentServerNode) {
        val flags = DisplayFlagPrefs.getFlags(context)
        ALL_DISPLAY_FLAGS.forEach { flag ->
            flagStates[flag.key] = flags[flag.key] ?: flag.isDefaultEnabled
        }
    }

    var flagsExpanded by remember { mutableStateOf(false) }
    var gesturesExpanded by remember { mutableStateOf(false) }
    var serverStartExpanded by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(serverPort.toString()) }
    var passwordText by remember { mutableStateOf(serverPassword) }

    LaunchedEffect(serverPort) {
        if (portText != serverPort.toString()) {
            portText = serverPort.toString()
        }
    }
    LaunchedEffect(serverPassword) {
        if (passwordText != serverPassword) {
            passwordText = serverPassword
        }
    }

    val bindAddresses by produceState<List<Pair<String, String>>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            NetUtils.getAvailableNetworkAddresses()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "系统设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        FlagsSettingsSection(
            context = context,
            scope = scope,
            flagStates = flagStates,
            expanded = flagsExpanded,
            onExpandedChange = { flagsExpanded = it }
        )

        GesturesSettingsSection(
            context = context,
            scope = scope,
            captureBack = captureBack,
            showPerformanceStats = showPerformanceStats,
            ultraLowLatency = ultraLowLatency,
            moveTasksOnDestroy = moveTasksOnDestroy,
            expanded = gesturesExpanded,
            onExpandedChange = { gesturesExpanded = it }
        )

        ServerStartSettingsSection(
            viewModel = viewModel,
            uiState = uiState,
            context = context,
            scope = scope,
            serverHost = serverHost,
            portText = portText,
            onPortTextChange = { portText = it },
            passwordText = passwordText,
            onPasswordTextChange = { passwordText = it },
            bindAddresses = bindAddresses,
            expanded = serverStartExpanded,
            onExpandedChange = { serverStartExpanded = it }
        )

        SystemInfoSection()
    }
}
