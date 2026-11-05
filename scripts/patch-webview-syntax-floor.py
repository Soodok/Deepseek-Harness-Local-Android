#!/usr/bin/env python3
"""把前端入口 chunk 的语法门槛从 Chrome 94 降下来（v1.6.9）。

## 为什么需要

`dsh-web-frontend` 的上游产物是 es2022，其中 `assets/index-*.js` 含 2 处
`class static {}` 静态块（Chrome 94 起支持）。这是**解析期**语法，
polyfill 补不了 —— 内核低于 94 的设备会「通过门禁 → 解析失败 → 白屏」。

实测（acorn 对 runtime.zip 内 61 个 chunk 逐级降版解析）：
    assets/index-<hash>.js    2 处 static{}    → 需 Chrome 94
    assets/vendor-<hash>.js   5 个私有字段/method → 需 Chrome 94
    61/61 在 es2022 通过，0/61 能在 es2021 通过

用 esbuild 以 `--target=chrome85` 重新转译后，`static{}` 被降级，
语法门槛与 polyfill 的 API 门槛（85）对齐 —— 于是在**构建期**就能保证
Chrome 85 可用，而不是把门槛抬到 94 去绕开问题。

## 代价（实测，非估算）

    target         未压缩           gzip          语法门槛
    不转译        1,712,298       474,427       Chrome 94
    chrome85      1,779,465       477,848       Chrome 85   (+3.4 KB gzip)
    chrome88      1,779,515       477,867       Chrome 88   (+3.4 KB gzip)
    chrome76      1,791,447       481,512       Chrome 76   (+7.1 KB gzip)
    只转译不 minify 2,138,509      508,914       Chrome 85   (+34 KB，不采用)

选 85 而不是 76/88：
  · 85 是 polyfill 清单里最低 API（`String#replaceAll`）对应的版本，两者对齐后
    有效门槛恰好 85，与 README 对外承诺一致，无需抬高门槛。
  · 85 与 88 的产物体积只差 50 字节（实测），没必要为了多 3 个版本号多降一轮。
  · 76 需要额外补 76~85 之间的 API（polyfill 目前没覆盖），是另一项工程。

## 幂等性

esbuild 对已降级的代码再跑一次输出相同（实测），所以重复执行安全。
脚本会记录自己的 target 到 `.dsh-syntax-floor`，便于排查「到底转没转」。

## 环境要求

需要 esbuild。不在本仓库依赖里（仓库根无 package.json），按需装：
    npm i --no-save esbuild
缺失时**不静默跳过** —— 直接 exit 1，因为静默跳过的后果是低版本设备白屏
（这正是 polyfill 脚本 v1.2.28 踩过的坑：patch 静默 WARN，CI 产物里从未生效）。

## 用法

    python3 scripts/patch-webview-syntax-floor.py <runtime-root>
    python3 scripts/patch-webview-syntax-floor.py --zip <runtime.zip>
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

# 目标语法级别。改这个值必须同步改 AppGate.MIN_WEBVIEW_CHROME（探针会校验）。
TARGET = 'chrome85'
MIN_CHROME = 85

# 入口 chunk：由 index.html 直接以 module / modulepreload 加载，必然执行，
# 所以它们的语法门槛就是整个前端的门槛。非入口 chunk 是动态 import 的，
# 也一并转译（成本几乎为零，且能防「点进某功能才崩」）。
FRONTEND_DIST = 'lib/node_modules/@deepseek-ai/dsh-web-frontend/dist'

STAMP = '.dsh-syntax-floor'


def find_esbuild():
    """找 esbuild。按顺序：仓库本地的**平台二进制** → 本地 .bin 包装 → PATH → npx。

    ⚠️ 两个必须踩过的坑：
    ① 必须优先查仓库本地，因为 CI 里 `npm i --no-save esbuild` 只把可执行文件
       放进 node_modules/.bin，**不进 PATH**。只查 PATH 会误判「没装」并 fail-fast。
    ② 必须优先用 `node_modules/@esbuild/<platform>/esbuild[.exe]` 这个**真二进制**，
       而不是 `node_modules/.bin/esbuild` 或 `node_modules/esbuild/bin/esbuild` ——
       后两个是 `#!/usr/bin/env node` 的 JS 包装脚本，在 Windows 上被 Python 的
       subprocess 直接调用会报 `OSError: [WinError 193] %1 不是有效的 Win32 应用程序`
       （它没有 .exe 扩展名，CreateProcess 不认 shebang）。
    """
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)  # scripts/ 的上一级
    nm = os.path.join(repo, 'node_modules')

    # ① 平台专用真二进制：esbuild 的 npm 包把二进制放在 @esbuild/<os>-<arch>/
    exe = 'esbuild.exe' if os.name == 'nt' else 'esbuild'
    plat_dir = os.path.join(nm, '@esbuild')
    if os.path.isdir(plat_dir):
        for sub in sorted(os.listdir(plat_dir)):
            cand = os.path.join(plat_dir, sub, exe)
            if os.path.isfile(cand):
                return [cand]

    # ② 本地 .bin 包装（POSIX 下是符号链接/脚本，Linux CI 可直接执行）
    if os.name != 'nt':
        for name in ('esbuild',):
            cand = os.path.join(nm, '.bin', name)
            if os.path.isfile(cand):
                return [cand]

    # ③ PATH
    for cand in ('esbuild', 'esbuild.exe', 'esbuild.cmd'):
        p = shutil.which(cand)
        if p:
            return [p]

    # ④ npx 兜底
    p = shutil.which('npx') or shutil.which('npx.cmd')
    if p:
        return [p, '--yes', 'esbuild@0.28.2']

    sys.exit(
        '错误：找不到 esbuild。低版本 WebView 会因为 static{} 解析失败而白屏。\n'
        '请先安装：npm i --no-save esbuild\n'
        '（本脚本刻意不静默跳过 —— 跳过等于让 Chrome 85~93 的设备白屏）'
    )


def list_chunks(dist_dir):
    """列出要转译的 js。包含入口与动态 import 的分包。"""
    assets = os.path.join(dist_dir, 'assets')
    if not os.path.isdir(assets):
        return []
    out = []
    for name in os.listdir(assets):
        if not name.endswith('.js'):
            continue
        # 只处理 JS bundle；跳过 sourcemap 等
        out.append(os.path.join(assets, name))
    return sorted(out)


def transpile_one(esb, src, dst):
    """esbuild --target=<TARGET> --minify --format=esm。

    必须带 --minify：实测只转译不 minify 会让 gzip 从 474KB 涨到 509KB（+34KB），
    而转译+minify 只涨 3.4KB（重新压缩抵消了转译膨胀）。
    """
    cmd = esb + [
        src,
        f'--target={TARGET}',
        '--minify',
        '--format=esm',
        '--log-level=error',
        f'--outfile={dst}',
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f'错误：esbuild 转译失败 {os.path.basename(src)}\n{r.stderr.strip()}')


def has_static_block(path):
    """粗检 static{} 是否还在。用正则而非 AST —— 这里只需要一个 fail-fast 的信号，
    真正的语法门槛由 scripts/probes/_test_webview_syntax_floor.mjs（acorn AST）守。"""
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        s = f.read()
    # minify 后的形态是 `static{`；带空格的是未压缩形态
    return 'static{' in s or 'static {' in s


def patch_root(root):
    dist = os.path.join(root, *FRONTEND_DIST.split('/'))
    if not os.path.isdir(dist):
        # 前端不在这个 runtime 形态里（比如 lite runtime 剥掉了前端）——允许跳过，
        # 但要打印出来，便于区分「不需要」和「找不到」。
        print(f'跳过：前端 dist 不存在（{dist}）')
        return
    chunks = list_chunks(dist)
    if not chunks:
        print(f'WARN: {dist}/assets 下没有 .js')
        return

    esb = find_esbuild()
    total_before = total_after = 0
    for src in chunks:
        before = os.path.getsize(src)
        tmp = src + '.dshfloor.tmp'
        transpile_one(esb, src, tmp)
        after = os.path.getsize(tmp)
        # 转译后仍有 static{} → 说明 target 没覆盖到，宁可留原文件也不要半个产物
        if has_static_block(tmp):
            os.unlink(tmp)
            raise SystemExit(
                f'错误：{os.path.basename(src)} 转译后仍有 static{{}} —— '
                f'target={TARGET} 不足以降到 {MIN_CHROME}'
            )
        os.replace(tmp, src)
        total_before += before
        total_after += after

    with open(os.path.join(dist, STAMP), 'w', encoding='utf-8') as f:
        json.dump(
            {'target': TARGET, 'minChrome': MIN_CHROME, 'chunks': len(chunks)}, f
        )
        f.write('\n')
    print(
        f'syntax floor: {len(chunks)} chunk(s) {TARGET} '
        f'({total_before} → {total_after} bytes)'
    )


def patch_zip(zpath):
    """对 runtime.zip 就地转译（构建门禁补救用）。"""
    fd, tmpzip = tempfile.mkstemp(suffix='.zip')
    os.close(fd)
    try:
        with zipfile.ZipFile(zpath) as zin, zipfile.ZipFile(
            tmpzip, 'w', zipfile.ZIP_DEFLATED
        ) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if (
                    item.filename.startswith(FRONTEND_DIST + '/assets/')
                    and item.filename.endswith('.js')
                ):
                    data = _transpile_bytes(data, item.filename)
                # 保留原时间戳与权限位
                zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                zi.external_attr = item.external_attr
                zi.compress_type = item.compress_type
                zout.writestr(zi, data)
        shutil.move(tmpzip, zpath)
        print(f'syntax floor: 已就地转译 {zpath}')
    finally:
        if os.path.exists(tmpzip):
            os.unlink(tmpzip)


def _transpile_bytes(data, name):
    esb = find_esbuild()
    fd, tmp_in = tempfile.mkstemp(suffix='.js')
    os.close(fd)
    fd, tmp_out = tempfile.mkstemp(suffix='.js')
    os.close(fd)
    try:
        with open(tmp_in, 'wb') as f:
            f.write(data)
        transpile_one(esb, tmp_in, tmp_out)
        if has_static_block(tmp_out):
            raise SystemExit(f'错误：{name} 转译后仍有 static{{}}（target={TARGET} 不足）')
        with open(tmp_out, 'rb') as f:
            return f.read()
    finally:
        for p in (tmp_in, tmp_out):
            if os.path.exists(p):
                os.unlink(p)


def main():
    ap = argparse.ArgumentParser(description='降低前端 bundle 的语法门槛')
    ap.add_argument('root', nargs='?', help='runtime 根目录（含 lib/node_modules）')
    ap.add_argument('--zip', help='改为就地对 runtime.zip 打补丁')
    a = ap.parse_args()
    if a.zip:
        patch_zip(a.zip)
        return
    if not a.root:
        ap.error('需要 runtime 根目录，或用 --zip 指定 zip')
    patch_root(a.root)


if __name__ == '__main__':
    main()
