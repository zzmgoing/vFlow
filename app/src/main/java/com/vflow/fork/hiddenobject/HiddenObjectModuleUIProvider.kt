package com.vflow.fork.hiddenobject

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class HiddenObjectModuleUIProvider : ModuleUIProvider {
    private class Holder(
        view: View,
        val card: MaterialCardView,
        val name: TextView,
        val summary: TextView,
        val selectButton: MaterialButton,
        val manageButton: MaterialButton,
    ) : CustomEditorViewHolder(view) {
        var templateId: String = ""
    }

    override fun getHandledInputIds(): Set<String> = setOf("template_id")

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?,
    ): CustomEditorViewHolder {
        val root = LayoutInflater.from(context).inflate(R.layout.partial_fork_hidden_object_editor, parent, false)
        val holder = Holder(
            root,
            root.findViewById(R.id.fork_hidden_object_template_card),
            root.findViewById(R.id.fork_hidden_object_template_name),
            root.findViewById(R.id.fork_hidden_object_template_summary),
            root.findViewById(R.id.fork_hidden_object_select_button),
            root.findViewById(R.id.fork_hidden_object_manage_button),
        )
        holder.templateId = currentParameters["template_id"] as? String ?: ""
        updateState(context, holder)

        val selectTemplate = {
            if (onStartActivityForResult == null) {
                Toast.makeText(context, R.string.fork_hidden_object_editor_picker_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(context, HiddenObjectTemplateActivity::class.java)
                    .putExtra(HiddenObjectTemplateActivity.EXTRA_SELECT_MODE, true)
                onStartActivityForResult(intent) { resultCode, data ->
                    if (resultCode == Activity.RESULT_OK) {
                        holder.templateId = data?.getStringExtra(HiddenObjectTemplateActivity.EXTRA_SELECTED_ID).orEmpty()
                        updateState(context, holder)
                        onParametersChanged()
                    }
                }
            }
        }
        holder.card.setOnClickListener { selectTemplate() }
        holder.selectButton.setOnClickListener { selectTemplate() }
        holder.manageButton.setOnClickListener {
            if (onStartActivityForResult != null) {
                onStartActivityForResult(Intent(context, HiddenObjectTemplateActivity::class.java)) { _, _ ->
                    updateState(context, holder)
                }
            }
        }
        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> =
        mapOf("template_id" to (holder as Holder).templateId)

    private fun updateState(context: Context, holder: Holder) {
        val template = holder.templateId.takeIf(String::isNotBlank)
            ?.let { HiddenObjectTemplateRepository(context).get(it) }
        if (template == null) {
            holder.name.setText(R.string.fork_hidden_object_editor_unselected_title)
            holder.summary.setText(R.string.fork_hidden_object_editor_unselected_summary)
            holder.selectButton.setText(R.string.fork_hidden_object_editor_select_button)
        } else {
            holder.name.text = template.name
            holder.summary.text = context.getString(
                R.string.fork_hidden_object_editor_template_summary,
                template.items.size,
                template.referenceWidth,
                template.referenceHeight,
            )
            holder.selectButton.setText(R.string.fork_hidden_object_editor_change_button)
        }
    }
}
