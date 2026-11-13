#!/usr/bin/env python3
"""v1.6.0 性能回归：TurnWatcher 的 zstd 全量解压被三道闸控住。

## 防的是什么

1.4.76 的帧扫描器漏跳过 zstd 尾部的 4 字节 XXH64 校验和，扫完第 1 帧就 break，
第一帧只有文件头、解压全失败 —— 于是「整份会话解压」这条开销**从未真正发生**。
b87d7f62 修好帧扫描之后它开始真跑（真机实测 29.5 MB 会话 → 233 MB 明文 /
1317 ms / 子进程峰值 745 MB），而 4c576dfe 只把明文挪出 Java 堆，没有减少工作量。

## 判据（每一条都必须能被定点破坏触发）

- 时间闸：同一文件两次解压之间至少 MIN_DECOMPRESS_INTERVAL_MS；
- 不丢事件：时间闸内 return 时**不得**更新 zstdMtime（否则下一轮被第一道闸挡掉，
  那个 turn/end 永远不来）；
- 目录闸：walkTopDown 不得每轮都跑；
- 增量：脚本按帧序号只解新增帧，基线只数帧不解压；
- 字节扫描：count 分支不得把每帧 toString("utf8") 再 split；
- 跨帧：carrier 预置 + 已计边界，保证跨帧 token 不漏不重。
"""
import os
import re
import shutil
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
ZSTD = os.path.join(ENGINE, "ZstdTail.kt")
WATCHER = os.path.join(ENGINE, "TurnWatcher.kt")

fails = []
def check(name, ok, hint=""):
    if not ok:
        fails.append(f"[{name}] {hint}")


def body_of(sig, text):
    m = re.search(sig, text)
    if not m:
        return ""
    i = text.find("{", m.end())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return ""


watch = open(WATCHER, encoding="utf-8").read()
zstd = open(ZSTD, encoding="utf-8").read()

# ---------- 时间闸 ----------
m = re.search(r"MIN_DECOMPRESS_INTERVAL_MS\s*=\s*([0-9_]+)L", watch)
check("A1", m is not None, "没有 MIN_DECOMPRESS_INTERVAL_MS —— 文件一变就全解")
if m:
    ms = int(m.group(1).replace("_", ""))
    check("A2", ms >= 5_000, f"时间闸只有 {ms}ms —— 覆盖不到一个 turn 的写入节奏")
    check("A3", ms <= 60_000, f"时间闸 {ms}ms 太长 —— 完成通知会明显迟到")

readzstd = body_of(r"private fun readZstd\s*\(", watch)
check("B1", readzstd != "", "readZstd 找不到")
if readzstd:
    # 时间闸必须在 stats 之前
    i_gate = readzstd.find("MIN_DECOMPRESS_INTERVAL_MS")
    i_stats = readzstd.find("ZstdTail.stats")
    check("B2", i_gate >= 0 and i_stats > i_gate,
          "时间闸没挡在 ZstdTail.stats 之前 —— 闸门在花钱之后就没意义")
    # 关键：任何一道闸 return 之前都不能落 stamp（否则下一轮被「没变化」挡掉，
    # turn/end 永久丢失）。判据范围是「函数开头 → stats 调用」整段，不是只看
    # 时间闸那几行 —— 变异实测：把 stamp 写在第一道闸之前，只看闸块是抓不到的。
    check("B3", i_stats > 0, "没找到 ZstdTail.stats 调用")
    gate_region = readzstd[:i_stats] if i_stats > 0 else ""
    check("B4", "zstdMtime[key] = stamp" not in gate_region,
          "stats 之前就落了 stamp —— 任一道闸 return 后事件永久丢失")
    # stamp 必须在 stats 之后才落库
    i_stamp_commit = readzstd.find("zstdMtime[key] = stamp", readzstd.find("ZstdTail.stats"))
    check("B5", i_stamp_commit > 0,
          "stamp 没有在解压之后才落库 —— 失败/被闸挡也会被当成「已处理」")

# ---------- 目录闸 ----------
m = re.search(r"DIR_SCAN_EVERY_POLLS\s*=\s*(\d+)", watch)
check("C1", m is not None, "没有 DIR_SCAN_EVERY_POLLS —— 每 1.5 秒仍在全量 walk 508 个文件")
if m:
    n = int(m.group(1))
    check("C2", n >= 2, f"DIR_SCAN_EVERY_POLLS={n} —— 等于没节流")
    check("C3", n <= 20, f"DIR_SCAN_EVERY_POLLS={n} —— 新会话要等太久才被发现")

