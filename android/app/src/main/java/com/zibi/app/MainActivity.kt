package com.zibi.app

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
        container.permissions.attach { permissionLauncher.launch(it) }
        setContent {
            ZibiTheme { ZibiRoot() }
        }
    }

    override fun onDestroy() {
        container.permissions.detach()
        super.onDestroy()
    }
}
