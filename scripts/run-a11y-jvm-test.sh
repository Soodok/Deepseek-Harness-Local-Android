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

# 怎样找到 Kotlin 编译器那几个 jar（v1.7.14 → v1.7.15，两次都被 CI 教做人）
#
# ① 别把版本号写进 glob。kotlinCompilerClasspath 里的 kotlin-reflect 是 1.6.10、
#    annotations 是 13.0，与项目 Kotlin 1.9.24 无关；本机缓存里恰好还躺着
#    1.9.24 / 23.0.0（别处引入的），所以本地绿、CI 红。
# ② 更要紧的是：`gradle :app:dependencies` 只解析元数据、**不下载 artifact**。
#    干净 runner 上缓存里只有 pom，按目录猜路径一个 jar 也找不到 → 全脚本 SKIP。
#
# 所以首选路径是让 Gradle 自己给出绝对路径（v1.7.15）：
#   gradle -p . -q :app:printKotlinCompilerClasspath > /tmp/kcp.txt
#   KOTLIN_CP_FILE=/tmp/kcp.txt bash scripts/run-a11y-jvm-test.sh
# `configuration.files` 会强制下载 artifact，这是唯一不依赖缓存布局的做法。
# 没给 KOTLIN_CP_FILE 时退回按缓存目录猜（本机日常够用）。
#
# 从清单里按构件名挑 jar；清单里本来就只有 kotlinCompilerClasspath 解析出的
# 那几个，同名多条时取版本号最大的（sort -V）。
# 路径分隔符要两种都认：Linux runner 上 Gradle 打印 /，Windows 本机打印 \。
# 曾经只匹配 `/${name}-`，于是本机一条都挑不中、静默回落到按缓存目录猜
# （v1.7.15 实测：带 KOTLIN_CP_FILE 仍 FAILED: compilation，就是这个原因）。
from_list() {  # from_list <artifact-name>
  [ -n "${KOTLIN_CP_FILE:-}" ] || return 0
  [ -r "$KOTLIN_CP_FILE" ] || return 0
  local p base
  # 逐行读时统一把 CRLF 去掉（tr，不用 ${p%\r} —— 模式里的 \r 是"字面 r"，
  # 会把每个 jar 名末尾的 r 啃掉变成 .ja，导致一条都匹配不上，见 v1.7.15 踩坑）。
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    base="${p##*/}"          # 去掉 / 之前的部分
    base="${base##*\\}"     # 再去掉 \ 之前的部分
    case "$base" in
      "$1"-*.jar) printf '%s\n' "$p" ;;
    esac
  done < <(tr -d '\r' < "$KOTLIN_CP_FILE") | sort -V | tail -1
}

pick() {  # pick <group-path> <artifact-name> [prefer-version]
  local dir="$CACHE/$1/$2" pref hit
  [ -d "$dir" ] || return 0
  if [ -n "${3:-}" ]; then
    pref=$(find "$dir" -name "$2-$3.jar" 2>/dev/null | head -1)
    [ -n "$pref" ] && { printf '%s\n' "$pref"; return 0; }
  fi
  hit=$(find "$dir" -name "$2-*.jar" 2>/dev/null | sort -V | tail -1)
  printf '%s\n' "$hit"
}

resolve() {  # resolve <group-path> <artifact-name> [prefer-version]
  local hit
  hit=$(from_list "$2")
  [ -n "$hit" ] && { printf '%s\n' "$hit"; return 0; }
  pick "$1" "$2" "${3:-}"
}

