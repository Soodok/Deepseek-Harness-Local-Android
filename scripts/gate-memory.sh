#!/usr/bin/env bash
# 无障碍记忆系统门禁（M0–M9 通用）
#
# 为什么要单独一个脚本：每完成 2–3 个功能就要跑一遍「JVM 断言 + 探针 + 隔离构建」，
# 手敲三条命令既慢又容易漏。这个脚本把口径固化下来 —— 红项与基线（docs/A11Y_MEMORY_PLAN.md §9）
# 相比只能更少，不能更多。
#
# 用法：
#   bash scripts/gate-memory.sh static   # JVM 断言 + py 探针（快，~2 分钟）
#   bash scripts/gate-memory.sh build    # 上面 + 隔离工作树构建 APK（~3 分钟）
#   bash scripts/gate-memory.sh all      # 上面 + 安装到 emulator-5556 并起引擎（~6 分钟）
#   bash scripts/gate-memory.sh e2e      # 真机端到端回归（scripts/e2e-memory.py，需先 all 装好包）
#   bash scripts/gate-memory.sh release  # all + e2e（发版前一条命令跑完）
#
# 基线（2026-11-11，commit 6d2e4e19）：
#   JVM passed=1897 failed=0 ／ py 探针 113 PASS 0 FAIL ／ i18n 1 红（他人 WIP）
set -uo pipefail

MODE="${1:-static}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${GATE_OUT:-/e/tmp/gate-memory.log}"
ADB="${ADB:-/e/Android/Sdk/platform-tools/adb.exe}"
SERIAL="${SERIAL:-emulator-5556}"
GRADLE="${GRADLE:-/c/Users/Qt/.gradle/wrapper/dists/gradle-9.2.1-bin/2t0n5ozlw9xmuyvbp7dnzaxug/gradle-9.2.1/bin/gradle.bat}"

export PYTHONIOENCODING=utf-8   # 不设这个，探针里的 ✓/⚠ 会抛 UnicodeEncodeError，看起来像 17 条逻辑失败
: > "$OUT"
say() { echo "$@" | tee -a "$OUT"; }

cd "$ROOT" || exit 1
FAILED=0

# ---------- 1. JVM 行为断言 ----------
say "== [1/3] JVM 断言 =="
JVM_OUT="$(bash scripts/run-a11y-jvm-test.sh 2>&1)"
echo "$JVM_OUT" > /e/tmp/gate-jvm.log
JVM_LINE="$(echo "$JVM_OUT" | grep -E "^passed=" | tail -1)"
say "  $JVM_LINE"
if ! echo "$JVM_OUT" | grep -q "All JVM behaviour checks passed"; then
  say "  FAIL: JVM 断言未全绿（详情 /e/tmp/gate-jvm.log）"
  FAILED=1
fi

# ---------- 2. py 探针 ----------
say "== [2/3] py 探针 =="
BASELINE="docs/reports/GATE_BASELINE.txt"
KNOWN="$(grep -E '^probe:' "$BASELINE" 2>/dev/null | cut -f1 | sed 's/^probe://')"
P=0; F=0; FAIL_LIST=""
# Windows 控制台默认 GBK：探针里有 µ 之类的字符，不强制 UTF-8 会抛 UnicodeEncodeError
# 被误判成「探针失败」（v1.7.9 踩过：_test_linux_chroot_sandbox）。
for f in scripts/probes/_test_*.py; do
  if PYTHONIOENCODING=utf-8 python "$f" >/e/tmp/gate-probe.out 2>&1; then P=$((P+1)); else F=$((F+1)); FAIL_LIST="$FAIL_LIST $(basename "$f")"; fi
done
say "  py probes: PASS=$P FAIL=$F"
NEW_RED=""
for f in $FAIL_LIST; do
  if echo "$KNOWN" | grep -qx "$f"; then
    say "  known-red (非本计划): $f"
  else
    NEW_RED="$NEW_RED $f"
  fi
done
if [ -n "$NEW_RED" ]; then
  say "  FAIL(新增红项):$NEW_RED"
  FAILED=1
