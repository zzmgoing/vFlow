package com.vflow.fork.goldfinger

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.chaomixian.vflow.R
import com.chaomixian.vflow.services.VFlowCoreBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 金手指连点器悬浮窗服务。
 *
 * 双击图标开始连续点击，单击或拖动图标停止点击。
 */
class GoldenFingerClickerService : Service() {
    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var iconView: ImageView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var clickJob: Job? = null
    private var exitHoldJob: Job? = null

    private var iconSizePx = 0
    private var intervalMs = 50L
    private var direction = DIRECTION_UP
    private var clickGapPx = 0
    private var offsetXPx = 0
    private var offsetYPx = 0
    private var doubleTapTimeoutMs = 500L

    private var initialWindowX = 0
    private var initialWindowY = 0
    private var windowScreenX = 0
    private var windowScreenY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false
    private var lastUpTime = 0L
    private var exitCountdownStarted = false

    companion object {
        const val ACTION_SHOW = "com.vflow.fork.goldfinger.SHOW"
        const val ACTION_CLOSE = "com.vflow.fork.goldfinger.CLOSE"
        const val EXTRA_ICON_SIZE_DP = "icon_size_dp"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_CLICK_GAP_DP = "click_gap_dp"
        const val EXTRA_OFFSET_X_DP = "offset_x_dp"
        const val EXTRA_OFFSET_Y_DP = "offset_y_dp"
        const val EXTRA_DOUBLE_TAP_TIMEOUT_MS = "double_tap_timeout_ms"

        const val DIRECTION_UP = "up"
        const val DIRECTION_DOWN = "down"
        const val DIRECTION_LEFT = "left"
        const val DIRECTION_RIGHT = "right"

        private const val TOUCH_SLOP_DP = 12
        private const val EXIT_HINT_MS = 3_000L
        private const val EXIT_TOTAL_MS = 6_000L
        private const val EXIT_FADE_FRAME_MS = 50L
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_SHOW) {
            ACTION_CLOSE -> closeWindow()
            ACTION_SHOW -> showWindow(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closeWindow()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showWindow(intent: Intent?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少悬浮窗权限，无法显示金手指。", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        closeWindow(removeService = false)

        iconSizePx = (intent?.getIntExtra(EXTRA_ICON_SIZE_DP, 60) ?: 60).coerceIn(32, 160).dpToPx()
        intervalMs = (intent?.getIntExtra(EXTRA_INTERVAL_MS, 50) ?: 50).coerceIn(10, 2_000).toLong()
        direction = (intent?.getStringExtra(EXTRA_DIRECTION) ?: DIRECTION_UP).takeIf {
            it == DIRECTION_UP || it == DIRECTION_DOWN || it == DIRECTION_LEFT || it == DIRECTION_RIGHT
        } ?: DIRECTION_UP
        clickGapPx = (intent?.getIntExtra(EXTRA_CLICK_GAP_DP, 0) ?: 0).coerceIn(0, 240).dpToPx()
        offsetXPx = (intent?.getIntExtra(EXTRA_OFFSET_X_DP, 0) ?: 0).coerceIn(-240, 240).dpToPx()
        offsetYPx = (intent?.getIntExtra(EXTRA_OFFSET_Y_DP, 0) ?: 0).coerceIn(-240, 240).dpToPx()
        doubleTapTimeoutMs = (intent?.getIntExtra(EXTRA_DOUBLE_TAP_TIMEOUT_MS, 500) ?: 500).coerceIn(120, 1_500).toLong()

        val displayMetrics = resources.displayMetrics
        val startX = ((displayMetrics.widthPixels - iconSizePx) / 2).coerceAtLeast(0)
        val startY = ((displayMetrics.heightPixels - iconSizePx) / 2).coerceAtLeast(0)

        windowParams = WindowManager.LayoutParams(
            iconSizePx,
            iconSizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }
        windowScreenX = startX
        windowScreenY = startY

        iconView = ImageView(this).apply {
            setImageResource(R.drawable.fork_golden_finger)
            scaleType = ImageView.ScaleType.FIT_CENTER
            rotation = directionToRotation(direction)
            setOnTouchListener { _, event -> handleTouch(event) }
        }

        try {
            windowManager.addView(iconView, windowParams)
            Toast.makeText(this, "双击金手指开始连点，单击或移动停止。", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "金手指悬浮窗启动失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            iconView = null
            windowParams = null
            stopSelf()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val params = windowParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialWindowX = params.x
                initialWindowY = params.y
                updateWindowScreenPosition(event)
                downRawX = event.rawX
                downRawY = event.rawY
                moved = false
                startExitHoldCountdown()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                val touchSlop = TOUCH_SLOP_DP.dpToPx()

                if (!moved && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                    moved = true
                    cancelExitHoldCountdown(restoreAlpha = true)
                    stopClicking(showToast = true)
                }

                if (moved) {
                    params.x = initialWindowX + deltaX.toInt()
                    params.y = initialWindowY + deltaY.toInt()
                    updateWindowScreenPosition(event)
                    iconView?.let { windowManager.updateViewLayout(it, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasExitCountdownStarted = exitCountdownStarted
                cancelExitHoldCountdown(restoreAlpha = true)

                if (moved || wasExitCountdownStarted) return true

                if (clickJob?.isActive == true) {
                    stopClicking(showToast = true)
                    lastUpTime = 0L
                    return true
                }

                val now = System.currentTimeMillis()
                if (lastUpTime > 0 && now - lastUpTime <= doubleTapTimeoutMs) {
                    startClicking()
                    lastUpTime = 0L
                } else {
                    lastUpTime = now
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelExitHoldCountdown(restoreAlpha = true)
                stopClicking(showToast = false)
                return true
            }
        }
        return true
    }

    private fun startClicking() {
        if (clickJob?.isActive == true) return

        clickJob = serviceScope.launch(Dispatchers.IO) {
            val connected = if (VFlowCoreBridge.isConnected) {
                true
            } else {
                VFlowCoreBridge.connect(applicationContext)
            }

            if (!connected) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Core 未连接，无法执行连点。", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "开始自动点击。", Toast.LENGTH_SHORT).show()
            }

            while (isActive) {
                val target = currentClickTarget()
                VFlowCoreBridge.performClick(target.first, target.second)
                delay(intervalMs)
            }
        }
    }

    private fun stopClicking(showToast: Boolean) {
        val wasRunning = clickJob?.isActive == true
        clickJob?.cancel()
        clickJob = null
        if (showToast && wasRunning) {
            Toast.makeText(this, "已停止自动点击。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExitHoldCountdown() {
        cancelExitHoldCountdown(restoreAlpha = true)
        exitCountdownStarted = false
        exitHoldJob = serviceScope.launch {
            val startedAt = System.currentTimeMillis()
            var hintShown = false

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startedAt
                val progress = (elapsed.toFloat() / EXIT_TOTAL_MS).coerceIn(0f, 1f)
                iconView?.alpha = 1f - progress

                if (!hintShown && elapsed >= EXIT_HINT_MS) {
                    hintShown = true
                    exitCountdownStarted = true
                    stopClicking(showToast = false)
                    Toast.makeText(
                        applicationContext,
                        "继续长按 3 秒将结束并退出金手指。",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                if (progress >= 1f) {
                    closeWindow()
                    return@launch
                }
                delay(EXIT_FADE_FRAME_MS)
            }
        }
    }

    private fun cancelExitHoldCountdown(restoreAlpha: Boolean) {
        exitHoldJob?.cancel()
        exitHoldJob = null
        exitCountdownStarted = false
        if (restoreAlpha) {
            iconView?.alpha = 1f
        }
    }

    private fun currentClickTarget(): Pair<Int, Int> {
        val view = iconView
        val left = windowScreenX
        val top = windowScreenY
        val width = view?.width?.takeIf { it > 0 } ?: iconSizePx
        val height = view?.height?.takeIf { it > 0 } ?: iconSizePx
        val centerX = left + width / 2
        val centerY = top + height / 2

        val base = when (direction) {
            DIRECTION_DOWN -> centerX to top + height + clickGapPx
            DIRECTION_LEFT -> left - clickGapPx - 1 to centerY
            DIRECTION_RIGHT -> left + width + clickGapPx to centerY
            else -> centerX to top - clickGapPx - 1
        }
        val x = base.first + offsetXPx
        val y = base.second + offsetYPx
        return x.coerceAtLeast(0) to y.coerceAtLeast(0)
    }

    private fun updateWindowScreenPosition(event: MotionEvent) {
        windowScreenX = (event.rawX - event.x).roundToInt()
        windowScreenY = (event.rawY - event.y).roundToInt()
    }

    private fun directionToRotation(direction: String): Float {
        return when (direction) {
            DIRECTION_RIGHT -> 90f
            DIRECTION_DOWN -> 180f
            DIRECTION_LEFT -> 270f
            else -> 0f
        }
    }

    private fun closeWindow(removeService: Boolean = true) {
        cancelExitHoldCountdown(restoreAlpha = false)
        stopClicking(showToast = false)
        iconView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 视图可能已经被系统移除，忽略即可。
            }
        }
        iconView = null
        windowParams = null
        lastUpTime = 0L
        if (removeService) stopSelf()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