scan = body_of(r"private fun scan\s*\(", watch)
if scan:
    i_poll = scan.find("pollCount")
    i_walk = scan.find("walkTopDown")
    check("C4", i_poll >= 0 and i_walk > i_poll,
          "walkTopDown 不在 pollCount 节流之后 —— 每轮照样全量遍历")
    # 只判「pollCount 在 walk 前面」不够：把 needRescan 改成 true 仍然满足。
    # 必须要求节流**条件**真的引用了 DIR_SCAN_EVERY_POLLS。
    #
    # 而且这条判据必须剥注释 —— scan 的注释里就写着「按 [DIR_SCAN_EVERY_POLLS]
    # 节流」，按全文 in 判，把 needRescan 改成 true 照样绿（变异实测踩到）。
    scan_code = "\n".join(
        line for line in scan.splitlines()
        if not line.lstrip().startswith("//")
    )
    cond = ""
    for line in scan_code.splitlines():
        if "needRescan =" in line:
            cond = line.strip()
    check("C6", cond != "" and "DIR_SCAN_EVERY_POLLS" in cond,
          f"目录遍历没有真正按 DIR_SCAN_EVERY_POLLS 节流（needRescan 恒真也能过）：{cond!r}")
    check("C5", "cachedLogs" in scan,
          "没有沿用上一轮的会话清单 —— 两次扫描之间无法只 stat 那几个文件")

# ---------- 增量：脚本侧 ----------
script_m = re.search(r'private const val SCRIPT = """(.*?)"""', zstd, re.S)
check("D1", script_m is not None, "脚本常量没找到")
if script_m:
    js = script_m.group(1)
    # 必须锚在 `if (mode === "count") {` 这条**语句**上，不能锚 `mode === "count"`
    # —— 前者前面还有 `const fromFrameRaw = (mode === "count") ? ...` 和基线块，
    # 一律会把它切到错误位置（实测把基线块的 exit 当成 count 分支的结尾）。
    cnt_i = js.find('if (mode === "count") {')
    end_i = js.find("process.exit(0);", cnt_i)
    branch = js[cnt_i:end_i] if cnt_i >= 0 and end_i > 0 else ""
    check("D2", branch != "", "count 分支找不到")
    # ⚠️ 判据必须只看**代码**，不能看注释：本分支的注释里就解释了
    # 「原来用 toString("utf8") + split("\n")」——直接用 in 判会把正确实现判红（踩过）。
    code = "\n".join(
        line for line in branch.splitlines()
        if not line.lstrip().startswith("//")
    )
    # 按字节扫，不做 UTF-16 解码、不按行切
    check("D3", 'toString("utf8")' not in code,
          "count 分支仍在 toString(\"utf8\") —— 46MB 帧会先变成 92MB UTF-16 字符串")
    check("D4", 'split("\\n")' not in code,
          "count 分支仍在按行 split —— 每行一个对象，峰值内存随行数爆炸")
    check("D5", "Buffer.from" in branch,
          "没有按字节构造 token —— 那只能靠解码后的字符串比较")
    # 增量基准
    check("D6", "process.argv[4]" in js,
          "脚本不收帧序号 —— 无法只解新增帧")
    check("D7", "fromFrame" in js,
          "脚本没有 fromFrame 概念 —— 每次仍从第 0 帧全解")
    # 基线：只数帧不解压，且最后一帧留待下次
    check("D8", "fromFrameRaw < 0" in js,
          "没有「只数帧不解压」的基线模式 —— 首次见到会话就要解一遍几百 MB")
    # ⚠️ 只看代码：上面那句注释里也写着 `frames.length - 1`，先前按全文 in 判，
    # 变异（把基线改成 frames.length）居然仍绿 —— 典型假绿。
    js_code = "\n".join(
        line for line in js.splitlines()
        if not line.lstrip().startswith("//")
    )
    check("D9", "frames.length - 1" in js_code,
          "基线没有让出最后一帧 —— 跨基线边界的 token 会永久丢失")
    # 跨帧 carry
    check("E1", "carrier" in branch and "CARRIER" in branch,
          "没有跨帧 carrier —— 帧边界落在 token 中间时会漏")
    m_car = re.search(r"const CARRIER = (\d+);", js)
    check("E1b", m_car is not None and int(m_car.group(1)) > 0,
          f"CARRIER 不是正数（{m_car.group(1) if m_car else '缺失'}）—— 跨帧 token 仍会漏")
    # 判重必须用 token 的**结束偏移**（不是起点）。
    #
    # 起点判重两个方向都错，用合成文件实测过：
    #  · 恰好结束在帧尾的 token，会在下一帧的 carrier 里被再看到一次 → 多计（1 变 2）；
    #  · 起点落在 carrier 里、结束在本帧的 token，谁都不认 → 漏计。
    # 所以：hits++ 必须由 endAt > scannedUpTo 把关，且 scannedUpTo 每帧推进到
    # 帧尾（globalStart），不能再退回 globalStart - CARRIER。
    check("E2", re.search(r"endAt > scannedUpTo", code) is not None,
          "没有「token 结束偏移 > 已扫边界」的判重 —— 结束在帧尾的 token 会被下一帧再计一次")
    check("E2c", "countedUpTo" not in js_code,
          "脚本里还留着 countedUpTo —— 起点判重会漏掉「起点在 carrier、结束在本帧」的 token")
    scan_adv = ""
    for line in code.splitlines():
        if "scannedUpTo =" in line:
            scan_adv = line.strip()
    check("E2d", "scannedUpTo = globalStart" in scan_adv and "CARRIER" not in scan_adv,
          f"每帧推进的边界退回了 globalStart - CARRIER —— 帧尾 token 会被再计一次：{scan_adv!r}")
    check("E3", "from > 0" in js and "frames[from - 1]" in js,
          "增量起点没有预置上一帧尾巴 —— 跨界 token 谁也看不到")

