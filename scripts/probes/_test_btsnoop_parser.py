#!/usr/bin/env python3
"""锁定 btsnoop 二进制解析（P0-3）的三块契约。

1. **格式解析**：用一份合成的 191 字节 / 5 包样本，按 BtsnoopParser 的逻辑
   逐字节复核 —— 文件头魔数 / version / datalink、记录头字段顺序、载荷切分。
2. **尾部重对齐**：resyncTail 必须在各种切口上还原出与完整解析一致的包序列。
3. **管道死锁回归**：`Privilege.runSu` 必须「先并发抽干 stdout 再 waitFor」，
   否则回大响应（binary 模式 349KB base64）时双方互等直到超时。

第 1、2 项是纯字节运算，第 3 项是源码契约检查 —— 都不需要真机。
"""

import pathlib
import struct
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/app/dsh/mobile"

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(p):
    return p.read_text(encoding="utf-8")


# ── 用 Python 复刻 BtsnoopParser 的解析逻辑（与 Kotlin 逐行对应） ──────────
MAGIC = b"btsnoop\x00"
HEADER_SIZE = 16
RECORD_SIZE = 24
MAX_PAYLOAD = 65536


def _i32(d, at):
    return int.from_bytes(d[at:at + 4], "big", signed=True)


def _record(d, off):
    incl = _i32(d, off + 4)
    if incl < 0 or incl > MAX_PAYLOAD:
        return None
    if off + RECORD_SIZE + incl > len(d):
        return None
    flags = _i32(d, off + 8)
    # 记录头 >IIIIQ：orig@0 incl@4 flags@8 drops@12 timestamp@16（8 字节）
    ts = (_i32(d, off + 16) & 0xFFFFFFFF) << 32 | (_i32(d, off + 20) & 0xFFFFFFFF)
    payload = d[off + RECORD_SIZE:off + RECORD_SIZE + incl]
    return dict(ts=ts, recv=bool(flags & 1),
                ptype=(payload[0] if payload else 0),
                payload=payload, size=RECORD_SIZE + incl)


def parse_records(d, start):
    pkts, off, idx = [], start, 0
    while off + RECORD_SIZE <= len(d):
        r = _record(d, off)
        if r is None:
            # 有头但载荷越过末尾：与 Kotlin 一致，停下但保留已解析部分
            if off + RECORD_SIZE + max(0, _i32(d, off + 4)) > len(d):
                break
            return None
        pkts.append(dict(i=idx, **r))
        off += r["size"]
        idx += 1
    return dict(pkts=pkts, consumed=off - start, trailing=len(d) - off)


def parse_full(d, start=0):
    if len(d) - start < HEADER_SIZE:
        return None
    if d[start:start + 8] != MAGIC:
        return None
    ver = _i32(d, start + 8)
    dl = _i32(d, start + 12)
    rec = parse_records(d, start + HEADER_SIZE)
    if rec is None:
        return None
    return dict(ver=ver, dl=dl, **rec)


RESYNC_SCAN = 4096


def resync(d):
    """与 Kotlin resyncTail 同策略：宽窗口 + 三项合法性校验 + 按吃掉字节数取胜。"""
    best = None
    limit = min(RESYNC_SCAN, max(0, len(d) - RECORD_SIZE))
    for skip in range(limit + 1):
        if len(d) - skip < RECORD_SIZE:
            break
        r = parse_records(d, skip)
        if r is None or r["trailing"] != 0 or not r["pkts"]:
            continue
        if any(not (1 <= p["ptype"] <= 5) for p in r["pkts"]):
            continue
        if any(r["pkts"][i]["ts"] > r["pkts"][i + 1]["ts"] for i in range(len(r["pkts"]) - 1)):
            continue
        if best is None or r["consumed"] > best["consumed"]:
            best = r
    return best


