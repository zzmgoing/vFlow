package com.vflow.fork.hiddenobject

import android.content.Context
import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HiddenObjectTemplateRepository private constructor(
    private val rootDir: File,
    private val imageDir: File,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    constructor(context: Context) : this(
        rootDir = File(context.applicationContext.filesDir, "fork_hidden_object/templates"),
        imageDir = File(context.applicationContext.cacheDir, "fork_hidden_object/template_images"),
    )

    init {
        rootDir.mkdirs()
        imageDir.mkdirs()
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
        if (!SAFE_ID.matches(id)) return false
        val template = get(id)
        template?.referenceImagePath?.let { path ->
            val image = File(path)
            if (image.isInside(imageDir) || image.isInside(requireNotNull(rootDir.parentFile))) image.delete()
        }
        referenceImageFile(id).delete()
        return File(rootDir, "$id.json").delete()
    }

    fun exportPackage(id: String, output: OutputStream) {
        val template = requireNotNull(get(id)) { "模板不存在" }
        val image = template.referenceImagePath?.let(::File)?.takeIf(File::isFile)
            ?: error("模板缺少参考截图")
        val imageExtension = detectImageExtension(image)
            ?: error("不支持的参考截图格式")
        val imageEntryName = "$IMAGE_ENTRY_PREFIX.$imageExtension"
        val portableTemplate = template.copy(referenceImagePath = imageEntryName)

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(CONFIG_ENTRY_NAME))
            zip.write(gson.toJson(portableTemplate).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(imageEntryName))
            image.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    fun importPackage(input: InputStream): HiddenObjectTemplate {
        val newId = UUID.randomUUID().toString()
        val temporaryImage = File(imageDir, "$newId.import.tmp")
        val targetImage = referenceImageFile(newId)
        var configJson: String? = null
        var imageEntryName: String? = null

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "模板包不能包含目录" }
                    when {
                        entry.name == CONFIG_ENTRY_NAME -> {
                            require(configJson == null) { "模板包包含重复配置文件" }
                            configJson = readEntryText(zip, MAX_CONFIG_BYTES)
                        }
                        isImageEntry(entry.name) -> {
                            require(imageEntryName == null) { "模板包只能包含一张参考截图" }
                            copyEntry(zip, temporaryImage, MAX_IMAGE_BYTES)
                            imageEntryName = entry.name
                        }
                        else -> error("模板包包含未知文件：${entry.name}")
                    }
                    zip.closeEntry()
                }
            }

            val json = requireNotNull(configJson) { "模板包缺少 $CONFIG_ENTRY_NAME" }
            val packagedImageName = requireNotNull(imageEntryName) { "模板包缺少参考截图" }
            val parsed = gson.fromJson(json, HiddenObjectTemplate::class.java)
            require(parsed.schemaVersion in 1..HiddenObjectTemplate.CURRENT_SCHEMA_VERSION) { "不支持的模板版本" }
            require(parsed.referenceImagePath == packagedImageName) { "配置中的参考截图与模板包不一致" }
            require(detectImageExtension(temporaryImage) != null) { "模板包中的参考截图格式无效" }

            check(temporaryImage.renameTo(targetImage) || run {
                temporaryImage.copyTo(targetImage, overwrite = true)
                temporaryImage.delete()
                true
            })
            return save(
                parsed.copy(
                    id = newId,
                    referenceImagePath = targetImage.absolutePath,
                )
            )
        } catch (error: Throwable) {
            temporaryImage.delete()
            targetImage.delete()
            throw error
        }
    }

    fun referenceImageFile(templateId: String): File {
        require(SAFE_ID.matches(templateId)) { "无效模板 ID" }
        imageDir.mkdirs()
        return File(imageDir, "$templateId.png")
    }

    private fun readFile(file: File): HiddenObjectTemplate {
        val value = gson.fromJson(file.readText(Charsets.UTF_8), HiddenObjectTemplate::class.java)
        require(value.schemaVersion in 1..HiddenObjectTemplate.CURRENT_SCHEMA_VERSION) { "不支持的模板版本" }
        return migrateReferenceImage(value)
    }

    private fun migrateReferenceImage(template: HiddenObjectTemplate): HiddenObjectTemplate {
        val source = template.referenceImagePath?.let(::File)?.takeIf(File::isFile) ?: return template
        val target = referenceImageFile(template.id)
        if (source.canonicalFile == target.canonicalFile) return template

        source.copyTo(target, overwrite = true)
        val migrated = save(template.copy(referenceImagePath = target.absolutePath))
        source.delete()
        return migrated
    }

    private fun File.isInside(directory: File): Boolean {
        val parentPath = directory.canonicalFile.toPath()
        return canonicalFile.toPath().startsWith(parentPath)
    }

    companion object {
        private const val CONFIG_ENTRY_NAME = "template.json"
        private const val IMAGE_ENTRY_PREFIX = "reference_image"
        private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 50 * 1024 * 1024
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val SAFE_IMAGE_ENTRY = Regex("""reference_image\.(png|jpg|jpeg|webp)""")

        fun forTests(
            rootDir: File,
            imageDir: File = File(requireNotNull(rootDir.parentFile), "template_images_cache"),
        ): HiddenObjectTemplateRepository = HiddenObjectTemplateRepository(rootDir, imageDir)
    }

    private fun isImageEntry(name: String): Boolean = SAFE_IMAGE_ENTRY.matches(name)

    private fun readEntryText(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        copyEntry(input, output, maxBytes)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun copyEntry(input: InputStream, target: File, maxBytes: Int) {
        target.outputStream().buffered().use { output -> copyEntry(input, output, maxBytes) }
    }

    private fun copyEntry(input: InputStream, output: OutputStream, maxBytes: Int) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "模板包中的文件过大" }
            output.write(buffer, 0, count)
        }
    }

    private fun detectImageExtension(file: File): String? {
        if (!file.isFile) return null
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        if (count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return "png"
        if (count >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) return "jpg"
        if (
            count >= 12 &&
            header.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
            header.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
        ) return "webp"
        return null
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
}
