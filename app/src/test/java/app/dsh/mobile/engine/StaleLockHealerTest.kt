package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 残留锁判定表（[StaleLockHealer.classify]）的断言。
 *
 * ## 为什么这一层必须单独测
 *
 * 判定错了的两种后果都是**数据事故**，而且都不会在正常路径上暴露：
 *
 * - 该删没删 → 引擎照旧 40.5 秒后启动失败，用户以为功能没做；
 * - 不该删删了 → 删掉别人持有的活锁，两个写者同时改`package.json`，
 *   lockfile 与配置文件被撕裂（这正是`healForeignLocks`一直不敢碰活锁的原因）。
 *
 * 判定表是纯函数，所以断言能精确到「同一个 pid、只改一个条件，结论翻转」，
 * 不必造进程、不必造文件系统。
 *
 * ## 数据来源
 *
 * 五种形态和它们的耗时都是在模拟器 emulator-5554 上实测出来的
 * （2026-11-21，脚本 /e/tmp/probe.mjs 与 probe2.mjs）：只有「死 pid」这一种
 * 上游`takeOverExitedLock()`能自己接管，其余四种都会等到超时。
 */
class StaleLockHealerTest {

    private val selfPid = 4242

    // ── 解析 ────────────────────────────────────────────────────────────

    @Test
    fun parsesUpstreamRecordForm() {
        assertEquals(1234, StaleLockHealer.parseHolder("1234\n"))
    }

    /** 上游的正则是 pid + 换行，缺换行/带空格/多行都不合法 —— 我们判定必须同一口径。 */
    @Test
    fun rejectsRecordsUpstreamWouldNotAccept() {
        assertNull("missing trailing newline", StaleLockHealer.parseHolder("1234"))
        assertNull("leading space", StaleLockHealer.parseHolder(" 1234\n"))
        assertNull("windows line ending", StaleLockHealer.parseHolder("1234\r\n"))
        assertNull("two lines", StaleLockHealer.parseHolder("1234\n5678\n"))
        assertNull("empty file (killed mid-write)", StaleLockHealer.parseHolder(""))
        assertNull("garbage", StaleLockHealer.parseHolder("notapid\n"))
        assertNull("null (unreadable)", StaleLockHealer.parseHolder(null))
        assertNull("non-positive", StaleLockHealer.parseHolder("0\n"))
    }

    // ── 判定表 ──────────────────────────────────────────────────────────

    /** 刚建出来 100ms 的锁：可能是正在写的写者，任何情况都不删。 */
    private val FRESH = 100L

    /** 已经放了 5 分钟的锁：临界区只有毫秒级，必然没有活写者。 */
    private val OLD = 5 * 60 * 1000L

    /**
     * 死 pid：上游本来也能接管，但接管要等一个退避周期（实测 7ms 起）。
     * 我们直接删掉 —— 删除与接管结果等价，只是更快。
     */
    @Test
    fun deadHolderIsDeleted() {
        assertEquals(
            StaleLockHealer.Action.DELETE,
            StaleLockHealer.classify("999999\n", selfPid, holderAlive = false, ageMs = FRESH),
        )
    }

    /**
     * 最重要的一条：活着的、且**能证明是本 app DSH 进程**的持有者绝不删 ——
     * 哪怕锁已经很老。
     *
     * 场景：退避重试时上一轮引擎还没死透，锁是真活的。删了就是两个 node
     * 同时写 `profiles/web/package.json`。
     */
    @Test
    fun liveDshHolderIsKept() {
        assertEquals(
            StaleLockHealer.Action.KEEP,
            StaleLockHealer.classify("1234\n", selfPid, holderAlive = true, holderIsDsh = true, ageMs = OLD),
        )
    }

    /**
     * 活着的**无关**进程（pid 被复用）+ 锁已老：删。
     *
     * 这条同时封住「一律不删活锁」的保守实现 —— 那种实现等于没做功能：
     * 上游照样等到超时，40.5 秒启动失败原样复发。
     */
    @Test
    fun oldLockWithLiveForeignHolderIsDeleted() {
        assertEquals(
            StaleLockHealer.Action.DELETE,
            StaleLockHealer.classify(
                "1\n", selfPid,
                holderAlive = true, holderIsDsh = false, ageMs = OLD,
            ),
        )
    }

    /**
     * **保守断言的正面**：活着、身份证明不了（读不到 cmdline）、锁还很新
     * ——可能是正在写的那一位，保留。
     */
    @Test
    fun freshUnprovableHolderIsKept() {
        assertEquals(
            StaleLockHealer.Action.KEEP,
            StaleLockHealer.classify(
                "1234\n", selfPid,
                holderAlive = true, holderIsDsh = null, ageMs = FRESH,
            ),
        )
    }

