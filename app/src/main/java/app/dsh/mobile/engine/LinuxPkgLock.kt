package app.dsh.mobile.engine

/**
 * 容器包数据库的**进程级串行锁**。
 *
 * ## 为什么需要它
 *
 * apk / apt 的包数据库是**全局独占资源**，同一时刻只允许一个进程持有：
 *
 * ```
 * A: apk add vim     → 开始装
 * B: apk add sqlite  → ERROR: Unable to lock database: Resource temporarily unavailable
 *                      ERROR: Failed to open apk database: Resource temporarily unavailable
 *                      rc=99
 * ```
 *
 * 实测复现（同一个 rootfs、两条 `apk add` 相隔 0.3s 启动）：B 立刻以 99 失败，
 * 一个包都没装。
 *
 * 而 [LinuxContainerRunner] 的 `SEM` 允许 **3** 个容器进程并发 —— 那个数字是
 * 给「读文件 / 跑命令」这类只读操作设计的，装包走的是同一条路。于是用户在
 * 扩展中心连点几个 Install（或 AI 通道连发几条 `/ext/install`）时：
 *
 * - 第一条成功；
 * - 其余全部**红着且界面上没有任何报错**。
 *
 * 因为 apk 的 stderr 只有那句 `Unable to lock database`，而 UI 的成功判据是
 * 「包在不在」，于是它们被判成「装完了但包不在」= 静默失败。用户看到的是
 * 「我点了，它没装，也不告诉我为什么」。
 *
 * ## 为什么是独立对象而不是各自的字段
 *
 * 两条写路径分属不同类：Alpine 的 `apk` 在 [LinuxPackageManager]，
 * Ubuntu 的 `apt`/`dpkg` 在 [LinuxEnvironmentManager.installPackagesApt]。
 * 锁如果是实例字段，两个类各持一把，跨发行版就互相看不见 —— 而它们改的是
 * **同一个** rootfs 的包数据库（切发行版只是换 rootfs 目录，装的还是同一个
 * 容器）。所以锁必须与「谁在写」无关，只与「在写什么」有关。
 *
 * ## 用法
 *
 * 只锁**写**操作（update / add / del / dpkg -i）。`installed()`、`search()`
 * 这些只读查询不要锁：apk 对读不要求排他，锁住只会让状态刷新排进安装队列，
 * UI 反而更卡。
 *
 * 用 `synchronized` 而非协程 Mutex：调用方既有 `runBlocking` 的 AI 通道，
 * 也有普通后台线程，`synchronized` 对两者都成立；这些操作本身就要阻塞几十秒，
 * 占住一个线程无所谓。
 */
internal object LinuxPkgLock {
    /** 锁对象本身（`synchronized(LinuxPkgLock)` 或 `withLock` 都可用） */
    fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}
