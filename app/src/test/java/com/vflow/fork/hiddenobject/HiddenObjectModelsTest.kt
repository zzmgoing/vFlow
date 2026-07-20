package com.vflow.fork.hiddenobject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HiddenObjectModelsTest {
    @Test
    fun `名称归一化和别名精确匹配且不会模糊匹配`() {
        val item = HiddenObjectItem(name = "木人桩", aliases = listOf("木 人 桩！"))
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
    fun `归一化场景坐标映射到不同分辨率`() {
        val template = HiddenObjectTemplate(
            sceneRegion = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
        )
        assertEquals(500 to 1000, template.clickPoint(HiddenObjectItem(normalizedX = 0.5f, normalizedY = 0.5f), 1000, 2000))
        assertEquals(250 to 500, template.clickPoint(HiddenObjectItem(normalizedX = 0.5f, normalizedY = 0.5f), 500, 1000))
    }

    @Test
    fun `轮次跟踪不重复点击并能识别新轮次和空闲结束`() {
        val tracker = HiddenObjectRoundTracker(idleFinishMs = 3000)
        assertEquals(setOf("a", "b"), tracker.observe(setOf("a", "b"), 0))
        tracker.markClicked("a", 10)
        tracker.markClicked("b", 20)
        assertTrue(tracker.observe(setOf("a", "b"), 100).isEmpty())
        assertFalse(tracker.shouldFinish(3019))
        assertTrue(tracker.shouldFinish(3020))
        assertEquals(setOf("b", "c"), tracker.observe(setOf("b", "c"), 4000))
        assertEquals(2, tracker.roundCount)
    }

    @Test
    fun `同一轮OCR逐步补全目标时不重复已点击项目`() {
        val tracker = HiddenObjectRoundTracker(idleFinishMs = 3000)
        assertEquals(setOf("a"), tracker.observe(setOf("a"), 0))
        tracker.markClicked("a", 10)
        assertEquals(setOf("b"), tracker.observe(setOf("a", "b"), 100))
        assertEquals(1, tracker.roundCount)
        assertEquals(1, tracker.clickCount)
    }

    @Test
    fun `模板仓库支持保存复制导入导出`() {
        val dir = Files.createTempDirectory("hidden-object-test").toFile()
        val repository = HiddenObjectTemplateRepository.forTests(dir)
        val saved = repository.save(HiddenObjectTemplate(name = "关卡1", referenceWidth = 100, referenceHeight = 200))
        assertNotNull(repository.get(saved.id))
        assertEquals(2, listOfNotNull(saved, repository.duplicate(saved.id)).size)
        val imported = repository.importJson(repository.exportJson(saved.id)!!)
        assertTrue(imported.id != saved.id)
        assertEquals(3, repository.list().size)
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
}
