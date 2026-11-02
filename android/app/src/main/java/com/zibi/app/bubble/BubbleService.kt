package com.zibi.app.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class BubbleService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    companion object {
        private const val CHANNEL_ID = "bubble_channel"
        private const val NOTIFICATION_ID = 1001
        private const val BUBBLE_SIZE_DP = 56
        private const val POSITION_PREFS = "bubble_position"
        private const val POSITION_X = "x"
        private const val POSITION_Y = "y"

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dockAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        setupView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        dockAnimator?.cancel()
        dockAnimator = null
        composeView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
            }
        }
        composeView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Zibi Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Zibi voice capture")
        .setContentText("Tap the bubble for chat, voice and quick actions")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

    private fun beginBubbleDrag(rawX: Float, rawY: Float) {
        val params = layoutParams ?: return
        dockAnimator?.cancel()
        dragStartX = params.x
        dragStartY = params.y
        dragDownRawX = rawX
        dragDownRawY = rawY
    }

    private fun moveBubble(rawX: Float, rawY: Float) {
        val params = layoutParams ?: return
        val (screenWidth, screenHeight) = screenSize()
        val position = BubblePositioning.dragFrom(dragStartX, dragStartY, dragDownRawX, dragDownRawY, rawX, rawY)
        updateBubblePosition(BubblePositioning.clamp(position, screenWidth, params.width, screenHeight))
    }

    private fun finishBubbleDrag() {
        val params = layoutParams ?: return
        val (screenWidth, screenHeight) = screenSize()
        val target = BubblePositioning.dock(BubblePosition(params.x, params.y), screenWidth, params.width, screenHeight)
        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofInt(params.x, target.x).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                updateBubblePosition(BubblePosition(animation.animatedValue as Int, target.y))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveBubblePosition()
                }
            })
            start()
        }
    }

    private fun updateBubblePosition(position: BubblePosition) {
        val params = layoutParams ?: return
        params.x = position.x
        params.y = position.y
        try {
            composeView?.let { windowManager.updateViewLayout(it, params) }
        } catch (_: IllegalArgumentException) {
        } catch (_: WindowManager.BadTokenException) {
        }
    }

    private fun saveBubblePosition() {
        val params = layoutParams ?: return
        getSharedPreferences(POSITION_PREFS, MODE_PRIVATE).edit()
            .putInt(POSITION_X, params.x)
            .putInt(POSITION_Y, params.y)
            .apply()
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val point = Point()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealSize(point)
        return point.x to point.y
    }

    private fun setupView() {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BubbleService)
            setViewTreeSavedStateRegistryOwner(this@BubbleService)
            setViewTreeViewModelStoreOwner(this@BubbleService)
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                com.zibi.app.ui.ZibiTheme {
                    BubbleOverlay(
                        onDragStart = ::beginBubbleDrag,
                        onDrag = ::moveBubble,
                        onDragEnd = ::finishBubbleDrag,
                    )
                }
            }
        }
        composeView = view

        val (screenWidth, screenHeight) = screenSize()
        val sizePx = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = getSharedPreferences(POSITION_PREFS, MODE_PRIVATE)
            val hasSavedPosition = saved.contains(POSITION_X) && saved.contains(POSITION_Y)
            val initial = if (hasSavedPosition) {
                BubblePosition(saved.getInt(POSITION_X, 0), saved.getInt(POSITION_Y, 0))
            } else {
                BubblePosition(screenWidth - sizePx - (16 * resources.displayMetrics.density).toInt(), screenHeight / 3)
            }
            val position = BubblePositioning.clamp(initial, screenWidth, sizePx, screenHeight)
            x = position.x
            y = position.y
        }
        layoutParams = params

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
}
