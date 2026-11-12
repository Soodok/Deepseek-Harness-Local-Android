package app.dsh.mobile.engine

import android.util.Log
import java.io.File

/**
 * 会话回收站（v1.7.7）。
 *
 * ## 为什么必须放在 sessions/ 之外
 *
 * 引擎的会话持久化会递归扫描 sessions/ 下所有 session.v4.jsonl.zstd，再用文件头里的
 * cwd 反推它「应该」躺在哪个 cwd-slug 目录里（assertStoredIdentity）。旧版回收站建在
 * sessions/.trash-<stamp>-<mode>/，里面是 <session-id>/session.v4.jsonl.zstd —— 路径与
 * 推断不符，于是引擎报：
 *
 *   workspace (@deepseek-ai/dsh-workspace): Error: corrupt session log
 *     ".../sessions/.trash-20261112-102907-delete/session-xxx/session.v4.jsonl.zstd":
 *     header id "session-xxx" and cwd identify ".../sessions/--<cwd-slug>--/session-xxx/..."
 *
 * → dsh-workspace 激活失败 → workspaceRegistry 服务缺失 → session-controller /
 * workspace-controller / ui-deliverables 全部 pending。真机 2026-11-12：在
 * 「诊断与维护」里删一次对话，引擎就半残（4 entries did not activate）。
 */
object SessionTrash {

    private const val TAG = "SessionTrash"

    /** 回收站根目录名（相对 DSH_HOME）。 */
    const val ROOT = ".trash-sessions"

    /** 旧版回收站前缀：曾建在 sessions/ 里，必须迁走。 */
    const val LEGACY_PREFIX = ".trash-"

    fun root(home: File): File = File(home, ROOT)

    /** 新建一次删除的回收站目录：<home>/.trash-sessions/<stamp>-<mode> */
    fun newDir(home: File, stamp: String, mode: String): File = File(root(home), "$stamp-$mode")

    /**
     * 把旧版 sessions/.trash-* 迁到新位置。
     *
     * 幂等；没有旧目录时只做一次 listFiles（成本可忽略）。**必须能自愈**：
     * 用户手机上已经躺着旧回收站，只改新代码不清旧目录，引擎仍然起不来。
     *
     * @return 迁走的目录数
     */
    fun migrateLegacy(home: File): Int {
        val sessions = File(home, "sessions")
        val legacy = sessions.listFiles()?.filter {
            it.isDirectory && it.name.startsWith(LEGACY_PREFIX)
        }.orEmpty()
        if (legacy.isEmpty()) return 0
        var n = 0
        for (d in legacy) {
            val dest = File(root(home), d.name)
            val ok = runCatching {
                dest.parentFile?.mkdirs()
                if (dest.exists()) {
                    // 同名目标已存在（重复迁移）：旧目录是残留，直接清掉。
                    d.deleteRecursively()
                    true
                } else {
                    d.renameTo(dest) || run {
                        // 跨文件系统 rename 会失败：退化成 copy + delete
                        d.copyRecursively(dest, overwrite = true)
                        d.deleteRecursively()
                        true
                    }
                }
            }.getOrElse {
                Log.w(TAG, "migrate ${d.name} failed: ${it.message}")
                false
            }
            if (ok) n++
        }
        if (n > 0) Log.i(TAG, "migrated $n legacy trash dir(s) out of sessions/")
        return n
    }

    /** 清掉超过 keepDays 天的回收站目录，返回清掉的目录数。 */
    fun purge(home: File, keepDays: Int): Int {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L
        var n = 0
        root(home).listFiles()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (f.lastModified() > cutoff) return@forEach
            if (f.deleteRecursively()) n++
        }
        if (n > 0) Log.i(TAG, "purged $n expired trash dir(s)")
        return n
    }
}
