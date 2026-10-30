# 旁白文案 v5（TTS 用）

> **已合成**：`voice/narration.wav`（52.000s，48kHz 立体声，-16 LUFS / 峰值 -1.5dB）
> 试听版：`voice/narration.mp3`
> 配音：`zh-CN-YunjianNeural`（男声，沉稳），语速 `+8%`
>
> 实测语速 **约 5 字/秒**（含标点停顿）。每镜字数按「镜头时长 × 5 × 0.8」配，
> 留 20% 呼吸。允许旁白跨镜头衔接（一句说完自然过渡到下一镜），
> 只要溢出量小于下一段的余量、不撞车即可。

## 镜头表与旁白（对齐 `remotion/src/v5/data.ts`）

| # | 镜头 | 时长 | 旁白 | 字数 | TTS 实际 |
|---|---|---|---|---|---|
| 1 | intro | 3.0s | 完整的 AI Agent，装进口袋。 | 14 | 3.26s |
| 2 | performance | 7.0s | 不是模拟，没有虚拟机。旗舰三秒四，连三 G 内存的手表，十三秒也能跑。 | 34 | 7.54s |
| 3 | extensions | 6.5s | 二十一种环境扩展。Python、Go、Rust、JDK，全都一键装好。 | 30 | 6.62s |
| 4 | plugins | 4.5s | 三千五百多个社区插件，一键装卸。 | 17 | 3.82s |
| 5 | accessibility | 6.0s | 它看得见，也点得动。一步一点四五秒。 | 19 | 4.85s |
| 6 | ocr | 3.5s | 识别三百毫秒，快四倍。 | 12 | 3.07s |
| 7 | webui | 5.5s | 大屏编辑，小屏执行。 | 10 | 2.81s |
| 8 | size | 5.5s | 一百四十九兆。proot 要三百九十二兆。差别是整个 Ubuntu。 | 26 | 6.46s |
| 9 | pet | 6.0s | 桌宠、灵动岛，都来自社区。二十四个模块，一百零六个动画。 | 29 | 6.50s |
| 10 | end | 4.5s | 从手机，开始一条更短的路径。 | 14 | 3.31s |

**总计**：人声 48.2s / 画面 52.0s，尾部留白 1.14s。

## 重新合成

```bash
# 1) 分段合成（改文案后跑这个）
python voice/tts.py zh-CN-YunjianNeural "+8%"

# 2) 拼成整轨（含响度标准化）
python voice/build_track.py
```

`voice/timing.json` 记录每段的字数/时长/余量，用于校验。

## 换音色

```bash
python voice/tts.py zh-CN-XiaoxiaoNeural "+8%"   # 女声，亲和
python voice/tts.py zh-CN-YunxiNeural "+5%"      # 男声，年轻
python voice/tts.py zh-CN-YunjianNeural "+8%"    # 男声，沉稳（当前）
```

换完记得重跑 `build_track.py`。

## 数字读法约定（避免 TTS 念错）

| 原文 | 写法 | 理由 |
|---|---|---|
| 3.4 秒 | 三秒四 | 「3.4」会被念成「三点四」，不自然 |
| 1.45 秒 | 一点四五秒 | 小数用中文数字 |
| 326–393ms | 三百毫秒 | 取整，区间念出来啰嗦 |
| 3GB | 三 G | 避免念成字母串 |
| 149MB | 一百四十九兆 | 「兆」比「MB」自然 |
| 21 / 24 / 106 | 中文数字 | 单个数念中文更顺 |

## 禁止事项

- **不要**把旁白写成字幕逐字稿——字幕另行处理
- **不要**念英文全称（bionic、proot 除外，这两个是核心卖点）
- **不要**用「颠覆」「革命性」「史上最强」这类词

## 数据出处（全部已核验）

| 数字 | 出处 |
|---|---|
| 3.4s / 13s(3GB 手表) | `README.md:60-67` |
| 21 个环境扩展 | `app/src/main/assets/extensions/catalog.json` |
| 3540 个社区插件 | `docs/promo/video/MATERIALS.md`（`PluginMarket` 实测条目数） |
| 1.45s 单步 / 326–393ms OCR / 快 4 倍 | `docs/ACCESSIBILITY.md:29-31` |
| 149MB / 392MB | `docs/promo/video/MATERIALS.md:114-127` |
| 24 个 Kotlin 模块 / 106 动画 | `docs/pet/README.md`；`pet-config.json` 实测 106 个 webp |
