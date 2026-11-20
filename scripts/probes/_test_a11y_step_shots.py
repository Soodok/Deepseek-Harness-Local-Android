#!/usr/bin/env python3
"""Locks 无障碍记忆的「逐步截图」链路（记忆里的界面证据）。

## 为什么要单独一条探针

这条链路的价值全在**时序与预算**上，任何一处悄悄改了，症状都是「看起来没事」：

- 取帧挪回动作线程 → 任务变慢一倍，但功能全对，没人会发现；
- 队列换成无界 → 内存涨到 OOM 才炸；
- 拼图丢了角标 → 模型看到一串相似界面，认不出第几格对应第几步；
- `shots` 与 `steps` 不再等长 → 重放时把 A 步的图配给 B 步，**比没有图更糟**；
- 老记忆文件读不出来 → 用户的历史记忆全消失。

这些都不是「代码写错」，是「接线错位」。纯 Kotlin 的算术与布局在
`scripts/jvm/BehaviourTest.kt` 的 `assertStepShots()` 里断言（可跑真实函数），
这里只钉**接线与契约**：谁在什么时候调谁、字段有没有一起改、端点在不在。

## 变异验证

- 把 `worker.execute { captureNow(...) }` 改成直接调 `captureNow(...)` → A3 红
- 把 `ArrayBlockingQueue(3)` 改成无界 `LinkedBlockingQueue()` → A4 红
- 删 `learnQuiet` 里的 `bundle(` → B2 红
- 删 `MemoryStore.upsertLocked` 里的 `normalizeShots` → C2 红
- 删 `AgentBridge` 的 `/memory/v2/shot` 注册 → D2 红
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
MAIN = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile")

LAYOUT = os.path.join(ENG, "StepShotLayout.kt")
SHOTS = os.path.join(ENG, "StepShots.kt")
ROUTES = os.path.join(ENG, "MemoryRoutes.kt")
STORE = os.path.join(ENG, "MemoryStore.kt")
CODEC = os.path.join(ENG, "MemoryCodec.kt")
BRIDGE = os.path.join(ENG, "AgentBridge.kt")
RECORDER = os.path.join(ENG, "TaskRecorder.kt")
SEED = os.path.join(ENG, "AgentContextSeed.kt")
LAYOUT_XML = os.path.join(ROOT, "app", "src", "main", "res", "layout", "activity_memory_detail.xml")
DETAIL = os.path.join(MAIN, "MemoryDetailActivity.kt")

fails = []
total = 0


def chk(cond, msg):
    global total
    total += 1
    if cond:
        print("  PASS  %s" % msg)
    else:
        print("  FAIL  %s" % msg)
        fails.append(msg)


def read(p):
    if not os.path.isfile(p):
        return ""
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def strip_line_comments(s):
    return "\n".join(ln for ln in s.split("\n") if not ln.lstrip().startswith("//"))


def fn_body(src, name):
    """截出某个函数的函数体近似区间（到下一个同级 `fun ` 为止）。

    用固定字符窗口会假绿：窗口开大一点，就能把**下一个**函数里的调用算进来，
    于是「这里根本没接线」也能通过。按函数边界切才是真的按调用点断言。
    """
    i = src.find("fun " + name + "(")
    if i < 0:
        return ""
    # 换行用 chr(10) 拼，不写进正则字面量：这个脚本要过 bash/python 两层转义，
    # 写 "\n" 在 heredoc 里会被折成真换行，直接把源码拆坏（踩过两次）。
    pat = chr(10) + "    (?:private )?(?:fun|val|var|internal) "
    m = re.search(pat, src[i + 5:])
    return src[i:i + 5 + m.start()] if m else src[i:]


layout = read(LAYOUT)
shots = read(SHOTS)
routes = read(ROUTES)
store = read(STORE)
codec = read(CODEC)
bridge = read(BRIDGE)
recorder = read(RECORDER)
seed = read(SEED)
xml = read(LAYOUT_XML)
detail = read(DETAIL)

print("[A] 录制侧：取帧必须离开动作线程，队列必须有界")
chk(bool(layout) and bool(shots), "A1 StepShotLayout.kt 与 StepShots.kt 都在")
# 纯逻辑层不许碰 android.graphics，否则进不了宿主 JVM 断言
chk("import android" not in layout,
    "A2 StepShotLayout 保持纯 Kotlin（无 android 依赖，可 JVM 跑真实函数）")
# 关键：截图必须在 worker.execute 里，不能在调用线程同步跑。
# v1.7.x 起改成提交 ShotTask 对象（不是为了好看：拒绝处理器要能通过它拿到
# traceId，把 pending 与名额**还回去**。DiscardPolicy 静默丢弃时这两笔账没人还，
# pending 永不归零 → awaitIdle 每次白等 2.5s，名额烧光后整条轨迹停止截图）。
m = re.search(r"worker\.execute\s*\(", shots)
chk(m is not None, "A3 取帧走 worker.execute（不同步阻塞动作路径）")
chk(re.search(r"class ShotTask", shots) is not None
    and re.search(r"ShotTask\.run|override fun run\(\)", shots) is not None,
    "A3b 提交的是带 traceId 的 ShotTask（拒绝时才有的可还账）")
_shot_task_body = fn_body(shots, "run") if "class ShotTask" in shots else ""
chk("captureNow" in _shot_task_body,
    "A3c ShotTask.run 里调的确实是 captureNow（execute 处能追到取帧实现）")
chk(re.search(r"ArrayBlockingQueue\s*\(\s*\d+\s*\)", shots) is not None,
    "A4 队列有界（ArrayBlockingQueue(容量)），防录制堆积吃内存")
# 判据只看**代码**不看注释：注释里提到「DiscardPolicy 会吞 pending」正是要解释
# 为什么不用它，若连注释一起查，这条断言会永远红（假红也是失效的断言）。
# 块注释也要剥掉：`/** 为什么不用 DiscardPolicy */` 里的字面量不算「在用」。
_no_comment = re.sub(r"\/\*[\s\S]*?\*\/", "", shots)
_no_comment = chr(10).join(ln.split("//")[0] for ln in _no_comment.split(chr(10)))
chk("RejectedExecutionHandler" in _no_comment and "DiscardPolicy" not in _no_comment,
    "A5 满了自己记账丢帧（自定义拒绝处理器）—— CallerRuns 会压回动作线程，"
    "DiscardPolicy 会静默吞掉 pending")
chk("THUMB_W" in shots and re.search(r"THUMB_W\s*=\s*\d+", shots) is not None,
    "A6 落盘就缩图（THUMB_W 常量存在），不存原始 1080p")
chk(re.search(r"MAX_TOTAL_BYTES\s*=\s*\d+", shots) is not None,
    "A7 有总预算常量（MAX_TOTAL_BYTES），不会写满手机")
chk(re.search(r"if \(protected\)\s*\{", shots) is not None,
    "A8 保护页不截图（密码框/支付页的像素不进记忆）")
chk(re.search(r"fun onStep\([^)]*stepIndex:\s*Int", shots) is not None
    and "stepIndex < 0" in shots,
    "A9 onStep 拒绝负索引（录制失败时 -1 不该落图）")

print("[B] 接线：录制钩子与入库时机")
chk(routes.count("StepShots.") >= 5,
    "B1 MemoryRoutes 里接了 StepShots（实际 %d 处）" % routes.count("StepShots."))
chk("StepShots.onTaskBegin" in fn_body(routes, "onActionBegin"),
    "B2 任务开始记起手屏（onActionBegin → onTaskBegin）")
chk("StepShots.onStep" in fn_body(routes, "onLoopStep"),
    "B3 本地闭环每步截图（onLoopStep → onStep）")
chk("StepShots.onStep" in fn_body(routes, "onActionDone"),
    "B4 普通动作每步截图（onActionDone → onStep）")
# 两条入库路径（自动 learnQuiet / 手动 memLearn）必须**共用**同一个打包口径。
# 真机实录：只接了自动那条，手动 /memory/v2/learn 存出来的记忆永远没有图，
# 而自动学来的有 —— 同一任务两种结果，查了很久才发现是漏接。
chk("StepShots.bundle(" in fn_body(routes, "learnQuiet"),
    "B5 自动学习入库搬图（learnQuiet → bundle）")
chk("StepShots.bundle(" in fn_body(routes, "memLearn"),
    "B5b 手动 learn 入库也搬图（memLearn → bundle，两条路径不许跑偏）")
chk(re.search(r"fun memLearn[\s\S]+?startShot\s*=", routes) is not None,
    "B5c memLearn 造出的 MemoryEntry 带上 startShot/shots/strip")
chk("StepShots.pruneOrphanTraces" in fn_body(routes, "onBoundaryEnd"),
    "B6 轨迹 TTL 清理时连带清图（否则图永久堆积）")
chk(re.search(r"startShot|shots\s*=", routes) is not None,
    "B7 入库写入 startShot/shots 字段")
# 只有成功的动作才截图：失败步的界面没有「当时该长什么样」的意义
chk(re.search(r"TraceStatus\.OK", routes) is not None,
    "B8 只对成功动作取证（失败/跳过的步不落图）")
chk(re.search(r"fun step\([\s\S]{0,400}?\):\s*Int", recorder) is not None,
    "B9 TaskRecorder.step 返回步序号（截图要知道自己在第几步）")

print("[C] 存储：字段、编解码、长度对齐")
for f in ("startShot", "shots", "strip"):
    chk(re.search(r"val\s+%s\s*:" % f, store) is not None,
        "C1 MemoryEntry 有字段 %s" % f)
chk("startShot" in codec and "strip" in codec,
    "C2 MemoryCodec 编解码新字段（老文件缺字段走默认值）")
# 「函数存在」不等于「被调用」—— 只查函数名会让「定义了却没人调」蒙混过关
# （实测：把调用点改名，仅查函数名的断言照样绿）。所以按**出现次数**与
# **所在函数**双判。
chk(store.count("normalizeShots(") >= 2,
    "C3 normalizeShots 有定义也有调用（%d 处）" % store.count("normalizeShots("))
chk("normalizeShots(" in fn_body(store, "upsertLocked"),
    "C3c upsertLocked 里真的调了对齐（写入口统一收口，patch/extend 不会绕过）")
chk(re.search(r"fun normalizeShots[\s\S]{0,700}?steps\.size", store) is not None,
    "C3b normalizeShots 真的按 steps 长度裁/补")
# 图片只存相对 filesDir 的路径：App 数据目录在恢复备份后会变
chk('relOf(' in shots and 'removePrefix(ctx.filesDir.absolutePath' in shots,
    "C4 记忆里存相对 filesDir 的路径（备份恢复/换用户后仍能解析）")

print("[D] 端点：AI 拿得到图，删记忆就删图")
chk("/memory/v2/shot" in bridge, "D1 AgentBridge 注册了 /memory/v2/shot")
chk("memShot" in routes, "D2 MemoryRoutes 实现了 memShot")
chk(re.search(r'fun memGet[\s\S]{0,2500}?"strip"', routes) is not None,
    "D3 /memory/v2/get 回 strip 路径")
chk(re.search(r'fun memGet[\s\S]{0,2500}?"images"', routes) is not None,
    "D4 /memory/v2/get 回逐步图路径列表")
chk("hasImages" in routes, "D5 列表与详情都带 hasImages（空图不误导模型）")
chk(re.search(r"StepShots\.deleteMemory", routes) is not None,
    "D6 删记忆时清图（不删会变成僵尸文件）")
# 只回路径不回 base64：一次 base64 图能把上下文吃掉
chk("base64" not in shots.lower(), "D7 落盘与端点不碰 base64（只交路径给 read_image）")

print("[E] AI 与 UI 都看得到")
chk("read_image" in seed, "E1 上下文提示词教模型用 read_image 看图")
chk("strip" in seed, "E2 提示词讲清了 strip 是拼接图")
chk("imgMemStrip" in xml, "E3 记忆详情页有拼图控件")
chk("mem_shots_hint" in xml, "E4 详情页有说明文案")
chk(re.search(r"decodeStrip|setImageBitmap", detail) is not None,
    "E5 MemoryDetailActivity 真的把图渲染出来（不是只拉控件）")

print("[G] 加固：越界、错位、竞态、生命周期")
# ---- 越界删除：id 会进 deleteRecursively，必须当目录名单段处理 ----
chk(re.search(r"fun isSafeId\(", shots) is not None,
    "G1 StepShots 有 id 安全校验（memoryId 会被拼成目录名再递归删）")
chk("isSafeId(memoryId)" in fn_body(shots, "memoryDirOf"),
    "G1b memoryDirOf 里真的校验了（函数存在但没调用等于没有）")
_mdo = fn_body(shots, "memoryDirOf")
# 签名要直接看函数头，不能在全文件里找 `File?`：别的函数也有可空返回值，
# 那样「把返回类型改回非空」这种退化就查不出来（本病最初就是这么假绿的）。
chk(re.search(r"fun memoryDirOf\([^)]*\):\s*File\?", shots) is not None,
    "G1c memoryDirOf 签名返回可空（不安全就返回 null，调用方必须当没有图）")
chk("parentFile" in _mdo and "root" in _mdo,
    "G1d memoryDirOf 体内还有一道父目录校验（两道锁，任一成立即挡）")
# resolve 拿的是记忆 JSON 里的相对路径，那个文件可被手工改
chk(re.search(r"fun resolve\([\s\S]{0,900}?canonical", shots) is not None,
    "G2 resolve 校验相对路径不越出 filesDir（记忆 JSON 可被手工改）")
# ---- 空占位：shots 里空串是「这一步没图」，下标就是含义 ----
chk(re.search(r"fun positionalList\(", codec) is not None,
    "G3 MemoryCodec 有位置敏感列表解码（不能用会过滤空串的 stringList）")
chk(re.search(r'shots\s*=\s*positionalList\(', codec) is not None,
    "G3b shots 真的走 positionalList（改回 stringList 会让图整体左移）")
# ---- 错位：后台取帧必须知道自己是不是还该拍 ----
chk("latestAsked" in shots, "G4 记录最新请求步号（判过期）")
# 判据必须看**比较本身**，不能只看 drop 的提示文字：
# 把 `if (latestAsked[...] != index)` 改成 `if (false)`，那句 "stale-after-capture"
# 仍留在 drop 调用里 —— 只查字面量会照绿（这条最初就是这么假绿的）。
_ci = fn_body(shots, "captureInner")
_shot_at = _ci.find("screenshotArgb")
_before = _ci[:_shot_at] if _shot_at > 0 else ""
_after = _ci[_shot_at:] if _shot_at > 0 else ""
chk(_shot_at > 0 and "latestAsked[traceId]" in _after,
    "G4b 取帧**后**再比一次（取帧本身 100-400ms，期间界面可能又走了）")
chk(_shot_at > 0 and "latestAsked[traceId]" in _before and "asked != index" in _before,
    "G4d 取帧**前**也比一次（队列积压时根本不用取这一帧）")
# 不能只查常量存在：把 `if (index == START_INDEX)` 改成 `if (false)` 之后，
# `> START_GRACE_MS` 那行仍在，常量还在，断言照样绿（这条最初就是这么假绿的）。
# 要查的是「宽限逻辑真的挂在起手帧上」。
chk("START_GRACE_MS" in shots and "START_INDEX" in _before and "START_GRACE_MS" in _before,
    "G4c 起手帧有宽限窗口（否则 latestAsked 一更新就系统性丢掉起点证据）")
# ---- 竞态/泄漏：队列满时账要还 ----
chk("ShotTask" in shots,
    "G5 有带 traceId 的任务对象（拒绝处理器才有的可还账）")
chk(re.search(r"fun awaitIdle\(", shots) is not None,
    "G6 入库前等截图落盘（不等会稳定缺尾图，真机第一轮就是这么缺的）")
# bundle 是两条入库路径（learnQuiet / memLearn）的公共收口，等待必须在那里兜底：
# 只把等待挂在某一个调用点，将来第三条路径就会漏（memLearn 第一轮就是这么漏的）。
chk("awaitIdle(" in fn_body(shots, "bundle"),
    "G6b bundle 内部自己等（公共收口，第三条入库路径也不会漏）")
chk(re.search(r"fun invalidate\(", shots) is not None,
    "G7 能作废轨迹（clear 后晚到的帧不许重建侧车）")
chk("StepShots.invalidate" in fn_body(routes, "traceClear"),
    "G7b traceClear 真的调了 invalidate（顺序反了会「清空成功但磁盘又长图」）")
chk("TaskRecorder.listIds" in fn_body(routes, "traceClear")
    and "StepShots.listTraceIdsWithShots" in fn_body(routes, "traceClear"),
    "G7c 清空要合并两个 id 来源（只扫现存 .jsonl 会漏掉图目录孤儿）")
chk(re.search(r"fun sweepOrphanMemoryDirs\(", shots) is not None,
    "G8 能扫掉不再对应任何存活记忆的图目录（容量淘汰不会自己删图）")
# 「函数存在」不等于「被调用」—— 定好了却从不调，等于没有（这条吃过亏）。
chk("sweepOrphanMemoryDirs" in fn_body(routes, "learnQuiet")
    and "sweepOrphanMemoryDirs" in fn_body(routes, "memLearn"),
    "G8b 两条入库路径都真的调了清扫（否则容量淘汰留下的孤图没人管）")
chk(re.search(r"fun pruneUnlisted\(", shots) is not None,
    "G9 重学后清残留文件（先删再打包会让旧图必丢）")
# 判据要剥注释再看：learnQuiet 里的注释**正是**在解释「为什么不能在这里 resetMemory」，
# 连注释一起查会让这条断言永远红（假红和假绿一样废）。
_lq = strip_line_comments(fn_body(routes, "learnQuiet"))
chk("resetMemory" not in _lq,
    "G9b learnQuiet 不许在 bundle 之前 resetMemory（bundle 失败时旧图已删光）")
# ---- 端点语义：step 与 pos 两个口径不许隐式回落 ----
chk("-1" in routes and "START_INDEX" in routes, "G10 起手屏有独立取值")
chk('"shotIndex"' in routes or "shotIndex" in routes,
    "G11 /memory/v2/get 给步骤-图片映射表（剪枝后 i 不连续，模型猜不出该传什么）")
chk(re.search(r'not both', routes) is not None,
    "G12 step 与 pos 同传时报错（隐式回落会返回错一步的图）")

print("[F] 六语文案齐备")
for loc, d in (("values", ""), ("values-zh-rCN", "-zh-rCN"), ("values-de", "-de"),
               ("values-pt-rBR", "-pt-rBR"), ("values-th", "-th"), ("values-ru", "-ru")):
    p = os.path.join(ROOT, "app", "src", "main", "res", "values%s" % d, "strings.xml")
    chk('name="mem_shots_title"' in read(p), "F %s 有 mem_shots_title" % loc)

print("")
if fails:
    print("FAILED (%d/%d)" % (len(fails), total))
    sys.exit(1)
print("All step-shot checks passed.")
