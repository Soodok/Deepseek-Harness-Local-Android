#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LinuxFrameSurface（容器画面贴图）静态接线探针

锁的是「容器渲染的画面能一路走到 App 界面」这条链路上的每一环，
任何一环被删/改名/顺序错位都要红。

背景（实测数据见镜像仓 docs/benchmark.md 6.7）：
  容器 framesrv 用宿主 GPU 渲染 → glReadPixels 写共享文件 → App mmap
  → Bitmap → SurfaceView。

★这个探针防的四个具体坑，都是实际踩过的：
  1. 共享文件头字段顺序（magic/seq/w/h）读写两边必须一致。
     我第一版 Kotlin 少读一个 seq，结果把 60 帧的 seq 当成了宽度，
     logcat 打出 `mapped 60x320`。
  2. magic 字面值必须与 C 侧一致。C 侧曾写成 0x4D524146（小端落盘是
     "FARM"），Kotlin 侧同步改 0x4D415246 才是 "FRAM"。
  3. memfd 不能跨进程 —— 代码里不能出现 /proc/<pid>/fd 共享的做法。
  4. 必须用 SurfaceView + Choreographer 拉帧，不能靠 Timer 轮询（脏帧
     判据是文件头 seq，Choreographer 天然与 VSYNC 对齐）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxFrameSurface.kt")
ACT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/LinuxActivity.kt")
ENVM = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt")
C_SRC = os.path.join(ROOT, "..", "dsh-linux-runtime", "bench", "framesrv.c")

checks = []


