package com.vflow.fork.hiddenobject

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinateRegion
import com.chaomixian.vflow.core.types.complex.VImage
import com.google.gson.JsonParser
import java.io.File

internal data class CapturedScreen(val image: VImage, val width: Int, val height: Int)

internal class HiddenObjectRuntime {
    suspend fun recognize(
        context: ExecutionContext,
        screen: CapturedScreen,
        labelRegion: NormalizedRect,
    ): Result<List<HiddenObjectRecognizedLabel>> {
        val region = labelRegion.toPixelRect(screen.width, screen.height)
        val ppResult = recognizeWithEngine(context, screen.image, region, "PP_OCR_V5")
        if (ppResult.isSuccess) return ppResult
        return recognizeWithEngine(context, screen.image, region, "ML_KIT")
    }

    private suspend fun recognizeWithEngine(
        context: ExecutionContext,
        image: VImage,
        region: android.graphics.Rect,
        engine: String,
    ): Result<List<HiddenObjectRecognizedLabel>> = runCatching {
        val module = requireNotNull(ModuleRegistry.getModule("vflow.interaction.ocr"))
        val ocrContext = context.copy(
            variables = mutableMapOf(
                "engine" to VObjectFactory.from(engine),
                "image" to image,
                "mode" to VObjectFactory.from("recognize"),
                "language" to VObjectFactory.from("chinese"),
                "region" to VCoordinateRegion(region.left, region.top, region.right, region.bottom),
            ),
            magicVariables = mutableMapOf(),
        )
        val result = module.execute(ocrContext) { }
        val outputs = (result as? ExecutionResult.Success)?.outputs
            ?: error((result as? ExecutionResult.Failure)?.errorMessage ?: "OCR 失败")
        val texts = (outputs["text_list"] as? List<*>)
            .orEmpty()
            .mapNotNull { value ->
                when (value) {
                    is VString -> value.raw
                    is String -> value
                    else -> null
                }
            }
        val structuredJson = (outputs["structured_json"] as? VString)?.raw
        readLabelsWithAppearance(context.applicationContext, image, structuredJson).ifEmpty {
            texts.map { HiddenObjectRecognizedLabel(it, 255) }
        }
    }

    private fun readLabelsWithAppearance(
        context: Context,
        image: VImage,
        structuredJson: String?,
    ): List<HiddenObjectRecognizedLabel> {
        if (structuredJson.isNullOrBlank()) return emptyList()
        val uri = Uri.parse(image.uriString)
        val bitmap = contextBitmap(context, uri) ?: return emptyList()
        return try {
            val items = JsonParser.parseString(structuredJson).asJsonObject
                .getAsJsonArray("items") ?: return emptyList()
            items.mapNotNull { element ->
                val item = element.asJsonObject
                val text = item.get("text")?.asString?.trim().orEmpty()
                val bounds = item.getAsJsonObject("bounding_box") ?: return@mapNotNull null
                if (text.isEmpty()) return@mapNotNull null
                val rect = Rect(
                    bounds.get("left").asFloat.toInt(),
                    bounds.get("top").asFloat.toInt(),
                    bounds.get("right").asFloat.toInt(),
                    bounds.get("bottom").asFloat.toInt(),
                )
                val appearance = analyzeAppearance(bitmap, rect)
                HiddenObjectRecognizedLabel(
                    text = text,
                    brightnessScore = appearance.brightnessScore,
                    hasStrikeThrough = appearance.hasStrikeThrough,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun contextBitmap(context: Context, uri: Uri): Bitmap? =
        runCatching {
            val path = uri.takeIf { it.scheme == "file" }?.path
            if (path != null) {
                BitmapFactory.decodeFile(path)
            } else {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()

    private data class LabelAppearance(
        val brightnessScore: Int,
        val hasStrikeThrough: Boolean,
    )

    private fun analyzeAppearance(bitmap: Bitmap, source: Rect): LabelAppearance {
        val left = source.left.coerceIn(0, bitmap.width)
        val top = source.top.coerceIn(0, bitmap.height)
        val right = source.right.coerceIn(left, bitmap.width)
        val bottom = source.bottom.coerceIn(top, bitmap.height)
        if (left >= right || top >= bottom) return LabelAppearance(0, false)

        val histogram = IntArray(256)
        var sampleCount = 0
        var hasStrikeThrough = false
        val strikeTop = top + (bottom - top) * 3 / 10
        val strikeBottom = top + (bottom - top) * 7 / 10
        val strikeLength = ((right - left) * 0.55f).toInt().coerceAtLeast(2)
        for (y in top until bottom) {
            var currentRun = 0
            var longestRun = 0
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val max = maxOf(red, green, blue)
                val min = minOf(red, green, blue)
                if (max - min <= 48) {
                    histogram[min]++
                    sampleCount++
                }
                if (y in strikeTop..strikeBottom && max - min <= 48 && min in 80..235) {
                    currentRun++
                    longestRun = maxOf(longestRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
            if (longestRun >= strikeLength) hasStrikeThrough = true
        }
        if (sampleCount == 0) return LabelAppearance(0, hasStrikeThrough)
        val percentile = (sampleCount * 0.82f).toInt().coerceAtLeast(1)
        var seen = 0
        histogram.forEachIndexed { brightness, count ->
            seen += count
            if (seen >= percentile) return LabelAppearance(brightness, hasStrikeThrough)
        }
        return LabelAppearance(255, hasStrikeThrough)
    }

    fun deleteTemporaryImage(image: VImage) {
        runCatching {
            val uri = Uri.parse(image.uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
        }
    }
}
