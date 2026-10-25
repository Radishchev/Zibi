package com.zibi.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.zibi.app.ui.ZibiRoot
import com.zibi.app.ui.ZibiTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as ZibiApp).container

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            container.permissions.onResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)
        container.permissions.attach { permissionLauncher.launch(it) }
        setContent {
            ZibiTheme { ZibiRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) intent.getStringExtra(Intent.EXTRA_TEXT) else null
            }
            Intent.ACTION_PROCESS_TEXT -> {
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            else -> null
        }
        if (!text.isNullOrBlank()) {
            container.sharedIncomingText.value = text
        }
    }

    override fun onDestroy() {
        container.permissions.detach()
        super.onDestroy()
    }
}
