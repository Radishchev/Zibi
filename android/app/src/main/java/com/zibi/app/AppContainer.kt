package com.zibi.app

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.zibi.app.data.MIGRATION_2_3
import com.zibi.app.data.MIGRATION_3_4
import com.zibi.app.data.MIGRATION_4_5
import com.zibi.app.data.SettingsRepository
import com.zibi.app.data.ZibiDatabase
import com.zibi.app.library.HandoffLauncher
import com.zibi.app.library.LibraryApi
import com.zibi.app.library.LibraryRepository
import com.zibi.app.net.ZibiClient
import com.zibi.app.privacy.KeystoreCipher
import com.zibi.app.privacy.PrivacyVault
import com.zibi.app.profile.ProfileRepository
import com.zibi.app.tools.AddTaskTool
import com.zibi.app.tools.ArchiveFactsTool
import com.zibi.app.tools.AddToListTool
import com.zibi.app.tools.ApprovalLedger
import com.zibi.app.tools.CreateCalendarEventTool
import com.zibi.app.tools.CreateReminderTool
import com.zibi.app.tools.ReminderScheduler
import com.zibi.app.tools.SendEmailTool
import com.zibi.app.tools.ForgetFactTool
import com.zibi.app.tools.ProposeHandoffTool
import com.zibi.app.tools.RecallMemoryTool
import com.zibi.app.tools.RememberFactTool
import com.zibi.app.tools.SaveMessageDraftTool
import com.zibi.app.tools.SendMessageTool
import com.zibi.app.tools.SetAlarmsTool
import com.zibi.app.tools.SetAlarmTool
import com.zibi.app.tools.ToolDispatcher
import com.zibi.app.tools.ToolRegistry
import com.zibi.app.tools.UpdateCalendarEventTool
import com.zibi.app.tools.UpdateTimetableTool
import com.zibi.app.ui.ChatViewModel
import com.zibi.app.ui.SettingsViewModel
import com.zibi.app.ui.QuickViewModel
import com.zibi.app.voice.SttApi
import com.zibi.app.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-wired dependencies. Later surfaces (bubble, share target) get the same singletons from here,
 * so there is one brain no matter where Zibi is invoked from.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: ZibiDatabase = Room.databaseBuilder(app, ZibiDatabase::class.java, "zibi.db")
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .fallbackToDestructiveMigration()
        .build()
    val settings = SettingsRepository(app)
    val approvals = ApprovalLedger()
    private val deviceId: String =
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
    val permissions = PermissionBroker(app)
    val reminderScheduler = ReminderScheduler(app, db.reminderDao())

    init {
        scope.launch { reminderScheduler.reschedulePending() }
    }

    val vault = PrivacyVault(db.vaultDao(), KeystoreCipher())
    val profile = ProfileRepository(db.profileDao(), vault)

    val registry = ToolRegistry(listOf(
        CreateReminderTool(db.reminderDao(), permissions, reminderScheduler),
        CreateCalendarEventTool(app, permissions),
        UpdateCalendarEventTool(app, permissions, db.actionLogDao()),
        SetAlarmTool(app),
        SetAlarmsTool(app),
        SendMessageTool(app, permissions),
        SendEmailTool(app, permissions),
        AddToListTool(db.listDao()),
        AddTaskTool(db.taskDao()),
        SaveMessageDraftTool(db.draftDao()),
        ProposeHandoffTool(db.libraryDao(), db.handoffDao()),
        RememberFactTool(db.profileDao(), vault),
        ForgetFactTool(db.profileDao()),
        ArchiveFactsTool(db.profileDao()),
        RecallMemoryTool(db.profileDao()),
        UpdateTimetableTool(db.profileDao()),
    ))
    val dispatcher = ToolDispatcher(registry, db.actionLogDao(), approvals, vault)

    val client = ZibiClient(
        scope = scope,
        settings = settings,
        registry = registry,
        dispatcher = dispatcher,
        deviceId = deviceId,
        profile = profile,
        vault = vault,
    )

    val api = LibraryApi(settings, deviceId)
    val library = LibraryRepository(app, db.libraryDao(), api)
    val handoffs = HandoffLauncher(app, db.libraryDao(), db.handoffDao())

    val voiceRecorder = VoiceRecorder(app)
    val stt = SttApi(settings, deviceId)

    // One brain for the whole app: the in-app screens and the floating bubble share these,
    val chatVm: ChatViewModel by lazy { ChatViewModel(this) }
    val quickVm: QuickViewModel by lazy { QuickViewModel(this) }
    val settingsVm: SettingsViewModel by lazy { SettingsViewModel(this) }
}

/** The app-level [AppContainer] from any composable. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as ZibiApp).container

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