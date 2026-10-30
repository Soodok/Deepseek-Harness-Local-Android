#!/usr/bin/env bash
# 把旁白音轨混进成片。画面不带音轨（Remotion 默认静音），所以直接 map 两个流。
# 用法: ./voice/mux.sh <输入mp4> <输出mp4>
set -euo pipefail
IN="${1:?input mp4 required}"
OUT="${2:?output mp4 required}"
NARR="$(dirname "$0")/narration.wav"

[ -f "$NARR" ] || { echo "缺少旁白音轨: $NARR（先跑 python voice/build_track.py）"; exit 1; }
[ -f "$IN" ]   || { echo "找不到输入画面: $IN"; exit 1; }

ffmpeg -y -v error -i "$IN" -i "$NARR" \
  -map 0:v:0 -map 1:a:0 \
  -c:v copy -c:a aac -b:a 192k -shortest \
  -movflags +faststart \
  "$OUT"

echo "OK -> $OUT"
ffprobe -v error -show_entries format=duration,size -show_entries stream=codec_type,codec_name \
  -of default=nw=1 "$OUT"
