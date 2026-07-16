package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.BlockBehavior
import com.chaomixian.vflow.core.module.BlockType
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ExecutionSignal
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.services.ExecutionUIService
import com.chaomixian.vflow.ui.workflow_editor.ListItemAdapter
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

const val MENU_PAIRING_ID = "choose_from_menu"
const val MENU_START_ID = "vflow.logic.menu.start"
const val MENU_ITEM_ID = "vflow.logic.menu.item"
const val MENU_END_ID = "vflow.logic.menu.end"

data class MenuItem(val id: String, val title: String)

/**
 * Helpers shared by the menu module and the editor. A menu option is a direct
 * BLOCK_MIDDLE child; its following steps belong to that option until the next
 * direct option or the matching end block.
 */
object MenuBlockSupport {
    private const val ITEM_ID = "itemId"
    private const val ITEM_TITLE = "itemTitle"

    fun createItems(titles: List<String>): List<MenuItem> = titles.map { MenuItem(UUID.randomUUID().toString(), it) }

    fun toParameters(items: List<MenuItem>): List<Map<String, String>> =
        items.map { mapOf(ITEM_ID to it.id, ITEM_TITLE to it.title) }

    fun readItems(value: Any?): List<MenuItem> {
        val entries = when (value) {
            is VList -> value.raw
            is List<*> -> value
            else -> emptyList<Any>()
        }
        return entries.mapNotNull { entry ->
            val map = when (entry) {
                is VDictionary -> entry.raw
                is Map<*, *> -> entry
                else -> null
            } ?: return@mapNotNull null
            val id = valueAsString(map[ITEM_ID])?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val title = valueAsString(map[ITEM_TITLE]) ?: ""
            MenuItem(id, title)
        }
    }

    fun reconcileBranches(steps: MutableList<ActionStep>, startPosition: Int) {
        val start = steps.getOrNull(startPosition) ?: return
        if (start.moduleId != MENU_START_ID) return
        val items = readItems(start.parameters["items"])
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, MENU_PAIRING_ID)
        if (endPosition == -1) return

        val branchPositions = findDirectBranchPositions(steps, startPosition, endPosition)
        val existingBranches = linkedMapOf<String, List<ActionStep>>()
        branchPositions.forEachIndexed { index, branchStart ->
            val branchEnd = branchPositions.getOrElse(index + 1) { endPosition }
            val itemId = steps[branchStart].parameters[ITEM_ID] as? String ?: return@forEachIndexed
            existingBranches[itemId] = steps.subList(branchStart, branchEnd).toList()
        }

        if (branchPositions.isNotEmpty()) {
            steps.subList(branchPositions.first(), endPosition).clear()
        }

        var insertPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, MENU_PAIRING_ID)
        if (insertPosition == -1) return
        items.forEach { item ->
            val branch = existingBranches[item.id]?.toMutableList()
                ?: mutableListOf(ActionStep(MENU_ITEM_ID, emptyMap()))
            branch[0] = branch[0].copy(parameters = mapOf(ITEM_ID to item.id, ITEM_TITLE to item.title))
            steps.addAll(insertPosition, branch)
            insertPosition += branch.size
        }
    }

    fun findBranchPosition(steps: List<ActionStep>, startPosition: Int, itemId: String): Int {
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, MENU_PAIRING_ID)
        if (endPosition == -1) return -1
        return findDirectBranchPositions(steps, startPosition, endPosition).firstOrNull { position ->
            steps[position].parameters[ITEM_ID] == itemId
        } ?: -1
    }

    private fun findDirectBranchPositions(steps: List<ActionStep>, start: Int, end: Int): List<Int> {
        var menuDepth = 1
        val positions = mutableListOf<Int>()
        for (index in (start + 1) until end) {
            val behavior = ModuleRegistry.getModule(steps[index].moduleId)?.blockBehavior ?: continue
            if (behavior.pairingId != MENU_PAIRING_ID) continue
            when (behavior.type) {
                BlockType.BLOCK_START -> menuDepth++
                BlockType.BLOCK_END -> menuDepth--
                BlockType.BLOCK_MIDDLE -> if (menuDepth == 1 && steps[index].moduleId == MENU_ITEM_ID) {
                    positions += index
                }
                BlockType.NONE -> Unit
            }
        }
        return positions
    }

    private fun valueAsString(value: Any?): String? = when (value) {
        is VObject -> value.asString()
        else -> value?.toString()
    }
}