fi

say "== [2b] i18n =="
I18N_ERR="$(python scripts/check-i18n.py 2>&1 | grep -c '::error::' || true)"
I18N_MAX="$(grep -E '^i18n:count=' "$BASELINE" 2>/dev/null | head -1 | sed 's/^i18n:count=//' | cut -f1)"
I18N_MAX="${I18N_MAX:-0}"
say "  i18n errors: $I18N_ERR (基线上限 $I18N_MAX)"
if [ "$I18N_ERR" -gt "$I18N_MAX" ]; then say "  FAIL: i18n 新增红项"; FAILED=1; fi

# ---------- 3. 隔离构建 ----------
if [ "$MODE" = "build" ] || [ "$MODE" = "all" ] || [ "$MODE" = "release" ]; then
  say "== [3/3] 隔离构建 =="
  WT=/e/tmp/wt-gate
  # Windows 上上次构建的 gradle daemon 可能还握着文件，rm 会失败；先摘注册再删，
  # 否则下一次 `git worktree add` 会报「already registered」。
  git worktree remove --force "$WT" >/dev/null 2>&1 || true
  git worktree prune
  rm -rf "$WT" 2>/dev/null || true
  [ -e "$WT" ] && { say "  FAIL: 旧 worktree 删不掉（gradle 还握着文件）"; exit 1; }
  git worktree add "$WT" HEAD >/dev/null 2>&1 || { say "  FAIL: worktree 创建失败"; exit 1; }
  # 把工作区里未提交的改动（含新增文件）带进去 —— 只带 app/ 与 scripts/ 下的，绝不带别人的无关文件
  # 工作区里**被删除**的文件也要同步（别人 WIP 删资源时，只 cp 会直接报错、构建段假死）
  git ls-files -m -o --exclude-standard | grep -E '^(app/src|scripts/)' | while read -r f; do
    if [ -f "$f" ]; then
      mkdir -p "$WT/$(dirname "$f")"
      cp "$f" "$WT/$f"
    else
      rm -f "$WT/$f"
    fi
  done
  # 临时绕过他人 sshd 依赖带来的 META-INF/DEPENDENCIES 冲突（**不提交**）
  python - "$WT/app/build.gradle.kts" <<'PY'
import io, sys
p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()
if 'META-INF/DEPENDENCIES' not in s:
    s = s.replace('    packaging {\n        jniLibs {',
                  '    packaging {\n        resources.excludes += "META-INF/DEPENDENCIES"\n        jniLibs {', 1)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    print('  patched packaging excludes')
PY
  ( cd "$WT" && "$GRADLE" -p . -Pabi=x86_64 -Ppackage=noruntime :app:assembleDebug ) > /e/tmp/gate-build.log 2>&1
  if grep -q "BUILD SUCCESSFUL" /e/tmp/gate-build.log; then
    APK="$WT/app/build/outputs/apk/debug/app-debug.apk"
    say "  BUILD SUCCESSFUL  $(stat -c %s "$APK" 2>/dev/null || echo '?') B  -> $APK"
    echo "$APK" > /e/tmp/gate-apk.txt
  else
    say "  FAIL: 构建失败（tail /e/tmp/gate-build.log）"
    tail -12 /e/tmp/gate-build.log | sed 's/^/    /' | tee -a "$OUT"
    FAILED=1
  fi
fi

