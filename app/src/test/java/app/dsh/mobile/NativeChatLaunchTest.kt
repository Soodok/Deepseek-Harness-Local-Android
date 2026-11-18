package app.dsh.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * 原生对话页「切换观感」的资源契约。
 *
 * ## 为什么这些断言必须存在
 * beta.52 的录屏（sw2.mp4）里，从 Web 点「原生对话」到原生页出现之间有一段
 * 1.9 秒的近黑整屏：逐帧亮度 YAVG 从 45 掉到 16.5 并持续，然后突然跳到 215。
 * 根因不在 Flutter 代码里，而在资源声明：NativeChatActivity 用的是全站主题
 * Theme.Dsh，它的 windowBackground 是 @color/bg —— 深色系统下 #101418。
 * Flutter 引擎冷启到画出第一帧之间显示的就是这层背景，而会话页本身是浅色
 * （暖砂/浅色外观），于是"深色空窗 + 浅色页面"就是一次硬切。
 *
 * 这类缺陷跑 Flutter 测试抓不到（它不在 Dart 层），跑界面测试也只能看到
 * 结果快照、说不出契约。所以这里直接断言资源的**契约本身**：
 *   ① 会话页必须声明自己的主题；
 *   ② 该主题的窗口底色与启动图必须是浅色（亮度下限）；
 *   ③ 进出必须有淡入淡出动画，而不是系统默认的横向滑入。
 *
 * 判据故意定在"亮度"上而不是某个具体色值：将来换配色不该误报，但把它改成
 * 深色就一定会红 —— 那正是这次要防的回归。
 */
class NativeChatLaunchTest {

    private val app: File = File("src/main")
    private val manifest: Document = parse(File(app, "AndroidManifest.xml"))
    private val themes: Document = parse(File(app, "res/values/themes.xml"))

    private fun parse(file: File): Document {
        assertTrue("missing " + file.path, file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    }

    /** 取 <activity android:name="..."> 节点。 */
    private fun activity(name: String): org.w3c.dom.Element {
        val nodes = manifest.getElementsByTagName("activity")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as org.w3c.dom.Element
            if (node.getAttribute("android:name") == name) return node
        }
        throw AssertionError("no <activity> for " + name)
    }

    /** 取 <style name="..."> 节点。 */
    private fun style(doc: Document, name: String): org.w3c.dom.Element {
        val nodes = doc.getElementsByTagName("style")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as org.w3c.dom.Element
            if (node.getAttribute("name") == name) return node
        }
        throw AssertionError("no <style> named " + name)
    }

    /** 取 style 下某个 <item> 的值（按 name 属性）。 */
    private fun item(style: org.w3c.dom.Element, name: String): String {
        val items = style.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val node = items.item(i) as org.w3c.dom.Element
            if (node.getAttribute("name") == name) return node.textContent.trim()
        }
        throw AssertionError("style " + style.getAttribute("name") + " has no " + name)
    }

    @Test
    fun `the native chat page declares its own theme instead of inheriting the dark shell`() {
        assertEquals("@style/Theme.Dsh.NativeChat", activity(".NativeChatActivity").getAttribute("android:theme"))
    }

    @Test
    fun `the engine surface does not paint an opaque black over the light launch background`() {
        // Flutter 3.50 的 embedding 已经没有 SplashScreenDrawable（反查 aar 的类表确认），
        // 冷启动空窗里显示的是引擎 surface 本身。默认 opaque 会把主题的浅色背景整个盖住，
        // 所以这一页必须显式改成 transparent —— 判据直接读源码里的重写。
        val source = File(app, "java/app/dsh/mobile/NativeChatActivity.kt").readText()
        assertTrue(
            "NativeChatActivity must override getBackgroundMode() with transparent",
            source.contains("override fun getBackgroundMode(): FlutterActivityLaunchConfigs.BackgroundMode"),
        )
        assertTrue(
            "background mode must be transparent, not opaque",
            source.contains("FlutterActivityLaunchConfigs.BackgroundMode.transparent"),
        )
    }

    @Test
    fun `the launch window is translucent, so the page underneath keeps showing`() {
        // 冷启那段空窗必须是「底下那页还看得见」，而不是一层纯色。实测两种不透明
        // 底色都失败：深色 #151517（beta.52，1.9 秒）、黑色（beta.53 第一版，4 秒）。
        val style = style(themes, "Theme.Dsh.NativeChat")
        assertEquals("@android:color/transparent", item(style, "android:windowBackground"))
        assertEquals("true", item(style, "android:windowIsTranslucent"))
        assertEquals("false", item(style, "android:backgroundDimEnabled"))
    }

    @Test
    fun `the page cross-fades instead of sliding sideways`() {
        val style = style(themes, "Theme.Dsh.NativeChat")
        assertEquals("@style/Animation.Dsh.NativeChat", item(style, "android:windowAnimationStyle"))

        val animation = style(themes, "Animation.Dsh.NativeChat")
        assertEquals("@anim/dsh_fade_in", item(animation, "android:activityOpenEnterAnimation"))
        assertEquals("@anim/dsh_fade_out", item(animation, "android:activityCloseExitAnimation"))
        // 被覆盖方必须原地不动。两边都淡会在交界处露出窗口栈底下的黑色 ——
        // beta.53 第一版就是这个结果：亮度掉到 16.6 并持续 2.3 秒。
        assertEquals("@anim/dsh_hold", item(animation, "android:activityOpenExitAnimation"))
        assertEquals("@anim/dsh_hold", item(animation, "android:activityCloseEnterAnimation"))
    }
}
