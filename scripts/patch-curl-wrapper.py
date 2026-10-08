#!/usr/bin/env python3
"""把修好的 curl 包装器写入 runtime.zip，并给 bin/ 下的 node 包装器补 LD_LIBRARY_PATH。

## 为什么需要这个脚本（v1.2.100）
runtime.zip 由 collect-termux-runtime.sh 从 Termux 仓库收集生成；其中 `bin/curl`
是脚本包装器（系统 curl 链接了坏掉的 OpenSSL，故改用 node fetch 实现）。
两处缺陷由 AI 自查发现：

1. **参数面太窄**：只认 `-o/-X/-H/-d/--max-time`。
   agent 常用的 `-m 5`（防挂死）被静默忽略 → 请求可能永久挂住；
   `-i`（看响应头）、`-f`（4xx/5xx 非零退出）也没有。
2. **缺 LD_LIBRARY_PATH**：包装器用 `exec "$(dirname "$0")/node"` 调 node，
   而 node 是动态链接的 bionic 二进制。引擎子进程本来就带这个环境变量，
   所以「在引擎里跑 bash 再 curl」能用；但闸门脚本、独立调用、AI 直接
   `screen -c` 类场景没有它 → `CANNOT LINK EXECUTABLE ... libz.so.1 not found`。

collect-termux-runtime.sh 里已同步修正（下次重新收集即生效）；本脚本用于
**给已收集好的 runtime.zip 打补丁**，避免为了一个脚本改动重跑整条收集流程。

## 用法
    python scripts/patch-curl-wrapper.py <runtime.zip> [更多 runtime.zip...]
"""
import shutil
import sys
import zipfile

BS = chr(92)  # 反斜杠

CURL_SRC = '''#!/system/bin/sh
# Android curl -> node fetch (system curl cannot link due to broken system OpenSSL).
# http(s) only, first bare arg = URL.
# Options: -s/-sS silent, -o FILE, -X METHOD, -H "K: V" (repeatable), -d DATA,
#          -m/--max-time/--connect-timeout SECONDS, -i (include headers), -f (fail on 4xx/5xx).
# Accepted-and-ignored: -L -k --compressed -A -e -b -u --proxy -w --retry.
URL="" METHOD="" OUT="" SILENT="" DATA=""
HDRS=""
TIMEOUT=""
INCLUDE=""
FAIL=""
nextval=""
for word in "$@"; do
  if [ -n "$nextval" ]; then
    case "$nextval" in
      -o|--output) OUT="$word" ;;
      -X|--request) METHOD="$word" ;;
      -H|--header) HDRS="$HDRS$word__BS__n" ;;
      -d|--data|--data-raw|--data-binary) DATA="$word" ;;
      -m|--max-time|--connect-timeout) TIMEOUT="$word" ;;
      -w|--write-out|--retry|--retry-delay|-A|--user-agent|-e|--referer|-b|--cookie|-u|--user|--proxy) : ;;
    esac
    nextval=""
    continue
  fi
  case "$word" in
    -o|--output|-X|--request|-H|--header|-d|--data|--data-raw|--data-binary) nextval="$word" ;;
    -m|--max-time|--connect-timeout) nextval="$word" ;;
    -w|--write-out|--retry|--retry-delay|-A|--user-agent|-e|--referer|-b|--cookie|-u|--user|--proxy) nextval="$word" ;;
    -s|-sS|-S|--silent) SILENT=1 ;;
    -i|--include) INCLUDE=1 ;;
    -f|--fail) FAIL=1 ;;
    -L|--location|-k|--insecure|--compressed|-g|--globoff) : ;;
    -*) : ;;
    *) if [ -z "$URL" ]; then URL="$word"; fi ;;
  esac
done
export CURL_URL="$URL" CURL_METHOD="$METHOD" CURL_OUT="$OUT" __BS__
  CURL_SILENT="$SILENT" CURL_DATA="$DATA" CURL_HDRS="$HDRS" __BS__
  CURL_TIMEOUT="$TIMEOUT" CURL_INCLUDE="$INCLUDE" CURL_FAIL="$FAIL"
PREFIX="$(cd "$(dirname "$0")/.." && pwd)"
# node 是动态链接的 bionic 二进制：缺 LD_LIBRARY_PATH 时连 libz.so.1 都找不到
# （v1.2.100 实测：独立调用时 CANNOT LINK ... libz.so.1 not found）。
export LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$(dirname "$0")/node" -e '
(async()=>{
  try{
    const url=process.env.CURL_URL.trim();
    const h={};
    (process.env.CURL_HDRS||"").split("__BS____BS__n").filter(Boolean).forEach(l=>{const c=l.indexOf(":");if(c>0)h[l.slice(0,c).trim()]=l.slice(c+1).trim()});
    const d=process.env.CURL_DATA||null;
    const m=(d&&!process.env.CURL_METHOD)?"POST":(process.env.CURL_METHOD||"GET");
    // 超时（v1.2.100）：agent 常写 -m 5 防挂死，此前该参数被忽略
    const tmo=parseInt(process.env.CURL_TIMEOUT||"",10);
    const ac=new AbortController();
    if(tmo>0)setTimeout(()=>ac.abort(),tmo*1000);
    const r=await fetch(url,{method:m,headers:h,body:d||void 0,signal:ac.signal});
    const buf=Buffer.from(await r.arrayBuffer());
    // -i：响应头 + 空行 + 正文（与真 curl 形状一致）
    const body=process.env.CURL_INCLUDE
      ? Buffer.concat([Buffer.from("HTTP/"+r.status+" "+r.statusText+"__BS__n"+[...r.headers.entries()].map(([k,v])=>k+": "+v).join("__BS__n")+"__BS__n__BS__n","utf8"),buf])
      : buf;
    if(process.env.CURL_OUT){require("fs").writeFileSync(process.env.CURL_OUT,body)}
    else{process.stdout.write(body)}
    // -f：HTTP >=400 以 22 退出（与 curl 一致）；不带 -f 时 >=400 退 1（保持原语义）
    process.exit((process.env.CURL_FAIL&&!r.ok)?22:(r.ok?0:1));
  }catch(e){if(!process.env.CURL_SILENT)console.error(e.message);process.exit(2)}
})()
'
'''.replace("__BS__", BS)

