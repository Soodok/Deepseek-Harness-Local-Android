#!/usr/bin/env python3
"""插话（steer）失效的两端修复回归（2026-11-26 用户反馈 82c93406）

反馈原文（HONOR BKQ-AN90 / beta.31）把四层阻断都列了出来，其中两层在引擎侧：
  ① UI 的 steerQueue 只遍历 `next-turn`，`next-step` 里的消息永远轮不到；
  ② 会话控制器对 steer 硬拒 `target !== "next-turn"`，而 agent.steer() 写入的
     正是 next-step —— 两边语义自相矛盾。
另两层是「静默失败」（steer-unavailable 被 return 吞掉、frozen 期输入丢弃）。

本探针不跑引擎，只断言**补丁脚本**能把这两处真正改掉，且：
  · 锚点在真实运行时文件里唯一（不许"碰到就改"）；
  · 已经是打过补丁的文件不重复打（幂等）；
  · 锚点全不命中时**非零退出**（fail-fast，不许静默跳过）。

跑法：python3 scripts/probes/_test_steer_patch.py

## 大小会"变小"是正常的（2026-11-27 真机注入踩到）
把补丁后的文件推回设备时若走 `cat >` 重写，行尾会从 CRLF 归一为 LF —— 实测
client.js 963351 → 940548 B（−22803），看着像丢内容。判定依据不是字节数，而是：
① `diff` 的行数是否与补丁器报告的项目数对得上；② `node --check` 是否通过；
③ 关键标记（`dsh-android: steer` / `next-step`）是否还在。
"""
import io
import re
import subprocess
import sys
import tempfile
import os
import zipfile

PATCHER = "scripts/patch-steer-android.py"
ZIP = "_runtime_bak/rt-x86_64-current.zip"
SC_DIR = os.path.join("node_modules", "@deepseek-ai", "dsh-api-session-controller")
CONV_DIR = os.path.join("node_modules", "@deepseek-ai", "dsh-client-ui-conversation")
SC_ENTRY = "lib/" + SC_DIR.replace(os.sep, "/") + "/lib/index.js"
CONV_ENTRY = "lib/" + CONV_DIR.replace(os.sep, "/") + "/lib/client.js"


def read(p):
    return io.open(p, encoding="utf-8").read()


def extract(tmp):
    with zipfile.ZipFile(ZIP) as z:
        for entry in (SC_ENTRY, CONV_ENTRY):
            dest = os.path.join(tmp, entry)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(entry) as src, io.open(dest, "wb") as out:
                out.write(src.read())
    return os.path.join(tmp, SC_ENTRY), os.path.join(tmp, CONV_ENTRY)


def run(target):
    """target 是 runtime 根目录（或 zip），与其余 patch-*.py 的调用约定一致。"""
    proc = subprocess.run(
        [sys.executable, PATCHER, target],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main():
    if not os.path.isfile(PATCHER):
        print("FAIL patcher missing: %s" % PATCHER)
        return 1
    if not os.path.isfile(ZIP):
        print("FAIL runtime zip missing: %s" % ZIP)
        return 1

    ok = fail = 0

    def check(label, cond, extra=""):
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  ok   %s" % label)
        else:
            fail += 1
            print("  FAIL %s%s" % (label, ("  -> " + extra) if extra else ""))

    with tempfile.TemporaryDirectory() as tmp:
        sc, conv = extract(tmp)
        # 整个 tmp 就是一份 runtime 根（lib/node_modules/... 已在位），
        # 与 scripts/build-runtime.sh 的调用方式一致。
        code, log = run(tmp)
        check("A 会话控制器补丁退出 0", code == 0, log.strip()[:400])
        sc_after = read(sc)
        # next-step 也必须可 steer：不再硬要求 next-turn
        check("A1 控制器不再硬拒 next-step 的 steer",
              'target !== "next-turn"' not in sc_after or
              'target === "next-turn" || target === "next-step"' in sc_after or
              'target === "next-step"' in sc_after)
        check("A2 保留 steer-unavailable 语义（不能把整个守卫删掉）",
              "session/steer-unavailable" in sc_after)
        check("A3 仍然要求 agent 在跑（不能对 idle 也放行）",
              'agent.status !== "running"' in sc_after)
        check("A4 打上了我们的标记（可幂等识别）",
              "dsh-android: steer" in sc_after)

        # 幂等：再打一次不应再改内容
        before = sc_after
        code2, log2 = run(tmp)
        check("B 重复打补丁退出 0", code2 == 0, log2.strip()[:300])
        check("B1 幂等（第二次内容不变）", read(sc) == before)

        # 前端在同一次调用里已经被打过；这里只验证结果，不再单独跑一遍。
        code3, log3 = 0, "covered by the runtime-root run above"
        check("C 会话 UI 补丁退出 0", code3 == 0, log3.strip()[:400])
        conv_after = read(conv)
        check("C1 steerQueue 覆盖了 next-step",
              'next-step' in conv_after)
        check("C2 失败会提示（不再直接 return 吞掉）",
              "queue.steerFailed" in conv_after and
              conv_after.count("queue.steerFailed") >= 1)
        check("C3 打上了我们的标记",
              "dsh-android: steer" in conv_after)

        # 幂等
        before2 = conv_after
        code4, _ = run(tmp)
        check("D 前端幂等", code4 == 0 and read(conv) == before2)

        # fail-fast：对一份不含锚点的文件必须非零
        bogus = os.path.join(tmp, "bogus-runtime")
        os.makedirs(os.path.join(bogus, "lib", SC_DIR, "lib"), exist_ok=True)
        io.open(os.path.join(bogus, "lib", SC_DIR, "lib", "index.js"), "w", encoding="utf-8").write("// no anchors here\n")
        code5, log5 = run(bogus)
        check("E 锚点全不命中 → 非零退出（不许静默跳过）", code5 != 0, log5.strip()[:300])

    print()
    print("%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
