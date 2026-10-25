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
import com.zibi.app.data.MIGRATION_2_3
import com.zibi.app.data.SettingsRepository
import com.zibi.app.data.ZibiDatabase
import com.zibi.app.library.HandoffLauncher
import com.zibi.app.library.LibraryApi
import com.zibi.app.library.LibraryRepository
import com.zibi.app.net.ZibiClient
import com.zibi.app.reminders.ReminderScheduler
import com.zibi.app.tools.AddTaskTool
import com.zibi.app.tools.AddToListTool
import com.zibi.app.tools.ApprovalLedger
import com.zibi.app.tools.CreateCalendarEventTool
import com.zibi.app.tools.CreateReminderTool
import com.zibi.app.tools.ProposeHandoffTool
import com.zibi.app.tools.SaveMessageDraftTool
import com.zibi.app.tools.SendMessageTool
import com.zibi.app.tools.SetAlarmSeriesTool
import com.zibi.app.tools.SetAlarmTool
import com.zibi.app.tools.ToolDispatcher
import com.zibi.app.tools.ToolRegistry
import com.zibi.app.tools.UpdateCalendarEventTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-wired dependencies. Later surfaces (bubble, share target) get the same singletons from here,
 * so there is one brain no matter where Zibi is invoked from.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: ZibiDatabase = Room.databaseBuilder(app, ZibiDatabase::class.java, "zibi.db")
        // Dev stage: a schema change wipes local data instead of needing a migration. Replace before real users.
        .addMigrations(MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()
    val settings = SettingsRepository(app)
    val approvals = ApprovalLedger()
    private val deviceId: String =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    val permissions = PermissionBroker(app)
    val scheduler = ReminderScheduler(app)
    val sharedIncomingText = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // To add a capability (music, notes, ...): write a ZibiTool and add it here. Nothing else changes.
    val registry = ToolRegistry(
        listOf(
            CreateReminderTool(db.reminderDao(), scheduler),
            CreateCalendarEventTool(app, permissions),
            UpdateCalendarEventTool(app, permissions),
            SetAlarmTool(app),
            SetAlarmSeriesTool(app),
            SendMessageTool(app, permissions),
            AddToListTool(db.listDao()),
            AddTaskTool(db.taskDao()),
            SaveMessageDraftTool(db.draftDao()),
            ProposeHandoffTool(db.libraryDao(), db.handoffDao()),
        )
    )
    val dispatcher = ToolDispatcher(registry, db.actionLogDao(), approvals)

    val client = ZibiClient(
        scope = scope,
        settings = settings,
        registry = registry,
        dispatcher = dispatcher,
        deviceId = deviceId,
    )

    val library = LibraryRepository(app, db.libraryDao(), LibraryApi(settings, deviceId))
    val handoffs = HandoffLauncher(app, db.libraryDao(), db.handoffDao())
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
