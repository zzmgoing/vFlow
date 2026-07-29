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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HiddenObjectAutoClickModule : BaseModule() {
    override val id = "vflow.fork.hidden_object_auto_click"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.fork_hidden_object_module_name,
        descriptionStringRes = R.string.fork_hidden_object_module_description,
        name = "自动寻物",
        description = "根据已录入关卡模板识别物品名称，并显示位置提示。",
        iconRes = R.drawable.rounded_image_search_24,
        category = "界面交互",
        categoryId = "interaction",
    )
    override val uiProvider: ModuleUIProvider = HiddenObjectModuleUIProvider()

    override fun getRequiredPermissions(step: ActionStep?): List<Permission> =
        listOf(PermissionManager.ACCESSIBILITY, PermissionManager.OVERLAY)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition("template_id", "关卡模板", ParameterType.STRING, "", acceptsMagicVariable = false),
        InputDefinition("auto_hint_interval_ms", "自动提示识别间隔(ms)", ParameterType.NUMBER, 2000.0, acceptsMagicVariable = true),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", "是否正常完成", VTypeRegistry.BOOLEAN.id),
        OutputDefinition("round_count", "处理轮数", VTypeRegistry.NUMBER.id),
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

        val autoHintInterval = (context.getVariableAsLong("auto_hint_interval_ms") ?: 2000L).coerceIn(500L, 30000L)
        val runtime = HiddenObjectRuntime()
        val unmatched = linkedSetOf<String>()
        val overlay = HiddenObjectControlOverlay(context.applicationContext)
        val runDir = HiddenObjectRunCache.prepare(context.applicationContext)
        var captureSession: HiddenObjectCaptureSession? = null
        val rootWorkflowId = context.workflowStack.firstOrNull()

        try {
            overlay.show(showPauseControl = true) {
                rootWorkflowId?.let(WorkflowExecutor::stopExecution)
            }
            onProgress(ProgressUpdate("等待用户点击悬浮窗开始按钮"))
            overlay.awaitStart()
            onProgress(ProgressUpdate("自动寻物已开始，正在准备截屏"))
            overlay.updateStatus("正在准备截屏…")
            captureSession = runCatching {
                withTimeout(15_000L) { HiddenObjectCaptureSession.create(context, runDir) }
            }.getOrElse {
                return ExecutionResult.Failure("截图权限不可用", it.message ?: "无法创建截图会话")
            }
            return executeHintMode(
                context = context,
                template = template,
                captureSession = requireNotNull(captureSession),
                runtime = runtime,
                overlay = overlay,
                unmatched = unmatched,
                autoHintInterval = autoHintInterval,
                onProgress = onProgress,
            )
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
        autoHintInterval: Long,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        var roundCount = 0
        val hintedItemIds = linkedSetOf<String>()
        val itemsById = template.items.associateBy { it.id }
        val intervalLabel = if (autoHintInterval % 1000L == 0L) {
            "${autoHintInterval / 1000L}秒"
        } else {
            "${autoHintInterval}毫秒"
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            overlay.updateStatus("正在自动识别名称区域…")
            val captured = try {
                withTimeout(5000L) { captureSession.capture() }
            } catch (error: Throwable) {
                return ExecutionResult.Failure("截图失败", error.message ?: "无法截取当前屏幕")
            }
            try {
                val labels = runtime.recognize(context, captured, template.labelRegion).getOrElse {
                    return ExecutionResult.Failure("OCR 失败", it.message ?: "无法识别名称区域")
                }
                val match = HiddenObjectNameMatcher.matchVisual(labels, template)
                unmatched += match.unmatchedTexts.filter { HiddenObjectNameMatcher.normalize(it).isNotBlank() }
                val activeItemIds = match.activeItems.mapTo(linkedSetOf()) { it.id }
                val newItems = match.activeItems.filter { it.id !in hintedItemIds }
                val removedItems = hintedItemIds
                    .filter { it !in activeItemIds }
                    .mapNotNull(itemsById::get)
                hintedItemIds.clear()
                hintedItemIds.addAll(activeItemIds)
                roundCount++
                val targets = hintedItemIds.mapNotNull { itemId ->
                    itemsById[itemId]?.let { item ->
                        val (x, y) = template.clickPoint(item, captured.width, captured.height)
                        HiddenObjectControlOverlay.HintTarget(item.id, x, y)
                    }
                }
                overlay.showHintTargets(targets)
                val status = if (hintedItemIds.isEmpty()) {
                    "未识别到物品，每${intervalLabel}自动重试"
                } else {
                    "当前提示 ${hintedItemIds.size} 个物品，每${intervalLabel}自动识别"
                }
                overlay.showHintRunning(status)
                if (newItems.isNotEmpty()) {
                    val recognizedNames = newItems.joinToString("、") { it.name }
                    onProgress(ProgressUpdate("第 $roundCount 轮：已提示 $recognizedNames，等待用户手动点击"))
                }
                if (removedItems.isNotEmpty()) {
                    val removedNames = removedItems.joinToString("、") { it.name }
                    onProgress(ProgressUpdate("第 $roundCount 轮：已移除提示 $removedNames"))
                }
                if (hintedItemIds.isEmpty() && newItems.isEmpty() && removedItems.isEmpty()) {
                    onProgress(ProgressUpdate("第 $roundCount 轮：未识别到物品，将自动重试"))
                }
            } finally {
                runtime.deleteTemporaryImage(captured.image)
            }
            var startNextScan = false
            while (!startNextScan) {
                when (overlay.awaitHintControlOrTimeout(autoHintInterval)) {
                    HiddenObjectControlOverlay.HintControlEvent.Timeout -> {
                        startNextScan = true
                    }
                    HiddenObjectControlOverlay.HintControlEvent.Start -> {
                        startNextScan = true
                        onProgress(ProgressUpdate("用户已手动触发重新识别"))
                    }
                    HiddenObjectControlOverlay.HintControlEvent.Pause -> {
                        overlay.showHintPaused()
                        onProgress(ProgressUpdate("提示模式自动识别已暂停"))
                        var resumed = false
                        while (!resumed) {
                            when (overlay.awaitHintControl()) {
                                HiddenObjectControlOverlay.HintControlEvent.Start -> {
                                    resumed = true
                                    startNextScan = true
                                    onProgress(ProgressUpdate("提示模式自动识别已恢复"))
                                }
                                HiddenObjectControlOverlay.HintControlEvent.Pause,
                                HiddenObjectControlOverlay.HintControlEvent.Timeout -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
