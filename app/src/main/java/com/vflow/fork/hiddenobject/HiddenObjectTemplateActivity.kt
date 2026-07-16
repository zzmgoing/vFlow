package com.vflow.fork.hiddenobject

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme
import com.chaomixian.vflow.ui.overlay.ScreenCaptureOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class HiddenObjectTemplateActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                HiddenObjectTemplateRoute(
                    initialEditId = intent.getStringExtra(EXTRA_EDIT_ID),
                    selectMode = intent.getBooleanExtra(EXTRA_SELECT_MODE, false),
                    onSelected = { id ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SELECTED_ID, id))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SELECT_MODE = "select_mode"
        const val EXTRA_EDIT_ID = "edit_id"
        const val EXTRA_SELECTED_ID = "selected_id"
    }
}

private data class ItemDraft(
    val itemId: String? = null,
    val name: String = "",
    val aliases: String = "",
    val x: String = "0.5",
    val y: String = "0.5",
    val selectPointAfterSave: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenObjectTemplateRoute(
    initialEditId: String?,
    selectMode: Boolean,
    onSelected: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { HiddenObjectTemplateRepository(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var refreshToken by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(initialEditId?.let(repository::get)) }
    var pendingExport by remember { mutableStateOf<HiddenObjectTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<HiddenObjectTemplate?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { repository.importJson(it.readText()) }
        }.onSuccess {
            refreshToken++
            scope.launch { snackbar.showSnackbar("模板已导入") }
        }.onFailure { scope.launch { snackbar.showSnackbar("导入失败：${it.message}") } }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val template = pendingExport
        pendingExport = null
        if (uri != null && template != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(repository.exportJson(template.id)) }
            }.onSuccess { scope.launch { snackbar.showSnackbar("模板已导出") } }
                .onFailure { scope.launch { snackbar.showSnackbar("导出失败：${it.message}") } }
        }
    }

    fun export(template: HiddenObjectTemplate) {
        pendingExport = template
        exportLauncher.launch("${template.name}.json")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            editing != null -> "编辑寻物模板"
                            selectMode -> "选择关卡模板"
                            else -> "寻物关卡模板"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (editing != null) editing = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (editing == null) {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("导入")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (editing == null) {
            TemplateListScreen(
                modifier = Modifier.padding(padding),
                templates = remember(refreshToken) { repository.list() },
                selectMode = selectMode,
                onCreate = { editing = repository.create() },
                onSelect = onSelected,
                onEdit = { editing = it },
                onDuplicate = { repository.duplicate(it.id); refreshToken++ },
                onExport = ::export,
                onDelete = { deleteTarget = it },
            )
        } else {
            TemplateEditorScreen(
                modifier = Modifier.padding(padding),
                initial = requireNotNull(editing),
                repository = repository,
                snackbar = snackbar,
                onChanged = { editing = it },
                onSaved = { editing = it; refreshToken++ },
                onExport = { value ->
                    val saved = repository.save(value)
                    editing = saved
                    export(saved)
                },
            )
        }
    }

    deleteTarget?.let { template ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模板") },
            text = { Text("确定删除“${template.name}”吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(template.id)
                    deleteTarget = null
                    refreshToken++
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TemplateListScreen(
    modifier: Modifier,
    templates: List<HiddenObjectTemplate>,
    selectMode: Boolean,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (HiddenObjectTemplate) -> Unit,
    onDuplicate: (HiddenObjectTemplate) -> Unit,
    onExport: (HiddenObjectTemplate) -> Unit,
    onDelete: (HiddenObjectTemplate) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = if (selectMode) "选择一个已录入模板用于自动寻物。" else "提前录入每个关卡的名称区域和物品坐标，运行时即可离线识别并点击。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            FilledTonalButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("新建关卡模板")
            }
        }
        if (templates.isEmpty()) {
            item { EmptyTemplateCard() }
        } else {
            items(templates, key = { it.id }) { template ->
                TemplateCard(
                    template = template,
                    selectMode = selectMode,
                    onClick = { if (selectMode) onSelect(template.id) else onEdit(template) },
                    onEdit = { onEdit(template) },
                    onDuplicate = { onDuplicate(template) },
                    onExport = { onExport(template) },
                    onDelete = { onDelete(template) },
                )
            }
        }
    }
}

@Composable
private fun EmptyTemplateCard() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("还没有关卡模板", style = MaterialTheme.typography.titleMedium)
            Text("点击上方按钮开始录入第一个关卡", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TemplateCard(
    template: HiddenObjectTemplate,
    selectMode: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = template.items.size.toString(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(template.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${template.items.size} 个物品 · ${template.referenceWidth} × ${template.referenceHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectMode) {
                Text("选择", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多操作") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuExpanded = false; onEdit() })
                        DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menuExpanded = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("导出 JSON") }, onClick = { menuExpanded = false; onExport() })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateEditorScreen(
    modifier: Modifier,
    initial: HiddenObjectTemplate,
    repository: HiddenObjectTemplateRepository,
    snackbar: SnackbarHostState,
    onChanged: (HiddenObjectTemplate) -> Unit,
    onSaved: (HiddenObjectTemplate) -> Unit,
    onExport: (HiddenObjectTemplate) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as HiddenObjectTemplateActivity
    val scope = rememberCoroutineScope()
    var template by remember(initial.id) { mutableStateOf(initial) }
    var canvasView by remember { mutableStateOf<HiddenObjectTemplateCanvasView?>(null) }
    var itemDraft by remember { mutableStateOf<ItemDraft?>(null) }
    var pendingPoint by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    var bitmap by remember(initial.id) {
        mutableStateOf(initial.referenceImagePath?.let(BitmapFactory::decodeFile))
    }

    DisposableEffect(bitmap) {
        val ownedBitmap = bitmap
        onDispose {
            if (canvasView?.bitmap === ownedBitmap) {
                canvasView?.bitmap = null
            }
            ownedBitmap?.takeUnless { it.isRecycled }?.recycle()
        }
    }
    LaunchedEffect(template) { onChanged(template) }

    fun showMessage(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun update(value: HiddenObjectTemplate) { template = value; canvasView?.template = value }
    fun save() {
        val errors = HiddenObjectNameMatcher.validationErrors(template)
        if (errors.isNotEmpty()) showMessage(errors.joinToString("；"))
        else {
            val saved = repository.save(template)
            update(saved)
            onSaved(saved)
            showMessage("模板已保存")
        }
    }
    fun storeImage(uri: Uri) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = repository.referenceImageFile(template.id)
                    context.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(target).use(input::copyTo) }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(target.absolutePath, options)
                    check(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
                    template.copy(referenceWidth = options.outWidth, referenceHeight = options.outHeight, referenceImagePath = target.absolutePath)
                }
            }.onSuccess { value ->
                bitmap = BitmapFactory.decodeFile(value.referenceImagePath)
                update(repository.save(value))
            }.onFailure { showMessage("图片保存失败：${it.message}") }
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::storeImage) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = "基本信息", description = "名称用于在工作流中识别这个关卡模板。") {
                OutlinedTextField(
                    value = template.name,
                    onValueChange = { update(template.copy(name = it)) },
                    label = { Text("模板名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SectionCard(title = "参考画面", description = "支持双指缩放和拖动平移。绿色为场景，青色为名称区，带序号圆圈为物品坐标。") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    if (bitmap == null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("尚未设置参考截图", style = MaterialTheme.typography.titleMedium)
                            Text("建议截取完整游戏画面", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        AndroidView(
                            factory = { ctx -> HiddenObjectTemplateCanvasView(ctx).also { canvasView = it } },
                            update = { view ->
                                view.bitmap = bitmap
                                view.template = template
                                view.onRegionSelected = { mode, region ->
                                    update(
                                        when (mode) {
                                            HiddenObjectTemplateCanvasView.Mode.SCENE_REGION -> template.copy(sceneRegion = region)
                                            HiddenObjectTemplateCanvasView.Mode.LABEL_REGION -> template.copy(labelRegion = region)
                                            else -> template
                                        }
                                    )
                                }
                                view.onItemPointSelected = pointSelected@{ x, y ->
                                    val pending = pendingPoint ?: return@pointSelected
                                    pendingPoint = null
                                    update(template.copy(items = template.items + HiddenObjectItem(name = pending.first, aliases = pending.second, normalizedX = x, normalizedY = y)))
                                    showMessage("已添加 ${pending.first}")
                                }
                                view.onItemPointChanged = { itemId, x, y ->
                                    update(
                                        template.copy(
                                            items = template.items.map { item ->
                                                if (item.id == itemId) item.copy(normalizedX = x, normalizedY = y) else item
                                            },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(420.dp),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { canvasView?.zoomBy(0.75f) },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("缩小") }
                    OutlinedButton(
                        onClick = { canvasView?.resetViewport() },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("适应") }
                    OutlinedButton(
                        onClick = { canvasView?.zoomBy(1.5f) },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("放大") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                runCatching { ScreenCaptureOverlay(activity, StorageManager.tempDir).captureAndCrop() }
                                    .onSuccess { it?.let(::storeImage) }
                                    .onFailure { showMessage("截图失败：${it.message}") }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("悬浮截图") }
                    OutlinedButton(onClick = { imageLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("从相册选择") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            canvasView?.mode = HiddenObjectTemplateCanvasView.Mode.SCENE_REGION
                            showMessage("拖动框内部可移动，拖动控制点可缩放，框外拖动可重新框选")
                        },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("框选场景") }
                    OutlinedButton(
                        onClick = {
                            canvasView?.mode = HiddenObjectTemplateCanvasView.Mode.LABEL_REGION
                            showMessage("拖动框内部可移动，拖动控制点可缩放，框外拖动可重新框选")
                        },
                        enabled = bitmap != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("框选名称区") }
                }
            }
        }
        item {
            SectionCard(title = "物品坐标", description = "双指缩放参考画面，拖动空白处平移；可直接拖动带序号圆圈精确修改位置。坐标取圆圈中心点。") {
                FilledTonalButton(
                    onClick = { itemDraft = ItemDraft(selectPointAfterSave = true) },
                    enabled = bitmap != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("添加并点选物品")
                }
                if (template.items.isEmpty()) {
                    Text("暂无物品，请从当前轮次开始逐个录入。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        template.items.forEachIndexed { index, item ->
                            ItemCard(
                                sequence = index + 1,
                                item = item,
                                onEdit = { itemDraft = ItemDraft(item.id, item.name, item.aliases.joinToString("，"), item.normalizedX.toString(), item.normalizedY.toString()) },
                                onDelete = { update(template.copy(items = template.items.filterNot { value -> value.id == item.id })) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = ::save, modifier = Modifier.weight(1f)) { Text("保存模板") }
                OutlinedButton(onClick = { onExport(template) }, modifier = Modifier.weight(1f)) { Text("导出 JSON") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    itemDraft?.let { draft ->
        ItemEditorDialog(
            initial = draft,
            onDismiss = { itemDraft = null },
            onConfirm = { changed ->
                itemDraft = null
                val aliases = changed.aliases.split(',', '，').map(String::trim).filter(String::isNotBlank)
                if (changed.selectPointAfterSave) {
                    pendingPoint = changed.name.trim() to aliases
                    canvasView?.mode = HiddenObjectTemplateCanvasView.Mode.ITEM_POINT
                    showMessage("请点击场景中的 ${changed.name.trim()}")
                } else {
                    val original = template.items.first { it.id == changed.itemId }
                    val edited = original.copy(
                        name = changed.name.trim(), aliases = aliases,
                        normalizedX = (changed.x.toFloatOrNull() ?: original.normalizedX).coerceIn(0f, 1f),
                        normalizedY = (changed.y.toFloatOrNull() ?: original.normalizedY).coerceIn(0f, 1f),
                    )
                    update(template.copy(items = template.items.map { if (it.id == edited.id) edited else it }))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ItemCard(sequence: Int, item: HiddenObjectItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(sequence.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "坐标 ${"%.3f".format(item.normalizedX)}, ${"%.3f".format(item.normalizedY)}${if (item.aliases.isEmpty()) "" else " · ${item.aliases.size} 个别名"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑物品") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, contentDescription = "删除物品", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ItemEditorDialog(initial: ItemDraft, onDismiss: () -> Unit, onConfirm: (ItemDraft) -> Unit) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.selectPointAfterSave) "添加物品" else "编辑物品") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("标准名称") }, singleLine = true)
                OutlinedTextField(draft.aliases, { draft = draft.copy(aliases = it) }, label = { Text("OCR 别名（逗号分隔）") }, singleLine = true)
                if (!initial.selectPointAfterSave) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(draft.x, { draft = draft.copy(x = it) }, label = { Text("归一化 X") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(draft.y, { draft = draft.copy(y = it) }, label = { Text("归一化 Y") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    Text("确认后请在参考画面中点击物品位置。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }, enabled = draft.name.isNotBlank()) { Text(if (initial.selectPointAfterSave) "下一步" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