class ChooseFromMenuModule : BaseModule() {
    override val id = MENU_START_ID
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_menu_start_name,
        descriptionStringRes = R.string.module_vflow_logic_menu_start_desc,
        name = "从菜单中选取",
        description = "显示菜单，并执行所选项目对应的操作",
        iconRes = R.drawable.rounded_list_alt_check_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val blockBehavior = BlockBehavior(BlockType.BLOCK_START, MENU_PAIRING_ID)
    override val uiProvider: ModuleUIProvider = MenuEditorUiProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition("prompt", "提示", ParameterType.STRING, defaultValue = "请选择", acceptsMagicVariable = false, nameStringRes = R.string.param_vflow_logic_menu_start_prompt_name),
        InputDefinition(
            "items",
            "菜单项目",
            ParameterType.ANY,
            defaultValue = emptyList<Map<String, String>>(),
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            nameStringRes = R.string.param_vflow_logic_menu_start_items_name
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("selected_item", "所选项目", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_logic_menu_start_selected_item_name),
        OutputDefinition("selected_item_id", "所选项目 ID", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_logic_menu_start_selected_item_id_name)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val prompt = PillUtil.createPillFromParam(step.parameters["prompt"], getInputs().first())
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_logic_menu_prefix),
            prompt,
            context.getString(R.string.summary_vflow_logic_menu_suffix)
        )
    }

    override fun createSteps(): List<ActionStep> {
        val items = MenuBlockSupport.createItems(listOf("选项 1"))
        return listOf(
            ActionStep(MENU_START_ID, mapOf("prompt" to "请选择", "items" to MenuBlockSupport.toParameters(items))),
            ActionStep(MENU_ITEM_ID, mapOf("itemId" to items.first().id, "itemTitle" to items.first().title)),
            ActionStep(MENU_END_ID, emptyMap())
        )
    }

    override fun onStepDeleted(steps: MutableList<ActionStep>, position: Int): Boolean {
        val end = BlockNavigator.findEndBlockPosition(steps, position, MENU_PAIRING_ID)
        if (end == -1) return super.onStepDeleted(steps, position)
        for (index in end downTo position) steps.removeAt(index)
        return true
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        val items = MenuBlockSupport.readItems(step.parameters["items"])
        if (items.isEmpty()) return ValidationResult(false, "请至少添加一个菜单项目")
        if (items.any { it.title.isBlank() }) return ValidationResult(false, "菜单项目不能为空")
        if (items.map(MenuItem::title).toSet().size != items.size) return ValidationResult(false, "菜单项目不能重名")
        return ValidationResult(true)
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val items = MenuBlockSupport.readItems(context.getVariableAsRaw("items"))
        if (items.isEmpty()) return ExecutionResult.Failure("菜单为空", "菜单中没有可选择的项目")
        val uiService = context.services.get(ExecutionUIService::class)
            ?: return ExecutionResult.Failure("服务缺失", "无法显示菜单")
        val resolvedTitles = items.map { VariableResolver.resolve(it.title, context) }
        val selectedIndex = uiService.requestMenuChoice(context.getVariableAsString("prompt", "请选择"), resolvedTitles)
            ?: return skipMenu(context)
        val selectedItem = items.getOrNull(selectedIndex) ?: return skipMenu(context)
        val selectedTitle = resolvedTitles[selectedIndex]
        val branchPosition = MenuBlockSupport.findBranchPosition(context.allSteps, context.currentStepIndex, selectedItem.id)
        if (branchPosition == -1) return ExecutionResult.Failure("菜单结构错误", "找不到所选项目对应的操作块")
        val startStepId = context.allSteps[context.currentStepIndex].id
        context.stepOutputs[startStepId] = mapOf(
            "selected_item" to VString(selectedTitle),
            "selected_item_id" to VString(selectedItem.id)
        )
        onProgress(ProgressUpdate("已选择: $selectedTitle"))
        return ExecutionResult.Signal(ExecutionSignal.Jump(branchPosition + 1))
    }

    private fun skipMenu(context: ExecutionContext): ExecutionResult {
        val end = BlockNavigator.findEndBlockPosition(context.allSteps, context.currentStepIndex, MENU_PAIRING_ID)
        return if (end == -1) ExecutionResult.Failure("菜单结构错误", "找不到结束菜单块")
        else ExecutionResult.Signal(ExecutionSignal.Jump(end + 1))
    }
}

