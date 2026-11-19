package app.dsh.mobile.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * 守 [ChatImportLayer] 的 YAML 契约 —— 这几条都是**实测踩过**的，不是想象：
 *
 * 1. 包名必须带双引号。裸写 `name: @deepseek-ai/dsh-deepseek-chat-import` 时引擎
 *    直接起不来：`Error: dsh: failed to parse overlay ...: YAMLException: bad
 *    indentation of a mapping entry (3:13)`。这个单测是那条实测的唯一防线。
 * 2. 必须是顶层**序列**（`- insert:`），不能是映射 —— patch 层文件被当成 overlay
 *    列表解析，写成映射会报同样的解析错误。
 * 3. 文本必须逐字节稳定：写入侧用全等比较决定要不要重写文件，多一个空行就会
 *    每次启动都重写一遍。
 * 4. 不含 CJK：`scripts/check-i18n.py` 会把 Kotlin 字符串字面量里的中文判红。
 */
class ChatImportLayerTest {

    @Test fun pluginPackageIsQuotedInYaml() {
        val body = ChatImportLayer.body()
        assertTrue("package name must be double-quoted",
            body.contains("name: \"" + ChatImportLayer.PLUGIN_PACKAGE + "\""))
        assertFalse("an unquoted package name breaks YAML parsing",
            body.contains("name: " + ChatImportLayer.PLUGIN_PACKAGE + "\n"))
    }

    @Test fun bodyIsATopLevelSequenceWithOneInsert() {
        val lines = ChatImportLayer.body().lines()
        val code = lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        assertEquals("layer must open with a top-level sequence entry", "- insert:", code[0])
        // 六行有效内容：insert、entry id、包名、config 头、两个上限。多一行或少一行
        // 都说明拼装语句被改过（config 少写会让手机按上游 512 MiB / 256 MiB 的桌面默认跑）。
        assertEquals(6, code.size)
        assertEquals("    - id: " + ChatImportLayer.LAYER_ID, code[1])
        assertEquals("      name: \"" + ChatImportLayer.PLUGIN_PACKAGE + "\"", code[2])
        assertEquals("      config:", code[3])
        assertEquals("        maxUploadBytes: " + ChatImportLayer.MAX_UPLOAD_BYTES, code[4])
        assertEquals("        maxEntryBytes: " + ChatImportLayer.MAX_ENTRY_BYTES, code[5])
    }

    @Test fun capsStayBelowTheDesktopDefaults() {
        // 上游默认 maxUploadBytes=512 MiB、maxEntryBytes=256 MiB，都不适合手机内存；
        // 这里的断言是「比桌面默认更严」，调大时要连带改注释与 README 边界。
        assertEquals(256L * 1024 * 1024, ChatImportLayer.MAX_UPLOAD_BYTES)
        assertEquals(64L * 1024 * 1024, ChatImportLayer.MAX_ENTRY_BYTES)
        assertTrue(ChatImportLayer.MAX_ENTRY_BYTES < ChatImportLayer.MAX_UPLOAD_BYTES)
    }

    @Test fun bodyIsByteStableAcrossCalls() {
        // 写入侧只做全等比较；文本抖动 = 每次启动都重写文件。
        assertEquals(ChatImportLayer.body(), ChatImportLayer.body())
        assertTrue("trailing newline keeps YAML block files diff-clean",
            ChatImportLayer.body().endsWith("\n"))
        // 少了结尾换行时，next write 会多一次全等失败 → 每次启动重写一遍。
        assertFalse(ChatImportLayer.body().endsWith("\n\n"))
    }

    @Test fun bodyHasNoCjk() {
        val cjk = Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]")
        assertFalse("i18n gate rejects CJK in Kotlin literals",
            cjk.containsMatchIn(ChatImportLayer.body()))
    }

    @Test fun pluginEntryMatchesTheRuntimeLayout() {
        // 与 plugin-package 的 node_modules 布局一致；写错路径会让开关永远灰着。
        assertEquals(
            "lib/node_modules/" + ChatImportLayer.PLUGIN_PACKAGE + "/lib/index.js",
            ChatImportLayer.PLUGIN_ENTRY,
        )
    }

    @Test fun shouldProvideRequiresBothSwitchAndRuntime() {
        assertTrue(ChatImportLayer.shouldProvide(enabled = true, pluginPresent = true))
        // 开关开着但老运行时里没这个包：不能写层，否则引擎只打一行 warning 就跳过。
        assertFalse(ChatImportLayer.shouldProvide(enabled = true, pluginPresent = false))
        assertFalse(ChatImportLayer.shouldProvide(enabled = false, pluginPresent = true))
        assertFalse(ChatImportLayer.shouldProvide(enabled = false, pluginPresent = false))
    }

    @Test fun layerFileStaysInsideEngineRoot() {
        // 容器模式把 patch 路径映射成 guest /eng/<rel>，只有 engineRoot 下的文件两种
        // 模式都可达 —— 文件名本身不含路径分隔符才能保证这一点。
        assertFalse(ChatImportLayer.LAYER_FILE_NAME.contains("/"))
        assertFalse(ChatImportLayer.LAYER_FILE_NAME.contains("\\"))
        assertTrue(ChatImportLayer.LAYER_FILE_NAME.endsWith(".yml"))
    }
}
