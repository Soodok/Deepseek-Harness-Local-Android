package app.dsh.mobile.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * zstd 多帧会话解压（v1.2.105）。
 *
 * ## 为什么需要（主人实测「decompress failed: session.v4.jsonl.zstd」）
 * dsh 的会话日志是**多帧串联容器**：`dsh-session-persistence-jsonl` 把 header
 * 压成第一帧，之后每批事件各压一帧（源码注释：backend owns a concatenated-frame
 * container so it can append and recover batches without exposing compression
 * mechanics）。
 *
 * 而 **Node 的 zstd API 只认第一帧** —— `zstdDecompressSync(整个文件)` 与
 * `createZstdDecompress()` 流式**都只吐第一帧**（2026-10-09 本地实测确认），
 * 而第一帧按设计**只有 header、没有对话内容**。于是悬浮条永远拿不到正文：
 * 解出来是 `{"type":"header",...}`，调用方解析后为空 → 表现为什么都读不到。
 *
 * 帧边界**必须完整解析**：magic 4 字节在压缩数据里可能巧合出现，扫 magic 会切错。
 * [SCRIPT] 里的扫描逻辑照搬 dsh 的 `scanZstdFrames`（frame header descriptor +
 * 逐块 block header 走到 last block）—— 属事实性结构，照抄即正确。
 *
 * ## 其他两处一起修的坑
 * - **超时 8s → 20s**：大会话整体解压 + 管道传输，8 秒在真机上不够。
 * - **失败原因回传**：返回值带诊断串，悬浮条能直接显示「为什么解不开」。
 */
object ZstdTail {

    private const val TAG = "ZstdTail"

    /**
     * 解压脚本（**内联**，不做资产部署）。
     *
     * 刻意内联：悬浮条是「出问题时唯一的观测窗口」，它的依赖越少越好 ——
     * 若改成部署到引擎目录的独立文件，部署失败就等于把唯一的窗口也关掉。
     */
    private const val SCRIPT = """
const zlib = require("node:zlib"), fs = require("node:fs");
const ZSTD_MAGIC = 0xFD2FB528;
function scanFrames(buf) {
  const frames = [];
  let off = 0;
  while (off + 4 <= buf.length) {
    const start = off;
    if (buf.readUInt32LE(off) !== ZSTD_MAGIC) break;
    off += 4;
    if (off >= buf.length) break;
    const descriptor = buf.readUInt8(off);
    off += 1;
    if ((descriptor & 24) !== 0) break;
    const contentSizeFlag = descriptor >>> 6;
    const singleSegment = (descriptor & 32) !== 0;
    const dictionaryFlag = descriptor & 3;
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag;
    const contentSizeBytes = contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
    const remainingHeaderBytes = (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
    if (off + remainingHeaderBytes > buf.length) break;
    off += remainingHeaderBytes;
    let complete = true;
    for (;;) {
      if (off + 3 > buf.length) { complete = false; break; }
      const blockHeader = buf.readUIntLE(off, 3);
      off += 3;
      const lastBlock = (blockHeader & 1) === 1;
      const blockType = (blockHeader >>> 1) & 3;
      const blockSize = blockHeader >>> 3;
      const bytes = blockType === 1 ? 1 : blockSize;
      if (off + bytes > buf.length) { complete = false; break; }
      off += bytes;
      if (lastBlock) break;
    }
    if (!complete) break;
    // v1.5.0-beta.7 real-device root cause: frame header descriptor bit 2 is the
    // Content_Checksum_flag. When set, 4 trailing XXH64 checksum bytes follow the
    // last block. The old scanner never skipped them, so the next frame magic fell
    // inside the checksum, the scan stopped immediately, and only the FIRST frame
    // was ever parsed -- and frame 1 holds only the header, never conversation
    // content. That is exactly the blank ticker bar / "session decompress failed"
    // seen on device. Measured sample: 44325 bytes, 19 frames, descriptor=0x04.
    // Without this skip: 1 frame, 182/44325 bytes. With it: 19 frames, all bytes.
    if ((descriptor & 4) !== 0) {
      if (off + 4 > buf.length) break;
      off += 4;
    }
    frames.push([start, off]);
  }
  return frames;
}
let buf;
try { buf = fs.readFileSync(process.argv[1]); }
catch (e) { process.stderr.write("read failed: " + e.message); process.exit(2); }
const frames = scanFrames(buf);
if (frames.length === 0) { process.stderr.write("no complete zstd frame"); process.exit(3); }
const raw = process.argv[2];
// mode=count returns ONLY {bytes,hits} (a few dozen bytes). It never hands the
// decompressed plaintext to the Java heap -- that is what caused issue #104:
// a 38 MB / 21,788-frame session expands to 128 MB, and ByteArrayOutputStream
// doubles to a 256 MB request while only 163 MB of a 402 MB heap was free.
// Both numbers are computed here so Java never holds the text.
const mode = process.argv[3] || "text";
const keep = (raw === undefined || raw === "") ? 24 : Number(raw);
const from = (mode === "count") ? 0 : (keep > 0 ? Math.max(0, frames.length - keep) : 0);
if (mode === "count") {
  // Mirrors TurnWatcher.isTurnEnd exactly: find "type", then read the quoted
  // value. Scans frame by frame and line by line without accumulating the whole
  // plaintext -- only one partial line is carried across frame boundaries.
  let bytes = 0, hits = 0, carry = "";
  const isTurnEnd = (line) => {
    const i = line.indexOf("\"type\"");
    if (i < 0) return false;
    const rest = line.slice(i + 6);
    const colon = rest.indexOf(":");
    if (colon < 0) return false;
    const after = rest.slice(colon + 1).trimStart();
    if (after[0] !== "\"") return false;
    const end = after.indexOf("\"", 1);
    if (end < 0) return false;
    return after.slice(1, end) === "turn/end";
  };
  for (let i = from; i < frames.length; i++) {
    const sf = frames[i][0], ef = frames[i][1];
    let chunk;
    try { chunk = zlib.zstdDecompressSync(buf.subarray(sf, ef)); } catch (_) { continue; }
    bytes += chunk.length;
    const text = carry + chunk.toString("utf8");
    const lastNl = text.lastIndexOf("\n");
    if (lastNl < 0) { carry = text; continue; }
    const complete = text.slice(0, lastNl);
    for (const line of complete.split("\n")) {
      if (line.length && isTurnEnd(line)) hits++;
    }
    carry = text.slice(lastNl + 1);
  }
  process.stdout.write(JSON.stringify({ bytes, hits }));
  process.exit(0);
}
const out = [];
for (let i = from; i < frames.length; i++) {
  const s = frames[i][0], e = frames[i][1];
  try { out.push(zlib.zstdDecompressSync(buf.subarray(s, e))); } catch (_) {}
}
if (out.length === 0) { process.stderr.write("all " + frames.length + " frame(s) failed"); process.exit(4); }
process.stdout.write(Buffer.concat(out));
"""

