package com.sam.twintracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sam.twintracker.ui.screens.DashboardScreen
import com.sam.twintracker.ui.screens.HealthScreen
import com.sam.twintracker.ui.screens.LogScreen
import com.sam.twintracker.ui.screens.SettingsScreen
import com.sam.twintracker.ui.screens.VaccineScreen
import com.sam.twintracker.ui.theme.TwinTheme
import com.sam.twintracker.voice.VoiceListenerService

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HEALTH = 2
        const val TAB_VACCINES = 3
    }

    private val vm: MainViewModel by viewModels()
    private var startVoiceAfterGrant = false
    private var initialTab by androidx.compose.runtime.mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic()
        if (startVoiceAfterGrant) {
            startVoiceAfterGrant = false
            if (mic) VoiceListenerService.start(this)
            else Toast.makeText(this, "Microphone permission is needed for voice logging", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun neededPermissions(): Array<String> = buildList {
        if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun toggleVoice() {
        if (VoiceListenerService.state.value.running) {
            VoiceListenerService.stop(this)
            return
        }
        val needed = neededPermissions()
        if (needed.isEmpty()) VoiceListenerService.start(this)
        else { startVoiceAfterGrant = true; permissions.launch(needed) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialTab = intent.getIntExtra(EXTRA_TAB, 0)
        neededPermissions().takeIf { it.isNotEmpty() && savedInstanceState == null }?.let { permissions.launch(it) }

        setContent {
            TwinTheme {
                var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab) }
                val voice by VoiceListenerService.state.collectAsStateWithLifecycle()
                val titles = listOf("Dashboard", "Log", "Health", "Vaccines", "Settings")
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (tab == 0) "Twin Tracker" else if (tab == 1) "Daily log" else titles[tab]) },
                            actions = {
                                IconButton(onClick = ::toggleVoice) {
                                    Icon(
                                        if (voice.running) Icons.Default.Mic else Icons.Default.MicOff,
                                        if (voice.running) "Stop voice logging" else "Start voice logging",
                                        tint = if (voice.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            val icons = listOf(
                                Icons.Default.Dashboard, Icons.AutoMirrored.Filled.List, Icons.Default.Medication,
                                Icons.Default.Vaccines, Icons.Default.Settings,
                            )
                            titles.forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], null) }, label = { Text(t) })
                            }
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> DashboardScreen(vm, ::toggleVoice)
                            1 -> LogScreen(vm)
                            TAB_HEALTH -> HealthScreen(vm)
                            TAB_VACCINES -> VaccineScreen(vm)
                            else -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) initialTab = intent.getIntExtra(EXTRA_TAB, 0)
    }
}
