package com.vflow.fork.hiddenobject

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.chaomixian.vflow.services.ServiceStateBus
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

internal class HiddenObjectControlOverlay(private val context: Context) {
    data class HintTarget(val itemId: String, val x: Int, val y: Int)
    sealed class HintControlEvent {
        data object Timeout : HintControlEvent()
        data object Start : HintControlEvent()
        data object Pause : HintControlEvent()
    }

    private data class HintMarkerEntry(
        val view: HintMarkerView,
        val params: WindowManager.LayoutParams,
        var target: HintTarget,
    )

    private val overlayContext = ServiceStateBus.getAccessibilityService() ?: context.applicationContext
    private val windowManager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val startRequests = Channel<Unit>(Channel.CONFLATED)
    private val pauseRequests = Channel<Unit>(Channel.CONFLATED)
    private var controlCard: MaterialCardView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var startButton: MaterialButton? = null
    private var pauseButton: MaterialButton? = null
    private val hintMarkers = linkedMapOf<String, HintMarkerEntry>()
    @Volatile private var closed = false

    suspend fun show(showPauseControl: Boolean = false, onClose: () -> Unit) = withContext(Dispatchers.Main) {
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
                pauseButton?.visibility = View.VISIBLE
                statusText?.text = "准备中"
            }
        }
        startButton = start
        val pause = MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "暂停"
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
            visibility = View.GONE
            setOnClickListener {
                pauseRequests.trySend(Unit)
                visibility = View.GONE
                startButton?.visibility = View.VISIBLE
                statusText?.text = "自动识别已暂停"
            }
        }
        pauseButton = pause.takeIf { showPauseControl }
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
                pauseRequests.close(CancellationException("用户关闭自动寻物"))
                dismissNow()
                onClose()
            }
        }
        content.addView(start)
        content.addView(View(themed), LinearLayout.LayoutParams(dp(4f).toInt(), 1))
        if (showPauseControl) {
            content.addView(pause)
            content.addView(View(themed), LinearLayout.LayoutParams(dp(4f).toInt(), 1))
        }
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

    suspend fun awaitHintControlOrTimeout(timeoutMillis: Long): HintControlEvent =
        withTimeoutOrNull(timeoutMillis) {
            awaitHintControl()
        } ?: HintControlEvent.Timeout

    suspend fun awaitHintControl(): HintControlEvent = select {
        startRequests.onReceiveCatching { result ->
            result.getOrNull()
                ?: throw CancellationException("用户关闭自动寻物")
            HintControlEvent.Start
        }
        pauseRequests.onReceiveCatching { result ->
            result.getOrNull()
                ?: throw CancellationException("用户关闭自动寻物")
            HintControlEvent.Pause
        }
    }

    suspend fun showHintRunning(status: String) = withContext(Dispatchers.Main) {
        if (!closed) {
            statusText?.text = status
            startButton?.visibility = View.VISIBLE
            pauseButton?.visibility = View.VISIBLE
        }
    }

    suspend fun showHintPaused() = withContext(Dispatchers.Main) {
        if (!closed) {
            statusText?.text = "自动识别已暂停"
            startButton?.visibility = View.VISIBLE
            pauseButton?.visibility = View.GONE
        }
    }

    fun updateStatus(value: String) {
        statusText?.post { statusText?.text = value }
    }

    suspend fun showHintTargets(targets: List<HintTarget>) = withContext(Dispatchers.Main) {
        val targetIds = targets.mapTo(hashSetOf()) { it.itemId }
        hintMarkers.keys.filter { it !in targetIds }.toList().forEach(::removeHintMarker)
        targets.forEach { target ->
            val existing = hintMarkers[target.itemId]
            if (existing == null) {
                addHintMarker(target)
            } else if (existing.target.x != target.x || existing.target.y != target.y) {
                existing.target = target
                existing.params.x = target.x - existing.params.width / 2
                existing.params.y = target.y - existing.params.height / 2
                runCatching { windowManager.updateViewLayout(existing.view, existing.params) }
            }
        }
    }

    suspend fun dismiss() = withContext(Dispatchers.Main) { dismissNow() }

    private fun dismissNow() {
        runCatching { controlCard?.let(windowManager::removeView) }
        removeAllHintMarkers()
        controlCard = null
        controlParams = null
        startButton = null
        pauseButton = null
    }

    private fun addHintMarker(target: HintTarget) {
        val size = dp(46f).toInt()
        val view = HintMarkerView(overlayContext)
        val params = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            x = target.x - size / 2
            y = target.y - size / 2
        }
        windowManager.addView(view, params)
        hintMarkers[target.itemId] = HintMarkerEntry(view, params, target)
    }

    private fun removeHintMarker(itemId: String) {
        val entry = hintMarkers.remove(itemId) ?: return
        runCatching { windowManager.removeView(entry.view) }
    }

    private fun removeAllHintMarkers() {
        hintMarkers.values.toList().forEach { entry ->
            runCatching { windowManager.removeView(entry.view) }
        }
        hintMarkers.clear()
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

    private class HintMarkerView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2f
        }
        private val minRadius = resources.displayMetrics.density * 11f
        private val radiusRange = resources.displayMetrics.density * 8f
        private var pulseProgress = 0f
        private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseProgress = it.animatedValue as Float
                invalidate()
            }
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            pulseAnimator.start()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = minRadius + radiusRange * pulseProgress
            fillPaint.alpha = (72 - 28 * pulseProgress).toInt()
            strokePaint.alpha = (210 - 70 * pulseProgress).toInt()
            canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
            canvas.drawCircle(width / 2f, height / 2f, radius, strokePaint)
        }

        override fun onDetachedFromWindow() {
            pulseAnimator.cancel()
            super.onDetachedFromWindow()
        }
    }
}
