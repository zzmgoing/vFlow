package com.vflow.fork.hiddenobject

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

internal class HiddenObjectTemplateCanvasView(context: Context) : View(context) {
    enum class Mode { NONE, SCENE_REGION, LABEL_REGION, ITEM_POINT }

    var bitmap: Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                zoom = 1f
                panX = 0f
                panY = 0f
            }
            invalidate()
        }
    var template: HiddenObjectTemplate? = null
        set(value) { field = value; invalidate() }
    var mode: Mode = Mode.NONE
    var onRegionSelected: ((Mode, NormalizedRect) -> Unit)? = null
    var onItemPointSelected: ((Float, Float) -> Unit)? = null
    var onItemPointChanged: ((String, Float, Float) -> Unit)? = null

    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.density * 12f
        isFakeBoldText = true
    }
    private val markerRadius = resources.displayMetrics.density * 17f
    private val markerHitRadius = resources.displayMetrics.density * 28f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var scaledDuringGesture = false
    private var draggingItemId: String? = null
    private var draftRect: RectF? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaledDuringGesture = true
                draggingItemId = null
                draftRect = null
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val image = bitmap?.takeUnless { it.isRecycled } ?: return false
                val oldRect = imageRect(image)
                if (oldRect.width() <= 0f || oldRect.height() <= 0f) return false
                val imageX = ((detector.focusX - oldRect.left) / oldRect.width()).coerceIn(0f, 1f)
                val imageY = ((detector.focusY - oldRect.top) / oldRect.height()).coerceIn(0f, 1f)
                val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
                if (newZoom == zoom) return true
                zoom = newZoom
                val base = baseImageRect(image)
                val newWidth = base.width() * zoom
                val newHeight = base.height() * zoom
                panX = detector.focusX - base.centerX() - (imageX - 0.5f) * newWidth
                panY = detector.focusY - base.centerY() - (imageY - 0.5f) * newHeight
                clampPan(image)
                invalidate()
                return true
            }
        },
    )

    fun zoomBy(factor: Float) {
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val oldZoom = zoom
        zoom = (zoom * factor).coerceIn(1f, 5f)
        if (oldZoom > 0f) {
            panX *= zoom / oldZoom
            panY *= zoom / oldZoom
        }
        clampPan(image)
        invalidate()
    }

    fun resetViewport() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap?.takeUnless { it.isRecycled } ?: return
        val target = imageRect(image)
        canvas.drawBitmap(image, null, target, null)
        template?.let { value ->
            regionPaint.color = Color.GREEN
            canvas.drawRect(value.sceneRegion.toViewRect(target), regionPaint)
            regionPaint.color = Color.CYAN
            canvas.drawRect(value.labelRegion.toViewRect(target), regionPaint)
            value.items.forEachIndexed { index, item ->
                if (!item.enabled) return@forEachIndexed
                val scene = value.sceneRegion.toViewRect(target)
                val x = scene.left + item.normalizedX * scene.width()
                val y = scene.top + item.normalizedY * scene.height()
                canvas.drawCircle(x, y, markerRadius, markerPaint)
                canvas.drawCircle(x, y, markerRadius, markerBorderPaint)
                val baseline = y - (markerTextPaint.ascent() + markerTextPaint.descent()) / 2f
                canvas.drawText((index + 1).toString(), x, baseline, markerTextPaint)
            }
        }
        draftRect?.let {
            regionPaint.color = Color.MAGENTA
            canvas.drawRect(it, regionPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val image = bitmap?.takeUnless { it.isRecycled } ?: return false
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            parent?.requestDisallowInterceptTouchEvent(true)
            moved = true
            return true
        }
        val target = imageRect(image)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                scaledDuringGesture = false
                draggingItemId = if (mode == Mode.NONE || mode == Mode.ITEM_POINT) findItemAt(event.x, event.y, target) else null
                parent?.requestDisallowInterceptTouchEvent(zoom > 1f || mode != Mode.NONE || draggingItemId != null)
                if (mode == Mode.SCENE_REGION || mode == Mode.LABEL_REGION) {
                    draftRect = RectF(startX, startY, startX, startY)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceX = event.x - startX
                val distanceY = event.y - startY
                if (distanceX * distanceX + distanceY * distanceY > touchSlop * touchSlop) moved = true
                if (mode == Mode.SCENE_REGION || mode == Mode.LABEL_REGION) {
                    draftRect = RectF(startX, startY, event.x, event.y).sorted()
                    invalidate()
                } else if (draggingItemId != null) {
                    updateDraggedItem(event.x, event.y, target)
                } else if (moved) {
                    panX += event.x - lastX
                    panY += event.y - lastY
                    clampPan(image)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.ITEM_POINT && draggingItemId == null && !moved && !scaledDuringGesture) {
                    val value = template ?: return true
                    val scene = value.sceneRegion.toViewRect(target)
                    if (scene.contains(event.x, event.y)) {
                        onItemPointSelected?.invoke(
                            ((event.x - scene.left) / scene.width()).coerceIn(0f, 1f),
                            ((event.y - scene.top) / scene.height()).coerceIn(0f, 1f),
                        )
                        mode = Mode.NONE
                    }
                } else if (draggingItemId != null) {
                    updateDraggedItem(event.x, event.y, target)
                } else if ((mode == Mode.SCENE_REGION || mode == Mode.LABEL_REGION) && !scaledDuringGesture) {
                    val rect = RectF(startX, startY, event.x, event.y).sorted()
                    if (rect.width() > 20f && rect.height() > 20f) {
                        onRegionSelected?.invoke(
                            mode,
                            NormalizedRect(
                                (rect.left - target.left) / target.width(),
                                (rect.top - target.top) / target.height(),
                                (rect.right - target.left) / target.width(),
                                (rect.bottom - target.top) / target.height(),
                            ).normalized(),
                        )
                        mode = Mode.NONE
                    }
                }
                draftRect = null
                draggingItemId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                draftRect = null
                draggingItemId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    private fun imageRect(image: Bitmap): RectF {
        val base = baseImageRect(image)
        val drawWidth = base.width() * zoom
        val drawHeight = base.height() * zoom
        val centerX = base.centerX() + panX
        val centerY = base.centerY() + panY
        return RectF(centerX - drawWidth / 2f, centerY - drawHeight / 2f, centerX + drawWidth / 2f, centerY + drawHeight / 2f)
    }

    private fun baseImageRect(image: Bitmap): RectF {
        val scale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        return RectF((width - drawWidth) / 2f, (height - drawHeight) / 2f, (width + drawWidth) / 2f, (height + drawHeight) / 2f)
    }

    private fun clampPan(image: Bitmap) {
        val base = baseImageRect(image)
        val maxPanX = (base.width() * (zoom - 1f) / 2f).coerceAtLeast(0f)
        val maxPanY = (base.height() * (zoom - 1f) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun findItemAt(x: Float, y: Float, imageRect: RectF): String? {
        val value = template ?: return null
        val scene = value.sceneRegion.toViewRect(imageRect)
        return value.items.asReversed().firstOrNull { item ->
            if (!item.enabled) return@firstOrNull false
            val markerX = scene.left + item.normalizedX * scene.width()
            val markerY = scene.top + item.normalizedY * scene.height()
            val dx = x - markerX
            val dy = y - markerY
            dx * dx + dy * dy <= markerHitRadius * markerHitRadius
        }?.id
    }

    private fun updateDraggedItem(x: Float, y: Float, imageRect: RectF) {
        val itemId = draggingItemId ?: return
        val value = template ?: return
        val scene = value.sceneRegion.toViewRect(imageRect)
        if (scene.width() <= 0f || scene.height() <= 0f) return
        onItemPointChanged?.invoke(
            itemId,
            ((x - scene.left) / scene.width()).coerceIn(0f, 1f),
            ((y - scene.top) / scene.height()).coerceIn(0f, 1f),
        )
    }

    private fun NormalizedRect.toViewRect(image: RectF) = RectF(
        image.left + left * image.width(),
        image.top + top * image.height(),
        image.left + right * image.width(),
        image.top + bottom * image.height(),
    )

    private fun RectF.sorted() = RectF(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
}