# ---------- 4. 设备 ----------
if [ "$MODE" = "all" ] || [ "$MODE" = "release" ]; then
  say "== [4/5] 设备安装 =="
  APK="$(cat /e/tmp/gate-apk.txt 2>/dev/null)"
  if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
    say "  SKIP: 没有 APK（先跑 build）"
  else
    # 不能用 install -r ... | tail -1：管道吞掉退出码，装失败（签名不符、
    #   版本降级、空间不足）也照样打印一行字然后全绿 —— 装旧包的下场是最难查的
    #   那种假通过。改成先收输出与退出码，再按 Success 判据双验。
    INSTALL_OUT="$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1)"
    INSTALL_RC=$?
    echo "$INSTALL_OUT" | tail -2 | sed "s/^/  /" | tee -a "$OUT"
    if [ "$INSTALL_RC" -ne 0 ] || ! echo "$INSTALL_OUT" | grep -q "Success"; then
      say "  FAIL: 安装失败（rc=$INSTALL_RC）"
      FAILED=1
    fi
    # 无障碍必须**在 App 停着的时候**重新绑定：直接 put 同值不会重绑，
    # 先 force-stop 再 '' → 真值，然后才启动（实测顺序反了会「服务未启用」）。
    "$ADB" -s "$SERIAL" shell am force-stop app.dsh.mobile >/dev/null 2>&1
    sleep 1
    "$ADB" -s "$SERIAL" shell "settings put secure enabled_accessibility_services ''" >/dev/null 2>&1
    sleep 1
    "$ADB" -s "$SERIAL" shell settings put secure enabled_accessibility_services \
      app.dsh.mobile/app.dsh.mobile.DshAccessibilityService >/dev/null 2>&1
    sleep 1
    "$ADB" -s "$SERIAL" shell am start -n app.dsh.mobile/.MainActivity >/dev/null 2>&1
    sleep 9
    # Android 11 + Chrome 83 的 WebView 门：冷启动要点一次「继续」
    "$ADB" -s "$SERIAL" shell input tap 653 1505 >/dev/null 2>&1
    sleep 10
    BOUND="$("$ADB" -s "$SERIAL" shell dumpsys accessibility | grep -c 'Bound services:{Service')"
    say "  a11y bound=$BOUND"
    if [ "$BOUND" -lt 1 ]; then
      say "  FAIL: 无障碍服务没绑上"
      FAILED=1
    fi

    PID="$("$ADB" -s "$SERIAL" shell "ps -A -o PID,NAME | grep -w node" | tr -d '\r' | head -1 | awk '{print $1}')"
    if [ -n "${PID:-}" ]; then
      say "  engine pid=$PID"
      "$ADB" -s "$SERIAL" forward tcp:13086 tcp:3083 >/dev/null 2>&1
      TOK="$("$ADB" -s "$SERIAL" shell "run-as app.dsh.mobile cat /proc/$PID/environ" \
            | tr '\0' '\n' | grep DSH_BRIDGE_TOKEN | tr -d '\r' | cut -d= -f2)"
      # 不落盘：token 是桥的完整凭据，写到 /e/tmp 等于给本机任何进程留一份明文，
      # 而且没有任何脚本消费它（只需在本次运行内传给 curl）。需要复现时从
      # 上面的「device token length」+ 自己沿同一条命令重取。
      say "  token len=${#TOK}"
      say "  ping: $(curl -s -m 10 -H "X-DSH-Token: $TOK" http://127.0.0.1:13086/dev/ping | head -c 80)"
    else
      say "  FAIL: 引擎没起来"
      FAILED=1
    fi
  fi
fi


# ---------- 5. 真机 E2E ----------
if [ "$MODE" = "e2e" ] || [ "$MODE" = "release" ]; then
  say "== [5/5] 真机 E2E =="
  E2E_OUT="$(cd "$ROOT" && PYTHONIOENCODING=utf-8 ADB="$ADB" SERIAL="$SERIAL" python scripts/e2e-memory.py 2>&1)"
  echo "$E2E_OUT" > /e/tmp/gate-e2e.log
  echo "$E2E_OUT" | grep -E "^(\[[0-9]+\]|  (PASS|FAIL|SKIP)|====)" | sed 's/^/  /' | tee -a "$OUT"
  E2E_FAIL="$(echo "$E2E_OUT" | grep -c '^  FAIL ' || true)"
  if [ "${E2E_FAIL:-0}" -gt 0 ]; then
    say "  FAIL: E2E 有 $E2E_FAIL 条失败（详情 /e/tmp/gate-e2e.log）"
    FAILED=1
  fi
fi

say ""
if [ "$FAILED" -eq 0 ]; then
  say "GATE PASS ($MODE)"
else
  say "GATE FAIL ($MODE)"
fi
exit "$FAILED"
