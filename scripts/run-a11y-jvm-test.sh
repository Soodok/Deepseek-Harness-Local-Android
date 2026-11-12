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

# MemoryStore.kt / MemoryCodec.kt 需要 android.content.Context、android.util.Log 与 org.json
# 的**编译期签名**：宿主机 JVM 没有这些类，所以借 SDK 里的 android.jar 当编译 classpath。
# 它只是 stub（运行时调用会抛 "Stub!"），所以断言只碰纯函数，绝不碰 IO。
# 找不到就**判红**：SKIP 退 0 会变成「静默假绿」—— 门禁显示全绿，而 MemoryStore/MemoryIndex
# 那几十条断言一条都没跑。宁可红，不要假绿。
ANDROID_JAR=""
for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Android/Sdk" /e/Android/Sdk /c/Android/Sdk; do
  [ -n "$d" ] || continue
  [ -d "$d/platforms" ] || continue
  ANDROID_JAR=$(ls -1 "$d"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
  [ -n "$ANDROID_JAR" ] && break
done
if [ -z "$ANDROID_JAR" ]; then
  echo "FAIL: android.jar not found (MemoryStore/MemoryIndex assertions cannot run)"
  echo "      point ANDROID_HOME at an SDK that has platforms/*/android.jar."
  echo "      refusing to report a green run that skipped those assertions."
  exit 1
fi

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
cp "$ROOT/app/src/main/java/app/dsh/mobile/MarkGeometry.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/SwipePath.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/HumanPace.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/FastMode.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/A11yEntry.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/ScrollGeometry.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/NumFmt.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/TraceModel.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/TracePruner.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/TraceRetention.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/MemoryStore.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/MemoryCodec.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/MemoryIndex.kt" "$WORK/src/"
# GuestPaths.kt 纯 java.io.File 逻辑（容器视角 /dsh/... → 宿主路径），无 Android 依赖。
# 它修的是「Linux 模式记下的工作区路径让原生模式会话恢复失败」，错一个字就白屏。
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/GuestPaths.kt" "$WORK/src/"
# FastPicker.kt 里住着 LocalEmbedder（嵌入服务的端口与就绪探测）。MemoryIndex 复用它，
# 端口才不会有两处真相；它只依赖 Log + org.json，能进这套纯 JVM 编译。
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/FastPicker.kt" "$WORK/src/"
# MemoryReplay.kt 复用 SceneMemory.fingerprint（锚点校验与 M4 检索必须对同一份文本判断），
# 而 run() 要碰的 DshAccessibilityService 在宿主 JVM 编不动 —— 由 stub 提供同名签名。
# stub 与真实服务的一致性由 BehaviourTest.assertStubMatchesService() 读源码钉住。
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/SceneMemory.kt" "$WORK/src/"
cp "$ROOT/app/src/main/java/app/dsh/mobile/engine/MemoryReplay.kt" "$WORK/src/"
cp "$ROOT/scripts/jvm/DshAccessibilityServiceStub.kt" "$WORK/src/"
cp "$ROOT/scripts/jvm/BehaviourTest.kt" "$WORK/src/"
# 可选：换成别的入口（scripts/measure-tier.sh 用它跑 TierMeasure，不打断言只打分数表）
EXTRA_SRC="${EXTRA_SRC:-}"
if [ -n "$EXTRA_SRC" ]; then
  cp "$ROOT/$EXTRA_SRC" "$WORK/src/" || { echo "FAILED: cannot copy $EXTRA_SRC"; exit 1; }
fi
EXTRA_NAME=""
if [ -n "$EXTRA_SRC" ]; then EXTRA_NAME="$(basename "$EXTRA_SRC")"; fi

if command -v cygpath >/dev/null 2>&1; then
  sep=";"; win() { cygpath -w "$1"; }
else
  sep=":"; win() { echo "$1"; }
fi

COMPILER_CP="$(win "$KC")$sep$(win "$STD")$sep$(win "$REF")$sep$(win "$DM")$sep$(win "$TROVE")$sep$(win "$ANN")"

# 额外入口的真实路径（win() 已定义，这里才能算）
# 用数组：路径里有空格（E:Deepeek harness），未加引号的变量会被拆成两个参数
EXTRA_ARGS=()
if [ -n "$EXTRA_NAME" ]; then EXTRA_ARGS=("$(win "$WORK/src/$EXTRA_NAME")"); fi

echo "== compile =="
java -Dfile.encoding=UTF-8 -cp "$COMPILER_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
  -cp "$(win "$STD")$sep$(win "$ANDROID_JAR")" -d "$(win "$WORK/out")" \
  "$(win "$WORK/src/ScreenMask.kt")" "$(win "$WORK/src/ProtectedPages.kt")" "$(win "$WORK/src/ScreenBudget.kt")" "$(win "$WORK/src/ScreenDigest.kt")" "$(win "$WORK/src/Handoff.kt")" "$(win "$WORK/src/PhoneTask.kt")" "$(win "$WORK/src/PhoneAsk.kt")" "$(win "$WORK/src/TaskReport.kt")" "$(win "$WORK/src/OverlayState.kt")" "$(win "$WORK/src/MarkGeometry.kt")" "$(win "$WORK/src/SwipePath.kt")" "$(win "$WORK/src/HumanPace.kt")" "$(win "$WORK/src/FastMode.kt")" "$(win "$WORK/src/A11yEntry.kt")" "$(win "$WORK/src/ScrollGeometry.kt")" "$(win "$WORK/src/NumFmt.kt")" "$(win "$WORK/src/TraceModel.kt")" "$(win "$WORK/src/TracePruner.kt")" "$(win "$WORK/src/TraceRetention.kt")" "$(win "$WORK/src/MemoryStore.kt")" "$(win "$WORK/src/MemoryCodec.kt")" "$(win "$WORK/src/MemoryIndex.kt")" "$(win "$WORK/src/GuestPaths.kt")" "$(win "$WORK/src/FastPicker.kt")" "$(win "$WORK/src/SceneMemory.kt")" "$(win "$WORK/src/MemoryReplay.kt")" "$(win "$WORK/src/DshAccessibilityServiceStub.kt")" "$(win "$WORK/src/BehaviourTest.kt")" "${EXTRA_ARGS[@]}" \
  || { echo "FAILED: compilation"; exit 1; }

echo "== run =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$(win "$WORK/out")$sep$(win "$STD")" "jvmtest.${JVM_MAIN:-BehaviourTest}"