    /**
     * 但同一个「证明不了」的持有者，锁老到 5 分钟就必须删 —— 否则 pid 复用
     * 会把引擎永久卡在等待里。
     *
     * 这两条一起才守得住「既不删活锁，也不留死锁」：单看 alive 判不出来，
     * 单看年龄又会误删被外部进程拖住的锁。
     */
    @Test
    fun oldUnprovableHolderIsDeleted() {
        assertEquals(
            StaleLockHealer.Action.DELETE,
            StaleLockHealer.classify(
                "1234\n", selfPid,
                holderAlive = true, holderIsDsh = null, ageMs = OLD,
            ),
        )
    }

    /** 取不到锁年龄（-1）：一律保守保留，绝不猜。 */
    @Test
    fun unknownAgeIsKept() {
        assertEquals(
            StaleLockHealer.Action.KEEP,
            StaleLockHealer.classify(
                "1234\n", selfPid,
                holderAlive = true, holderIsDsh = null, ageMs = -1L,
            ),
        )
    }

    /** 记录指向自己：本进程根本没持有这把锁（引擎由它 spawn，还没起来）。 */
    @Test
    fun selfPidIsDeleted() {
        val record = selfPid.toString() + "\n"
        assertEquals(
            StaleLockHealer.Action.DELETE,
            StaleLockHealer.classify(record, selfPid, holderAlive = true, holderIsDsh = true, ageMs = FRESH),
        )
    }

    /**
     * 空文件与坏内容：**不论多新都要删**。
     *
     * 这是用户报的那一幕的原型 —— 引擎被系统杀掉时正卡在
     * `open(wx)` 与 `write(pid)` 之间，留下一个 0 字节的 `.lock`。
     * 上游把「不是合法记录」一律当成「活着」，于是等到超时
     * （实测 3109ms / 3094ms；真机是 40.5 秒后整个引擎失败）。
     *
     * 为什么敢在「新鲜」时也删：本类只在 spawnEngine **之前**跑，
     * 那一刻没有任何 DSH 进程在写配置；有引擎在跑时整体会被 portInUse 跳过。
     * 这条断言对应的设备证据：`record= pid=unparsable` 的新鲜空锁被保留后，
     * 引擎 health-poll 花了 7643ms，而删掉时是 9505ms→正常就绪。
     */
    @Test
    fun malformedRecordsAreAlwaysDeleted() {
        for (age in listOf(OLD, FRESH, -1L)) {
            for (record in listOf("", "notapid\n", null)) {
                assertEquals(
                    "record=" + record + " age=" + age,
                    StaleLockHealer.Action.DELETE,
                    StaleLockHealer.classify(record, selfPid, ageMs = age),
                )
            }
        }
    }

    // ── 文件名过滤 ──────────────────────────────────────────────────────

    /**
     * `session.lock`必须放行到「永不触碰」。
     *
     * 它不是 atomic-write 的锁，而是`flock(2)`内核锁
     * （dsh-session-persistence-jsonl 的 LEASE_FILENAME）。删除文件会生成新 inode，
     * 后来的写者拿到的是一个**没人持有**的文件 → 绕开在世的持有者，真正的双写。
     * 它也不需要我们管：持有者进程一死，内核自动释放。
     */
    @Test
    fun sessionLockIsNeverTouched() {
        assertFalse(StaleLockHealer.isLockName("session.lock"))
    }

    @Test
    fun acceptsAtomicWriteLocksAndClaims() {
        assertTrue(StaleLockHealer.isLockName(".credentials.yaml.lock"))
        assertTrue(StaleLockHealer.isLockName("package.json.lock"))
        assertTrue(StaleLockHealer.isLockName("compatibility.json.lock"))
        assertTrue(StaleLockHealer.isLockName("files-v3.json.lock"))
        // takeover claim：进程死在「建了 claim、没删 claim」窗口时的永久自锁
        assertTrue(StaleLockHealer.isLockName(".credentials.yaml.lock.takeover-14d01c6abd3f99f2"))
    }

    @Test
    fun ignoresUnrelatedNames() {
        assertFalse(StaleLockHealer.isLockName("AGENTS.md"))
        assertFalse(StaleLockHealer.isLockName("package.json"))
        assertFalse(StaleLockHealer.isLockName("pnpm-lock.yaml"))
        assertFalse(StaleLockHealer.isLockName("engine.log"))
    }
}
