package com.zibi.app

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.zibi.app.data.SettingsRepository
import com.zibi.app.data.ZibiDatabase
import com.zibi.app.net.ZibiClient
import com.zibi.app.tools.ApprovalLedger
import com.zibi.app.tools.CreateCalendarEventTool
import com.zibi.app.tools.CreateReminderTool
import com.zibi.app.tools.SetAlarmTool
import com.zibi.app.tools.ToolDispatcher
import com.zibi.app.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-wired dependencies. Later surfaces (bubble, share target) get the same singletons from here,
 * so there is one brain no matter where Zibi is invoked from.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: ZibiDatabase = Room.databaseBuilder(app, ZibiDatabase::class.java, "zibi.db").build()
    val settings = SettingsRepository(app)
    val approvals = ApprovalLedger()

    // To add a capability (music, notes, ...): write a ZibiTool and add it here. Nothing else changes.
    val registry = ToolRegistry(
        listOf(
            CreateReminderTool(db.reminderDao()),
            CreateCalendarEventTool(app),
            SetAlarmTool(app),
        )
    )
    val dispatcher = ToolDispatcher(registry, db.actionLogDao(), approvals)

    val client = ZibiClient(
        scope = scope,
        settings = settings,
        registry = registry,
        dispatcher = dispatcher,
        deviceId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device",
    )
}

/** Creates a ViewModel that receives the [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM =
    viewModel(
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ZibiApp
                create(app.container)
            }
        }
    )
