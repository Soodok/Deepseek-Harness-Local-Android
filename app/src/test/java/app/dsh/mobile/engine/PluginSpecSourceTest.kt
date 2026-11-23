package app.dsh.mobile.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 依赖来源判定 + 失败归类（v1.7.32）。
 *
 * ## 为什么必须有这层判定
 *
 * `PluginCenter.install()` 对已装包用 `$pkg@latest` 来「强制重新解析」，这本是为
 * 了修「已装包更新无效」（pnpm 对已满足的版本是 no-op）。但内置插件的 spec 是
 * `file:<staging tgz>`，对它发 `@latest` 会被 pnpm 判定为「来源变了」并原地改写：
 *
 *     [WARN] Replaced "@xiaopb/dsh-mobile-layout" ("file:.../staged.tgz")
 *            with "@xiaopb/dsh-mobile-layout@latest" from a different source.
 *
 * 之后该插件永久脱离 APK 自带资产，跟着 registry 走；一旦暂存 tgz 缺失，
 * 连 `pnpm install --fix-lockfile` 都救不回来（实测 exit 1，一个字节不改）。
 *
 * 所以「哪些 spec 绝不能被 @latest 覆盖」必须是一条可被单测钉死的规则，
 * 而不是散落在 install() 里的 if。
 *
 * 后半部分守的是**失败归类**：目标文件缺失与 lockfile 自相矛盾是两类故障，
 * 处置完全不同（重建资产 vs 让 pnpm 重建索引），认错类别就会把「资产丢了」
 * 误报成「lockfile 修不动」，用户看到的就是「装什么都失败」。
 */
class PluginSpecSourceTest {

    @Test
    fun localTarballIsNeverUpgradedToLatest() {
        // 内置插件的真实形态（真机 profile package.json 原文）
        assertTrue(
            PluginCenter.isLocalOrDirectSpec(
                "file:/data/user/0/app.dsh.mobile/files/plugin-staging/" +
                    "xiaopb-dsh-mobile-layout-0.4.12-staged.tgz",
            ),
        )
        assertTrue(PluginCenter.isLocalOrDirectSpec("file:../plugin-staging/x.tgz"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("link:../packages/x"))
    }

    @Test
    fun directSourcesAreNotUpgradedToLatest() {
        // 直连来源换掉就等于换了内容，不能擅自替换
        assertTrue(PluginCenter.isLocalOrDirectSpec("git+https://github.com/a/b.git"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("git:github.com/a/b"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("github:a/b"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("https://example.com/x.tgz"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("http://example.com/x.tgz"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("./local-plugin"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("../sibling"))
        assertTrue(PluginCenter.isLocalOrDirectSpec("/abs/path/plugin"))
    }

    @Test
    fun registrySpecsStillTrackLatest() {
        // 反向对照：常见的 registry 版本范围仍应追新，否则「检查更新」失效
        for (spec in listOf("^0.7.1", "~1.2.3", "1.2.3", "latest", "*", "")) {
            assertFalse("spec=$spec 不该被当成本地来源", PluginCenter.isLocalOrDirectSpec(spec))
        }
        // 包名里带 file 字样但不是本地来源
        assertFalse(PluginCenter.isLocalOrDirectSpec("dsh-file-tools"))
        assertFalse(PluginCenter.isLocalOrDirectSpec("^2.0.0-beta.1"))
    }

    // ─────────────────────────────────────── 失败归类：目标缺失 vs lockfile 坏

    /** 设备实测原文（vC402 / pnpm 12.21.0，删掉 staged tgz 后跑 add）。 */
    private val realMissingTargetMessage = """
        ? Verifying lockfile against supply-chain policies (16 entries)...
        Error:   × adding a new package
          ├─▶ Failed to resolve dependency tree: Failed to resolve dependency: Could
          │   not install from "/data/user/0/app.dsh.mobile/files/plugin-staging/
          │   xiaopb-dsh-mobile-layout-0.4.12-staged.tgz" as it does not exist.
          │
          │   Failed to resolve @xiaopb/dsh-mobile-layout@file:/data/user/0/
          │   app.dsh.mobile/files/plugin-staging/xiaopb-dsh-mobile-layout-0.4.12-
          │   staged.tgz
          ╰─▶ Failed to resolve dependency: Could not install from "/data/user/0/
              app.dsh.mobile/files/plugin-staging/xiaopb-dsh-mobile-layout-0.4.12-
              staged.tgz" as it does not exist.

        Command failed with exit code 1: pnpm add is-odd
    """.trimIndent()

    @Test
    fun realMissingTargetErrorIsClassifiedAsMissingTarget() {
        assertTrue(PluginCenter.isMissingLocalTargetError(realMissingTargetMessage))
        // 且**不能**被归到 lockfile 分支：设备实测这条报错里 Broken lockfile 计数为 0，
        // 认错了就会去跑注定失败的 --fix-lockfile，把真正的原因埋掉。
        assertFalse(PluginCenter.isBrokenLockfileError(realMissingTargetMessage))
    }

    @Test
    fun enoentOnTarballAlsoCounts() {
        // 解包阶段抛的形态（pnpm 对已解析但读不到的 file: 目标）
        val s = "Error: ENOENT: no such file or directory, open " +
            "'/data/user/0/app.dsh.mobile/files/plugin-staging/x-staged.tgz'"
        assertTrue(PluginCenter.isMissingLocalTargetError(s))
    }

    @Test
    fun brokenLockfileIsNotClassifiedAsMissingTarget() {
        val s = """
            Error: ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY
              × adding a new package
              ╰─▶ Broken lockfile: no entry for 'is-odd@3.0.1' in pnpm-lock.yaml
            help: To fix the lockfile, run 'pnpm install --fix-lockfile'.
        """.trimIndent()
        assertTrue(PluginCenter.isBrokenLockfileError(s))
        assertFalse(PluginCenter.isMissingLocalTargetError(s))
    }

    @Test
    fun unrelatedErrorsAreNeitherClass() {
        // 防止判据放宽到「提到 tgz 就算」或「提到错误就算」：
        // 网络失败、权限失败都不该触发任何自动重建。
        val network = "ERR_PNPM_FETCH_404  GET https://registry.npmjs.org/x: Not Found"
        val perm = "EACCES: permission denied, open '/data/data/x/plugin-staging/a.tgz'"
        for (s in listOf(network, perm)) {
            assertFalse(s, PluginCenter.isMissingLocalTargetError(s))
            assertFalse(s, PluginCenter.isBrokenLockfileError(s))
        }
    }
}
