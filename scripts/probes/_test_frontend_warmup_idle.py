#!/usr/bin/env python3
"""前端语法高亮预热必须走空闲时段（v1.6.0-beta.18）。

## 守的是什么

上游 `dsh-web-frontend` 的 `dist/assets/index-*.js` 在模块求值后立刻
`setTimeout(()=>{warmup()},0)` 构建 Shiki 高亮器并 tokenize 三段样例。
实测（emulator-5554 / x86_64 / Android 14 / 8 GB AVD，2026-11-19）该预热
占主线程 641~843 ms：createHighlighter 仅 3~21 ms，typescript 那段
tokenize 独占 494~662 ms。timeout=0 使它落进 load 窗口，拖慢首绘。

本仓库用 `FrontendWarmup.kt` 在运行时把这次调用改写成
`requestIdleCallback`（或 1200ms 兜底 setTimeout）。

## 为什么要防「删掉预热」

预热删掉也能让首绘变快，但第一次滚到代码块时要现场编译 500~660 ms 的
typescript grammar —— 只是把卡顿挪到用户眼前。所以这里既禁止「原样保留
立即预热」，也禁止「完全不预热」，必须在两者之间（空闲执行）。

## 可打红条件

1. 删掉 FrontendWarmup.healInstalled 的两个调用点 → 第 2 节红
2. 把 idleBody 改回直接 fn()（去掉 requestIdleCallback）→ 第 3 节红
3. 把空闲超时去掉只剩 requestIdleCallback（无兜底）→ 第 4 节红
4. 去掉已打补丁的幂等判断 → 第 5 节红
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine',
                  'FrontendWarmup.kt')
INSTALLER = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile',
                         'engine', 'RuntimeInstaller.kt')

ok = 0
bad = 0


def check(name, cond, detail=''):
    global ok, bad
    if cond:
        ok += 1
        print('  ok   ' + name)
    else:
        bad += 1
        print('  FAIL ' + name + (('  <- ' + detail) if detail else ''))


def read(path):
    return io.open(path, encoding='utf-8', errors='replace').read()


def main():
    for p in (KT, INSTALLER):
        if not os.path.isfile(p):
            print('missing file: ' + p)
            sys.exit(1)
    kt = read(KT)
    inst = read(INSTALLER)

    print('[1] 补丁模块存在且按结构匹配（函数名会被压缩）')
    check('FrontendWarmup.kt 里有 unref 惯用法正则',
          'unrefForm' in kt and r'.unref)' in kt)
    check('另有裸 setTimeout 形态', 'plainForm' in kt)
    check('不按固定函数名匹配（无硬编码 Ji/Mi/Xi）',
          not re.search(r'{s*(Ji|Mi|Xi)s*(s*)s*}', kt))
    check('取函数名用捕获组而非字面量', 'groupValues[3]' in kt and 'groupValues[1]' in kt)

    print('[2] 安装路径确实调用（否则补丁不会生效）')
    calls = inst.count('FrontendWarmup.healInstalled(ctx)')
    check('RuntimeInstaller 至少两处调用（复用 + 新装）', calls >= 2, 'calls=%d' % calls)
    check('复用分支里紧跟 polyfill 之后',
          'WebViewPolyfill.healInstalled(ctx)\n            // 前端语法高亮的启动预热挪到空闲时段' in inst)

    print('[3] 改写目标必须是空闲执行，而不是「就地执行」或「删除」')
    # 只查字面量会假绿：把条件写成 if(false) 照样含 requestIdleCallback。
    # 判据必须钉住「按 typeof 检测能力再决定走空闲」，否则改写等于没生效。
    # 源码里字符串被拆成多个 Kotlin 字面量拼接，先合并再匹配，否则永远匹配不上。
    kt_flat = re.sub(r'"\s*\+\s*\n\s*"', '', kt)
    check('按 typeof 检测能力后才走 requestIdleCallback',
          re.search(r'if\(typeof requestIdleCallback[^)]*\)\s*requestIdleCallback\(__f,',
                    kt_flat) is not None)
    check('替换体含 requestIdleCallback', 'requestIdleCallback' in kt)
    check('预热函数仍被调用（没被删掉）', '$fn()' in kt)
    check('不再以 timeout 0 直接执行原函数',
          'setTimeout(()=>{$fn()},0)' not in kt.replace(' ', ''))

    print('[4] 空闲回调必须有兜底超时（否则永不执行）')
    check('requestIdleCallback 带 timeout 选项',
          re.search(r'requestIdleCallback\(__f,\{\s*timeout\s*:', kt) is not None)
    check('无 requestIdleCallback 时有 setTimeout 兜底',
          re.search(r'else setTimeout\(__f,\s*\d+\)', kt) is not None)

    print('[5] 幂等与安全')
    check('已打补丁的文件跳过（含 MARKER 判断）',
          'source.contains(MARKER)' in kt)
    check('先消费 unref 形再消费裸形（顺序敏感）',
          kt.index('unrefForm.replace(source)') < kt.index('plainForm.replace(out)'))
    check('写盘走临时文件 + ATOMIC_MOVE',
          'ATOMIC_MOVE' in kt and 'dsh-warm.tmp' in kt)
    check('只处理 index-*.js，不碰 vendor 与其他 chunk',
          'startsWith("index-")' in kt and 'endsWith(".js")' in kt)

    print()
    print('passed=%d failed=%d' % (ok, bad))
    if bad:
        sys.exit(1)


if __name__ == '__main__':
    main()
