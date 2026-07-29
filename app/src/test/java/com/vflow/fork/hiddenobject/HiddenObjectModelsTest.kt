package com.vflow.fork.hiddenobject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class HiddenObjectModelsTest {
    @Test
    fun `标准名称归一化后精确匹配且不会模糊匹配`() {
        val item = HiddenObjectItem(name = "木人桩")
        val template = HiddenObjectTemplate(items = listOf(item))
        val result = HiddenObjectNameMatcher.match(listOf(" 木 人 桩！", "木人柱"), template)
        assertEquals(listOf(item.id), result.items.map { it.id })
        assertEquals(listOf("木人柱"), result.unmatchedTexts)
    }

    @Test
    fun `重复OCR结果只返回一个物品`() {
        val item = HiddenObjectItem(name = "钥匙")
        val result = HiddenObjectNameMatcher.match(
            listOf("钥匙", "钥匙"),
            HiddenObjectTemplate(items = listOf(item)),
        )
        assertEquals(1, result.items.size)
    }

    @Test
    fun `视觉匹配只把白色名称视为未完成物品`() {
        val white = HiddenObjectItem(name = "钥匙")
        val gray = HiddenObjectItem(name = "木人桩")
        val result = HiddenObjectNameMatcher.matchVisual(
            labels = listOf(
                HiddenObjectRecognizedLabel("海螺", 214),
                HiddenObjectRecognizedLabel("长椅", 72, hasStrikeThrough = true),
            ),
            template = HiddenObjectTemplate(
                items = listOf(
                    white.copy(name = "海螺"),
                    gray.copy(name = "长椅"),
                ),
            ),
        )

        assertEquals(listOf(white.id), result.activeItems.map { it.id })
        assertEquals(listOf(gray.id), result.completedItems.map { it.id })
    }

    @Test
    fun `同批名称存在明显亮度差时自适应区分白色和灰色`() {
        assertEquals(
            listOf(true, true, false, false),
            HiddenObjectLabelAppearance.activeMask(listOf(218, 224, 172, 168)),
        )
    }

    @Test
    fun `带删除线的名称即使偏亮也视为已完成`() {
        val item = HiddenObjectItem(name = "钥匙")
        val result = HiddenObjectNameMatcher.matchVisual(
            labels = listOf(HiddenObjectRecognizedLabel("钥匙", 220, hasStrikeThrough = true)),
            template = HiddenObjectTemplate(items = listOf(item)),
        )

        assertTrue(result.activeItems.isEmpty())
        assertEquals(listOf(item.id), result.completedItems.map { it.id })
    }

    @Test
    fun `旧模板中的OCR别名可读取但不再参与识别`() {
        val dir = Files.createTempDirectory("hidden-object-legacy-alias-test").toFile()
        val repository = HiddenObjectTemplateRepository.forTests(dir)
        val imported = repository.importJson(
            """
            {
              "schemaVersion": 1,
              "id": "legacy",
              "name": "旧模板",
              "referenceWidth": 100,
              "referenceHeight": 100,
              "referenceImagePath": "reference_image.png",
              "items": [
                {
                  "id": "item-1",
                  "name": "钥匙",
                  "aliases": ["锁匙"],
                  "normalizedX": 0.5,
                  "normalizedY": 0.5,
                  "enabled": true
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("钥匙"), HiddenObjectNameMatcher.match(listOf("钥匙"), imported).items.map { it.name })
        assertTrue(HiddenObjectNameMatcher.match(listOf("锁匙"), imported).items.isEmpty())
    }

    @Test
    fun `归一化场景坐标映射到不同分辨率`() {
        val template = HiddenObjectTemplate(
            sceneRegion = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
        )
        assertEquals(500 to 1000, template.clickPoint(HiddenObjectItem(normalizedX = 0.5f, normalizedY = 0.5f), 1000, 2000))
        assertEquals(250 to 500, template.clickPoint(HiddenObjectItem(normalizedX = 0.5f, normalizedY = 0.5f), 500, 1000))
    }

    @Test
    fun `模板仓库以包含配置和截图的压缩包导入导出`() {
        val dir = Files.createTempDirectory("hidden-object-test").toFile()
        val repository = HiddenObjectTemplateRepository.forTests(dir)
        val source = HiddenObjectTemplate(name = "关卡1", referenceWidth = 100, referenceHeight = 200)
        val image = repository.referenceImageFile(source.id).apply { writeBytes(TEST_PNG_BYTES) }
        val saved = repository.save(source.copy(referenceImagePath = image.absolutePath))
        assertNotNull(repository.get(saved.id))
        assertEquals(2, listOfNotNull(saved, repository.duplicate(saved.id)).size)

        val packageBytes = ByteArrayOutputStream().also { repository.exportPackage(saved.id, it) }.toByteArray()
        val entries = unzip(packageBytes)
        assertEquals(setOf("template.json", "reference_image.png"), entries.keys)
        assertTrue(entries.getValue("template.json").toString(Charsets.UTF_8).contains("\"referenceImagePath\": \"reference_image.png\""))
        assertTrue(entries.getValue("reference_image.png").contentEquals(TEST_PNG_BYTES))

        val imported = repository.importPackage(ByteArrayInputStream(packageBytes))
        assertTrue(imported.id != saved.id)
        assertTrue(java.io.File(requireNotNull(imported.referenceImagePath)).readBytes().contentEquals(TEST_PNG_BYTES))
        assertEquals(3, repository.list().size)
    }

    @Test(expected = IllegalStateException::class)
    fun `导出缺少参考截图的模板会失败`() {
        val dir = Files.createTempDirectory("hidden-object-export-without-image-test").toFile()
        val repository = HiddenObjectTemplateRepository.forTests(dir)
        val saved = repository.save(HiddenObjectTemplate(name = "无截图模板"))

        repository.exportPackage(saved.id, ByteArrayOutputStream())
    }

    @Test
    fun `复制模板时参考图独立保存`() {
        val dir = Files.createTempDirectory("hidden-object-image-test").toFile()
        val imageCache = java.io.File(dir.parentFile, "${dir.name}-cache")
        val repository = HiddenObjectTemplateRepository.forTests(dir, imageCache)
        val source = HiddenObjectTemplate(name = "关卡", referenceWidth = 10, referenceHeight = 10)
        val sourceImage = repository.referenceImageFile(source.id).apply { writeText("image") }
        assertEquals(imageCache.canonicalFile, requireNotNull(sourceImage.parentFile).canonicalFile)
        repository.save(source.copy(referenceImagePath = sourceImage.absolutePath))
        val copied = repository.duplicate(source.id)!!
        assertTrue(copied.referenceImagePath != sourceImage.absolutePath)
        val copiedImage = java.io.File(copied.referenceImagePath!!)
        assertTrue(copiedImage.isFile)
        repository.delete(copied.id)
        assertFalse(copiedImage.exists())
        assertTrue(sourceImage.isFile)
    }

    @Test
    fun `读取旧模板时将参考图迁移到缓存目录`() {
        val dir = Files.createTempDirectory("hidden-object-migration-test").toFile()
        val imageCache = java.io.File(dir.parentFile, "${dir.name}-cache")
        val repository = HiddenObjectTemplateRepository.forTests(dir, imageCache)
        val template = HiddenObjectTemplate(name = "旧关卡", referenceWidth = 10, referenceHeight = 10)
        val legacyImage = java.io.File(dir.parentFile, "legacy.png").apply { writeText("image") }
        repository.save(template.copy(referenceImagePath = legacyImage.absolutePath))

        val migrated = repository.get(template.id)!!

        val migratedImage = java.io.File(requireNotNull(migrated.referenceImagePath))
        assertEquals(imageCache.canonicalFile, requireNotNull(migratedImage.parentFile).canonicalFile)
        assertTrue(migratedImage.isFile)
        assertFalse(legacyImage.exists())
    }

    @Test
    fun `每次执行前清理旧的自动寻物缓存目录`() {
        val root = Files.createTempDirectory("hidden-object-cache-test").toFile()
        val stale = java.io.File(root, "stale").apply { mkdirs() }
        java.io.File(stale, "capture.jpg").writeText("data")
        val runDir = HiddenObjectRunCache.prepareRoot(root)
        assertFalse(stale.exists())
        assertTrue(runDir.isDirectory)
        HiddenObjectRunCache.cleanup(runDir)
        assertFalse(runDir.exists())
    }

    private fun HiddenObjectTemplateRepository.importJson(json: String): HiddenObjectTemplate =
        importPackage(ByteArrayInputStream(createPackage(json, TEST_PNG_BYTES)))

    private fun createPackage(json: String, image: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("template.json"))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("reference_image.png"))
                zip.write(image)
                zip.closeEntry()
            }
        }.toByteArray()

    private fun unzip(packageBytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    companion object {
        private val TEST_PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00,
        )
    }
}
