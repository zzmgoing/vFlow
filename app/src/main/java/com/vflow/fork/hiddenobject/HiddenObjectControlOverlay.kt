package com.vflow.fork.hiddenobject

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.chaomixian.vflow.services.ServiceStateBus
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

internal class HiddenObjectControlOverlay(private val context: Context) {
    private val overlayContext = ServiceStateBus.getAccessibilityService() ?: context.applicationContext
    private val windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val startRequests = Channel<Unit>(Channel.CONFLATED)
    private var controlCard: MaterialCardView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var startButton: MaterialButton? = null
    private var markerView: TargetMarkerView? = null
    private var markerParams: WindowManager.LayoutParams? = null
    @Volatile private var closed = false

    suspend fun show(onClose: () -> Unit) = withContext(Dispatchers.Main) {
        if (controlCard != null) return@withContext
        check(canShowOverlay()) { "缺少悬浮窗权限" }
        val themed = ThemeUtils.createThemedContext(overlayContext)
        val card = MaterialCardView(themed).apply {
            radius = dp(20f)
            cardElevation = dp(6f)
            setCardBackgroundColor(resolveColor(themed, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            setContentPadding(dp(8f).toInt(), dp(5f).toInt(), dp(8f).toInt(), dp(5f).toInt())
            alpha = 0.96f
        }
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusText = TextView(themed).apply {
            text = "等待开始"
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            maxWidth = dp(220f).toInt()
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(resolveColor(themed, com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(8f).toInt(), 0, dp(4f).toInt(), 0)
        }
        val start = MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
            text = "开始"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34f).toInt(),
            )
            setOnClickListener {
                startRequests.trySend(Unit)
                visibility = View.GONE
                statusText?.text = "准备中"
            }
        }
        startButton = start
        val close = MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "结束"
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34f).toInt(),
            )
            setTextColor(resolveColor(themed, android.R.attr.colorError))
            setOnClickListener {
                if (closed) return@setOnClickListener
                closed = true
                startRequests.close(CancellationException("用户关闭自动寻物"))
                dismissNow()
                onClose()
            }
        }
        content.addView(start)
        content.addView(View(themed), LinearLayout.LayoutParams(dp(4f).toInt(), 1))
        content.addView(close)
        content.addView(statusText)
        card.addView(content)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8f).toInt()
            y = dp(80f).toInt()
        }
        installDrag(requireNotNull(statusText), params, card)
        windowManager.addView(card, params)
        controlCard = card
        controlParams = params
    }

    suspend fun awaitStart() {
        startRequests.receiveCatching().getOrNull()
            ?: throw CancellationException("用户关闭自动寻物")
    }

    suspend fun pause(status: String) = withContext(Dispatchers.Main) {
        if (!closed) {
            statusText?.text = status
            startButton?.visibility = View.VISIBLE
        }
    }

    fun updateStatus(value: String) {
        statusText?.post { statusText?.text = value }
    }

    suspend fun hideForCapture() = withContext(Dispatchers.Main) {
        controlCard?.visibility = View.INVISIBLE
        markerView?.visibility = View.INVISIBLE
        delay(120)
    }

    suspend fun restoreAfterCapture() = withContext(Dispatchers.Main) {
        if (!closed) {
            controlCard?.visibility = View.VISIBLE
            markerView?.takeIf { it.hasTargets() }?.visibility = View.VISIBLE
        }
    }

    suspend fun showTarget(x: Int, y: Int) = withContext(Dispatchers.Main) {
        val marker = markerView ?: TargetMarkerView(overlayContext).also { view ->
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val params = WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                gravity = Gravity.TOP or Gravity.START
                this.x = 0
                this.y = 0
            }
            windowManager.addView(view, params)
            markerParams = params
            markerView = view
        }
        marker.addTarget(x.toFloat(), y.toFloat())
        marker.visibility = View.VISIBLE
    }

    suspend fun clearTargets() = withContext(Dispatchers.Main) {
        markerView?.clearTargets()
        markerView?.visibility = View.INVISIBLE
    }

    suspend fun dismiss() = withContext(Dispatchers.Main) { dismissNow() }

    private fun dismissNow() {
        runCatching { controlCard?.let(windowManager::removeView) }
        runCatching { markerView?.let(windowManager::removeView) }
        controlCard = null
        controlParams = null
        startButton = null
        markerView = null
        markerParams = null
    }

    private fun installDrag(handle: View, params: WindowManager.LayoutParams, windowView: View) {
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY; downX = params.x; downY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downX + (event.rawX - downRawX).toInt()
                    params.y = downY + (event.rawY - downRawY).toInt()
                    runCatching { windowManager.updateViewLayout(windowView, params) }
                    true
                }
                else -> true
            }
        }
    }

    private fun overlayType(): Int = if (ServiceStateBus.getAccessibilityService() != null) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else WindowManager.LayoutParams.TYPE_PHONE

    private fun canShowOverlay(): Boolean = ServiceStateBus.getAccessibilityService() != null ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun resolveColor(context: Context, attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(value: Float): Float = value * overlayContext.resources.displayMetrics.density

    private class TargetMarkerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.5f
            setShadowLayer(resources.displayMetrics.density * 2f, 0f, 0f, 0x66000000)
        }
        private val targetRadius = resources.displayMetrics.density * 10f
        private val targets = mutableListOf<Pair<Float, Float>>()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        fun addTarget(x: Float, y: Float) {
            targets += x to y
            invalidate()
        }

        fun clearTargets() {
            targets.clear()
            invalidate()
        }

        fun hasTargets(): Boolean = targets.isNotEmpty()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            targets.forEach { (x, y) ->
                canvas.drawCircle(x, y, targetRadius, paint)
            }
        }
    }
}
