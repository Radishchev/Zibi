package com.zibi.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Translucent activity used solely to request runtime permissions when the bubble
 * is used without MainActivity in the foreground (e.g., mic permission from the
 * floating panel).
 */
class PermissionActivity : ComponentActivity() {

    private val container get() = (application as ZibiApp).container

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            container.permissions.onResult(result)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.permissions.attach(
            owner = this,
            launch = { permissionLauncher.launch(it) },
            launchExactAlarmSettings = {},
        )
        // Request microphone permission immediately
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    override fun onDestroy() {
        container.permissions.detach(this)
        super.onDestroy()
    }

    companion object {
        fun requestMicrophone(context: Context) {
            context.startActivity(Intent(context, PermissionActivity::class.java))
        }
    }
}