def _strip_kt_comments(text):
    """剥掉 // 行注释与 /* */ 块注释，保留字符串（含三引号 raw string）内容。

    为什么必须剥：代码注释里常写「不能用 pkill -f [f]ramesrv」这种反面教材，
    直接对全文做正则会被注释蒙对 —— D5 就这么假绿过一轮。
    """
    out = []
    i, n = 0, len(text)
    in_str = in_raw = False
    while i < n:
        c = text[i]
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            if c == "\\":
                out.append(text[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if text.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def ck(name, cond, detail=""):
    checks.append((name, bool(cond), detail))


def read(p):
    if not os.path.isfile(p):
        return None
    return io.open(p, encoding="utf-8").read()


kt = read(KT)
act = read(ACT)
envm = read(ENVM)

# ---------- A. Kotlin 侧文件与结构 ----------
ck("A1 LinuxFrameSurface.kt 存在", kt is not None, KT)
if kt:
    ck("A2 继承 SurfaceView", re.search(r"class LinuxFrameSurface\b.*:.*SurfaceView", kt) is not None)
    ck("A3 实现 SurfaceHolder.Callback", "SurfaceHolder.Callback" in kt)
    ck("A4 用 Choreographer 拉帧", "Choreographer" in kt and "postFrameCallback" in kt)
    ck("A5 没有用 Timer/Handler.postDelayed 轮询",
       "java.util.Timer" not in kt and "postDelayed" not in kt)

# ---------- B. 文件头契约：magic/seq/w/h ----------
if kt:
    ck("B1 HEADER_BYTES = 16", re.search(r"HEADER_BYTES\s*=\s*16\b", kt) is not None)
    ck("B2 MAGIC = 0x4D415246（小端落盘为 FRAM）",
       re.search(r"MAGIC\s*=\s*0x4D415246", kt) is not None,
       "C 侧 FRAME_MAGIC 必须是同一个值")
    # 读头必须连读四个 int，顺序 magic→seq→w→h；漏读 seq 会让 w 拿到 seq
    m = re.search(r"val magic = head\.int(.{0,400}?)val w = head\.int", kt, re.S)
    ck("B3 读头时先吃掉 offset 4 的 seq 再读 w",
       m is not None and re.search(r"head\.int", m.group(1)) is not None,
       "漏读 seq 会把 seq 当成宽度（实测 mapped 60x320）")
    ck("B4 有 magic 校验", "magic != MAGIC" in kt)
    ck("B5 像素从 HEADER_BYTES 偏移开始",
       re.search(r"position\(\s*HEADER_BYTES\s*\)", kt) is not None)
    ck("B6 脏帧判据是 seq 不是 mtime",
       "getInt(4)" in kt and "lastModified" not in kt)
    ck("B7 Bitmap 为 ARGB_8888", "Bitmap.Config.ARGB_8888" in kt)
    ck("B8 有 release() 供 onDestroy 调用", re.search(r"fun release\(\)", kt) is not None)
    ck("B9 用 lockHardwareCanvas 而非 lockCanvas",
       "lockHardwareCanvas()" in kt and "holder.lockCanvas()" not in kt,
       "软件 Canvas 的 drawBitmap 走 CPU 合成，实测 12.7ms/帧 vs 0.6ms")

# ---------- C. 跨进程共享必须是普通文件，不能是 memfd ----------
if kt:
    ck("C1 共享文件在 filesDir（宿主与容器都能 open）",
       "ctx.filesDir" in kt and "dshframe.bin" in kt)
    # 只检查代码行（去掉 // 与 /* */ 注释块与行尾注释），注释里说明「为什么不用
    # memfd」是必需的文档，不该被判违规。
    _code = re.sub(r"/\*.*?\*/", "", kt, flags=re.S)
    _code = re.sub(r"//[^\n]*", "", _code)
    ck("C2 代码不使用 memfd / /proc/<pid>/fd 做跨进程共享",
       "memfd" not in _code and "/proc/" not in _code,
       "memfd 的 /proc/<pid>/fd 只有 root 能 open，同 uid 会被 SELinux 拒")
    ck("C3 用 FileChannel.map 只读映射", "MapMode.READ_ONLY" in kt)
    ck("C4 映射区显式设小端（否则 seq 读成垃圾值、每帧白拷）",
       re.search(r"\.map\(FileChannel\.MapMode\.READ_ONLY.*?order\(java\.nio\.ByteOrder\.LITTLE_ENDIAN\)",
                 kt, re.S) is not None,
       "ByteBuffer 默认 BIG_ENDIAN，容器按小端写")

# ---------- D. Activity 接线 ----------
if act:
    ck("D1 LinuxActivity 持有 frameSurface 字段",
       re.search(r"frameSurface\s*:\s*.*LinuxFrameSurface", act) is not None)
    ck("D2 buildRoot 里加了容器画面区块", "screenCard()" in act)
    ck("D3 onDestroy 释放 frameSurface",
       re.search(r"onDestroy\(\)(.{0,300}?)frameSurface\?\.release\(\)", act, re.S) is not None)
    ck("D4 有开始/停止画面的入口",
       "startFrameServer" in act and "stopFrameServer" in act)
    # D5/D6 只验语义，不锁写法与转义形态。两条都实际假绿过：
    #
    # D5 原断言 `pkill -f \[f\]ramesrv`：代码里那串 `[f]ramesrv` 只存在于
    #    *注释*（反面教材示范里写着「不能用 pkill -f [f]ramesrv」），真正执行
    #    的是 `pkill -f '^/framesrv'`。对全文做正则 → 靠注释通过。
    # D6 原断言 `/framesrv.{0,40}\$shared`：Kotlin raw string 里 `$` 必须写成
    #    `${'$'}`，实际文本是 `/framesrv 1280 720 0 16 ${'$'}shared`，中间那个
    #    `{` 让 `\$shared` 匹配不上 → 明明传了却报没传。
    #
    # 自匹配本身由 _test_pkill_selfmatch.py 的 A 段统一守（语义判定）。
    act_code = _strip_kt_comments(act)
    # 把 Kotlin 的 `${'$'}` 还原成 shell 的 `$`，判据才对得上真实执行的脚本。
    act_sh = act_code.replace("${'$'}", chr(36))

    # D5 必须在 startFrameServer *函数体内* 找。第一版对全文找，于是
    # captureCompositor / stopFrameServer 里的 `^/framesrv` 把它蒙对了 ——
    # 实测把 startFrameServer 里那一行整条删掉，探针照样绿。
    _start_body = act_code.split("private fun startFrameServer")[-1]         if "private fun startFrameServer" in act_code else ""
    _start_body = _start_body.split("private fun ")[0]
    _fs_kills = []
    for m in re.finditer(r"pkill\s+-f\s+(?:'([^']*)'|\"([^\"]*)\"|([^\s;]+))", _start_body):
        pat = m.group(1) or m.group(2) or m.group(3) or ""
        if "framesrv" in pat and "x11" not in pat:
            _fs_kills.append(pat)
    ck("D5 startFrameServer 启动前先清旧帧服务",
       len(_fs_kills) >= 1,
       "不先清，上一轮 framesrv 与本轮抢同一共享文件，seq 会乱跳")
    ck("D6 传共享文件路径给 framesrv",
       re.search(r"/framesrv[^\n]{0,60}\$\{?shared", act_sh) is not None,
       "传宿主路径而不是容器路径，framesrv 会写到一个 App 看不到的地方")
    # D7 必须先剥掉行注释再判 —— 注释里引号包着的中文不算字面量。
    # 原来直接对全文做正则，于是注释里写 `"[w]lshot"` 这种引号示例就假红。
    act_nc = chr(10).join(
        ln for ln in act.split(chr(10)) if not ln.lstrip().startswith("//"))
    ck("D7 文案走资源不做中文字面量（已剥行注释）",
       not re.search(r'"[^"\n]*[\u4e00-\u9fff][^"\n]*"', act_nc),
       "check-i18n 会拦 Kotlin 里出现 CJK")
    # AHB 路径：framesrv 从第 7 个参数选取像素方式。产线实测 720p
    # 210.4->286.0 fps、1080p 80.8->121.4 fps（1.50 倍），落盘像素逐字节一致。
    # 必须传 auto 而不是硬编码 ahb：真机 gralloc 与模拟器不同（pitfalls #41），
    # 硬编码 ahb 一旦不支持就会让整个画面功能失败，而 auto 只是慢一点。
    ck("D8 启动参数传 pixmode=auto（不硬编码 ahb）",
       re.search(r"/framesrv[^\"\n]*\bauto\b", act) is not None
       and re.search(r"/framesrv[^\"\n]*\bahb\b(?!\w)", act) is None,
       "auto = 有 AHB 就直写、没有自动退回 glReadPixels")

# ---------- E. framesrv 资产与部署 ----------
assets = os.path.join(ROOT, "app/src/main/assets/linux")
for abi in ("arm64", "x86_64"):
    p = os.path.join(assets, "framesrv-" + abi)
    ok = os.path.isfile(p) and os.path.getsize(p) > 2000
    ck("E1 framesrv-%s 已随包（>2KB）" % abi, ok,
       "%d bytes" % (os.path.getsize(p) if os.path.isfile(p) else 0))

if envm:
    ck("E2 有 FRAMESRV_ASSET 常量", "FRAMESRV_ASSET" in envm)
    ck("E3 按 ABI 选 frame 服务资产",
       re.search(r"FRAMESRV_ASSET\s*:\s*String\?\s*=\s*\n?\s*if \(RUNNER_ABI_ARM64\)", envm) is not None)
    ck("E4 部署后设可执行位", re.search(r"framesrv", envm) is not None and "setExecutable(true" in envm)
    ck("E5 幂等用标记文件而非比长度",
       ".dsh-framesrv-version" in envm)
    # ★戳记必须含字节数，不能只记 asset 路径。
    # 实测踩过：framesrv 换成 AHB 版后，设备上仍是旧的 11728 字节文件 ——
    # 因为戳里只有 "linux/framesrv-x86_64" 这个路径，内容变了路径没变，
    # stale 判定为 false，永远不重装。
    ck("E6 幂等戳含 asset 字节数（内容变了要重装）",
       re.search(r"val wantStamp = .{0,80}assetSizeOf\(ctx, want\)", envm) is not None,
       "只记路径会让二进制更新后用户一直跑旧版（实测 11728 字节旧文件）")
    ck("E7 assetSizeOf 有压缩回退（AssetManager.openFd 对压缩 asset 会抛）",
       "openFd" in envm and "FileNotFoundException" in envm or
       ("openFd" in envm and "getOrElse" in envm),
       "AGP 默认压缩小 asset，openFd 会抛 'can not be opened as a file descriptor'")

# ---------- F. 与 C 侧契约一致 ----------
#
# ★跨仓契约：framesrv.c 在**兄弟仓** dsh-linux-runtime，不在本仓。
#
# 所以「本仓是否检出」不能作为契约是否成立的判据。这里区分两件事：
#   · 源码在场   → 逐条校验 magid/字段顺序/写盘方式（真契约）；
#   · 源码不在场 → SKIP，**不是 FAIL**。
#
# 为什么必须分开：CI 只检出 dsh-android 一个仓，兄弟仓永远不在。旧写法在
# else 分支里直接 `ck(..., False)`，于是这个探针在 CI 上恒定红 —— i18n-check
# 工作流的「regression probes」步骤因此长期失败，而它想守的东西根本没被检查。
# 假失败比不检查更糟：真正的契约破坏也会淹没在同一片红里。
#
# 需要强制校验时（本机有兄弟仓 / 想卡住契约）传 --require-cross-repo 或设
# DSH_REQUIRE_CROSS_REPO=1，此时源码缺失仍算失败。
REQUIRE_CROSS = ("--require-cross-repo" in sys.argv
                 or os.environ.get("DSH_REQUIRE_CROSS_REPO") == "1")

if os.path.isfile(C_SRC):
    c = io.open(C_SRC, encoding="utf-8", errors="replace").read()
    ck("F1 C 侧 FRAME_MAGIC 与 Kotlin 一致",
       re.search(r"FRAME_MAGIC\s+0x4D415246", c) is not None)
    ck("F2 C 侧 HDR_BYTES = 16", re.search(r"HDR_BYTES\s+16u", c) is not None)
    ck("F3 C 侧写头顺序 magic/seq/w/h",
       re.search(r"put_u32\(base\+0,\s*FRAME_MAGIC\)", c) is not None and
       re.search(r"put_u32\(base\+4,\s*seq\)", c) is not None and
       re.search(r"put_u32\(base\+8,", c) is not None and
       re.search(r"put_u32\(base\+12,", c) is not None)
    ck("F4 C 侧 glReadPixels 写 pixels（跳过 16 字节头）",
       re.search(r"glReadPixels|rp\(0,0,W,H,0x1908,0x1401,pixels\)", c) is not None)
    ck("F5 C 侧每帧推进 seq", re.search(r"write_hdr\(hdr,\s*\(unsigned\)i", c) is not None)
    ck("F6 C 侧用普通文件不用 memfd",
       "memfd_create" not in c and "O_CREAT" in c)
    ck("F7 C 侧文件 = 16 字节头 + 整帧",
       re.search(r"sz\s*=\s*\(size_t\)HDR_BYTES\s*\+\s*pxsz", c) is not None)
else:
    # 兄弟仓未检出：只有在显式要求时才判失败（见上方说明）。
    ck("F0 镜像仓 framesrv.c 可读（跨仓契约校验）", not REQUIRE_CROSS,
       "兄弟仓 dsh-linux-runtime 未检出；本机校验请加 --require-cross-repo" if REQUIRE_CROSS
       else C_SRC)

# ---------- 输出 ----------
failed = [c for c in checks if not c[1]]
print("== LinuxFrameSurface / 容器画面 ==")
for name, ok, detail in checks:
    print(("  ✓ " if ok else "  ✗ ") + name + ("  —— " + detail if detail and not ok else ""))
print("\n%d checks, %d failed" % (len(checks), len(failed)))
sys.exit(1 if failed else 0)
