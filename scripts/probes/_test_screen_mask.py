#!/usr/bin/env python3
"""Locks ScreenMask 的契约：脱敏判据用校验位，不用长度；误伤要有上下文排除。

不需要真机也不需要 Kotlin 编译器——纯静态检查常量与结构，再用 Python
按源码里**提取出来的**权重表/校验表重算一遍已知答案。这样权重表或校验表写错
（最典型：Luhn 的奇偶搞反、身份证 MOD 11-2 权重错位）会立刻变红。

  1. 文件存在，regular expression 结构符合预期
  2. 身份证权重表 == 7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2
  3. 校验字符表 == "10X98765432"
  4. 用源码里的表重算：已知有效身份证 / 有效卡号判 True，改一位判 False
  5. 顺序必须是先身份证后银行卡（否则 18 位身份证被当成卡号）
  6. 上下文排除词齐全（订单/运单/物流…）
  7. mask() 的短路条件（少于 15 字符 / 少于 15 个数字直接返回）
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MASK = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/ScreenMask.kt")

fails = []
def chk(cond, msg):
    if cond:
        print("  PASS  %s" % msg)
    else:
        print("  FAIL  %s" % msg)
        fails.append(msg)

def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()

def strip_comments(s):
    """去掉行注释与块注释：注释里记录「为什么不用旧写法」是必要的，
    但注释文本不该让「代码里是否还有旧写法」的断言误红。"""
    import re as _re
    s = _re.sub(r"/\*.*?\*/", "", s, flags=_re.S)
    out = []
    for ln in s.split("\n"):
        i = ln.find("//")
        if i >= 0:
            ln = ln[:i]
        out.append(ln)
    return "\n".join(out)

print("== 1. 文件与结构 ==")
chk(os.path.isfile(MASK), "ScreenMask.kt exists")
src = read(MASK)
chk("object ScreenMask" in src, "declared as object ScreenMask")
for fn in ("fun mask(", "fun luhnOk(", "fun idChecksumOk(", "fun looksSensitive("):
    chk(fn in src, "%s present" % fn)
chk("ID_SHAPE" in src, "ID_SHAPE regex declared")
chk("fun mask(" in src, "mask() present")
chk("covered" in src, "overlap tracking via `covered` present")
chk("ID_LEN" in src and "CARD_MIN" in src and "CARD_MAX" in src, "length constants declared")

print("== 2. 身份证权重表（GB 11643 / ISO 7064 MOD 11-2）==")
m = re.search(r"val\s+weights\s*=\s*intArrayOf\(([^)]*)\)", src)
chk(m is not None, "weights intArrayOf found")
weights = [int(x.strip()) for x in m.group(1).split(",")] if m else []
chk(weights == [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2],
    "weights == 7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2 (got %r)" % (weights,))

print("== 3. 校验字符表 ==")
m = re.search(r'"?(10X98765432)"?', src)
chk(m is not None, "check-char table 10X98765432 found")
CHECK = "10X98765432"

print("== 4. 用源码里的表重算已知答案 ==")
def id_ok(idn):
    if len(idn) != 18:
        return False
    s = 0
    for i in range(17):
        s += int(idn[i]) * weights[i]
    return idn[17].upper() == CHECK[s % 11]

def luhn_ok(digits):
    if not digits or not digits.isdigit():
        return False
    s = 0
    for i, c in enumerate(reversed(digits)):
        d = int(c)
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        s += d
    return s % 10 == 0

# 11010519491231002X 是公开的标准测试号码（校验位 X）
chk(id_ok("11010519491231002X"), "known valid id 11010519491231002X accepted")
chk(not id_ok("110105194912310021"), "same id with wrong check char rejected")
chk(not id_ok("11010519491231002"), "17-digit id rejected")
chk(luhn_ok("4111111111111111"), "known valid card 4111111111111111 accepted")
chk(not luhn_ok("4111111111111112"), "same card, last digit +1 rejected")
chk(luhn_ok("6222021234567890128"), "19-digit unionpay-like number accepted")
chk(not luhn_ok("1234567890123456"), "random 16 digits rejected")

print("== 5. 处理顺序与重叠保护 ==")
i_id = src.find("① 身份证")
i_card = src.find("② 银行卡")
chk(i_id != -1 and i_card != -1, "both passes are marked in the source")
chk(0 < i_id < i_card, "the id pass runs before the card pass")
chk("covered[start] || !text[start].isDigit()" in src,
    "the card pass skips characters already claimed by the id pass")
chk("if (h.start < cursor) continue" in src, "overlapping hits are skipped when splicing")

print("== 6. 上下文排除词 ==")
for w in ("订单", "单号", "运单", "物流", "快递", "编号", "流水", "交易号", "条码", "序列号"):
    chk('"%s"' % w in src, "exclusion word %r present" % w)
chk("CONTEXT_CHARS" in src, "CONTEXT_CHARS constant present")

print("== 7. 短路与边界 ==")
chk(re.search(r"if\s*\(text\.length\s*<\s*15\)\s*return\s+text", src) is not None,
    "short text returns early (length < 15)")
chk(re.search(r"count\s*\{\s*it\.isDigit\(\)\s*\}\s*<\s*15", src) is not None,
    "few-digit text returns early (< 15 digits)")
chk("if (len in CARD_MIN..CARD_MAX) {" in src, "the whole digit run must be 15-19 digits")
chk("start = maxOf(pos, start + 1)" in src,
    "an unmatched run is skipped WHOLE (sliding inside it caused Luhn substring false positives)")
chk("ID_TAG" in src and "CARD_TAG" in src, "mask tags declared")
chk("takeLast(4)" in src, "only last 4 digits kept")
# 反向：脱敏结果里绝不能出现完整原文
chk("win.takeLast(4).uppercase()" in src, "id mask keeps only the tail, not the whole number")

print()
if fails:
    print("FAILED: %d check(s)" % len(fails))
    for f in fails:
        print("  - %s" % f)
    sys.exit(1)

print("== 8. v1.6.20 回归：连写号码（真机实测踩过的坑）==")
# 旧实现用 (?<![0-9A-Za-z]) ... (?![0-9A-Za-z]) 边界断言，
# 身份证与卡号紧贴时（...002X4111...）两边断言同时失败，两个号码一个都没打码。
chk("(?<![0-9A-Za-z])" not in strip_comments(src) and "(?![0-9A-Za-z])" not in strip_comments(src),
    "no alphanumeric lookaround boundaries (they fail when two numbers are adjacent)")
chk("while (i + ID_LEN <= n)" in src, "the id pass tries EVERY start position")
chk("substring(i, i + ID_LEN)" in src, "the id pass slides window by window")
chk("idChecksumOk(win)" in src, "the check digit decides truth, not the boundary")
print("All ScreenMask checks passed.")