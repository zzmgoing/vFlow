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
        description = "根据已录入关卡模板识别物品名称并自动点击。",
        iconRes = R.drawable.rounded_image_search_24,
        category = "界面交互",
        categoryId = "interaction",
    )
    override val uiProvider: ModuleUIProvider = HiddenObjectModuleUIProvider()

    override fun getRequiredPermissions(step: ActionStep?): List<Permission> =
        listOf(PermissionManager.ACCESSIBILITY, PermissionManager.OVERLAY)

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition("template_id", "关卡模板", ParameterType.STRING, "", acceptsMagicVariable = false),
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
            overlay.show {
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
}
