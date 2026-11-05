// [dsh-android] Node 编译缓存刷盘器 —— 以 `node --require` 注入引擎进程。
//
// 为什么必须有它：
//   Node 的模块编译缓存（NODE_COMPILE_CACHE）是**懒写盘**的 —— 实测进程跑到
//   「引擎就绪」那一刻，缓存目录里还是 0 个文件；只有进程**优雅退出**时才落盘
//   （实测发 SIGTERM 后 5 秒出现 1293 个文件 / 11MB）。
//
//   而 App 侧停引擎是 kill -9 / force-stop，永远不给优雅退出的机会，
//   于是缓存**一次都不会写成功**，这个优化等于不存在。
//   （这个坑曾经让我们误判为「ESM 不支持 compile cache」—— 其实是没写盘。）
//
// 做法：启用后起一个 unref 的定时器定期 flush，进程退出时再 flush 一次。
//   unref 保证这个定时器不会阻止进程自然退出。
//   flush 是幂等的，重复调用只是把还没落盘的条目写出去。
//
// 实测收益（真机，交替 4 轮）：
//   有缓存 1449 / 1442 ms（CPU 3290 / 3380 ms）
//   无缓存 1617 / 1622 / 1606 ms（CPU 3610 / 3850 / 3810 ms）
//   → wall 省约 165ms（10%），CPU 省约 450ms（12%）
// 注意：仅当引擎环境里设了 NODE_COMPILE_CACHE 时才有意义（由 Kotlin 侧设置）。

const mod = require("node:module");
if (typeof mod.flushCompileCache === "function" && typeof mod.getCompileCacheDir === "function") {
  // getCompileCacheDir() 在未启用时为 undefined，此时不必起定时器
  const dir = mod.getCompileCacheDir();
  if (dir) {
    const flush = () => { try { mod.flushCompileCache(); } catch { /* 磁盘满/只读等不影响引擎 */ } };
    const timer = setInterval(flush, 3000);
    // unref：绝不能因为刷盘定时器把进程吊住不退出
    if (typeof timer.unref === "function") timer.unref();
    process.on("exit", flush);
    flush();
  }
}
