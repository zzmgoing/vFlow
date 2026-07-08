package com.vflow.fork.workflow.module

import android.content.Context
import android.content.Intent
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.vflow.fork.goldfinger.GoldenFingerClickerService

/**
 * 金手指连点器模块。
 *
 * 启动一个独立悬浮窗：双击图标开始连续点击，单击或拖动图标停止点击。
 */
class GoldenFingerClickerModule : BaseModule() {
    override val id = "vflow.fork.goldfinger_clicker"

    companion object {
        private const val DIRECTION_UP = "up"
        private const val DIRECTION_DOWN = "down"
        private const val DIRECTION_LEFT = "left"
        private const val DIRECTION_RIGHT = "right"
    }

    override val metadata = ActionMetadata(
        name = "金手指连点器",
        description = "显示金手指悬浮窗，双击开始连点，单击或移动停止。",
        iconRes = R.drawable.rounded_ads_click_24,
        category = "界面交互",
        categoryId = "interaction"
    )

    override fun getRequiredPermissions(step: ActionStep?) = listOf(
        PermissionManager.OVERLAY,
        PermissionManager.CORE
    )

    override fun getInputs() = listOf(
        InputDefinition(
            id = "icon_size",
            name = "图标大小(dp)",
            staticType = ParameterType.NUMBER,
            defaultValue = 60,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "interval_ms",
            name = "点击间隔(ms)",
            staticType = ParameterType.NUMBER,
            defaultValue = 50,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "direction",
            name = "金手指方向",
            staticType = ParameterType.ENUM,
            defaultValue = DIRECTION_UP,
            options = listOf(DIRECTION_UP, DIRECTION_DOWN, DIRECTION_LEFT, DIRECTION_RIGHT),
            optionsStringRes = listOf(
                R.string.fork_goldfinger_direction_up,
                R.string.fork_goldfinger_direction_down,
                R.string.fork_goldfinger_direction_left,
                R.string.fork_goldfinger_direction_right
            ),
            legacyValueMap = mapOf(
                "上" to DIRECTION_UP,
                "下" to DIRECTION_DOWN,
                "左" to DIRECTION_LEFT,
                "右" to DIRECTION_RIGHT
            ),
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "click_gap",
            name = "点击距离(dp)",
            staticType = ParameterType.NUMBER,
            defaultValue = 0,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "offset_x",
            name = "点击点X偏移(dp)",
            staticType = ParameterType.NUMBER,
            defaultValue = 0,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "offset_y",
            name = "点击点Y偏移(dp)",
            staticType = ParameterType.NUMBER,
            defaultValue = 0,
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "double_tap_timeout_ms",
            name = "双击间隔(ms)",
            staticType = ParameterType.NUMBER,
            defaultValue = 500,
            acceptsMagicVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?) = listOf(
        OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val inputs = getInputs()
        return PillUtil.buildSpannable(
            context,
            "启动金手指连点器，间隔 ",
            PillUtil.createPillFromParam(step.parameters["interval_ms"], inputs[1]),
            "ms，方向 ",
            PillUtil.createPillFromParam(step.parameters["direction"], inputs[2]),
            "，距离 ",
            PillUtil.createPillFromParam(step.parameters["click_gap"], inputs[3]),
            "dp"
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val iconSize = context.getVariableAsInt("icon_size")?.coerceIn(32, 160) ?: 60
        val intervalMs = context.getVariableAsInt("interval_ms")?.coerceIn(10, 2_000) ?: 50
        val directionInput = getInputs().first { it.id == "direction" }
        val rawDirection = context.getVariableAsString("direction", DIRECTION_UP)
        val direction = directionInput.normalizeEnumValue(rawDirection) ?: DIRECTION_UP
        val clickGap = context.getVariableAsInt("click_gap")?.coerceIn(0, 240) ?: 0
        val offsetX = context.getVariableAsInt("offset_x")?.coerceIn(-240, 240) ?: 0
        val offsetY = context.getVariableAsInt("offset_y")?.coerceIn(-240, 240) ?: 0
        val doubleTapTimeoutMs = context.getVariableAsInt("double_tap_timeout_ms")?.coerceIn(120, 1_500) ?: 500

        val intent = Intent(context.applicationContext, GoldenFingerClickerService::class.java).apply {
            action = GoldenFingerClickerService.ACTION_SHOW
            putExtra(GoldenFingerClickerService.EXTRA_ICON_SIZE_DP, iconSize)
            putExtra(GoldenFingerClickerService.EXTRA_INTERVAL_MS, intervalMs)
            putExtra(GoldenFingerClickerService.EXTRA_DIRECTION, direction)
            putExtra(GoldenFingerClickerService.EXTRA_CLICK_GAP_DP, clickGap)
            putExtra(GoldenFingerClickerService.EXTRA_OFFSET_X_DP, offsetX)
            putExtra(GoldenFingerClickerService.EXTRA_OFFSET_Y_DP, offsetY)
            putExtra(GoldenFingerClickerService.EXTRA_DOUBLE_TAP_TIMEOUT_MS, doubleTapTimeoutMs)
        }

        context.applicationContext.startService(intent)
        onProgress(ProgressUpdate("金手指悬浮窗已启动，双击图标开始连点。"))

        return ExecutionResult.Success(mapOf("success" to VBoolean(true)))
    }
}
