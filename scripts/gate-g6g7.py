#!/usr/bin/env python3
"""G6/G7 门禁场景：修正扩展 + 自动学习转正。

用法：python scripts/gate-g6g7.py [--keep]

设计要点（踩过的坑）：
- force-stop 会杀掉无障碍服务，必须 settings delete + put 重新绑定；
- 应用重启后桥 token 会变，每次重启都要重新从 /proc/<pid>/environ 取；
- POST /task/stop 之后动作会被「用户叫停」闸门拒绝，下一轮开始前要放行。
"""
import json, subprocess, sys, time

ADB = 'E:/Android/Sdk/platform-tools/adb.exe'
DEV = 'emulator-5556'
BASE = 'http://127.0.0.1:13086'
TOKEN = ''


def sh(*args):
    return subprocess.run([ADB, '-s', DEV, 'shell'] + list(args),
                          capture_output=True, text=True, encoding='utf-8', errors='replace').stdout


def refresh_token():
    global TOKEN
    lines = sh('ps', '-A', '-o', 'PID,NAME').split('\n')
    pids = [l.split()[0] for l in lines if l.strip().endswith(' node')]
    if not pids:
        return ''
    env = sh('run-as', 'app.dsh.mobile', 'cat', '/proc/%s/environ' % pids[0])
    for kv in env.split('\0'):
        if kv.startswith('DSH_BRIDGE_TOKEN='):
            TOKEN = kv.split('=', 1)[1].strip()
            return TOKEN
    return ''


def call(method, path, body=None, timeout=40):
    cmd = ['curl', '-s', '-m', str(timeout), '-X', method,
           '-H', 'X-DSH-Token: ' + TOKEN, '-H', 'Content-Type: application/json']
    if body is not None:
        cmd += ['-d', json.dumps(body, ensure_ascii=False)]
    cmd.append(BASE + path)
    out = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8').stdout
    try:
        return json.loads(out)
    except Exception:
        return {'_raw': out}


def bind_a11y():
    sh('settings', 'delete', 'secure', 'enabled_accessibility_services')
    time.sleep(1)
    sh('settings', 'put', 'secure', 'enabled_accessibility_services',
       'app.dsh.mobile/app.dsh.mobile.DshAccessibilityService')
    time.sleep(4)


def home_screen():
    subprocess.run([ADB, '-s', DEV, 'shell', 'am', 'start', '-n',
                    'app.dsh.mobile/.MainActivity'], capture_output=True)
    time.sleep(3)


def main():
    if not refresh_token():
        print('FAIL: bridge token not found (engine down?)')
        return 1
    bind_a11y()
    home_screen()
    print('bridge token len =', len(TOKEN))
    print('screen ok =', call('GET', '/screen?filter=all').get('ok'))

    # ---------------- G6：修正与扩展 ----------------
    call('POST', '/trace/clear')
    for e in call('GET', '/memory/v2/list').get('entries', []):
        call('POST', '/memory/v2/delete?id=' + e['id'])

    steps = [{'action': 'tap', 'targetText': 'A'}, {'action': 'tap', 'targetText': 'B'},
             {'action': 'tap', 'targetText': 'C'}]
    r = call('POST', '/memory/v2/upsert', {'pkg': 'com.gate.g6', 'intent': 'g6 修正测试',
                                           'title': 'g6', 'sceneFp': ['root', 'pkg:com.gate.g6'],
                                           'steps': steps})
    mid = r.get('id')
    print('G6 seed ->', mid, 'steps', r.get('steps'))

    p = call('POST', '/memory/v2/patch', {'id': mid, 'step': 1, 'targetText': 'B2'})
    print('G6 patch  ->', {k: p.get(k) for k in ('ok', 'steps', 'rev', 'history')})
    g = call('GET', '/memory/v2/get?id=' + mid)
    print('           步骤文本 =', [s['targetText'] for s in g.get('steps', [])])

    e1 = call('POST', '/memory/v2/extend', {'id': mid, 'steps': [{'action': 'tap', 'targetText': 'D'}]})
    print('G6 extend ->', {k: e1.get(k) for k in ('ok', 'steps', 'rev')})

    f = call('POST', '/memory/v2/fork', {'id': mid, 'fromStep': 2, 'intent': 'g6 分叉', 'title': 'fork'})
    print('G6 fork   ->', {k: f.get(k) for k in ('ok', 'id', 'steps', 'hits')})

    rb = call('POST', '/memory/v2/rollback', {'id': mid, 'rev': 1})
    print('G6 rollbk ->', {k: rb.get(k) for k in ('ok', 'steps', 'rev', 'history')})
    g2 = call('GET', '/memory/v2/get?id=' + mid)
    print('           回滚后步骤文本 =', [s['targetText'] for s in g2.get('steps', [])])

    # ---------------- G7：自动学习 + 转正 ----------------
    call('POST', '/trace/clear')
    for e in call('GET', '/memory/v2/list').get('entries', []):
        call('POST', '/memory/v2/delete?id=' + e['id'])
    print('\nG7 起点记忆数 =', call('GET', '/memory/v2/list').get('count'))

    for i in (1, 2, 3):
        home_screen()
        a = call('POST', '/tap', {'text': 'Configurações'})
        time.sleep(2)
        b = call('POST', '/key', {'action': 'back'})
        time.sleep(2)
        stop = call('POST', '/task/stop', {})
        time.sleep(2)
        lst = call('GET', '/memory/v2/list').get('entries', [])
        print(' G7 run%d tap=%s back=%s stop=%s -> %s' % (
            i, a.get('ok'), b.get('ok'), stop.get('ok'),
            [(x['intent'], 'pending=%s' % x['pending'], 'steps=%d' % x['steps']) for x in lst]))
        # 叫停之后必须放行，否则下一轮所有动作都被闸门拒绝。
        # 注意 /task/allow 要求显式 {"allowed":true}，空 body 会 400（踩过）。
        allow = call('POST', '/task/allow', {'allowed': True})
        print('   allow ->', allow.get('ok'), allow.get('allowed'))

    lst = call('GET', '/memory/v2/list').get('entries', [])
    if lst:
        lab = call('POST', '/memory/v2/label', {'id': lst[0]['id'], 'intent': '打开设置页再返回'})
        print(' G7 label ->', lab.get('ok'), lab.get('intent'))
    st = call('GET', '/memory/v2/stats')
    print(' G7 stats -> pending=%s unlabeled=%s traces=%s byPkg=%s' % (
        st.get('pending'), st.get('unlabeled'), st.get('traces'),
        json.dumps(st.get('byPkg'), ensure_ascii=False)[:160]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
