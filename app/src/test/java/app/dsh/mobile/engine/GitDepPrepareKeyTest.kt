package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * git 源插件的构建批准键（v1.7.40，2026-11-28 用户截图）。
 *
 * ## 为什么必须钉死
 *
 * 截图里插件页装 https://github.com/dsh-market/dsh-market 直接失败，弹窗只说
 * 「有依赖的安装脚本被 pnpm 拦下，请在 profile 的 pnpm-workspace.yaml 的
 * allowBuilds 中放行后重试」—— 没有「批准并重试」按钮。两层原因：
 *
 *  ① pnpm 拒绝 prepare git 源包时的错误码是 ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED，
 *     和注册源的 ERR_PNPM_IGNORED_BUILDS 完全不同，旧判据根本认不出；
 *  ② 它建议的 allowBuilds 键是 <name>@<codeload URL>/tar.gz/<sha>，既不是裸包名，
 *     又被 pnpm 在 74 列处折成两行、allowBuilds: 还是缩进的 ——
 *     上游那条「顶格 + 单行」的正则对真实输出返回 undefined（本机实测）。
 *
 * 断言全部喂**真实 pnpm 输出**（scripts/probes/data/pnpm-git-dep-prepare.txt），
 * 不跑 Android API，因此可以在 JVM 单测里直接执行。
 */
class GitDepPrepareKeyTest {

    /** pnpm 12.21.0 对 pnpm add https://github.com/dsh-market/dsh-market 的真实输出。 */
    private val realOutput = listOf(
        "Progress: resolved 0, reused 0, downloaded 1, added 0",
        "Progress: resolved 4, reused 1, downloaded 2, added 0",
        "Error: ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED",
        "",
        "  \u00d7 adding a new package",
        "  \u2570\u2500\u25b6 The git-hosted package \"dshmarket@1.82.0\" needs to execute build scripts",
        "      but is not in the \"allowBuilds\" allowlist.",
        "  help: Add the package to \"allowBuilds\" in your project's pnpm-workspace.yaml",
        "        to allow it to run scripts. For example:",
        "        allowBuilds:",
        "          dshmarket@https://codeload.github.com/dsh-market/dsh-market/",
        "        tar.gz/18004a19b1c5bca1cd3ea405e2171a5fb3c089ce: true",
    ).joinToString("\n")

    private val expectedKey =
        "dshmarket@https://codeload.github.com/dsh-market/dsh-market/" +
            "tar.gz/18004a19b1c5bca1cd3ea405e2171a5fb3c089ce"

    @Test
    fun realGitPrepareOutputYieldsTheFullKey() {
        assertEquals(expectedKey, PluginCenter.gitDepPrepareKey(realOutput))
    }

    @Test
    fun keySurvivesAnyIndentAndLineWrapping() {
        // pnpm 的折行列位置随终端宽度变化；缩进也可能不同。这里换三种排版都必须成功。
        val reflowed = listOf(
            "Error: ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED",
            "            allowBuilds:",
            "dshmarket@https://codeload.github.com/dsh-market/",
            "                  dsh-market/tar.gz/18004a19b1c5bca1cd3ea405e2171a5fb3c089ce: true",
        ).joinToString("\n")
        assertEquals(expectedKey, PluginCenter.gitDepPrepareKey(reflowed))
    }

    @Test
    fun registryScriptFailureIsNotMistakenForGitKey() {
        val registry = listOf(
            "Error: ERR_PNPM_IGNORED_BUILDS",
            "  \u2570\u2500\u25b6 Ignored build scripts: esbuild@0.28.2",
        ).joinToString("\n")
        assertNull(PluginCenter.gitDepPrepareKey(registry))
    }

    @Test
    fun truncatedOutputDoesNotGuess() {
        // 输出被裁掉尾行时不能拼出半截键（半截键既不看批准、还会污染策略文件）。
        val truncated = realOutput.substringBefore(": true")
        assertNull(PluginCenter.gitDepPrepareKey(truncated))
    }

