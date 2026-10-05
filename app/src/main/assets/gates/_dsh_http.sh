# [dsh-android] agent 门脚本共用的 HTTP 客户端（纯 bash，零依赖）
#
# 为什么不用 node：此前 notify/scr/say 每次调用都 `exec node -e 'fetch(...)'`，
# 实测每次白烧约 150ms（5 次 0.75s）外加一个 Node 进程的峰值内存；内存高水位时
# （用户实测 free 仅 344MB、load≈10）scr dump 挂 >60s、notify 挂 >120s，
# 正是「冷启慢 + 客户端无超时」叠加的结果。
#
# 本库要求 bash（/dev/tcp 是 bash 特性，/system/bin/sh 不支持）；门脚本统一用
# engine/bin/bash 作解释器。
#
# 要点：
#  · 不再 fork Node，调用开销降到毫秒级
#  · 内建整体超时（read -t），服务端不响应时快速失败而非永久挂住
#  · Content-Length 按【字节】计算（中文 UTF-8 每字 3 字节）—— v1.2.43 修过服务端
#    「按字符数读」的同源 bug，客户端侧同样必须按字节
#  · JSON body 做最小转义（反斜杠/双引号/换行），与服务端 JSONObject 解析兼容

# 允许外部覆盖（便于测试与将来端口变更）；默认回环 3083
DSH_BRIDGE_HOST="${DSH_BRIDGE_HOST:-127.0.0.1}"
DSH_BRIDGE_PORT="${DSH_BRIDGE_PORT:-3083}"
# 单次请求的整体读超时（秒）；bridge 正常时毫秒级返回
DSH_HTTP_TIMEOUT="${DSH_HTTP_TIMEOUT:-10}"

# JSON 字符串最小转义：反斜杠 → \\、双引号 → \"、换行 → \n（去掉裸回车）
#
# ⚡ v1.2.58 提速（用户反馈"有时候还是偏慢"）：旧实现用 sed+tr+awk **三个外部进程**
# 串联，每次调用都要 fork 三次 —— 手机上 fork 是毫秒级开销，而这类门脚本单次
# 调用本就只有几十毫秒，转义占了可观比例。现改为**纯 bash 内建**（参数展开），零 fork。
#
# ⚠️ 写法有坑（实测踩过两次，已用 15 组用例对拍验证与旧实现等价）：
#   · 反斜杠必须用字面惯用法 `${s//\\/\\\\}`（pattern `\\`=一个反斜杠，
#     replacement `\\\\`=两个）；用变量拼接会静默失效。
#   · 换行替换的 replacement 里 `\n` 要写成字面 `\\n` —— 写 `$BSn` 会被解析成
#     变量 `BSn`（未定义→空），换行符直接丢失。
#   · 顺序：先反斜杠，再双引号，最后控制字符（否则会把新引入的反斜杠再转一遍）。
_dsh_json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\r'/}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

# UTF-8 字节长度（Content-Length 必须按字节算，中文每字 3 字节）
# wc -c 仍是外部命令，但只此一处且不可避免（bash 内建 ${#s} 按字符计数）。
_dsh_byte_len() {
  local n
  n=$(LC_ALL=C; printf '%s' "$1" | wc -c)
  printf '%s' "${n//[[:space:]]/}"
}

# dsh_http <METHOD> <PATH> [BODY]
#   响应体 → stdout；退出码 0=2xx，1=非 2xx，2=连接失败，3=超时
dsh_http() {
  _m="$1"; _p="$2"; _b="$3"
  _len=$(_dsh_byte_len "$_b")

  # 注意：/dev/tcp 需要 bash；若被 toybox sh 执行会报 "can't create /dev/tcp/..."
  # 门脚本已自举到 engine bash，这里只做连接失败的统一处理。
  if ! exec 3<>/dev/tcp/$DSH_BRIDGE_HOST/$DSH_BRIDGE_PORT 2>/dev/null; then
    echo "dsh: bridge 不可达（$DSH_BRIDGE_HOST:$DSH_BRIDGE_PORT 未监听）" >&2
    return 2
  fi

  {
    printf '%s %s HTTP/1.0\r\n' "$_m" "$_p"
    printf 'Host: %s:%s\r\n' "$DSH_BRIDGE_HOST" "$DSH_BRIDGE_PORT"
    printf 'Connection: close\r\n'
    if [ "$_m" = "POST" ]; then
      printf 'Content-Type: application/json\r\n'
      printf 'Content-Length: %s\r\n' "$_len"
    fi
    printf '\r\n'
    [ "$_m" = "POST" ] && printf '%s' "$_b"
  } >&3

  # 读响应头（带超时；正常毫秒级）
  _status=""; _clen=""; _hdr_timeout=0
  while IFS= read -r -t "$DSH_HTTP_TIMEOUT" _line <&3; do
    _line=${_line%$'\r'}
    [ -z "$_line" ] && break
    case "$_line" in
      HTTP/*) _status=${_line#HTTP/* }; _status=${_status%% *} ;;
      [Cc]ontent-[Ll]ength:*) _clen=${_line#*: }; _clen=$(printf '%s' "$_clen" | tr -d ' \r') ;;
    esac
    _hdr_timeout=1
  done

  # 一个字节都没读到 = 连接后无响应（超时）
  if [ -z "$_status" ] && [ "$_hdr_timeout" = 0 ]; then
    exec 3<&- 3>&-
    echo "dsh: 读取响应超时（bridge 无响应）" >&2
    return 3
  fi

  # 读响应体：优先按 Content-Length 精确读；否则读到 EOF（服务端 Connection: close）
  if [ -n "$_clen" ] && [ "$_clen" -gt 0 ] 2>/dev/null; then
    head -c "$_clen" <&3
  else
    cat <&3
  fi
  exec 3<&- 3>&-

  case "$_status" in
    2*) return 0 ;;
    *) return 1 ;;
  esac
}
