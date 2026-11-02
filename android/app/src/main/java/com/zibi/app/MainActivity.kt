package com.zibi.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.zibi.app.bubble.BubbleService
import com.zibi.app.ui.ZibiRoot
import com.zibi.app.ui.ZibiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SCREEN = "com.zibi.app.EXTRA_SCREEN"
        const val SCREEN_CHAT = "chat"
        const val SCREEN_QUICK = "quick"
    }

    private val container get() = (application as ZibiApp).container
    private var requestedScreen by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            container.permissions.onResult(result)
        }
    private val exactAlarmSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            container.permissions.onExactAlarmSettingsReturned()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedScreen = intent.getStringExtra(EXTRA_SCREEN)
        container.permissions.attach(
            launch = { permissionLauncher.launch(it) },
            launchExactAlarmSettings = {
                exactAlarmSettingsLauncher.launch(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                )
            },
        )
        setContent {
            ZibiTheme {
                ZibiRoot(
                    requestedScreen = requestedScreen,
                    onScreenHandled = { requestedScreen = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedScreen = intent.getStringExtra(EXTRA_SCREEN)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val enabled = container.settings.bubbleEnabled.first()
            if (enabled && canDrawOverlays()) {
                BubbleService.start(this@MainActivity)
            } else if (!enabled) {
                BubbleService.stop(this@MainActivity)
            }
        }
    }

    override fun onDestroy() {
        container.permissions.detach()
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(this)
    } else true
}