    @Test
    fun deniedKeyIsAlsoRecognised() {
        // pnpm 在「明确拒绝」时给的也是同一段 help，只是值可能是 false。
        val denied = realOutput.replace(": true", ": false")
        assertEquals(expectedKey, PluginCenter.gitDepPrepareKey(denied))
    }

    // ── 键合法性：git 形态要放行，注入面要收死 ────────────────────────────
    @Test
    fun bareNpmNamesStayLegal() {
        assertTrue(PluginCenter.isSafeAllowBuildsKey("esbuild"));
        assertTrue(PluginCenter.isSafeAllowBuildsKey("@parcel/watcher"));
    }

    @Test
    fun gitKeyIsLegal() {
        assertTrue(PluginCenter.isSafeAllowBuildsKey(expectedKey))
    }

    @Test
    fun versionedNameStaysLegal() {
        // pnpm 的注册源报告里键会带版本号（真机实测 `esbuild@0.28.2` 也能跑 postinstall）。
        // allowBuildsFor 那条自愈路径以前先 stripVersion，现在必须两种都收。
        assertTrue(PluginCenter.isSafeAllowBuildsKey("esbuild@0.28.2"))
        assertTrue(PluginCenter.isSafeAllowBuildsKey("@parcel/watcher@2.5.1"))
        assertFalse(PluginCenter.isSafeAllowBuildsKey("esbuild@latest"))
    }

    @Test
    fun wildcardsAndInjectionAreRefused() {
        assertFalse(PluginCenter.isSafeAllowBuildsKey("*"));
        assertFalse(PluginCenter.isSafeAllowBuildsKey("esbuild?"));
        assertFalse(PluginCenter.isSafeAllowBuildsKey("x\n  \"*\": true"));
        assertFalse(PluginCenter.isSafeAllowBuildsKey("x y"));
        assertFalse(PluginCenter.isSafeAllowBuildsKey(""));
        assertFalse(PluginCenter.isSafeAllowBuildsKey(expectedKey + "\n"));
    }

    @Test
    fun credentialBearingGitKeysAreRefused() {
        // 带 user:pass 的键不许写进 profile 设置（上游 carriesCredential 口径）。
        assertFalse(
            PluginCenter.isSafeAllowBuildsKey(
                "dshmarket@https://user:secret@codeload.github.com/a/b/tar.gz/c0ffee"),
            );
        assertFalse(
            PluginCenter.isSafeAllowBuildsKey(
                "dshmarket@git+https://user@github.com/a/b.git"),
            );
    }

    // ── 写盘往返：键必须能被自己的 YAML 改写器写进去、再读回来 ────────────
    @Test
    fun gitKeyRoundTripsThroughThePolicyWriter() {
        // 键里有 `@` `:` `/`，都是 YAML 里有含义的字符；mergeAllowBuilds 一律写双引号，
        // pendingFromAllowBuilds 必须能原样读回来 —— 否则「批准并重试」点了也判 stale。
        val policy = "packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n"
        val merged = PluginCenter.mergeAllowBuilds(policy, mapOf(expectedKey to "set this to true or false"))
        assertTrue("必须写进文件：" + merged, merged != null && merged.contains("\"dshmarket@https://"))
        val pending = PluginCenter.pendingFromAllowBuilds(merged!!)
        assertEquals(listOf(expectedKey), pending)
        // 批准后再读回：不再待定，但键还在
        val approved = PluginCenter.mergeAllowBuilds(merged, mapOf(expectedKey to "true"))
        assertTrue(approved != null)
        assertTrue(PluginCenter.pendingFromAllowBuilds(approved!!).isEmpty())
    }

    @Test
    fun unknownProtocolsAreRefused() {
        // 只认 pnpm 真会产出的四类 spec，别的协议不写文件。
        assertFalse(PluginCenter.isSafeAllowBuildsKey("dshmarket@ssh://git@github.com/a/b"));
        assertFalse(PluginCenter.isSafeAllowBuildsKey("dshmarket@ftp://x/a/b"));
    }
}
