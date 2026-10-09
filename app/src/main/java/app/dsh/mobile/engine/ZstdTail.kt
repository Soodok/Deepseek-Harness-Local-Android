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
 * 解出来是 `{"type":"header",...}`，SessionTail 解析后为空 → 条上一片空白。
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
const keep = (raw === undefined || raw === "") ? 24 : Number(raw);
const from = keep > 0 ? Math.max(0, frames.length - keep) : 0;
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
                node.absolutePath, "-e", SCRIPT, file.absolutePath, keepFrames.toString(),
            )
            val root = EngineConfig.engineRoot(ctx)
            pb.environment()["LD_LIBRARY_PATH"] =
                "${root.absolutePath}/lib:${root.absolutePath}/usr/lib"
            pb.environment()["HOME"] = EngineConfig.dshHome(ctx).absolutePath
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
}