# ---------- 反向自检：判据能区分旧实现 ----------
old_js = '''
let bytes = 0, hits = 0, carry = "";
for (let i = 0; i < frames.length; i++) {
  const text = carry + chunk.toString("utf8");
  for (const line of text.slice(0, lastNl).split("\\n")) if (isTurnEnd(line)) hits++;
}
'''
check("F1", 'toString("utf8")' in old_js and 'split("\\n")' in old_js,
      "反向自检失效：旧脚本没被识别为「解码后切行」")
check("F2", "Buffer.from" not in old_js and "carrier" not in old_js,
      "反向自检失效：旧脚本被判成新的字节实现")

old_gate = '''
val stamp = f.lastModified() xor (f.length() shl 21)
synchronized(zstdMtime) { zstdMtime[key] = stamp }
if (prev != null && prev == stamp) return
val (stats, why) = ZstdTail.stats(ctx, f)
'''
check("F3", old_gate.index("zstdMtime[key] = stamp") < old_gate.index("ZstdTail.stats"),
      "反向自检失效：旧写法（先写 stamp 再解）没有被识别出来")

# ---------- 行为验证：真跑脚本，跨帧不重不漏 ----------
#
# 上面那些都只是源码静态判据 —— 而「按起点还是按结束判重」这种差异，
# 静态判据只能钉住形状，钉不住结果。这里把提取出的脚本**真的跑起来**，
# 喂手工构造的合成会话，断言命中数。每项是 (base64, 起始帧, 期望命中)：
#
#   A      token 完全在帧内                        → 全解 1
#   B      token 跨帧（一半在前一帧尾、一半在后）  → 全解 1（carrier 救回来）
#   C      对照：token 在帧中间                    → 全解 1
#   D      8 帧混合（帧内 / 跨帧 / 恰好贴帧尾对齐） → 全解 3
#   D4     D 的前 4 帧快照                          → 全解 2
#   D@4    D 从第 4 帧接着解（生产增量路径）        → 1
#   E@1    跨帧 token 的后半落在增量起点帧          → 全解 1、从第 1 帧解 1（靠 carrier）
#
# D4 的 2 加 D@4 的 1 正好等于 D 全解的 3 —— 这条是增量语义的直接证据：
# 分两段跑与一次跑完结果相同，说明前缀帧没有重复计、边界帧没有漏。
#
# 这些期望值是拿**变异**挑出来的，不是照抄实现：
#  · 判重改回「按起点 + 边界退回 globalStart-CARRIER」→ A 从 1 变 2、D 从 3 变 6；
#  · 判重改回「按起点」但仍推进到帧尾 → B 从 1 变 0、D 从 3 变 2；
#  · 去掉 from>0 的 carrier 预置 → D@4 从 1 变 0。
# 三种破坏都被这组用例抓到，所以它们能真正区分实现好坏。
FIXTURES = {
    "A": ("KLUv/WBAAO0AALBhYXsidHlwZSI6InR1cm4vZW5kIn0KAQAnKsACKLUv/SAUoQAAeyJ0eXBlIjoic3RlcC9lbmQifQo=", 0, 1),
    "B": ("KLUv/WA4AK0AAHBhYXsidHlwZSI6InR1cgEAJyrAAii1L/0gHOEAAG4vZW5kIn0KeyJ0eXBlIjoic3RlcC9lbmQifQo=", 0, 1),
    "C": ("KLUv/WBAAA0BALhhYXsidHlwZSI6InR1cm4vZW5kIn0KYgIAJYBiEQpgASi1L/0gFKEAAHsidHlwZSI6InN0ZXAvZW5kIn0K", 0, 1),
    "D": ("KLUv/SCM5QAAsHp6eyJ0eXBlIjoidHVybi9lbmQifQoBADMFWCi1L/0ghU0AABB6egEAAApgASi1L/0gnq0AAHB6ensidHlwZSI6InR1cgEADQpgASi1L/0gp4UAAEhuL2VuZCJ9CnoBABsKHAEotS/9IKxNAAAQenoBACcKYAEotS/9IM3tAACwenp7InR5cGUiOiJ0dXJuL2VuZCJ9CgEANApgASi1L/0gxk0AABB6egEAQQpgASi1L/0g000AABB6egEATgpgAQ==", 0, 3),
    "D4": ("KLUv/SCM5QAAsHp6eyJ0eXBlIjoidHVybi9lbmQifQoBADMFWCi1L/0ghU0AABB6egEAAApgASi1L/0gnq0AAHB6ensidHlwZSI6InR1cgEADQpgASi1L/0gp4UAAEhuL2VuZCJ9CnoBABsKHAE=", 0, 2),
    # E@1：跨帧 token 的后半正好落在增量起点那一帧 —— 少了 carrier 预置就是 0。
    "E@1": ("KLUv/SDUrQAAcGFheyJ0eXBlIjoidHVyAQBDCmABKLUv/SDQhQAASG4vZW5kIn0KYgEARAocAQ==", 1, 1),
    "D@4": ("KLUv/SCM5QAAsHp6eyJ0eXBlIjoidHVybi9lbmQifQoBADMFWCi1L/0ghU0AABB6egEAAApgASi1L/0gnq0AAHB6ensidHlwZSI6InR1cgEADQpgASi1L/0gp4UAAEhuL2VuZCJ9CnoBABsKHAEotS/9IKxNAAAQenoBACcKYAEotS/9IM3tAACwenp7InR5cGUiOiJ0dXJuL2VuZCJ9CgEANApgASi1L/0gxk0AABB6egEAQQpgASi1L/0g000AABB6egEATgpgAQ==", 4, 1),
}

