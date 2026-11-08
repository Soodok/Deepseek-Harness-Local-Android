#!/usr/bin/env bash
# JVM 行为测试运行器（v1.6.20）
#
# 与 scripts/probes/ 里那三个源码静态探针不同，这里**真的在 JVM 上执行**
# ScreenMask / ProtectedPages 的代码，断言函数的实际返回值。
#
# 为什么必须要有：静态探针只能证明「常量还在、结构没删」，证明不了
# 「身份证真的被打码了」「设置首页真的没被误拦」。写这条路时它立刻抓到了
# 我自己写错的两条断言数据（6222021234567890 / 1234567890123456 的 Luhn
# 本来就不通过，代码是对的、测试是错的）。
#
# 依赖：JDK 17+ 与 Gradle 缓存里的 kotlin-compiler-embeddable（不联网安装）。
# 用法：bash scripts/run-a11y-jvm-test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"

pick() {  # pick <group-path> <artifact-glob>
  find "$CACHE/$1" -name "$2" 2>/dev/null | head -1
}

STD=$(pick org.jetbrains.kotlin "kotlin-stdlib-1.9.24.jar")
REF=$(pick org.jetbrains.kotlin "kotlin-reflect-1.9.24.jar")
# 回落：缓存里不一定有 1.9.24 的 reflect（stdlib 有、reflect 可能只有 1.9.23）。
# 没有回落的话整套测试会被 SKIP，看起来像「跑过了」实际上一条断言都没执行。
[ -z "$REF" ] && REF=$(find "$CACHE/org.jetbrains.kotlin/kotlin-reflect" -name "kotlin-reflect-1.9.*.jar" 2>/dev/null | sort | tail -1)
KC=$(pick org.jetbrains.kotlin "kotlin-compiler-embeddable-1.9.24.jar")
DM=$(pick org.jetbrains.kotlin "kotlin-daemon-embeddable-1.9.24.jar")
TROVE=$(pick org.jetbrains.intellij.deps "trove4j-*.jar")
ANN=$(pick org.jetbrains/annotations "annotations-23.0.0.jar")

for pair in "kotlin-stdlib:$STD" "kotlin-reflect:$REF" "kotlin-compiler-embeddable:$KC" \
            "kotlin-daemon-embeddable:$DM" "trove4j:$TROVE" "annotations:$ANN"; do
  name="${pair%%:*}"; path="${pair#*:}"
  if [ -z "$path" ]; then
    echo "SKIP  $name not found in the Gradle cache ($CACHE)"
    echo "      this test needs a JDK plus a local Kotlin compiler; skip is acceptable in CI."
    exit 0
  fi
done

WORK="$ROOT/build/a11y-jvm-test"
rm -rf "$WORK"; mkdir -p "$WORK/src" "$WORK/out"

# 只拷纯 Kotlin 的两个源文件（它们不依赖 Android SDK）
cp "$ROOT/app/src/main/java/app/dsh/mobile/ScreenMask.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/ProtectedPages.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/ScreenBudget.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/ScreenDigest.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/Handoff.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/PhoneTask.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/PhoneAsk.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/TaskReport.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/OverlayState.kt" "$WORK/src/"
cp "$ROOT/scripts/jvm/BehaviourTest.kt" "$WORK/src/"

if command -v cygpath >/dev/null 2>&1; then
  sep=";"; win() { cygpath -w "$1"; }
else
  sep=":"; win() { echo "$1"; }
fi

COMPILER_CP="$(win "$KC")$sep$(win "$STD")$sep$(win "$REF")$sep$(win "$DM")$sep$(win "$TROVE")$sep$(win "$ANN")"

echo "== compile =="
java -Dfile.encoding=UTF-8 -cp "$COMPILER_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp "$(win "$STD")" -d "$(win "$WORK/out")" \
  "$(win "$WORK/src/ScreenMask.kt")" "$(win "$WORK/src/ProtectedPages.kt")" "$(win "$WORK/src/ScreenBudget.kt")" "$(win "$WORK/src/ScreenDigest.kt")" "$(win "$WORK/src/Handoff.kt")" "$(win "$WORK/src/PhoneTask.kt")" "$(win "$WORK/src/PhoneAsk.kt")" "$(win "$WORK/src/TaskReport.kt")" "$(win "$WORK/src/OverlayState.kt")" "$(win "$WORK/src/BehaviourTest.kt")" \
  || { echo "FAILED: compilation"; exit 1; }

echo "== run =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$(win "$WORK/out")$sep$(win "$STD")" jvmtest.BehaviourTest