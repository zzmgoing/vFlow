package com.vflow.fork.hiddenobject

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Base64
import android.view.WindowManager
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.services.ExecutionUIService
import com.chaomixian.vflow.services.VFlowCoreBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal object HiddenObjectRunCache {
    private const val ROOT_NAME = "hidden_object_runs"

    @Synchronized
    fun prepare(context: Context): File = prepareRoot(File(context.cacheDir, ROOT_NAME))

    internal fun prepareRoot(root: File): File {
        root.mkdirs()
        root.listFiles()?.forEach { it.deleteRecursively() }
        return File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    }

    fun cleanup(runDir: File) {
        runDir.deleteRecursively()
        runDir.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }
}

internal class HiddenObjectCaptureSession private constructor(
    private val mediaProjection: MediaProjection?,
    private val imageReader: ImageReader?,
    private val virtualDisplay: VirtualDisplay?,
    private val handlerThread: HandlerThread?,
    private val projectionCallback: MediaProjection.Callback?,
    private val runDir: File,
    private val width: Int,
    private val height: Int,
    private val useCoreCapture: Boolean = false,
) {
    private val pendingCapture = AtomicReference<CompletableDeferred<CapturedScreen>?>(null)
    private val prefetchedCapture = AtomicReference<CapturedScreen?>(null)
    @Volatile private var closed = false

    init {
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val pending = pendingCapture.getAndSet(null)
            if (pending == null || !pending.isActive || closed) {
                image.close()
                return@setOnImageAvailableListener
            }
            try {
                val plane = image.planes.firstOrNull() ?: error("截图像素数据为空")
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (padded.width == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                val file = File(runDir, "capture_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                if (bitmap !== padded) padded.recycle()
                bitmap.recycle()
                pending.complete(CapturedScreen(VImage(Uri.fromFile(file).toString()), width, height))
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
            } finally {
                image.close()
            }
        }, Handler(requireNotNull(handlerThread).looper))
    }

    suspend fun capture(): CapturedScreen {
        check(!closed) { "截图会话已关闭" }
        prefetchedCapture.getAndSet(null)?.let { return it }
        if (useCoreCapture) return captureWithCore(runDir)
        val deferred = CompletableDeferred<CapturedScreen>()
        check(pendingCapture.compareAndSet(null, deferred)) { "已有截图请求正在执行" }
        deferred.invokeOnCompletion { if (deferred.isCancelled) pendingCapture.compareAndSet(deferred, null) }
        return deferred.await()
    }

    fun close() {
        if (closed) return
        closed = true
        pendingCapture.getAndSet(null)?.cancel()
        prefetchedCapture.getAndSet(null)?.let { it.image.uriString.removePrefix("file://").let(::File).delete() }
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        runCatching { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } }
        runCatching { mediaProjection?.stop() }
        runCatching { imageReader?.close() }
        handlerThread?.quitSafely()
    }

    companion object {
        suspend fun create(context: ExecutionContext, runDir: File): HiddenObjectCaptureSession {
            createCoreSession(runDir)?.let { return it }

            val uiService = context.services.get(ExecutionUIService::class)
                ?: error("无法请求屏幕截图权限")
            val permissionData = uiService.requestMediaProjectionPermission()
                ?: error("用户未授予屏幕截图权限")
            val appContext = context.applicationContext
            val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = manager.getMediaProjection(Activity.RESULT_OK, permissionData)
                ?: error("无法创建屏幕截图会话")
            val reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            val thread = HandlerThread("HiddenObjectCapture").apply { start() }
            val projectionCallback = object : MediaProjection.Callback() {}
            projection.registerCallback(projectionCallback, Handler(thread.looper))
            val display = projection.createVirtualDisplay(
                "vFlow-HiddenObject",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(thread.looper),
            ) ?: run {
                reader.close(); projection.stop(); thread.quitSafely()
                error("无法创建截图虚拟屏幕")
            }
            return HiddenObjectCaptureSession(projection, reader, display, thread, projectionCallback, runDir, metrics.widthPixels, metrics.heightPixels)
        }

        private suspend fun createCoreSession(runDir: File): HiddenObjectCaptureSession? {
            if (!VFlowCoreBridge.isConnected) return null
            return runCatching {
                val firstCapture = captureWithCore(runDir)
                HiddenObjectCaptureSession(
                    mediaProjection = null,
                    imageReader = null,
                    virtualDisplay = null,
                    handlerThread = null,
                    projectionCallback = null,
                    runDir = runDir,
                    width = firstCapture.width,
                    height = firstCapture.height,
                    useCoreCapture = true,
                ).also { it.prefetchedCapture.set(firstCapture) }
            }.getOrNull()
        }

        private suspend fun captureWithCore(runDir: File): CapturedScreen = withContext(Dispatchers.IO) {
            val encoded = VFlowCoreBridge.captureScreenEx(format = "jpeg", quality = 94)
            check(encoded.isNotBlank()) { "Core 截图返回为空" }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            check(options.outWidth > 0 && options.outHeight > 0) { "Core 截图尺寸无效" }
            val file = File(runDir, "capture_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            CapturedScreen(VImage(Uri.fromFile(file).toString()), options.outWidth, options.outHeight)
        }
    }
}
