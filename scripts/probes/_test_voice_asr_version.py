#!/usr/bin/env python3
"""语音服务页「本地识别状态卡」的版本判定探针。

## 为什么需要（2026-11-14 真机事故）

用户报：「设置里语音服务也不能正常设置，我明明都已经安装好了语音服务软件，而且
下载了模型，点击它还是显示先安装语音服务，真实测试根本用不了」。

真机取证（OnePlus PLZ110）：

    app.dsh.asr  已安装  versionName=0.7.4  versionCode=31
    content query --uri content://app.dsh.asr.status/status
        → Could not find provider: app.dsh.asr.status
    cmd package query-activities -a app.dsh.asr.SET_DEFAULT
        → No activities found
    cmd package query-activities -a app.dsh.asr.CHECK
        → No activities found

状态卡是照 v0.8.0（versionCode 32）的接口写的，而设备装的是 0.7.4。
旧实现只判 `getPackageInfo` 成功与否 → 判定「已安装」→ 一键按钮全部可点 →
`startActivity` 抛 ActivityNotFoundException → 兜底弹「请先安装语音识别服务」。
**装了却报没装**，用户当然认为坏了。

## 判据

V 组 —— 必须以 versionCode 区分「没装 / 装的是旧版 / 装的是新版」三态，
         且旧版的提示必须与"没装"不同（否则用户又看到同一句误导文案）。
W 组 —— 三个一键动作在版本不足时必须提前 return，不能走到 startActivity 再兜底。
X 组 —— 六语言齐全（漏一个语种就是那个语种的用户看到空白/英文）。
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/VoiceServiceActivity.kt")
VALUES = ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th", "values-ru"]

FAILS = []


def check(name, ok, detail=""):
    if ok:
        print("PASS  " + name)
    else:
        FAILS.append(name)
        print("FAIL  " + name + ("  -> " + detail if detail else ""))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_kotlin_comments(txt):
    """去掉 // 行注释与 /* */ 块注释。

    ⚠️ 必须剥：本文件里到处是用中文解释「为什么这么改」的注释，注释里会原样
    提到 `getPackageInfo` / `startActivity` / 旧行为。不剥的话任何一条
    「不许出现 X」的断言都会被注释喂饱，永远红不了。
    """
    txt = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
    txt = re.sub(r"//[^\n]*", "", txt)
    return txt


src = read(KT)
code = strip_kotlin_comments(src)

# ---- V. 三态判定必须靠 versionCode ----
check("V1 定义了最低版本常量 ASR_MIN_VERSION_CODE", "ASR_MIN_VERSION_CODE" in code,
      "没有它就只能判'装没装'，装了旧版会误报为可用")
check("V2 最低版本 = 32（对应 ASR v0.8.0）",
      re.search(r"ASR_MIN_VERSION_CODE\s*=\s*32\b", code) is not None,
      "0.8.0 才引入 StatusProvider / SET_DEFAULT / CHECK；写错就一直误判")
check("V3 读到了已装应用的 versionCode",
      re.search(r"versionCode\.toLong\(\)", code) is not None,
      "必须真读版本号，不能靠包名/文件名猜")
check("V4 有'装了但太旧'的独立判定",
      re.search(r"fun\s+asrTooOld\s*\(\s*\)\s*:\s*Boolean", code) is not None,
      "与 asrInstalled() 分开 → 三态才分得开")
check("V5 太旧的判据是 versionCode 落在 [1, MIN) 区间",
      re.search(r"v\s+in\s+1\s+until\s+ASR_MIN_VERSION_CODE", code) is not None,
      "写成 v < MIN 会把 0（没装）也算成旧版，两态又混了")

# ---- W. 旧版必须有自己的按钮分支，且提前 return ----
block = None
# ⚠️ 两个坑，都实测踩过：
#  ① `re.search(r"listOf\(.*?\)\.forEach", code, re.S)` 非贪婪会在第一个 `)` +
#    `.forEach` 处停住，group(0) 只有 listOf 的参数、**不含 lambda 体** → 所有
#    「分支里有没有 return」的断言恒假。
#  ② `code.find("listOf(")` 找到的是文件里**第一个** listOf（TTS 语速表），
#    不是要查的那个。
# 正确锚点：唯一标识 ASR 分发表的那串 action 名，从它往前找 listOf 起点。
_anchor = 'R.id.btnAsrOpenConfig to "app.dsh.asr.OPEN"'
_j = code.find(_anchor)
if _j >= 0:
    _i = code.rfind("listOf(", 0, _j)
    if _i >= 0:
        block = code[_i:_i + 1600]
check("W1 三个一键动作在同一个 listOf 里分发", block is not None,
      "锚点 %s 未命中" % _anchor if _j < 0 else "")
if block:
    check("W2 旧版分支先 toast 再 return（不许落到 startActivity）",
          re.search(r"asrTooOld\(\s*\)\s*->\s*\{[^}]*return@setOnClickListener", block, flags=re.S)
          is not None,
          "旧版点下去会抛 ActivityNotFoundException，再被兜底说成'没装'")
    check("W3 旧版提示与会话文案分开（asr_local_need_update）",
          "asr_local_need_update" in block,
          "与'请先安装'共用一句 = 用户仍然以为没装")
    check("W4 按钮置灰也考虑版本",
          re.search(r"alpha\s*=\s*if\s*\(\s*installed\s*&&\s*!\s*tooOld\s*\)", block) is not None,
          "只按 installed 置灰 → 旧版看着是亮的，点不动")
check("W5 状态徽标三态都覆盖（就绪/需更新/已安装/未安装）",
      all(k in code for k in (
          "asr_local_state_ready", "asr_local_state_outdated",
          "asr_local_state_installed", "asr_local_state_missing")),
      "少了 outdated 就只能显示成'已安装'，与实际能力不符")
check("W6 旧版单独一段说明（解释为什么一键动作不可用）",
      "asr_local_desc_outdated" in code)
check("W7 旧版时不再去读 StatusProvider",
      re.search(r"if\s*\(\s*installed\s*&&\s*!\s*tooOld\s*\)\s*asrStatus\(\)", code) is not None,
      "旧版没有该 provider，查询只会白跑并留一条异常日志")

# ---- X. 六语言齐全 ----
for d in VALUES:
    p = os.path.join(ROOT, "app/src/main/res", d, "strings.xml")
    if not os.path.exists(p):
        check("X %s 存在" % d, False)
        continue
    txt = read(p)
    missing = [k for k in ("asr_local_state_outdated", "asr_local_desc_outdated",
                           "asr_local_need_update", "asr_local_action_failed") if k not in txt]
    check("X %s 四条新文案齐全" % d, not missing, "缺：%s" % ", ".join(missing))

if FAILS:
    print("\nFAILED: %d check(s)" % len(FAILS))
    sys.exit(1)
print("\nOK: voice ASR version-gate checks passed")
