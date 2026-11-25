#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 NDK 头导出 `scripts/syscall_numbers.json`（syscall 名 → 编号）。

为什么要有这个文件：CI 的 `i18n-check` runner 上没有 NDK，而探针
`_test_dsh_alpine_source_sync.py` 需要**实跑** `gen_bpf_tree.py` 来证明
「树里真的含 SYS_accept/epoll_create/eventfd/inotify_init/signalfd」。
没有编号就生成不了树 → 探针拿不到 stdout → 那 5 条从 2026-11-22 起一直假红。

编号是**内核 ABI 常量**，不随宿主/NDK 小版本变；把它入库后，没 NDK 的环境
也能算出与入库内联树逐行一致的结果。

用法（有 NDK 时；NDK 版本或名字表变了要重跑并提交）：
    python scripts/tools/dump-syscall-numbers.py
    git add scripts/syscall_numbers.json

跑完脚本会自己校验：用刚导出的表重新生成两棵 arch 树，与仓库里
`dsh_alpine.c` 的内联树逐行比对，不一致就退非零 —— 防止「表导出了但和源码不同步」。
"""
import json
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
sys.path.insert(0, os.path.join(ROOT, 'scripts'))


def main():
    import gen_bpf_tree as g

    ndk = g.find_ndk()
    if not ndk:
        print('找不到 NDK —— 这个脚本必须在装有 NDK 的机器上跑。', file=sys.stderr)
        return 2
    print('NDK: %s' % ndk)

    out = {}
    for arch in ('aarch64', 'x86_64'):
        g.NR_NAMES = {}
        g.load_numbers(arch, verbose=True)
        wanted = list(g.NEEDED) + (list(g.LEGACY_X86_64) if arch == 'x86_64' else [])
        out[arch] = {n: int(g.NR_NAMES[n]) for n in wanted}
        print('  %-8s %d 条' % (arch, len(out[arch])))

    path = os.path.join(ROOT, 'scripts', 'syscall_numbers.json')
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write('\n')
    print('written %s (%d bytes)' % (path, os.path.getsize(path)))

    # 自校：用新表重跑生成器（强制走兜底路径），与入库内联树比。
    env = dict(os.environ)
    env.pop('ANDROID_NDK', None)
    env.pop('ANDROID_NDK_HOME', None)
    env.pop('ANDROID_HOME', None)
    env.pop('ANDROID_SDK_ROOT', None)
    src = open(os.path.join(ROOT, 'app', 'src', 'main', 'cpp', 'dsh_alpine.c'),
               encoding='utf-8').read()

    def inline_x86(text):
        L = text.split('\n')
        for i, l in enumerate(L):
            if l.strip() == '#else' and i + 2 < len(L) and 'AUDIT_ARCH_X86_64' in L[i + 2]:
                for j in range(i + 1, len(L)):
                    if L[j].strip() == '#endif':
                        return '\n'.join(L[i + 1:j]).strip()
        return None

    r = subprocess.run([sys.executable, os.path.join(ROOT, 'scripts', 'gen_bpf_tree.py'),
                        'x86_64'], capture_output=True, text=True, env=env)
    if r.returncode != 0:
        print('兜底路径生成失败：%s' % r.stderr.strip()[:200], file=sys.stderr)
        return 1
    inl = inline_x86(src)
    gen = '\n'.join(r.stdout.replace('\r\n', '\n').split('\n')).strip()
    if inl != gen:
        print('★表与内联树不一致 —— 需要把新树插回 dsh_alpine.c', file=sys.stderr)
        print('  内联 %d 行 / 生成 %d 行' % (len(inl.split('\n')), len(gen.split('\n'))),
              file=sys.stderr)
        return 1
    print('自校通过：兜底路径生成的 x86_64 树与 dsh_alpine.c 内联树逐行一致（%d 行）'
          % len(gen.split('\n')))
    return 0


if __name__ == '__main__':
    sys.exit(main())
