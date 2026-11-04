package com.zibi.app

import android.content.Context
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets tools ask for a runtime permission at the moment they need it, from any thread.
 * MainActivity attaches the real permission launcher while it is on screen.
 * PermissionActivity attaches its own launcher when requesting mic permission from the bubble.
 */
class PermissionBroker(
    private val context: Context?,
    private val permissionChecker: (String) -> Boolean = {
        context != null && ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    },
) {
    private val permissionMutex = Mutex()
    private var launcher: ((Array<String>) -> Unit)? = null
    private var launcherOwner: Any? = null
    private var pending: CompletableDeferred<Boolean>? = null
    private var exactAlarmLauncher: (() -> Unit)? = null
    private var exactAlarmLauncherOwner: Any? = null
    private var exactAlarmPending: CompletableDeferred<Boolean>? = null

    fun attach(owner: Any, launch: (Array<String>) -> Unit, launchExactAlarmSettings: () -> Unit) {
        launcher = launch
        launcherOwner = owner
        exactAlarmLauncher = launchExactAlarmSettings
        exactAlarmLauncherOwner = owner
    }

    fun detach(owner: Any) {
        if (launcherOwner == owner) {
            launcher = null
            launcherOwner = null
            pending?.complete(false)
            pending = null
        }
        if (exactAlarmLauncherOwner == owner) {
            exactAlarmLauncher = null
            exactAlarmLauncherOwner = null
            exactAlarmPending?.complete(false)
            exactAlarmPending = null
        }
    }

    fun isGranted(permission: String): Boolean = permissionChecker(permission)

    /** True if all permissions are (now) granted. False if denied or the app isn't on screen to ask. */
    suspend fun ensure(vararg permissions: String): Boolean = permissionMutex.withLock {
        if (permissions.all { isGranted(it) }) return@withLock true
        val launch = launcher ?: return@withLock false
        val answer = CompletableDeferred<Boolean>()
        pending = answer
        try {
            withContext(Dispatchers.Main) { launch(arrayOf(*permissions)) }
            return@withLock withTimeoutOrNull(60_000) { answer.await() } ?: false
        } finally {
            if (pending === answer) pending = null
        }
    }

    suspend fun ensureExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val context = context ?: return false
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return true
        val launch = exactAlarmLauncher ?: return false
        val answer = CompletableDeferred<Boolean>()
        exactAlarmPending = answer
        try {
            withContext(Dispatchers.Main) { launch() }
            return withTimeoutOrNull(60_000) { answer.await() } ?: false
        } finally {
            if (exactAlarmPending === answer) exactAlarmPending = null
        }
    }

    fun onExactAlarmSettingsReturned() {
        exactAlarmPending?.complete(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (context?.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true)
        )
        exactAlarmPending = null
    }

    fun onResult(results: Map<String, Boolean>) {
        pending?.complete(results.isNotEmpty() && results.values.all { it })
        pending = null
    }
}