# ── 构造合成样本 ────────────────────────────────────────────────────────
PAYLOADS = [
    (0x00, bytes.fromhex("01030c00")),
    (0x01, bytes.fromhex("040e0401030c00")),
    (0x00, bytes.fromhex("0141180d00112233445566010000000000000000000000000000")),
    (0x01, bytes.fromhex("040f0400010408")),
    (0x00, bytes.fromhex("0220010006aabbccddeeff")),
]
BASE_TS = 63961679127858871

buf = bytearray(MAGIC) + struct.pack(">II", 1, 1002)
for i, (flags, pl) in enumerate(PAYLOADS):
    buf += struct.pack(">IIIIQ", len(pl), len(pl), flags, 0, BASE_TS + i * 1000)
    buf += pl
sample = bytes(buf)

# ── 1. 格式契约 ─────────────────────────────────────────────────────────
check("synthetic sample is 191 bytes", len(sample) == 191, "got %d" % len(sample))
r = parse_full(sample)
check("synthetic sample parses", r is not None)
if r:
    check("header version == 1", r["ver"] == 1, "got %r" % r["ver"])
    check("header datalink == 1002 (H4)", r["dl"] == 1002, "got %r" % r["dl"])
    check("5 packets decoded", len(r["pkts"]) == 5, "got %d" % len(r["pkts"]))
    check("all bytes consumed", r["trailing"] == 0, "trailing=%d" % r["trailing"])
    types = [p["ptype"] for p in r["pkts"]]
    check("H4 types are cmd,event,cmd,event,acl",
          types == [0x01, 0x04, 0x01, 0x04, 0x02], "got %r" % types)
    check("flags bit0 marks direction",
          [p["recv"] for p in r["pkts"]] == [False, True, False, True, False])
    check("timestamps are monotonic",
          all(r["pkts"][i]["ts"] < r["pkts"][i + 1]["ts"] for i in range(4)))
    check("payload keeps its H4 prefix byte", r["pkts"][0]["payload"][0] == 0x01)

# ── 2. 尾部重对齐 ───────────────────────────────────────────────────────
long_stream = bytearray(MAGIC) + struct.pack(">II", 1, 1002)
for k in range(200):
    for flags, pl in PAYLOADS:
        long_stream += struct.pack(">IIIIQ", len(pl), len(pl), flags, 0, BASE_TS + k * 1000)
        long_stream += pl
long_stream = bytes(long_stream)
full = parse_full(long_stream)
check("long stream parses fully", full is not None and full["trailing"] == 0)

resync_ok = 0
resync_tries = 0
for cut in (RECORD_SIZE * 3 + 7, 137, 500, 1000, 1500, 2000, 2001, 4000, 7000, 11000, 20000):
    if cut >= len(long_stream):
        continue
    got = resync(long_stream[-cut:])
    resync_tries += 1
    if got is None:
        continue
    expected = full["pkts"][-len(got["pkts"]):]
    same = all(a["payload"] == b["payload"] and a["ts"] == b["ts"]
               for a, b in zip(got["pkts"], expected))
    if same and got["trailing"] == 0:
        resync_ok += 1
check("resyncTail recovers every tested cut",
      resync_tries > 0 and resync_ok == resync_tries,
      "%d/%d cuts recovered" % (resync_ok, resync_tries))

check("bad magic is rejected", parse_full(b"NOTSNOOP" + b"\x00" * 100) is None)
check("truncated header is rejected", parse_full(MAGIC) is None)

# ── 3. 源码契约 ─────────────────────────────────────────────────────────
parser = read(APP / "engine/cap/BtsnoopParser.kt")
check("parser validates the magic", "MAGIC" in parser and "bad magic" in parser)
check("parser documents H4 datalink 1002", "1002" in parser)
check("parser has resyncTail", "fun resyncTail(" in parser)
# 锚定**常量赋值的实际值**，不能只匹配注释里出现的数字 ——
# 注释里写着 4096、常量却改成 24 时，松散的匹配会漏报（反证时真踩过）。
check("resync scans wider than one record header",
      "private const val RESYNC_SCAN = 4096" in parser,
      "a cut inside a long ACL payload needs a window wider than 24 bytes")

