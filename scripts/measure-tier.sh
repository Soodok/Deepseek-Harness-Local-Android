#!/usr/bin/env bash
# 分档打分实测运行器（不是门禁，是量尺）
#
# 与 run-a11y-jvm-test.sh 共用同一套纯 Kotlin 源与同一个 Kotlin 编译器，
# 只是把入口换成 jvmtest.TierMeasure：它不打断言，只打印分数表。
#
# 用法：bash scripts/measure-tier.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JVM_MAIN=TierMeasure EXTRA_SRC=scripts/jvm/TierMeasure.kt bash "$ROOT/scripts/run-a11y-jvm-test.sh"
