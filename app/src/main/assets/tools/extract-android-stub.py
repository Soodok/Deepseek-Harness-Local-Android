#!/usr/bin/env python3
"""从 Google 官方 SDK platform 包提取编译用 android.jar（裁剪版）。

## 为什么需要

手机本地编译 Android 应用需要 android.jar（`android.*` API 的编译期 stub）。
Termux 仓库**不提供**它；设备上的 `/system/framework/framework.jar` 是 **DEX 格式**
（不能当 javac classpath）；只有 Google 官方 SDK 包含 class 格式的 android.jar。

## 做法

1. 下载官方 `platform-<API>_r<NN>.zip`（约 60MB，含 android.jar 与若干工具）
2. 从中取出 `android.jar`，**裁掉 res/assets/NOTICES 等编译不需要的部分**
   （实测 26.5MB → 5.3MB，保留 6228 个 .class）
3. 输出裁剪版到指定路径

不随本项目分发 android.jar（避免再分发 SDK 组件）；由使用者/AI 按需从官方源获取。

## 用法

    python3 extract-android-stub.py <API 级别> <输出路径> [--cache 缓存目录]
    例：python3 extract-android-stub.py 36 ~/android.jar
"""
import argparse
import io
import os
import sys
import urllib.request
import zipfile

REPO = 'https://dl.google.com/android/repository'
# 已知的 platform 包修订号（按 API 级别）；未知时脚本会尝试常见修订
KNOWN = {
    34: 'platform-34_r03.zip',
    35: 'platform-35_r02.zip',
    36: 'platform-36_r01.zip',
}
FALLBACK_REVS = ['r03', 'r02', 'r01', 'r04', 'r05']


def log(msg: str) -> None:
    print(f'[android-stub] {msg}', flush=True)


def download(url: str, dest: str) -> bool:
    log(f'downloading {url}')
    try:
        with urllib.request.urlopen(url, timeout=120) as r, open(dest, 'wb') as f:
            total = int(r.headers.get('Content-Length') or 0)
            got = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                if total and got % (8 << 20) < (1 << 20):
                    log(f'  {got * 100 // total}% ({got >> 20}MB / {total >> 20}MB)')
        return True
    except Exception as e:
        log(f'  failed: {e}')
        try:
            os.remove(dest)
        except OSError:
            pass
        return False


def resolve_platform_zip(api: int, cache: str) -> str | None:
    """下载 platform 包到缓存，返回本地路径；失败返回 None"""
    candidates = [KNOWN[api]] if api in KNOWN else []
    candidates += [f'platform-{api}_{rev}.zip' for rev in FALLBACK_REVS
                   if f'platform-{api}_{rev}.zip' not in candidates]
    for name in candidates:
        local = os.path.join(cache, name)
        if os.path.isfile(local) and os.path.getsize(local) > 1_000_000:
            log(f'cached: {name}')
            return local
        if download(f'{REPO}/{name}', local):
            log(f'  ok: {name} ({os.path.getsize(local) >> 20}MB)')
            return local
    return None


def trim_android_jar(zip_path: str, out_path: str) -> bool:
    """从 platform zip 中提取 android.jar 并裁剪为编译用最小集"""
    with zipfile.ZipFile(zip_path) as z:
        # platform 包内路径形如 android-36/android.jar
        names = [n for n in z.namelist() if n.endswith('/android.jar') or n == 'android.jar']
        if not names:
            log('ERROR: android.jar not found inside platform package')
            return False
        src = names[0]
        log(f'extracting {src}')
        data = z.read(src)

    kept = dropped = kept_bytes = 0
    with zipfile.ZipFile(io.BytesIO(data)) as src_jar, \
            zipfile.ZipFile(out_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as out_jar:
        for info in src_jar.infolist():
            n = info.filename
            # 编译只需 .class 与 MANIFEST；res/ assets/ NOTICES 等一律丢弃
            if n.endswith('.class') or n.endswith('MANIFEST.MF'):
                out_jar.writestr(info, src_jar.read(n))
                kept += 1
                kept_bytes += info.file_size
            else:
                dropped += 1
    log(f'trimmed: kept {kept} classes ({kept_bytes >> 20}MB uncompressed), '
        f'dropped {dropped} resource entries')
    log(f'output: {out_path} ({os.path.getsize(out_path) / 1048576:.1f}MB)')
    return kept > 1000


def main() -> int:
    ap = argparse.ArgumentParser(description='Extract a trimmed android.jar from the official SDK')
    ap.add_argument('api', type=int, help='Android API level (e.g. 36)')
    ap.add_argument('out', help='output path for the trimmed android.jar')
    ap.add_argument('--cache', default=os.environ.get('TMPDIR') or '/tmp',
                    help='directory to cache the downloaded platform package')
    args = ap.parse_args()

    os.makedirs(args.cache, exist_ok=True)
    out = os.path.abspath(os.path.expanduser(args.out))
    os.makedirs(os.path.dirname(out) or '.', exist_ok=True)

    zp = resolve_platform_zip(args.api, args.cache)
    if not zp:
        log(f'ERROR: could not download platform package for API {args.api}')
        return 1
    if not trim_android_jar(zp, out):
        return 1
    log('done')
    return 0


if __name__ == '__main__':
    sys.exit(main())