    /** 只解尾部这么多帧：最新内容在最后，全解在大会话上要几秒且无收益 */
    private const val KEEP_FRAMES = 24

    /**
     * 解压会话明文。
     *
     * @param keepFrames 只解尾部这么多帧；**传 0 表示全解**。
     *   TurnWatcher 必须传 0 —— 它按「解压后文本长度」做增量去重，
     *   只解尾部帧会让长度不再单调增长，turn/end 检测会静默失效。
     * @return (明文, 错误原因)。成功时错误为 null；失败时明文为 null、原因为人话串
     *         （会原样出现在悬浮条诊断里，故保持英文短句）。
     */
    fun read(ctx: Context, file: File, keepFrames: Int = KEEP_FRAMES): Pair<String?, String?> {
        val node = EngineConfig.nodeBin(ctx)
        if (!node.canExecute()) return null to "engine node not executable"
        return try {
            val pb = ProcessBuilder(
                node.absolutePath, "-e", SCRIPT, file.absolutePath, keepFrames.toString(), "text",
            )
            val root = EngineConfig.engineRoot(ctx)
            // ⚠️ v1.5.0-beta.7 真机第二根因：**漏了 OPENSSL_CONF**。
            // 旧代码只给子进程注入了 LD_LIBRARY_PATH 与 HOME，而 engine 内置的 node
            // 是 Termux 交叉编译产物，编译期把 OpenSSL 配置路径写死成
            // `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`（本机不存在）。
            // 于是 node 一启动就 `OpenSSL configuration error` 并以 **exit=13** 退出，
            // ZstdTail 必然失败 —— 这才是「模拟器正常、真机不行」的真正差异来源
            // （模拟器上那台常有用 Termux 或该路径恰好可读）。
            // 实测日志：`W ZstdTail: zstd exit=13: .../engine/bin/node: OpenSSL configuration error:`
            pb.environment()["LD_LIBRARY_PATH"] =
                "${root.absolutePath}/lib:${root.absolutePath}/usr/lib"
            pb.environment()["HOME"] = EngineConfig.dshHome(ctx).absolutePath
            // 与 EngineConfig.buildEnv 的注入保持一致（见 EngineConfig.kt 的 buildEnv）。
            File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let {
                pb.environment()["OPENSSL_CONF"] = it.absolutePath
            }
            pb.environment()["TMPDIR"] = EngineConfig.tmpDir(ctx).absolutePath
            val p = pb.start()
            val out = p.inputStream.readBytes()
            val err = p.errorStream.readBytes()
            // ⚠️ 旧实现只等 8 秒（见文件顶部）：大会话在真机上不够，超时后静默返回 null，
            // 用户看到的就是「解压失败」而没有别的线索。
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroy()
                return null to "timeout 20s"
            }
            if (p.exitValue() != 0) {
                val why = String(err, Charsets.UTF_8).trim().take(80)
                Log.w(TAG, "zstd exit=${p.exitValue()}: $why")
                return null to if (why.isEmpty()) "exit=${p.exitValue()}" else why
            }
            String(out, Charsets.UTF_8) to null
        } catch (e: Exception) {
            Log.w(TAG, "zstd spawn failed: ${e.message}")
            null to (e.message ?: "spawn failed")
        }
    }

    /** [stats] 的结果：解压后明文总字节数 + 其中的 turn/end 事件数。 */
    data class Stats(val bytes: Long, val turnEnds: Int)

    /**
     * 只统计「解压后有多长」和「几个 turn/end」，**不把明文回传给 Java 堆**。
     *
     * ## 为什么需要这个入口（issue #104）
     *
     * TurnWatcher 是全量解压的唯一存活调用方（悬浮条的 TickerBar / SessionTail
     * 已于 v1.4.x 整条移除）。它原来走 `read(keepFrames = 0)` 拿完整明文，再用
     * `text.length` 做增量去重、用 `delta.lineSequence()` 数 turn/end。而
     * `readBytes()` 没有上限：
     *
     * ```
     * 压缩后   38.38 MB（21,788 帧）
     * 解压后  128.3 MB
     * ByteArrayOutputStream 按 2 倍扩容 → 申请 256 MB
     * 402 MB 堆里已用 239 MB，剩 163 MB → OutOfMemoryError
     *     at ZstdTail.read(ZstdTail.kt:146)
     * ```
     *
     * 而这两个数字在 Node 侧算完只有几十字节。改成 count 模式后，Java 侧内存
     * 占用与「会话有多大」**完全解耦**：38 MB 会话与 380 MB 会话都是几十字节。
     *
     * 注意语义：`bytes` 是**解压后总字节数**，不是字符数。原来用
     * `text.length`（UTF-16 码元数），中文比字节数少 —— 但那个值只用于
     * 「有没有增长」的比较，单调性一致，所以两者都正确。用字节数更准
     * （不受解码替换字符影响）。
     *
     * @return (统计, 错误原因)。超时/退出码非 0/JSON 解析失败都返回 null + 原因。
     */
    fun stats(ctx: Context, file: File, timeoutSeconds: Long = 60L): Pair<Stats?, String?> {
        val node = EngineConfig.nodeBin(ctx)
        if (!node.canExecute()) return null to "engine node not executable"
        return try {
            // keepFrames 传 "" → 脚本内 count 模式固定从第 0 帧开始全解，
            // 与原先 keepFrames=0 的语义一致（长度必须单调）。
            val pb = ProcessBuilder(
                node.absolutePath, "-e", SCRIPT, file.absolutePath, "", "count",
            )
            val root = EngineConfig.engineRoot(ctx)
            pb.environment()["LD_LIBRARY_PATH"] =
                "${root.absolutePath}/lib:${root.absolutePath}/usr/lib"
            pb.environment()["HOME"] = EngineConfig.dshHome(ctx).absolutePath
            File(root, "etc/tls/openssl.cnf").takeIf { it.isFile }?.let {
                pb.environment()["OPENSSL_CONF"] = it.absolutePath
            }
            pb.environment()["TMPDIR"] = EngineConfig.tmpDir(ctx).absolutePath
            val p = pb.start()
            // ⚠️ 这里必须继续用 readBytes()，但它读的是**几十字节的 JSON**，
            // 不是明文。count 模式下脚本只写 {"bytes":N,"hits":M}。
            val out = p.inputStream.readBytes()
            val err = p.errorStream.readBytes()
            // 大会话全解要几秒（128 MB 实测约 3~6 秒），但绝不该等到分钟级。
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroy()
                return null to "timeout ${timeoutSeconds}s"
            }
            if (p.exitValue() != 0) {
                val why = String(err, Charsets.UTF_8).trim().take(80)
                Log.w(TAG, "zstd count exit=${p.exitValue()}: $why")
                return null to if (why.isEmpty()) "exit=${p.exitValue()}" else why
            }
            // 用 org.json 而不是正则：正则里的 \s / \d 在 Kotlin 字符串里是非法转义，
            // 且这类「解析结构化输出」本来就有现成的解析器。
            val json = runCatching { org.json.JSONObject(String(out, Charsets.UTF_8).trim()) }
                .getOrElse { return null to "bad stats json" }
            Stats(json.optLong("bytes", -1L), json.optInt("hits", -1)) to null
        } catch (e: Exception) {
            Log.w(TAG, "zstd count failed: ${e.message}")
            null to (e.message ?: "stats failed")
        }
    }
}
