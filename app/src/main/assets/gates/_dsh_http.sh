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
  # ⚠️ 制表符也要转义：裸 tab 在 JSON 字符串里是非法字符。org.json 宽容所以线上
  # 一直没炸，但任何严格解析器（python json / jq）遇到 `say "a<TAB>b"` 都会报错。
  s="${s//$'\t'/\\t}"
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
  #
  # ⚠️ v1.2.100 修（独立安全审查发现）：**必须用 `{ ...; } 2>/dev/null` 组重定向**，
  # 不能写 `exec 3<>... 2>/dev/null` —— exec 不带命令时重定向会**永久作用于当前 shell**，
  # 连接成功后整个脚本的 stderr 全指向 /dev/null，本文件里所有诊断输出
  # （403 token 指引、超时三原因、notify/tap 的失败提示）**一条都到不了调用方**，
  # AI 拿到的是静默失败。组的重定向是临时的，而 exec 打开的 fd 3 仍然保留。
  if ! { exec 3<>"/dev/tcp/$DSH_BRIDGE_HOST/$DSH_BRIDGE_PORT"; } 2>/dev/null; then
    echo "dsh: bridge 不可达（$DSH_BRIDGE_HOST:$DSH_BRIDGE_PORT 未监听）" >&2
    # ⚠️ v1.2.104：这里**不再让调用方自己猜**。
    # 连不上有两种完全不同的处境，处置方式相反：
    #   A) 端口上有**别的进程**在 LISTEN —— 桥早就 bind 失败了（历史孤儿进程
    #      攥着继承来的监听 fd，见 OrphanReaper 顶部），等多久都没用，必须重启 App。
    #   B) 端口真空着 —— 引擎/无障碍刚起来，等 2-3 秒重试就行。
    # 旧文案把两者混成「未监听」，于是 A 情况被反复误判成「无障碍没开」。
    _hex=$(printf '%04X' "$DSH_BRIDGE_PORT" 2>/dev/null)
    _info=$(awk -v h=":$_hex" '$2 ~ h"$" && $4 == "0A" { print $8 " " $10; exit }' \
      /proc/net/tcp 2>/dev/null)
    if [ -n "$_info" ]; then
      echo "dsh: 但端口上有进程在监听（uid=${_info%% *} inode=${_info##* }）—— 本 app 的桥没抢到端口" >&2
      echo "dsh:   多半是历史孤儿进程占着（AI 起的后台进程没被销毁，继承了监听 socket）" >&2
      echo "dsh:   处置：重启 App 本体（重启引擎没用）；新版本已从源头断掉这个泄漏" >&2
    else
      echo "dsh: 端口当前无监听 —— 引擎/无障碍服务未就绪，等 2-3 秒重试" >&2
    fi
    return 2
  fi

  {
    printf '%s %s HTTP/1.0\r\n' "$_m" "$_p"
    printf 'Host: %s:%s\r\n' "$DSH_BRIDGE_HOST" "$DSH_BRIDGE_PORT"
    printf 'Connection: close\r\n'
    # 桥 token（v1.2.99）：由引擎环境注入，第三方 App 拿不到 → 无法调用设备能力
    if [ -n "${DSH_BRIDGE_TOKEN:-}" ]; then
      printf 'X-DSH-Token: %s\r\n' "$DSH_BRIDGE_TOKEN"
    fi
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
      [Cc]ontent-[Ll]ength:*)
        # 不用 tr（容器模式下 fork+exec 出来的 bionic 二进制会 REEXEC FAIL）；
        # bash 参数展开就能剥掉空白与 CR。
        _clen=${_line#*:}
        _clen=${_clen//[[:space:]]/}
        ;;
    esac
    _hdr_timeout=1
  done

  # 一个字节都没读到 = 连接后无响应（超时）
  #
  # ⚠️ v1.2.100：诊断信息具体化。旧版只报「读取响应超时」，AI 自查看不出该重试、
  # 该查权限、还是桥已死 —— 只能干等或放弃（用户实测 AI 遇到 rc=3 后停止工作）。
  # 现在把「可能原因 + 下一步」一并给出，让调用方能自行判断。
  if [ -z "$_status" ] && [ "$_hdr_timeout" = 0 ]; then
    exec 3<&- 3>&-
    echo "dsh: 读取响应超时（${DSH_HTTP_TIMEOUT}s 内 bridge 未返回）" >&2
    echo "dsh: bridge=$DSH_BRIDGE_HOST:$DSH_BRIDGE_PORT" >&2
    echo "dsh: 可能原因与建议：" >&2
    echo "dsh:  1) 桥正在处理耗时请求（读屏/截图/批量动作）→ 稍后重试一次即可" >&2
    echo "dsh:  2) 引擎/无障碍服务刚重启，桥尚未就绪 → 等 2-3 秒重试" >&2
    echo "dsh:  3) 通知权限未授予（仅影响 notify）→ 到 App 设置里开通知权限" >&2
    echo "dsh:  可用 'scr dump' 快速探活；若同样超时说明桥确实没在跑" >&2
    return 3
  fi

  # 读响应体：优先按 Content-Length 精确读；否则读到 EOF（服务端 Connection: close）。
  #
  # ⚠️ v1.2.100 修（独立安全审查发现）：旧实现用裸 `cat <&3` —— **没有超时**。
  # 若服务端发完响应头就停住（半开连接），`read -t` 只管头阶段、这里会**永久阻塞**
  # （实测：伪造 slow-header 服务器 + DSH_HTTP_TIMEOUT=3，进程被外部 timeout 8 杀掉）。
  #
  # 用 `timeout` 命令包住读取：engine 的 coreutils/timeout 在 PATH 里有，
  # 比手写分块循环更简单可靠（分块循环要处理 read 的返回值/EOF/短读，易错）。
  #
  # ⚠️ v1.7.x（Linux 容器模式真机，2026-11-12）：**绝不能用外部 timeout/head/cat**。
  # 容器里 PATH 首位 /eng/bin 里没有 timeout，于是落到 Alpine 的 /usr/bin/timeout，
  # 而它 exec 出来的子进程会经 runner 走 reexec 重开 rootfs，preload 失败后直接吐：
  #
  #     REEXEC: preload FAIL        （stdout）
  #     BIONIC: cleaner_exec FAIL   （stderr）
  #
  # 这两行字符串被当成 JSON 响应读走 → dev 报 JSON 解析失败，且**退出码仍是 0**
  # （失败被伪装成成功）。真机实测：容器内 `timeout 5 head -c 4` → rc=11 且 stdout
  # 就是这两行；裸 `head -c 4` 正常 —— 差别在「被外部命令包裹」这一层。
  #
  # 改用 bash 内建读取，一个子进程都不 fork：
  #   · read -N <n>   精确读 n 字节（LC_ALL=C 下按字节计数，正是 Content-Length 语义）
  #   · read -t <sec> 超时返回非 0
  # 组合后比原来更严格：原来 timeout 杀掉 head 时可能已吐出半截，现在读不满即判失败。
  #
  # ⚠️ v1.7.x 真机（2026-11-13）：**`read -N` 没读满时返回非 0，但已读到的内容
  #    仍然赋给了变量**。旧写法只看返回值就把整段丢掉，于是「读到 19031 字节、
  #    期望 25571」被判成彻底失败 —— 调用方拿到空串 + 报错，用户侧表现是
  #    `scr dump` / `dev status` 全废，而同样端点 `curl` 直连完全正常
  #    （curl 自己会循环读）。
  #
  #    为什么读不满：bash 的 `-N` 文档承诺「读满 N 字节」，但对**非阻塞 socket**
  #    它的实际行为是「读到当前可用的那一段就返回」，不继续等剩下的。桥是分块
  #    flush 的（先 header + 前半段，后半段下一轮），大响应必然撞上这个分界。
  #
  # 修法（继续不 fork 任何子进程，容器模式仍安全）：
  #   1. 先 `read -N` 拿一段；
  #   2. **拿到了就保留**，然后循环补读剩余字节；
  #   3. 循环用**总时限**而不是每次重置，半开连接不会把调用方永久挂住；
  #   4. 判定按实际字节数：够了才 0，不够返回 3，但**已读部分照常输出** ——
  #      半截 JSON 也比空串有用，调用方至少能看出响应长什么样。
  _body=""
  _body_rc=0
  if [ -n "$_clen" ] && [ "$_clen" -gt 0 ] 2>/dev/null; then
    # LC_ALL=C 让 read -N 以字节为单位（默认按字符，中文会读多）
    _remaining=$_clen
    _deadline=$(( $(date +%s) + DSH_HTTP_TIMEOUT ))
    while [ "$_remaining" -gt 0 ]; do
      _chunk=""
      _left=$(( _deadline - $(date +%s) ))
      # read 的 -t 给 0 表示「不超时」，所以要兜到 1；超时与否由 _chunk 是否为空判。
      [ "$_left" -lt 1 ] && _left=1
      if LC_ALL=C IFS= read -r -N "$_remaining" -t "$_left" _chunk <&3; then
        _body+=$_chunk
        break
      fi
      # 非 0 有两种：没读满（_chunk 有内容）或真超时（_chunk 为空）。
      if [ -z "$_chunk" ]; then
        _body_rc=1
        break
      fi
      _body+=$_chunk
      _remaining=$(( _clen - ${#_body} ))
    done
    # 补一道字节数核对：循环因 EOF 退出时可能仍然不够。
    [ "${#_body}" -lt "$_clen" ] && _body_rc=1
    printf '%s' "$_body"
  else
    # 无 Content-Length：读到 EOF（服务端用 Connection: close）。块内不 fork。
    while LC_ALL=C IFS= read -r -t "$DSH_HTTP_TIMEOUT" _line <&3; do
      printf '%s\n' "$_line"
    done
  fi
  exec 3<&- 3>&-
  if [ "$_body_rc" = 1 ]; then
    echo "dsh: 响应体不完整（拿到 ${#_body} / 期望 $_clen 字节）" >&2
    echo "dsh: 已读部分已照常输出，可直接看内容判断桥是否在工作" >&2
    echo "dsh: 桥可能仍在处理；稍后重试一次。持续发生请导出 App 日志" >&2
    return 3
  fi

  # 读响应体（已在上方 cat 输出到 stdout），这里只决定退出码。
  #
  # v1.2.100：把 HTTP 状态码通过 DSH_HTTP_STATUS 暴露给调用方，便于给出准确提示
  # （旧版只回 1，闸门脚本无法区分 403 鉴权失败 / 404 路径错 / 500 服务端异常）。
  case "$_status" in
    2*) return 0 ;;
    403)
      # token 由 App 进程生成、随 App 进程存活期不变（引擎重启不影响）。
      # 不匹配通常是：引擎在 App 之外启动（无 token）、或 App 刚重启还没重新拉起引擎。
      echo "dsh: 403 未授权 —— bridge token 不匹配。" >&2
      echo "dsh: 常见原因：引擎不是由 App 拉起的（缺 token 环境变量），或 App 刚重启。" >&2
      echo "dsh: 处理：回到 App 界面让它重新启动引擎（token 会随新 App 进程重新注入）" >&2
      return 1 ;;
    404)
      echo "dsh: 404 端点不存在 —— 可能是引擎版本与桥版本不匹配" >&2
      return 1 ;;
    5*)
      echo "dsh: $_status 服务端错误 —— 稍后重试；持续失败请在 App 内导出日志" >&2
      return 1 ;;
    *) return 1 ;;
  esac
}
