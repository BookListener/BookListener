package com.booklistener.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.0.14 i18n 资源完整性单测：
 * 双语言 strings.xml（values=英文默认/兜底、values-zh=简体中文）必须满足：
 * 1) zh 键集 == en 键集（无缺失、无多余，新增文案必须双语言同步）；
 * 2) 所有键两语言值均非空；
 * 3) en 值不得含中文字符（出现即漏译）；
 * 4) 除专名（app_name / 语言名 / 专有名词）外，en 与 zh 值应不同（真正翻译而非复制）。
 */
class I18nResourcesTest {

    private val resDir: File = locateResDir()

    private fun locateResDir(): File {
        val base = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(base, "app/src/main/res"),
            File(base, "src/main/res"),
            File("app/src/main/res"),
            File("src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("无法定位 res 目录，user.dir=${base.absolutePath}")
    }

    private fun parseKeys(xmlPath: String): Map<String, String> {
        val text = File(xmlPath).readText()
        val re = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val map = mutableMapOf<String, String>()
        for (m in re.findAll(text)) {
            val name = m.groupValues[1]
            var value = m.groupValues[2]
            value = value
                .replace("\\n", "\n")
                .replace("\\'", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            map[name] = value
        }
        return map
    }

    @Test
    fun zhAndEnKeySetsAreIdentical() {
        assertTrue("资源目录缺失: $resDir", File(resDir, "values/strings.xml").exists())
        val en = parseKeys("$resDir/values/strings.xml")
        val zh = parseKeys("$resDir/values-zh/strings.xml")

        val missingInZh = en.keys - zh.keys
        val extraInZh = zh.keys - en.keys
        assertTrue("zh 缺失键（新增文案未同步中文）: $missingInZh", missingInZh.isEmpty())
        assertTrue("zh 多余键（应同步删除）: $extraInZh", extraInZh.isEmpty())
        assertEquals("键数量应一致", en.size, zh.size)
    }

    @Test
    fun allValuesNonEmptyInBothLanguages() {
        val en = parseKeys("$resDir/values/strings.xml")
        val zh = parseKeys("$resDir/values-zh/strings.xml")
        for ((k, v) in en) {
            assertTrue("en[$k] 为空", v.isNotBlank())
        }
        for ((k, v) in zh) {
            assertTrue("zh[$k] 为空", v.isNotBlank())
        }
    }

    @Test
    fun enValuesContainNoCjk() {
        val en = parseKeys("$resDir/values/strings.xml")
        val cjk = Regex("[\u4e00-\u9fff]")
        // 语言名用母语显示（language_zh="简体中文"），属刻意设计，豁免
        val languageNameKeys = setOf("language_zh", "language_en")
        for ((k, v) in en) {
            if (k in languageNameKeys) continue
            assertFalse("en[$k] 含中文字符（漏译）: $v", cjk.containsMatchIn(v))
        }
    }

    @Test
    fun zhDiffersFromEnExceptProperNouns() {
        val en = parseKeys("$resDir/values/strings.xml")
        val zh = parseKeys("$resDir/values-zh/strings.xml")
        // 专名/语言母语名允许两语言同值
        val allowedSame = setOf(
            "app_name",
            "language_zh", "language_en",
            "cloud_url", "cloud_user", "cloud_password",
            "source_aliyun", "source_local",
            "notification_channel_playback"
        )
        val same = en.keys.filter { key -> en[key] == zh[key] }.filter { key -> !allowedSame.contains(key) }
        assertTrue("en/zh 值相同的键（疑似复制未翻译）: $same", same.isEmpty())
    }
}
