package com.vflow.fork.hiddenobject

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.File
import java.util.UUID

class HiddenObjectTemplateRepository private constructor(private val rootDir: File) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "fork_hidden_object/templates"))

    init {
        rootDir.mkdirs()
    }

    fun list(): List<HiddenObjectTemplate> = rootDir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { runCatching { readFile(it) }.getOrNull() }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    fun get(id: String): HiddenObjectTemplate? {
        if (!SAFE_ID.matches(id)) return null
        val file = File(rootDir, "$id.json")
        return if (file.isFile) runCatching { readFile(file) }.getOrNull() else null
    }

    @Synchronized
    fun save(template: HiddenObjectTemplate): HiddenObjectTemplate {
        require(SAFE_ID.matches(template.id)) { "无效模板 ID" }
        val upgraded = template.copy(schemaVersion = HiddenObjectTemplate.CURRENT_SCHEMA_VERSION)
        val target = File(rootDir, "${upgraded.id}.json")
        val temporary = File(rootDir, "${upgraded.id}.json.tmp")
        temporary.writeText(gson.toJson(upgraded), Charsets.UTF_8)
        check(temporary.renameTo(target) || run { temporary.copyTo(target, overwrite = true); temporary.delete(); true })
        return upgraded
    }

    fun create(name: String = "新建关卡"): HiddenObjectTemplate = save(HiddenObjectTemplate(name = name))

    fun duplicate(id: String): HiddenObjectTemplate? = get(id)?.let { source ->
        val newId = UUID.randomUUID().toString()
        val copiedImagePath = source.referenceImagePath?.let { sourcePath ->
            val sourceImage = File(sourcePath)
            if (sourceImage.isFile) {
                val targetImage = referenceImageFile(newId)
                sourceImage.copyTo(targetImage, overwrite = true)
                targetImage.absolutePath
            } else null
        }
        save(source.copy(id = newId, name = "${source.name} 副本", referenceImagePath = copiedImagePath))
    }

    fun delete(id: String): Boolean {
        val template = get(id)
        template?.referenceImagePath?.let { path ->
            val image = File(path)
            if (image.canonicalPath.startsWith(requireNotNull(rootDir.parentFile).canonicalPath)) image.delete()
        }
        return SAFE_ID.matches(id) && File(rootDir, "$id.json").delete()
    }

    fun exportJson(id: String): String? = get(id)?.let(gson::toJson)

    fun importJson(json: String): HiddenObjectTemplate {
        val parsed = gson.fromJson(json, HiddenObjectTemplate::class.java)
        require(parsed.schemaVersion in 1..HiddenObjectTemplate.CURRENT_SCHEMA_VERSION) { "不支持的模板版本" }
        val imported = parsed.copy(id = UUID.randomUUID().toString(), referenceImagePath = null)
        return save(imported)
    }

    fun referenceImageFile(templateId: String): File {
        val imageDir = File(rootDir.parentFile, "images").apply { mkdirs() }
        return File(imageDir, "$templateId.png")
    }

    private fun readFile(file: File): HiddenObjectTemplate {
        val value = gson.fromJson(file.readText(Charsets.UTF_8), HiddenObjectTemplate::class.java)
        require(value.schemaVersion in 1..HiddenObjectTemplate.CURRENT_SCHEMA_VERSION) { "不支持的模板版本" }
        return value
    }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")

        fun forTests(rootDir: File): HiddenObjectTemplateRepository = HiddenObjectTemplateRepository(rootDir)
    }
}
