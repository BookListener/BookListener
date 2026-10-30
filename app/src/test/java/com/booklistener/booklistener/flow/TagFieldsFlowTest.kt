package com.booklistener.booklistener.flow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.booklistener.core.EntryIndexStore
import com.booklistener.core.MetadataExtractor
import com.booklistener.core.Mp4ChapterReader
import com.booklistener.core.Mp4IlstReader
import com.booklistener.core.ResolvedTags
import com.booklistener.core.ScanCoordinator
import com.booklistener.core.Source
import com.booklistener.core.SourceFile
import com.booklistener.core.SourceType
import com.booklistener.db.AppDao
import com.booklistener.db.AppDatabase
import com.booklistener.db.SavedEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 标签全字段入库场景测试（spec: tag-fields-full，业务入口）。
 *
 * 用真实测试音频（probe_txxx.mp3 / probe_custom2.m4a）+ 真实解析器组合
 * （MetadataExtractor + Mp4IlstReader，与 ScanCoordinatorFactory.forLocal 同链路）
 * 走 ScanCoordinator.scan，断言数据库产物：
 * - entry_index 结构化列（series/seriesNumber/mediaType/genre…）
 * - entry_tag_extras 扩展键值（©ser/©sno/stik 原始键名）
 * - works 作品层聚合（series/seriesNumber/mediaType）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagFieldsFlowTest {

    private lateinit var dao: AppDao
    private lateinit var indexStore: EntryIndexStore
    private val entryId = "content://tree/tag-fields-flow"
    private val extractor = MetadataExtractor()

    private class FakeSource(val files: List<SourceFile>) : Source {
        override val type: SourceType = SourceType.LOCAL
        override suspend fun listFiles(rootUri: String): List<SourceFile> = files
    }

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dao()
        indexStore = EntryIndexStore(dao)
        runBlocking {
            dao.insertSavedEntries(
                listOf(
                    SavedEntryEntity(
                        id = entryId, displayName = "标签全字段测试", uri = entryId,
                        sourceType = "LOCAL", coverUri = null, fileCount = 0, lastPlayedAt = 0L,
                        artist = null, lastOpenedAt = 1L, sortOrder = 0
                    )
                )
            )
        }
    }

    /** 与 ScanCoordinatorFactory.resolveLocalTags 同链路（v2.2.19：MP4 容器全部自研解析，不再调用 Media3）：
     * m4a/mp4/alac/m4b → Mp4IlstReader.read + readDurationMs（+m4b 章节）；其余 → Media3 */
    private fun realTagReader(file: SourceFile): ResolvedTags? {
        val ext = file.name.substringAfterLast('.', "")
        if (ext.lowercase() !in setOf("m4a", "mp4", "alac", "m4b")) {
            val m3 = extractor.extract(resource("tagprobe/${file.name}"), ext)
            if (m3.entries.isEmpty() && m3.durationMs == null) return null
            return ResolvedTags.fromEntries(m3.entries, m3.durationMs)
        }
        val bytes = resource("tagprobe/${file.name}")
        val entries = Mp4IlstReader.read(bytes)
        val durationMs = Mp4IlstReader.readDurationMs(bytes)
        val chapters = if (ext.equals("m4b", ignoreCase = true)) {
            Mp4ChapterReader.read(bytes).takeIf { it.isNotEmpty() }
        } else null
        return ResolvedTags.fromEntries(entries, durationMs).copy(chapters = chapters)
    }

    private fun scan(files: List<SourceFile>) {
        val coord = ScanCoordinator(
            source = FakeSource(files),
            tagReader = { realTagReader(it) },
            cueTextReader = { null },
            indexStore = indexStore
        )
        runBlocking { coord.scan(entryId) }
    }

    private fun sourceFile(name: String) = SourceFile(
        name = name,
        path = "content://doc/$name",
        relativePath = "",
        isDirectory = false,
        sizeBytes = 5_000_000L
    )

    // ---- 场景1：mp3（TXXX 系列字段）扫描入库 ----

    @Test
    fun `mp3 扫描后 系列字段进入结构化列与extras`() {
        scan(listOf(sourceFile("probe_txxx.mp3")))

        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        val row = rows[0]
        // 结构化列（TXXX:SERIES / SERIESNUMBER）
        assertEquals("哈利·波特系列", row.series)
        assertEquals("1", row.seriesNumber)
        assertEquals("Audiobook", row.mediaType) // TCON=Audiobook
        assertEquals("大难不死的男孩", row.displayName)
        assertEquals("J.K. Rowling", row.albumArtist) // TPE2
        assertNotNull(row.durationMs)

        // extras：原始键名全量保留
        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        assertTrue("extras 为空", extras.isNotEmpty())
        val keys = extras.map { it.key }.toSet()
        assertTrue("缺少 SERIES", "SERIES" in keys)
        assertTrue("缺少 SERIESNUMBER", "SERIESNUMBER" in keys)
        assertTrue("缺少 TIT2", "TIT2" in keys)
        assertTrue("缺少 TPE2", "TPE2" in keys)
        assertTrue("缺少 TRCK", "TRCK" in keys)

        // works 聚合
        val work = runBlocking { dao.getWorksByEntry(entryId) }.single()
        assertEquals("哈利·波特与魔法石", work.title)
        assertEquals("哈利·波特系列", work.series)
        assertEquals("1", work.seriesNumber)
        assertEquals("Audiobook", work.mediaType)
    }

    // ---- 场景2：m4a（©ser/©sno/stik 扩展原子）扫描入库 ----

    @Test
    fun `m4a 扫描后 扩展原子入extras 结构化列聚合`() {
        scan(listOf(sourceFile("probe_custom2.m4a")))

        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        val row = rows[0]
        // 结构化列：系列/媒体类型（自研 ilst 补齐，Media3 不可达）
        assertEquals("哈利·波特系列", row.series)
        assertEquals("1", row.seriesNumber)
        assertEquals("Audiobook", row.mediaType) // stik=2 → Audiobook
        assertEquals("大难不死的男孩", row.displayName)
        assertNotNull(row.durationMs)

        // extras：©ser/©sno/stik 原始键名（自研解析结果）
        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        val keys = extras.map { it.key }.toSet()
        assertTrue("缺少 ©ser（自研 ilst 未生效）", "\u00A9ser" in keys)
        assertTrue("缺少 ©sno", "\u00A9sno" in keys)
        assertTrue("缺少 stik", "stik" in keys)
        assertTrue("缺少 trkn", "trkn" in keys)
        assertTrue("缺少 aART", "aART" in keys)

        val work = runBlocking { dao.getWorksByEntry(entryId) }.single()
        assertEquals("哈利·波特系列", work.series)
        assertEquals("Audiobook", work.mediaType)
    }

    // ---- 场景3：解析失败的文件 不产生 extras、行走 error ----

    @Test
    fun `解析失败文件 文件名兜底且无extras`() {
        scan(listOf(sourceFile("broken.mp3")))

        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        assertEquals("broken", rows[0].displayName) // 去扩展名文件名兜底
        assertEquals("tag parse failed", rows[0].error)
        assertNull(rows[0].series)

        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        assertTrue(extras.isEmpty())
    }

    // ---- 场景4：m4a 同命名空间成对（自由格式 SERIES + SERIES-PART 首选）----

    @Test
    fun `m4a 同命名空间成对 自由格式系列字段首选`() {
        scan(listOf(sourceFile("probe_ff_pair.m4a")))

        val rows = runBlocking { dao.getEntryIndex(entryId) }
        assertEquals(1, rows.size)
        val row = rows[0]
        // 结构化列：自由格式优先，同命名空间成对同取
        assertEquals("三体", row.series)
        assertEquals("2", row.seriesNumber)
        assertEquals("Audiobook", row.mediaType) // stik=2
        assertEquals("大难不死的男孩", row.displayName)

        // extras：命名空间保留的原始键名
        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        val keys = extras.map { it.key }.toSet()
        assertTrue("缺少 ----:nsA:SERIES", "----:nsA:SERIES" in keys)
        assertTrue("缺少 ----:nsA:SERIES-PART", "----:nsA:SERIES-PART" in keys)

        val work = runBlocking { dao.getWorksByEntry(entryId) }.single()
        assertEquals("三体", work.series)
        assertEquals("2", work.seriesNumber)
        assertEquals("Audiobook", work.mediaType)
    }

    // ---- 场景5：m4a 跨命名空间禁止（名 nsA、序号 nsB → 序号回退 ©sno）----

    @Test
    fun `m4a 跨命名空间 序号不拼凑 回退标准原子`() {
        scan(listOf(sourceFile("probe_ff_cross.m4a")))

        val row = runBlocking { dao.getEntryIndex(entryId) }.single()
        // 系列名取 nsA 自由格式；序号不取 nsB（跨命名空间禁止），回退 ©sno=1
        assertEquals("三体", row.series)
        assertEquals("1", row.seriesNumber)

        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        val keys = extras.map { it.key }.toSet()
        assertTrue("缺少 ----:nsA:SERIES", "----:nsA:SERIES" in keys)
        assertTrue("缺少 ----:nsB:SERIES-PART（extras 仍全量保留）", "----:nsB:SERIES-PART" in keys)
        assertTrue("缺少 ©sno", "\u00A9sno" in keys)
    }

    // ---- 场景6：m4a 仅小写镜像（series/series-part 同命名空间）----

    @Test
    fun `m4a 小写镜像 同命名空间成对命中`() {
        scan(listOf(sourceFile("probe_ff_lower.m4a")))

        val row = runBlocking { dao.getEntryIndex(entryId) }.single()
        assertEquals("三体", row.series)
        assertEquals("3", row.seriesNumber)

        val extras = runBlocking { dao.getEntryTagExtrasByEntry(entryId) }
        val keys = extras.map { it.key }.toSet()
        assertTrue("缺少 ----:nsA:series", "----:nsA:series" in keys)
        assertTrue("缺少 ----:nsA:series-part", "----:nsA:series-part" in keys)
    }

    // ---- 场景7：m4a 自由格式仅名（序号字段级回退 ©sno）----

    @Test
    fun `m4a 自由格式仅名 序号回退标准原子`() {
        scan(listOf(sourceFile("probe_ff_nameonly.m4a")))

        val row = runBlocking { dao.getEntryIndex(entryId) }.single()
        // Q2：名取自由格式、序号回退 ©sno（字段级降级链）
        assertEquals("三体", row.series)
        assertEquals("2", row.seriesNumber)
    }

    // ---- 场景8：m4a 自由格式仅序号（名回退 ©ser）----

    @Test
    fun `m4a 自由格式仅序号 名回退标准原子`() {
        scan(listOf(sourceFile("probe_ff_partonly.m4a")))

        val row = runBlocking { dao.getEntryIndex(entryId) }.single()
        // Q3：序号取自由格式、名回退 ©ser（字段级降级链）
        assertEquals("旧系列名", row.series)
        assertEquals("7", row.seriesNumber)
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }
}