KOTLIN_VER="1.9.24"
STD=$(resolve org.jetbrains.kotlin kotlin-stdlib "$KOTLIN_VER")
# reflect 与 annotations 都只是编译器自己的**运行时**依赖，版本与项目无关：
# 实测把 1.6.10 与 13.0 喂给 K2JVMCompiler 能正常编译产出 class（2026-11-13 验证）。
REF=$(resolve org.jetbrains.kotlin kotlin-reflect "$KOTLIN_VER")
KC=$(resolve org.jetbrains.kotlin kotlin-compiler-embeddable "$KOTLIN_VER")
DM=$(resolve org.jetbrains.kotlin kotlin-daemon-embeddable "$KOTLIN_VER")
TROVE=$(resolve org.jetbrains.intellij.deps trove4j)
ANN=$(resolve org.jetbrains annotations)

for pair in "kotlin-stdlib:$STD" "kotlin-reflect:$REF" "kotlin-compiler-embeddable:$KC" \
            "kotlin-daemon-embeddable:$DM" "trove4j:$TROVE" "annotations:$ANN"; do
  name="${pair%%:*}"; path="${pair#*:}"
  if [ -z "$path" ]; then
    echo "SKIP  $name not found in the Gradle cache ($CACHE)"
    echo "      this test needs a JDK plus a local Kotlin compiler; skip is acceptable locally."
    echo "      in CI, pass KOTLIN_CP_FILE (see :app:printKotlinCompilerClasspath) so jars are pinned."
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
# 灵动岛配色（v1.7.11）：纯 ARGB 常量 + 对比度计算，宿主 JVM 可直接断言
cp "$ROOT/app/src/main/java/app/dsh/mobile/IslandChrome.kt" "$WORK/src/"
# 对比度/前景色工具（v1.7.11）：纯 Int 运算，IslandPalette 与 Accent 都转发到它
cp "$ROOT/app/src/main/java/app/dsh/mobile/ContrastColor.kt" "$WORK/src/"
# 方案 E 工作台的纯归约器：不依赖 Android，三态/故障/待重启都在这里判。
cp "$ROOT/app/src/main/java/app/dsh/mobile/LinuxWorkbenchUiState.kt" "$WORK/src/"
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
  "$(win "$WORK/src/ScreenMask.kt")" "$(win "$WORK/src/ProtectedPages.kt")" "$(win "$WORK/src/ScreenBudget.kt")" "$(win "$WORK/src/ScreenDigest.kt")" "$(win "$WORK/src/Handoff.kt")" "$(win "$WORK/src/PhoneTask.kt")" "$(win "$WORK/src/PhoneAsk.kt")" "$(win "$WORK/src/TaskReport.kt")" "$(win "$WORK/src/OverlayState.kt")" "$(win "$WORK/src/MarkGeometry.kt")" "$(win "$WORK/src/SwipePath.kt")" "$(win "$WORK/src/HumanPace.kt")" "$(win "$WORK/src/IslandChrome.kt")" "$(win "$WORK/src/ContrastColor.kt")" "$(win "$WORK/src/LinuxWorkbenchUiState.kt")" "$(win "$WORK/src/FastMode.kt")" "$(win "$WORK/src/A11yEntry.kt")" "$(win "$WORK/src/ScrollGeometry.kt")" "$(win "$WORK/src/NumFmt.kt")" "$(win "$WORK/src/TraceModel.kt")" "$(win "$WORK/src/TracePruner.kt")" "$(win "$WORK/src/TraceRetention.kt")" "$(win "$WORK/src/MemoryStore.kt")" "$(win "$WORK/src/MemoryCodec.kt")" "$(win "$WORK/src/MemoryIndex.kt")" "$(win "$WORK/src/GuestPaths.kt")" "$(win "$WORK/src/FastPicker.kt")" "$(win "$WORK/src/SceneMemory.kt")" "$(win "$WORK/src/MemoryReplay.kt")" "$(win "$WORK/src/DshAccessibilityServiceStub.kt")" "$(win "$WORK/src/BehaviourTest.kt")" "${EXTRA_ARGS[@]}" \
  || { echo "FAILED: compilation"; exit 1; }

echo "== run =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$(win "$WORK/out")$sep$(win "$STD")" "jvmtest.${JVM_MAIN:-BehaviourTest}"