#!/usr/bin/env python3
"""任务化执行（/dev/jobs）的静态断言探针。

为什么要有：这组能力**任何一处漏接都会让功能"看起来有、实际用不了"**：

  · JobsCap 的 6 个方法漏挂一条路由 → 那个操作 404，但其余五个都正常，很容易以为只是"这个功能没做"
  · Route 的 handle 返回 `Pair<Int, String>` → artifact 若返回 File 编译不过；已改成 base64 + sha256
  · 任务体不 catch Throwable → 抛异常后任务永远停在 running，调用方白轮询
  · 终态没落 result.json → 轮询方看不出结束，只能靠超时
  · 路径不规范化 → `..` 能穿出任务目录，等于任意文件读
  · 日志不设上限 → 一条 `yes` 写满磁盘，整个 App 一起坏
  · EngineConfig 三处列表漏一处 → 门脚本不部署，AI 敲 dev-jobs 得到 not found
  · EndpointCatalog 没登记 → `dev` 目录里发现不了这 6 个端点

断言分七段：
  A 门禁       每个方法第一行必须检查 cap.linux（漏一个等于绕过开关）
  B 路由一致性 6 条路由都挂在 AgentBridge，且都指向 JobsCap
  C 目录登记   EndpointCatalog 有「任务化执行」组且 6 条都在
  D 门脚本     文件存在 + 三处 EngineConfig 列表都登记 + 有自举头
  E 并发与配额 WORKERS=2 / MAX_KEPT=50 / MAX_LOG_BYTES / MAX_ARTIFACT_BYTES
  F 终态完整性 所有早退/异常路径都调 finish（否则任务永远 running）
  G 安全       路径规范化比对 + base64 上限 + 取消不假装成功

反向验证（改坏必须变红，见 _rv_linux_jobs.py）：
  1. 删 submit 的 cap 检查                → A1 红
  2. 删 /dev/jobs/log 路由                → B3 红
  3. 删 EndpointCatalog 的 jobs/log 条目   → C3 红
  4. 删 EngineConfig 一处 dev-jobs         → D2 红
  5. WORKERS 改 8                          → E1 红
  6. runJob 的 catch 去掉                  → F1 红
  7. 路径比对改成不规范化                   → G2 红
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
JOBSCAP = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine', 'cap', 'JobsCap.kt')
BRIDGE = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine', 'AgentBridge.kt')
CATALOG = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine', 'EndpointCatalog.kt')
ENGINECFG = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'dsh', 'mobile', 'engine', 'EngineConfig.kt')
GATE = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'gates', 'dev-jobs')

checks = 0
fails = 0


def ok(cond, label, detail=''):
    global checks, fails
    checks += 1
    if cond:
        print(f'  \u2713 {label}')
    else:
        fails += 1
        print(f'  \u2717 {label}' + (f'  \u2014 {detail}' if detail else ''))


def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()


def strip_comments(src):
    """剥掉 Kotlin 的行注释与块注释（支持嵌套）。

    必须剥：JobsCap 的注释里大量引用了方法名与常量名，不剥会让
    「某方法是否存在」这类断言恒真（这个项目已经吃过十几次假绿）。
    """
    out = []
    i = 0
    n = len(src)
    depth = 0
    in_line = False
    in_str = False
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if in_line:
            if c == '\n':
                in_line = False
                out.append(c)
            i += 1
            continue
        if depth > 0:
            if two == '/*':
                depth += 1
                i += 2
                continue
            if two == '*/':
                depth -= 1
                i += 2
                continue
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == '\\':
                out.append(src[i + 1] if i + 1 < n else '')
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if two == '//':
            in_line = True
            i += 2
            continue
        if two == '/*':
            depth = 1
            i += 2
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def main():
    if not os.path.isfile(JOBSCAP):
        print(f'FATAL: {JOBSCAP} 不存在')
        return 2

    cap_raw = read(JOBSCAP)
    cap = strip_comments(cap_raw)
    bridge = strip_comments(read(BRIDGE))
    catalog = strip_comments(read(CATALOG))
    cfg = strip_comments(read(ENGINECFG))

    print('\n=== A 门禁：每个方法第一行都要查 cap.linux ===')
    # 六个公开方法：submit / list / get / log / cancel / artifact
    for fn in ('submit', 'list', 'get', 'log', 'cancel', 'artifact'):
        m = re.search(r'\n    fun ' + fn + r'\([^)]*\)\s*:\s*Pair<Int,\s*String>\s*\{', cap)
        if not m:
            ok(False, f'A 找到 fun {fn}', '函数签名没匹配上')
            continue
        body_start = m.end()
        body = cap[body_start:body_start + 400]
        ok('CapabilityGate.can(ctx, CapabilityGate.LINUX)' in body,
           f'A {fn} 第一行检查 cap.linux',
           '前 400 字符里没有 CapabilityGate.can')

    print('\n=== B 路由一致性：6 条都挂在 AgentBridge 且指向 JobsCap ===')
    for path, method in (
        ('/dev/jobs', 'POST'),
        ('/dev/jobs', 'GET'),
        ('/dev/jobs/get', 'GET'),
        ('/dev/jobs/log', 'GET'),
        ('/dev/jobs/cancel', 'POST'),
        ('/dev/jobs/artifact', 'GET'),
    ):
        pat = '"' + method + ' ' + path + '" to Route("' + method + '")'
        ok(pat in bridge, f'B {method} {path} 已挂载', '路由表里找不到')

    # 六条都必须真的调到 JobsCap（而不是别的实现）
    ok(bridge.count('cap.JobsCap.') == 6, 'B 六条路由都指向 JobsCap',
       f'实际 {bridge.count("cap.JobsCap.")} 处')

    print('\n=== C 目录登记：EndpointCatalog 有「任务化执行」组 ===')
    ok('任务化执行' in catalog, 'C 有「任务化执行」分组')
    for eid in ('jobs/submit', 'jobs/list', 'jobs/get', 'jobs/log', 'jobs/cancel', 'jobs/artifact'):
        ok(f'"{eid}"' in catalog, f'C 登记了 {eid}')
    # cap 字段必须写 linux，否则 dev help 里能力归属错。
    # 取组区间而不是截前 N 字符 —— 截断会把后几条 cap 切掉，得到「6 条只标了 5 个」
    # 的假红（我第一版就是这么错的）。
    i = catalog.index('"任务化执行"')
    j = catalog.index('Triple(', i + len('"任务化执行"'))
    seg = catalog[i:j]
    ok(seg.count('cap = "linux"') == 6, 'C 6 条都标了 cap = "linux"',
       f'实际 {seg.count("cap = " + chr(34) + "linux" + chr(34))} 个')
    ok(seg.count('"jobs/') == 6, 'C 组内恰好 6 条 jobs 条目',
       f'实际 {seg.count(chr(34) + "jobs/")} 条')

    print('\n=== D 门脚本 ===')
    ok(os.path.isfile(GATE), 'D dev-jobs 文件存在')
    if os.path.isfile(GATE):
        g = read(GATE)
        ok(g.startswith('#!/system/bin/sh'), 'D1 shebang 是 /system/bin/sh', g.split('\n')[0])
        ok('_DSH_REEXEC' in g, 'D2 有自举到 bash 的头')
        ok('. "$DIR/_dsh_http.sh"' in g, 'D3 source 了 _dsh_http.sh')
        for sub in ('submit', 'log', 'cancel'):
            ok(f'\n  {sub})' in g, f'D4 有 {sub} 子命令')
    cnt = cfg.count('"dev-jobs"')
    ok(cnt == 3, 'D5 EngineConfig 三处列表都登记了 dev-jobs', f'实际 {cnt} 处')

    print('\n=== E 并发与配额 ===')
    ok('const val WORKERS = 2' in cap, 'E1 WORKERS = 2', '并发数变了要先想 phantom killer')
    ok('const val MAX_KEPT = 50' in cap, 'E2 MAX_KEPT = 50')
    ok('MAX_LOG_BYTES = 8L * 1024 * 1024' in cap, 'E3 单流日志上限 8 MiB')
    ok('MAX_ARTIFACT_BYTES = 8L * 1024 * 1024' in cap, 'E4 产物上限 8 MiB（base64 膨胀 33%）')
    ok('JOB_TIMEOUT_MAX_MS = 1_200_000L' in cap, 'E5 硬超时上限 20 分钟')
    ok('Executors.newFixedThreadPool(WORKERS)' in cap, 'E6 用固定池不是每任务一线程')

    print('\n=== F 终态完整性（漏一条就是"永远 running"）===')
    ok('} catch (t: Throwable) {' in cap, 'F1 任务体 catch Throwable')
    ok('finish(dir, -1, 0L, "error"' in cap, 'F2 异常路径也落终态')
    ok('cancelled before start' in cap, 'F3 启动前已取消也落终态')
    ok('File(dir, "result.json").writeText' in cap, 'F4 终态写 result.json')
    ok('"finished_at"' in cap, 'F5 meta 记录 finished_at')
    # 早退路径：容器未部署时不能建目录就跑
    sub_body = cap[cap.index('fun submit('):cap.index('private fun runJob(')]
    ok(sub_body.index('isDeployed') < sub_body.index('newId()'),
       'F6 未部署要在建任务目录之前就拒', '否则留下僵尸目录')

    print('\n=== G 安全 ===')
    ok('dir.canonicalFile' in cap, 'G1 任务目录本身规范化')
    # ★ 必须断言 **target 那一行** 带 canonicalFile。只查「全文有 canonicalFile」
    #   会假绿：`val base = dir.canonicalFile` 那行还在，改坏 target 也不红
    #   （这条是变异反验抓出来的，不是我想出来的）。
    ok(re.search(r'val target = File\(base, rel\)\.canonicalFile', cap) is not None,
       'G2 用户给的相对路径也要规范化', 'target 少了 canonicalFile 就能用 .. 穿出去')
    ok('startsWith(base.path + File.separator)' in cap, 'G3 规范化后比对目录前缀')
    ok('path escapes the job directory' in cap, 'G4 穿出目录明确报错')
    ok('content_base64' in cap and 'sha256' in cap, 'G5 产物带 base64 + sha256')
    ok('job already finished' in cap, 'G6 取消已终态任务返 409 不假装成功')
    ok('cooperative' in cap, 'G7 取消语义写明是协作式')
    # 裁剪不能删还在跑的
    ok('terminal' in cap and 'success", "failed", "timeout", "cancelled", "error"' in cap.replace("'", '"'),
       'G8 prune 只删已终态任务')
    ok('MAX_KEPT' in cap and 'deleteRecursively' in cap, 'G9 超出配额删最老的')

    print(f'\nRESULT: {checks} checks, {fails} failed')
    return 0 if fails == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
