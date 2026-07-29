package com.vflow.fork.hiddenobject

import java.text.Normalizer
import java.util.UUID
import kotlin.math.roundToInt

data class NormalizedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun normalized(): NormalizedRect {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(0f, 1f)
        return NormalizedRect(l, t, r, b)
    }

    fun toPixelRect(width: Int, height: Int): android.graphics.Rect {
        val value = normalized()
        return android.graphics.Rect(
            (value.left * width).roundToInt(),
            (value.top * height).roundToInt(),
            (value.right * width).roundToInt(),
            (value.bottom * height).roundToInt(),
        )
    }
}

data class HiddenObjectItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val enabled: Boolean = true,
)

data class HiddenObjectTemplate(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String = UUID.randomUUID().toString(),
    val name: String = "未命名关卡",
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0,
    val referenceImagePath: String? = null,
    val sceneRegion: NormalizedRect = NormalizedRect(0f, 0f, 1f, 0.82f),
    val labelRegion: NormalizedRect = NormalizedRect(0f, 0.82f, 1f, 1f),
    val items: List<HiddenObjectItem> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }

    fun clickPoint(item: HiddenObjectItem, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val scene = sceneRegion.normalized()
        val x = scene.left + item.normalizedX.coerceIn(0f, 1f) * (scene.right - scene.left)
        val y = scene.top + item.normalizedY.coerceIn(0f, 1f) * (scene.bottom - scene.top)
        return (x * screenWidth).roundToInt() to (y * screenHeight).roundToInt()
    }
}

object HiddenObjectNameMatcher {
    private val ignoredCharacters = Regex("[\\s\\p{P}\\p{S}]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(ignoredCharacters, "")

    fun index(template: HiddenObjectTemplate): Map<String, HiddenObjectItem> {
        val result = linkedMapOf<String, HiddenObjectItem>()
        template.items.filter { it.enabled }.forEach { item ->
            normalize(item.name).takeIf { it.isNotBlank() }?.let { key -> result.putIfAbsent(key, item) }
        }
        return result
    }

    fun match(texts: List<String>, template: HiddenObjectTemplate): HiddenObjectMatchResult {
        val itemIndex = index(template)
        val matched = linkedMapOf<String, HiddenObjectItem>()
        val unmatched = linkedSetOf<String>()
        texts.forEach { raw ->
            val key = normalize(raw)
            if (key.isBlank()) return@forEach
            val item = itemIndex[key]
            if (item == null) unmatched += raw.trim() else matched.putIfAbsent(item.id, item)
        }
        return HiddenObjectMatchResult(matched.values.toList(), unmatched.toList())
    }

    fun validationErrors(template: HiddenObjectTemplate): List<String> {
        val errors = mutableListOf<String>()
        if (template.name.isBlank()) errors += "模板名称不能为空"
        if (template.referenceWidth <= 0 || template.referenceHeight <= 0) errors += "请先设置参考截图"
        if (template.items.none { it.enabled }) errors += "至少需要一个已启用的物品"
        val owners = mutableMapOf<String, String>()
        template.items.filter { it.enabled }.forEach { item ->
            if (item.name.isBlank()) errors += "物品名称不能为空"
            val key = normalize(item.name)
            if (key.isNotBlank()) {
                val owner = owners.putIfAbsent(key, item.id)
                if (owner != null && owner != item.id) errors += "物品名称重复：${item.name}"
            }
        }
        return errors.distinct()
    }
}

data class HiddenObjectMatchResult(
    val items: List<HiddenObjectItem>,
    val unmatchedTexts: List<String>,
)

class HiddenObjectRoundTracker(private val idleFinishMs: Long) {
    private val roundItems = linkedSetOf<String>()
    private val clickedItems = linkedSetOf<String>()
    var roundCount: Int = 0
        private set
    var clickCount: Int = 0
        private set
    private var lastActionableAt: Long = 0L

    fun observe(itemIds: Set<String>, nowMs: Long): Set<String> {
        if (itemIds.isEmpty()) return emptySet()
        val hasNewItem = itemIds.any { it !in roundItems }
        val isNewRound = roundItems.isNotEmpty() && hasNewItem && !itemIds.containsAll(roundItems)
        if (roundItems.isEmpty() || isNewRound) {
            roundItems.clear()
            clickedItems.clear()
            roundItems.addAll(itemIds)
            roundCount++
        } else {
            roundItems.addAll(itemIds)
        }
        val actionable = itemIds - clickedItems
        if (actionable.isNotEmpty()) lastActionableAt = nowMs
        return actionable
    }

    fun markClicked(itemId: String, nowMs: Long) {
        if (clickedItems.add(itemId)) clickCount++
        lastActionableAt = nowMs
    }

    fun startNextCycle() {
        roundItems.clear()
        clickedItems.clear()
        lastActionableAt = 0L
    }

    fun shouldPause(nowMs: Long): Boolean = roundItems.isNotEmpty() &&
        clickedItems.containsAll(roundItems) &&
        nowMs - lastActionableAt >= idleFinishMs
}
