package com.vflow.fork.hiddenobject

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HiddenObjectAutoClickModule : BaseModule() {
    override val id = "vflow.fork.hidden_object_auto_click"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.fork_hidden_object_module_name,
        descriptionStringRes = R.string.fork_hidden_object_module_description,
        name = "自动寻物",
        description = "根据已录入关卡模板识别物品名称，并自动点击或显示位置提示。",
        iconRes = R.drawable.rounded_image_search_24,
        category = "界面交互",
        categoryId = "interaction",
    )
    override val uiProvider: ModuleUIProvider = HiddenObjectModuleUIProvider()

    override fun getRequiredPermissions(step: ActionStep?): List<Permission> =
        listOf(PermissionManager.ACCESSIBILITY, PermissionManager.OVERLAY)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition("template_id", "关卡模板", ParameterType.STRING, "", acceptsMagicVariable = false),
        InputDefinition(
            "execution_mode",
            "执行模式",
            ParameterType.ENUM,
            MODE_CLICK,
            options = listOf(MODE_CLICK, MODE_HINT),
            optionsStringRes = listOf(
                R.string.fork_hidden_object_execution_mode_click,
                R.string.fork_hidden_object_execution_mode_hint,
            ),
            acceptsMagicVariable = false,
        ),
        InputDefinition("poll_interval_ms", "轮询间隔(ms)", ParameterType.NUMBER, 500.0, acceptsMagicVariable = true),
        InputDefinition("click_interval_ms", "点击间隔(ms)", ParameterType.NUMBER, 150.0, acceptsMagicVariable = true),
        InputDefinition("idle_finish_ms", "空闲结束等待(ms)", ParameterType.NUMBER, 3000.0, acceptsMagicVariable = true),
        InputDefinition("max_duration_sec", "最长执行时间(秒)", ParameterType.NUMBER, 120.0, acceptsMagicVariable = true),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", "是否正常完成", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("round_count", "处理轮数", VTypeRegistry.NUMBER.id),
        OutputDefinition("click_count", "点击数量", VTypeRegistry.NUMBER.id),
        OutputDefinition("unmatched_texts", "未匹配文字", VTypeRegistry.LIST.id, listElementType = VTypeRegistry.STRING.id),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val templateId = step.parameters["template_id"] as? String
        val name = templateId?.let { HiddenObjectTemplateRepository(context).get(it)?.name }
            ?: context.getString(R.string.fork_hidden_object_template_unselected)
        return PillUtil.buildSpannable(context, context.getString(R.string.fork_hidden_object_summary_prefix), PillUtil.Pill(name, "template_id"))
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val templateId = context.getVariableAsString("template_id", "")
        val template = HiddenObjectTemplateRepository(context.applicationContext).get(templateId)
            ?: return ExecutionResult.Failure("模板不可用", "请选择一个存在的寻物关卡模板。")
        val errors = HiddenObjectNameMatcher.validationErrors(template)
        if (errors.isNotEmpty()) return ExecutionResult.Failure("模板配置不完整", errors.joinToString("；"))

        val executionMode = context.getVariableAsString("execution_mode", MODE_CLICK)
        val pollInterval = (context.getVariableAsLong("poll_interval_ms") ?: 500L).coerceIn(150L, 5000L)
        val clickInterval = (context.getVariableAsLong("click_interval_ms") ?: 150L).coerceIn(50L, 3000L)
        val idleFinish = (context.getVariableAsLong("idle_finish_ms") ?: 3000L).coerceIn(500L, 30000L)
        val maxDuration = (context.getVariableAsLong("max_duration_sec") ?: 120L).coerceIn(5L, 3600L) * 1000L
        val runtime = HiddenObjectRuntime()
        val tracker = HiddenObjectRoundTracker(idleFinish)
        val unmatched = linkedSetOf<String>()
        val overlay = HiddenObjectControlOverlay(context.applicationContext)
        val runDir = HiddenObjectRunCache.prepare(context.applicationContext)
        var captureSession: HiddenObjectCaptureSession? = null
        val rootWorkflowId = context.workflowStack.firstOrNull()

        try {
            overlay.show(showPauseControl = executionMode == MODE_HINT) {
                rootWorkflowId?.let(WorkflowExecutor::stopExecution)
            }
            onProgress(ProgressUpdate("等待用户点击悬浮窗开始按钮"))
            overlay.awaitStart()
            onProgress(ProgressUpdate("自动寻物已开始，正在准备截屏"))
            overlay.updateStatus("正在准备截屏…")
            captureSession = try {
                overlay.hideForCapture()
                runCatching {
                    withTimeout(15_000L) { HiddenObjectCaptureSession.create(context, runDir) }
                }.getOrElse {
                    return ExecutionResult.Failure("截图权限不可用", it.message ?: "无法创建截图会话")
                }
            } finally {
                overlay.restoreAfterCapture()
            }
            if (executionMode == MODE_HINT) {
                return executeHintMode(
                    context = context,
                    template = template,
                    captureSession = requireNotNull(captureSession),
                    runtime = runtime,
                    overlay = overlay,
                    unmatched = unmatched,
                    onProgress = onProgress,
                )
            }
            while (true) {
                tracker.startNextCycle()
                overlay.updateStatus("正在识别名称区域…")
                val startedAt = System.currentTimeMillis()
                var pausedByIdle = false

                while (System.currentTimeMillis() - startedAt < maxDuration) {
                    currentCoroutineContext().ensureActive()
                    val captured = try {
                        overlay.hideForCapture()
                        withTimeout(5000L) { captureSession.capture() }
                    } catch (error: Throwable) {
                        return ExecutionResult.Failure("截图失败", error.message ?: "无法截取当前屏幕")
                    } finally {
                        overlay.restoreAfterCapture()
                    }
                    try {
                        val texts = runtime.recognize(context, captured, template.labelRegion).getOrElse {
                            return ExecutionResult.Failure("OCR 失败", it.message ?: "无法识别名称区域")
                        }
                        val match = HiddenObjectNameMatcher.match(texts, template)
                        unmatched += match.unmatchedTexts.filter { HiddenObjectNameMatcher.normalize(it).isNotBlank() }
                        val byId = match.items.associateBy { it.id }
                        val previousRoundCount = tracker.roundCount
                        val actionable = tracker.observe(byId.keys, System.currentTimeMillis())
                        if (actionable.isNotEmpty()) {
                            val actionableItems = actionable.mapNotNull(byId::get)
                            val recognizedNames = actionableItems.joinToString("、") { it.name }
                            overlay.updateStatus("识别到了：$recognizedNames")
                            onProgress(ProgressUpdate("第 ${tracker.roundCount} 轮：识别到了 $recognizedNames"))
                            delay(600)
                            if (tracker.roundCount > previousRoundCount) {
                                overlay.clearTargets()
                            }
                            actionableItems.forEach { item ->
                                currentCoroutineContext().ensureActive()
                                val (x, y) = template.clickPoint(item, captured.width, captured.height)
                                overlay.updateStatus("正在点击：${item.name}")
                                overlay.showTarget(x, y)
                                delay(350)
                                runtime.tap(context, x, y).getOrElse {
                                    return ExecutionResult.Failure("点击失败", "${item.name}：${it.message ?: "无法点击"}")
                                }
                                delay(180)
                                tracker.markClicked(item.id, System.currentTimeMillis())
                                onProgress(ProgressUpdate("已点击 ${item.name} ($x, $y)"))
                                delay(clickInterval)
                            }
                            overlay.updateStatus("点击完毕，正在识别下一轮…")
                        }
                        if (tracker.shouldPause(System.currentTimeMillis())) {
                            pausedByIdle = true
                            break
                        }
                    } finally {
                        runtime.deleteTemporaryImage(captured.image)
                    }
                    delay(pollInterval)
                }

                overlay.clearTargets()
                val pauseStatus = if (pausedByIdle) {
                    "本轮已完成，点击开始识别新一轮"
                } else {
                    "已达到本轮时限，点击开始重新识别"
                }
                overlay.pause(pauseStatus)
                onProgress(ProgressUpdate("$pauseStatus，或点击结束退出"))
                overlay.awaitStart()
                onProgress(ProgressUpdate("自动寻物已重新开始"))
            }
        } finally {
            withContext(NonCancellable) {
                captureSession?.close()
                overlay.dismiss()
                HiddenObjectRunCache.cleanup(runDir)
            }
        }
    }

    private suspend fun executeHintMode(
        context: ExecutionContext,
        template: HiddenObjectTemplate,
        captureSession: HiddenObjectCaptureSession,
        runtime: HiddenObjectRuntime,
        overlay: HiddenObjectControlOverlay,
        unmatched: MutableSet<String>,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        var roundCount = 0
        var hintedItemIds = emptySet<String>()
        var forceRefresh = true
        while (true) {
            currentCoroutineContext().ensureActive()
            overlay.updateStatus("正在自动识别名称区域…")
            val captured = try {
                overlay.hideForCapture()
                withTimeout(5000L) { captureSession.capture() }
            } catch (error: Throwable) {
                return ExecutionResult.Failure("截图失败", error.message ?: "无法截取当前屏幕")
            } finally {
                overlay.restoreAfterCapture()
            }
            try {
                val texts = runtime.recognize(context, captured, template.labelRegion).getOrElse {
                    return ExecutionResult.Failure("OCR 失败", it.message ?: "无法识别名称区域")
                }
                val match = HiddenObjectNameMatcher.match(texts, template)
                unmatched += match.unmatchedTexts.filter { HiddenObjectNameMatcher.normalize(it).isNotBlank() }
                val recognizedItemIds = match.items.mapTo(linkedSetOf()) { it.id }
                val hasNewItem = recognizedItemIds.any { it !in hintedItemIds }
                val shouldReplaceHints = forceRefresh || hasNewItem
                roundCount++
                if (shouldReplaceHints) {
                    val points = match.items.map { item ->
                        template.clickPoint(item, captured.width, captured.height)
                    }
                    overlay.showHintTargets(points)
                    hintedItemIds = recognizedItemIds
                }
                val status = if (shouldReplaceHints && match.items.isEmpty()) {
                    "未识别到物品，每2秒自动重试"
                } else if (shouldReplaceHints) {
                    "已提示 ${match.items.size} 个物品，每2秒自动识别"
                } else {
                    "当前提示 ${hintedItemIds.size} 个物品，每2秒自动识别"
                }
                overlay.showHintRunning(status)
                if (shouldReplaceHints) {
                    val recognizedNames = match.items.joinToString("、") { it.name }
                    if (match.items.isEmpty()) {
                        onProgress(ProgressUpdate("第 $roundCount 轮：未识别到物品，将自动重试"))
                    } else {
                        onProgress(ProgressUpdate("第 $roundCount 轮：已提示 $recognizedNames，等待用户手动点击"))
                    }
                }
            } finally {
                runtime.deleteTemporaryImage(captured.image)
            }
            when (overlay.awaitHintControlOrTimeout(HINT_RECOGNITION_INTERVAL_MS)) {
                HiddenObjectControlOverlay.HintControlEvent.TIMEOUT -> {
                    forceRefresh = false
                }
                HiddenObjectControlOverlay.HintControlEvent.START -> {
                    forceRefresh = true
                    onProgress(ProgressUpdate("用户已手动触发重新识别"))
                }
                HiddenObjectControlOverlay.HintControlEvent.PAUSE -> {
                    overlay.showHintPaused()
                    onProgress(ProgressUpdate("提示模式自动识别已暂停"))
                    overlay.awaitStart()
                    forceRefresh = true
                    onProgress(ProgressUpdate("提示模式自动识别已恢复"))
                }
            }
        }
    }

    companion object {
        const val MODE_CLICK = "click"
        const val MODE_HINT = "hint"
        private const val HINT_RECOGNITION_INTERVAL_MS = 2000L
    }
}