node = shutil.which("node") or shutil.which("node.exe")
if not script_m:
    check("G0", False, "脚本常量缺失，无法做行为验证")
elif not node:
    print("  (skip behaviour fixtures: no node on PATH -- static checks above still apply)")
else:
    import base64
    import json
    import subprocess
    import tempfile
    # 必须用 node -e 传脚本，不能把脚本写成 .js 再 node file.js：
    # 生产代码就是这么调的（ZstdTail.stats 用 -e → argv[1] 是数据文件）。
    # 存成脚本文件的话 argv[1] 变成脚本自身、数据文件挪到 argv[2]，
    # 脚本读到的「文件」其实是脚本路径 → 一律 rc=3 no complete zstd frame，
    # 用例全红却与实现无关（本轮踩过）。
    tmpdir = tempfile.mkdtemp(prefix="twperf-")
    js_src = script_m.group(1)
    for name, (b64, from_frame, expect) in FIXTURES.items():
        zst = os.path.join(tmpdir, name.replace("@", "_at_") + ".zst")
        with open(zst, "wb") as fh:
            fh.write(base64.b64decode(b64))
        r = subprocess.run([node, "-e", js_src, zst, "", "count", str(from_frame)],
                           capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            check("G1" + name, False, f"脚本跑失败 rc={r.returncode}: {r.stderr.strip()[:200]}")
            continue
        try:
            hits = json.loads(r.stdout.strip()).get("hits")
        except Exception as exc:
            check("G1" + name, False, f"输出不是 JSON（{exc}）: {r.stdout[:120]!r}")
            continue
        check("G1" + name, hits == expect,
              f"合成会话 {name}（from={from_frame}）命中 {hits}，应为 {expect}")
    for name in FIXTURES:
        try:
            os.unlink(os.path.join(tmpdir, name.replace("@", "_at_") + ".zst"))
        except OSError:
            pass
    try:
        os.rmdir(tmpdir)
    except OSError:
        pass

print("turnwatcher perf checks done")
if fails:
    print("FAILED (%d):" % len(fails))
    for f in fails:
        print("  " + f)
    sys.exit(1)
print("OK")