class MenuItemModule : BaseModule() {
    private val titleInput = InputDefinition(
        id = "itemTitle",
        name = "菜单项目",
        staticType = ParameterType.STRING,
        acceptsMagicVariable = true,
        acceptsNamedVariable = true
    )

    override val id = MENU_ITEM_ID
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_menu_item_name,
        descriptionStringRes = R.string.module_vflow_logic_menu_item_desc,
        name = "菜单项目",
        description = "所选菜单项目的操作",
        iconRes = R.drawable.rounded_list_alt_check_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val blockBehavior = BlockBehavior(BlockType.BLOCK_MIDDLE, MENU_PAIRING_ID)

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val title = step.parameters["itemTitle"] ?: return metadata.getLocalizedName(context)
        return PillUtil.buildSpannable(context, PillUtil.createPillFromParam(title, titleInput))
    }

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult {
        val end = BlockNavigator.findEndBlockPosition(context.allSteps, context.currentStepIndex, MENU_PAIRING_ID)
        return if (end == -1) ExecutionResult.Failure("菜单结构错误", "找不到结束菜单块")
        else ExecutionResult.Signal(ExecutionSignal.Jump(end + 1))
    }
}

class EndMenuModule : BaseModule() {
    override val id = MENU_END_ID
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_menu_end_name,
        descriptionStringRes = R.string.module_vflow_logic_menu_end_desc,
        name = "结束菜单",
        description = "菜单块的结束点",
        iconRes = R.drawable.rounded_list_alt_check_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val blockBehavior = BlockBehavior(BlockType.BLOCK_END, MENU_PAIRING_ID)
    override fun getSummary(context: Context, step: ActionStep): CharSequence = metadata.getLocalizedName(context)

    override suspend fun execute(context: ExecutionContext, onProgress: suspend (ProgressUpdate) -> Unit): ExecutionResult =
        ExecutionResult.Success()
}

private class MenuEditorViewHolder(
    view: android.view.View,
    val promptInput: TextInputEditText?,
    val items: MutableList<MenuItem>
) : CustomEditorViewHolder(view)

private class MenuEditorUiProvider : ModuleUIProvider {
    override fun getHandledInputIds(): Set<String> = setOf("prompt", "items")

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((inputId: String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((android.content.Intent, (Int, android.content.Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padding, 0, padding)
        }
        val prompt = StandardControlFactory.createTextInputLayout(
            context = context,
            isNumber = false,
            currentValue = currentParameters["prompt"] ?: "请选择",
            hint = context.getString(R.string.param_vflow_logic_menu_start_prompt_name)
        ).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = padding
                marginEnd = padding
            }
            editText?.doAfterTextChanged { onParametersChanged() }
        }
        val items = MenuBlockSupport.readItems(currentParameters["items"])
            .ifEmpty { MenuBlockSupport.createItems(listOf("选项 1")) }
            .toMutableList()
        val labels = items.map(MenuItem::title).toMutableList()
        val editorView = LayoutInflater.from(context).inflate(R.layout.partial_list_editor, root, false)
        editorView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = padding
            marginEnd = padding
        }
        val adapter = ListItemAdapter(
            labels,
            allSteps,
            showMagicControls = true,
            onItemAdded = { position -> items.add(position, MenuItem(UUID.randomUUID().toString(), "")) },
            onItemRemoved = { position -> items.removeAt(position) },
            onItemChanged = { position, title -> items[position] = items[position].copy(title = title) },
            onMagicClick = { position ->
                onMagicVariableRequested?.invoke("items.$position.itemTitle")
            }
        )
        editorView.findViewById<RecyclerView>(R.id.recycler_view_list).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        editorView.findViewById<android.widget.Button>(R.id.button_add_list_item).setOnClickListener {
            adapter.addItem()
            onParametersChanged()
        }
        root.addView(prompt)
        root.addView(editorView)
        return MenuEditorViewHolder(root, prompt.editText as? TextInputEditText, items)
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val menuHolder = holder as MenuEditorViewHolder
        val items = menuHolder.items.map { it.copy(title = it.title.trim()) }
        return mapOf(
            "prompt" to (menuHolder.promptInput?.text?.toString()?.trim().orEmpty()),
            "items" to MenuBlockSupport.toParameters(items)
        )
    }
}
