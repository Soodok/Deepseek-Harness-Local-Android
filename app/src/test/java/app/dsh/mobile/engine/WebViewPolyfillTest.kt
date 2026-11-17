package app.dsh.mobile.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * 覆盖 WebViewPolyfill 的注入/替换/幂等语义。
 *
 * dsh-polyfill-version-fixture: 下面几处 "legacy-webview polyfill v5" 是**断言素材**
 * （要验证「v4 旧块被整块替换成当前版本」「v99 将来升级也照样替换」），不是构建
 * 门禁里那个不得写死的版本判据。scripts/probes/_test_webview_polyfill_gate.py 认这个
 * 标记 + app/src/test/ 路径双重条件才放行；真实版本号仍只由
 * scripts/patch-webview-polyfill.py 的 POLYFILL 常量决定。
 */
class WebViewPolyfillTest {
    private val polyfill = "<!-- [dsh-android] legacy-webview polyfill v5 -->\n" +
        "<script>URL.canParse; URL.parse; AbortSignal.any; structuredClone;</script>\n"
    private val modules = "<script type=\"module\" src=\"first.js\"></script>" +
        "<script type='module' src=\"second.js\"></script>"

    @Test fun insertsWholeScriptBeforeEveryModuleAndIsIdempotent() {
        val original = "<html><head></head><body>" + modules + "</body></html>"
        val patched = WebViewPolyfill.patchHtml(original, polyfill)
        assertTrue(WebViewPolyfill.hasRequiredPolyfill(patched, polyfill))
        assertTrue(patched.indexOf(polyfill) < patched.indexOf("<script type="))
        assertEquals(patched, WebViewPolyfill.patchHtml(patched, polyfill))
        assertEquals(original, patched.replace(polyfill + "\n", ""))
    }

    @Test fun upgradesOldBlockEvenWhenCommentsMentionRequiredApis() {
        val old = "<!-- [dsh-android] legacy-webview polyfill v4 -->" +
            "<script>/* URL.canParse URL.parse AbortSignal.any structuredClone */</script>"
        val original = old + modules
        assertFalse(WebViewPolyfill.hasRequiredPolyfill(original, polyfill))
        val patched = WebViewPolyfill.patchHtml(original, polyfill)
        assertFalse(patched.contains("polyfill v4"))
        assertTrue(WebViewPolyfill.hasRequiredPolyfill(patched, polyfill))
    }

    @Test fun repairsLateAndDuplicateBlocks() {
        for (original in listOf(modules + polyfill, polyfill + modules + polyfill)) {
            assertFalse(WebViewPolyfill.hasRequiredPolyfill(original, polyfill))
            val patched = WebViewPolyfill.patchHtml(original, polyfill)
            assertTrue(WebViewPolyfill.hasRequiredPolyfill(patched, polyfill))
            assertEquals(patched, WebViewPolyfill.patchHtml(patched, polyfill))
        }
    }

    @Test fun leavesNonModuleDocumentsUnchanged() {
        val original = "<html><body>Authentication required</body></html>"
        assertEquals(original, WebViewPolyfill.patchHtml(original, polyfill))
        assertFalse(WebViewPolyfill.hasRequiredPolyfill(original, polyfill))
    }
}
