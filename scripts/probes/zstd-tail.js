// zstd 多帧会话解压（只取尾部若干帧）
//
// 为什么需要（v1.2.105，主人实测「decompress failed: session.v4.jsonl.zstd」）：
// dsh 的会话日志是**多帧串联容器** —— dsh-session-persistence-jsonl 的 zstd 后端
// 把 header 压成第一帧、之后每批事件各压一帧（源码注释：backend owns a
// concatenated-frame container so it can append and recover batches）。
// 而 Node 的 zstd API **只认第一帧**：zstdDecompressSync(整个文件) 与
// createZstdDecompress() 流式都只吐第一帧（本地实测确认），
// 而第一帧按设计**只有 header、没有对话内容** → 悬浮条永远拿不到正文。
//
// 帧边界必须**完整解析**（不能扫 magic 猜）：magic 4 字节在压缩数据里可能巧合出现。
// 本文件的扫描逻辑照搬 dsh 的 scanZstdFrames（事实性结构，照抄即正确）。
//
// 用法: node zstd-tail.js <会话文件> [保留帧数，默认 24]
// 输出: 解出的尾部若干帧明文（UTF-8）到 stdout；无法解析时非零退出。

const zlib = require("node:zlib");
const fs = require("node:fs");

const ZSTD_MAGIC = 0xFD2FB528; // dsh 源码里的 4247762216

/** 完整解析帧结构，返回每个完整帧的 [start, end) 偏移（照搬 dsh scanZstdFrames 的解析路径） */
function scanFrames(buf) {
  const frames = [];
  let off = 0;
  while (off + 4 <= buf.length) {
    const start = off;
    if (buf.readUInt32LE(off) !== ZSTD_MAGIC) break;
    off += 4;
    if (off >= buf.length) break; // 尾部截断
    const descriptor = buf.readUInt8(off);
    off += 1;
    if ((descriptor & 24) !== 0) break; // 保留位非零 = 不是合法帧头
    const contentSizeFlag = descriptor >>> 6;
    const singleSegment = (descriptor & 32) !== 0;
    const dictionaryFlag = descriptor & 3;
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag;
    const contentSizeBytes =
      contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
    const remainingHeaderBytes =
      (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
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
      // RLE 块：内容只占 1 字节（blockSize 表示解压后大小）；raw/compressed：字节数 = blockSize
      const bytes = blockType === 1 ? 1 : blockSize;
      if (off + bytes > buf.length) { complete = false; break; }
      off += bytes;
      if (lastBlock) break;
    }
    if (!complete) break; // 最后一帧不完整（写入中断）→ 不纳入
    frames.push([start, off]);
  }
  return frames;
}

const file = process.argv[2];
const keep = Number(process.argv[3] || 24);
if (!file) {
  process.stderr.write("usage: node zstd-tail.js <session file> [frames]\n");
  process.exit(2);
}

let buf;
try {
  buf = fs.readFileSync(file);
} catch (e) {
  process.stderr.write("read failed: " + e.message + "\n");
  process.exit(2);
}

const frames = scanFrames(buf);
if (frames.length === 0) {
  process.stderr.write("no complete zstd frame in " + file + "\n");
  process.exit(3);
}

// 只解尾部若干帧：头部帧只是 header，最新的事件都在最后；全解在大会话上要几秒
const from = Math.max(0, frames.length - keep);
const out = [];
for (let i = from; i < frames.length; i++) {
  const [s, e] = frames[i];
  try {
    out.push(zlib.zstdDecompressSync(buf.subarray(s, e)));
  } catch (_) {
    // 单帧损坏不应毁掉整次读取：跳过，保留其余帧的明文
  }
}
if (out.length === 0) {
  process.stderr.write("all " + frames.length + " frame(s) failed to decompress\n");
  process.exit(4);
}
process.stdout.write(Buffer.concat(out));
