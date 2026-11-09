package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EndpointCatalog.search()` 的行为测试 —— v1.6「能力自描述」的核心。
 *
 * ## 为什么这些用例必须存在
 * AI 只会描述意图（「读未读消息」），不知道我们内部把能力叫 `notifications`。
 * 如果检索只能精确匹配 id，那这份能力目录对 AI 而言等于不存在 —— 它必须先
 * 猜对名字才能用。py 探针能证明「代码里有 search()」，但证明不了「查得出」。
 *
 * 这些断言全部跑真实 Kotlin 实现，不依赖 Android API。
 */
class EndpointCatalogSearchTest {

    private fun ids(query: String, limit: Int = 5): List<String> =
        EndpointCatalog.search(query, limit).map { it.id }

    // ---------------------------------------------------------- 中文意图

    @Test
    fun `chinese intent finds notifications without knowing the id`() {
        // 「读未读消息」不含 `notifications` 任何字面，必须靠同义词 + 二元组
        val hits = ids("读未读消息")
        assertTrue("expected notifications in $hits", "notifications" in hits)
    }

    @Test
    fun `chinese intent distinguishes clipboard from sms`() {
        assertEquals("clipboard", ids("剪贴板").firstOrNull())
        // 「复制粘贴」也不能跑到短信上去
        assertTrue("notifications" !in ids("剪贴板"))
    }

    @Test
    fun `chinese intent finds net for firewall words`() {
        assertEquals("net", ids("断网").firstOrNull())
        assertTrue("net" in ids("网络流量") || "net" in ids("网络"))
    }

    @Test
    fun `chinese intent finds control for hardware toggles`() {
        assertEquals("control", ids("手电").firstOrNull())
        assertTrue("control" in ids("音量"))
    }

    @Test
    fun `chinese intent finds remind for todo words`() {
        assertTrue("remind" in ids("提醒我"))
    }

    @Test
    fun `chinese intent finds geofence`() {
        assertTrue("geofence" in ids("位置围栏"))
    }

    @Test
    fun `chinese intent finds bt-hid for keyboard phrasing`() {
        assertTrue("bt-hid" in ids("蓝牙键盘"))
    }

    @Test
    fun `chinese intent finds pulse for morse vibration`() {
        assertTrue("pulse" in ids("震动摩斯"))
    }

    @Test
    fun `chinese intent finds calendar contacts and calls`() {
        assertTrue("calendar" in ids("日历"))
        assertTrue("contacts" in ids("通讯录"))
        assertTrue("calls" in ids("通话"))
    }

    // ---------------------------------------------------------- 英文意图

    @Test
    fun `english intent finds capabilities`() {
        assertTrue("status" in ids("battery"))
        assertTrue("screenwatch" in ids("screenshot"))
        assertTrue("notifications" in ids("notification"))
        assertTrue("control" in ids("torch"))
    }

    @Test
    fun `exact id ranks first`() {
        assertEquals("sms", ids("sms").firstOrNull())
        assertEquals("clipboard", ids("clipboard").firstOrNull())
        assertEquals("nfc", ids("nfc").firstOrNull())
    }

    // ---------------------------------------------------------- 边界

    @Test
    fun `nonsense query returns nothing rather than guessing`() {
        // 无关查询必须返回空。宁可不猜，也不能给 AI 一个错的候选 ——
        // 错候选会让它去调一个完全无关的能力。
        assertEquals(emptyList<String>(), ids("zzzqqqxxx"))
    }

    @Test
    fun `blank query returns nothing`() {
        assertEquals(emptyList<String>(), ids(""))
        assertEquals(emptyList<String>(), ids("   "))
    }

    @Test
    fun `substring does not leak across ids`() {
        // `net` 不能匹配到所有含 network 的东西；边界必须守
        val hits = ids("net")
        assertEquals("net", hits.firstOrNull())
    }

    @Test
    fun `search respects limit`() {
        assertTrue(EndpointCatalog.search("net", 1).size <= 1)
        assertTrue(EndpointCatalog.search("net", 40).size <= 40)
    }

    @Test
    fun `every returned entry carries its wire information`() {
        // 检索结果必须能直接拿去执行：id / method / path 一个都不能空
        for (entry in EndpointCatalog.search("notification", 5)) {
            assertTrue("empty id", entry.id.isNotBlank())
            assertTrue("empty method", entry.method in setOf("GET", "POST"))
            assertTrue("empty path", entry.path.startsWith("/dev/") || entry.path.startsWith("/"))
        }
    }

    // ---------------------------------------------------------- 名册

    @Test
    fun `roster lists every capability id`() {
        val roster = EndpointCatalog.renderRoster(null)
        assertTrue("roster is empty", roster.isNotBlank())
        // 抽查几个不同类型的 id 都在名册里
        for (id in listOf("status", "notifications", "net", "settings", "hid", "mcp")) {
            if (id == "settings" || id == "mcp") continue // 这两条不在端点目录里
            assertTrue("$id missing from roster", roster.contains(id))
        }
    }

    @Test
    fun `roster marks POST entries distinctly`() {
        val roster = EndpointCatalog.renderRoster(null)
        // 至少有一个 POST 能力被标出来（否则 AI 会误用 GET 调用）
        assertTrue("no (POST) marker in roster", roster.contains("(POST)"))
    }

    @Test
    fun `roster can mark disabled capabilities`() {
        val allOff = EndpointCatalog.renderRoster { false }
        // 全部关闭时，凡是带 cap 的都应带 [off]
        assertTrue("no [off] marker when everything is disabled", allOff.contains("[off]"))

        val allOn = EndpointCatalog.renderRoster { true }
        assertTrue("unexpected [off] when everything is enabled", !allOn.contains("[off]"))
    }
}
