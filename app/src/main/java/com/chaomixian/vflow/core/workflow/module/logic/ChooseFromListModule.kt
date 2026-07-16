package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
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
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.services.ExecutionUIService
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/** A single action that presents a list value and returns the item the user picks. */
class ChooseFromListModule : BaseModule() {
    override val id = "vflow.logic.list.choose"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_list_choose_name,
        descriptionStringRes = R.string.module_vflow_logic_list_choose_desc,
        name = "从列表中选取",
        description = "从传入列表中选择一个项目",
        iconRes = R.drawable.rounded_list_alt_check_24,
        category = "逻辑控制",
        categoryId = "logic"
    )

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "prompt",
            nameStringRes = R.string.param_vflow_logic_list_choose_prompt_name,
            name = "提示",
            staticType = ParameterType.STRING,
            defaultValue = "请选择",
            acceptsMagicVariable = false
        ),
        InputDefinition(
            id = "items",
            nameStringRes = R.string.param_vflow_logic_list_choose_items_name,
            name = "列表",
            staticType = ParameterType.ANY,
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.LIST.id)
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("selected_item", "所选项目", VTypeRegistry.ANY.id, nameStringRes = R.string.output_vflow_logic_list_choose_selected_item_name),
        OutputDefinition("selected_index", "所选索引", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_logic_list_choose_selected_index_name)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val listPill = PillUtil.createPillFromParam(step.parameters["items"], getInputs()[1])
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_logic_list_choose_prefix),
            listPill,
            context.getString(R.string.summary_vflow_logic_list_choose_suffix)
        )
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val items = (context.getVariable("items") as? VList)?.raw
            ?: return ExecutionResult.Failure("列表无效", "请选择一个列表变量作为输入")
        if (items.isEmpty()) return ExecutionResult.Failure("列表为空", "没有可选择的项目")
        val uiService = context.services.get(ExecutionUIService::class)
            ?: return ExecutionResult.Failure("服务缺失", "无法显示列表")
        val selectedIndex = uiService.requestMenuChoice(
            context.getVariableAsString("prompt", "请选择"),
            items.map { it.asString() }
        ) ?: return ExecutionResult.Failure("已取消选择", "用户取消了列表选择")
        val selectedItem = items.getOrNull(selectedIndex)
            ?: return ExecutionResult.Failure("选择无效", "未找到所选项目")
        onProgress(ProgressUpdate("已选择: ${selectedItem.asString()}"))
        return ExecutionResult.Success(
            mapOf(
                "selected_item" to selectedItem,
                "selected_index" to VNumber(selectedIndex + 1)
            )
        )
    }
}