NODE_EXEC_OLD = 'exec "$(dirname "$0")/node"'
NODE_EXEC_NEW = (
    'PREFIX="$(cd "$(dirname "$0")/.." && pwd)"' + "\n"
    'export LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"' + "\n"
    'exec "$(dirname "$0")/node"'
)


def patch(zip_path: str) -> None:
    zin = zipfile.ZipFile(zip_path)
    items = [(i, zin.read(i.filename)) for i in zin.infolist()]
    zin.close()
    shutil.copy2(zip_path, zip_path + ".bak")

    touched = []
    out = []
    for info, data in items:
        name = info.filename
        if name == "bin/curl":
            data = CURL_SRC.encode()
            touched.append("bin/curl (replaced)")
        elif name.startswith("bin/") and name.count("/") == 1 and not info.is_dir():
            try:
                txt = data.decode("utf-8")
            except UnicodeDecodeError:
                txt = None
            if txt and NODE_EXEC_OLD in txt and "LD_LIBRARY_PATH" not in txt:
                data = txt.replace(NODE_EXEC_OLD, NODE_EXEC_NEW).encode()
                touched.append(name + " (+LD_LIBRARY_PATH)")
        out.append((info, data))

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for info, data in out:
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type if not info.is_dir() else zipfile.ZIP_STORED
            zi.external_attr = info.external_attr
            zi.internal_attr = info.internal_attr
            zi.create_system = info.create_system
            zi.comment = info.comment
            zout.writestr(zi, data)

    print(f"{zip_path}:")
    for t in touched:
        print("  " + t)


def verify(zip_path: str) -> bool:
    z = zipfile.ZipFile(zip_path)
    d = z.read("bin/node")[:64]
    arch = {0x3E: "x86-64", 0xB7: "arm64"}.get(int.from_bytes(d[18:20], "little"), "?")
    c = z.read("bin/curl").decode("utf-8", "replace")
    ok = all(k in c for k in (
        "-m|--max-time|--connect-timeout) TIMEOUT",
        "export LD_LIBRARY_PATH=",
        "CURL_TIMEOUT=",
        "ac.abort()",
        "CURL_INCLUDE",
        "CURL_FAIL",
    ))
    links = sum(1 for n in z.namelist() if (z.getinfo(n).comment or b"").startswith(b"LINK:"))
    print(f"  校验: 架构={arch} 去重条目={links} curl 修复={'OK' if ok else '缺失'}")
    return ok


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    all_ok = True
    for path in sys.argv[1:]:
        patch(path)
        all_ok &= verify(path)
    sys.exit(0 if all_ok else 1)