# 时间戳偏移：上面第 1 节验的是 Python 复刻，验不到 Kotlin 本体。
# 这里直接锚定源码里两处读取的偏移量（记录头 >IIIIQ：timestamp 在 @16，8 字节）。
ts_hi_16 = parser.count("readInt(data, off + 16)")
ts_lo_20 = parser.count("readInt(data, off + 20)")
check("both timestamp reads use the right offset (hi @16, lo @20)",
      ts_hi_16 == 2 and ts_lo_20 == 2,
      "hi@16 x%d, lo@20 x%d (expected 2 and 2)" % (ts_hi_16, ts_lo_20))
check("timestamp high word is not read from the drops field (@12)",
      "readInt(data, off + 12).toLong()" not in parser,
      "@12 is drops; reading it as timestamp shifts the clock by 3 words")
check("resync validates H4 packet types", "packetType < 1 || it.packetType > 5" in parser)
check("resync requires timestamps to be monotonic", "monotonic" in parser)
check("resync scores by bytes consumed", "bytesConsumed > best.bytesConsumed" in parser)
check("parser caps payload size", "MAX_PAYLOAD" in parser)
check("parser names cmd/event/acl", all(k in parser for k in ('"cmd"', '"event"', '"acl"')))
check("parser decodes Command_Complete inner opcode", "0x0E" in parser)
check("parser decodes Command_Status inner opcode", "0x0F" in parser)
check("parser explains why it lives outside Android APIs",
      "纯字节运算" in parser or "不碰 Android" in parser)

radio = read(APP / "engine/cap/RadioCap.kt")
check("bt op=log accepts format=binary", 'format == "binary"' in radio)
check("binary path reads from cached_hci", "cached_hci" in radio)
check("binary path base64-decodes privileged output", "Base64.decode" in radio)
check("binary path defaults to tail slicing", "BTSNOOP_TAIL_BYTES" in radio)
check("binary supports full=true", 'optBoolean("full"' in radio)
check("binary supports hex flag", 'optBoolean("hex"' in radio)
check("binary audits the read", "bt.log.binary" in radio)
check("text (legacy) mode is still the default", 'ifBlank { "text" }' in radio)
check("binary resyncs the tail", "resyncTail" in radio)

# runSu 管道死锁：必须在 waitFor 之前起读线程
priv = read(APP / "engine/Privilege.kt")
su_body = priv[priv.index("fun runSu("):]
su_body = su_body[:su_body.index("fun runPrivileged(")]
i_thread = su_body.find("reader.start()")
i_wait = su_body.find("p.waitFor(")
check("runSu starts the stdout reader before waitFor",
      i_thread != -1 and i_wait != -1 and i_thread < i_wait,
      "reader.start() at %d, waitFor at %d — reversed order deadlocks above 64KB"
      % (i_thread, i_wait))
# 只禁止"waitFor 之后"的阻塞读；注释里提到旧写法不算违规
i_wait2 = su_body.find("p.waitFor(")
after_wait = su_body[i_wait2:] if i_wait2 != -1 else ""
check("runSu no longer does a blocking readText after waitFor",
      ".readText()" not in after_wait,
      "blocking readText() after waitFor is the deadlock shape")
check("runSu joins the reader before returning", "reader.join(" in su_body)
check("runSu drains with a bounded chunk loop", "CharArray(8192)" in su_body)

catalog = read(APP / "engine/EndpointCatalog.kt")
check("catalog documents format=text|binary", "format=text|binary" in catalog)
check("catalog warns that tail slicing is the default", "256KB" in catalog)
check("catalog states frames are HCI not app-level", "HCI 帧" in catalog)

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok    " if ok else "  FAIL  ") + name
          + (("\n         -> " + detail) if not ok and detail else ""))
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All btsnoop checks passed (%d checks)." % len(checks))
