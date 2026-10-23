package com.zibi.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets tools ask for a runtime permission at the moment they need it, from any thread.
 * MainActivity attaches the real permission launcher while it is on screen.
 */
class PermissionBroker(private val context: Context) {
    private var launcher: ((Array<String>) -> Unit)? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun attach(launch: (Array<String>) -> Unit) { launcher = launch }
    fun detach() { launcher = null }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True if all permissions are (now) granted. False if denied or the app isn't on screen to ask. */
    suspend fun ensure(vararg permissions: String): Boolean {
        if (permissions.all { isGranted(it) }) return true
        val launch = launcher ?: return false
        val answer = CompletableDeferred<Boolean>()
        pending = answer
        withContext(Dispatchers.Main) { launch(arrayOf(*permissions)) }
        return withTimeoutOrNull(60_000) { answer.await() } ?: false
    }

    fun onResult(results: Map<String, Boolean>) {
        pending?.complete(results.isNotEmpty() && results.values.all { it })
        pending = null
    }
}
