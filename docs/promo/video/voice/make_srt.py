"""从 timing.json 与 v5 时间轴生成 SRT 字幕。

字幕与旁白不是逐字稿关系 —— 字幕只保留最关键的短句，避免和画面文字打架。
"""
import json

FPS = 60
FPS_TOTAL = 3120
SHOTS = [
    ("intro", 0, 180), ("performance", 180, 420), ("extensions", 600, 390),
    ("plugins", 990, 270), ("accessibility", 1260, 360), ("ocr", 1620, 210),
    ("webui", 1830, 330), ("size", 2160, 330), ("pet", 2490, 360),
    ("end", 2850, 270),
]
LEAD = 0.35
LAST_LEAD = 0.05

# 字幕比旁白更短：只留观众一眼要抓住的那句
SUBS = {
    "intro": "完整的 AI Agent，装进口袋",
    "performance": "不是模拟，没有虚拟机",
    "extensions": "21 种环境扩展，一键装好",
    "plugins": "3540 个社区插件",
    "accessibility": "一步 1.45 秒",
    "ocr": "识别 300ms，快 4 倍",
    "webui": "大屏编辑，小屏执行",
    "size": "149MB vs 392MB",
    "pet": "桌宠 · 灵动岛 · 社区驱动",
    "end": "从手机，开始一条更短的路径",
}

timing = {r["key"]: r for r in json.load(open("voice/timing.json", encoding="utf-8"))}

def ts(sec):
    h = int(sec // 3600); m = int(sec % 3600 // 60); s = sec % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")

lines, idx = [], 1
for key, start, dur in SHOTS:
    lead = LAST_LEAD if key == "end" else LEAD
    t0 = start / FPS + lead
    # 字幕跟着人声走，比人声略短（人声结束后半秒内消失）
    d = min(timing[key]["dur"], (dur / FPS) - lead - 0.1)
    t1 = t0 + max(0.8, d)
    lines.append(f"{idx}\n{ts(t0)} --> {ts(t1)}\n{SUBS[key]}\n")
    idx += 1

open("voice/subtitles.srt", "w", encoding="utf-8").write("\n".join(lines))
print(f"subtitles.srt: {idx-1} cues, ends {t1:.2f}s / {FPS_TOTAL/FPS:.2f}s")
