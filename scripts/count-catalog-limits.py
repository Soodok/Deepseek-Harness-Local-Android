# -*- coding: utf-8 -*-
"""精确统计 EndpointCatalog 里还缺 limits 的条目（括号配对，不用正则切分）。"""
import io
import re

P = "app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt"
s = io.open(P, encoding="utf-8").read()
BS = chr(92)  # 反斜杠

starts = [m.start() for m in re.finditer(r"\n {16}e\(", s)]
missing = []
for st in starts:
    i = st + 1
    depth = 0
    j = i + 1
    instr = False
    while j < len(s):
        c = s[j]
        if instr:
            if c == BS:
                j += 2
                continue
            if c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
        j += 1
    seg = s[i:j]
    m = re.search(r'"([^"]+)"', seg)
    eid = m.group(1) if m else "?"
    if "limits =" not in seg:
        missing.append(eid)

print("entries:", len(starts), "missing:", len(missing))
print(", ".join(missing))
