package com.vflow.fork.hiddenobject

import android.net.Uri
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VCoordinateRegion
import com.chaomixian.vflow.core.types.complex.VImage
import java.io.File

internal data class CapturedScreen(val image: VImage, val width: Int, val height: Int)

internal class HiddenObjectRuntime {
    suspend fun recognize(
        context: ExecutionContext,
        screen: CapturedScreen,
        labelRegion: NormalizedRect,
    ): Result<List<String>> {
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
    ): Result<List<String>> = runCatching {
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
        @Suppress("UNCHECKED_CAST")
        (outputs["text_list"] as? List<*>)
            .orEmpty()
            .mapNotNull { value ->
                when (value) {
                    is VString -> value.raw
                    is String -> value
                    else -> null
                }
            }
    }

    suspend fun tap(context: ExecutionContext, x: Int, y: Int): Result<Unit> = runCatching {
        val module = requireNotNull(ModuleRegistry.getModule("vflow.interaction.screen_operation"))
        val tapContext = context.copy(
            variables = mutableMapOf(
                "operation_type" to VObjectFactory.from("tap"),
                "target" to VCoordinate(x, y),
                "duration" to VObjectFactory.from(0),
                "execution_mode" to VObjectFactory.from("auto"),
            ),
            magicVariables = mutableMapOf(),
        )
        val result = module.execute(tapContext) { }
        if (result is ExecutionResult.Failure) error(result.errorMessage)
    }

    fun deleteTemporaryImage(image: VImage) {
        runCatching {
            val uri = Uri.parse(image.uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
        }
    }
}
