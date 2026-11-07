#!/usr/bin/env bash
# 一键回归：JVM 单测 → 打包 → 约定检查 → lint → PC AI 探针。
# 用法：bash tools/verify_all.sh [模型名]
#   模型默认 deepseek；GLM 传 ccs-claude-openai--glm-5.3-flash
# 退出码：0 全通过；非 0 表示有环节失败（日志里会指出是哪一步）。
set -uo pipefail
cd "$(dirname "$0")/.."

MODEL="${1:-ccs-claude-openai--deepseek-v4.1-flash[1m]}"
FAILED=()

echo "== 1/5 单元测试"
if ./gradlew :app:testDebugUnitTest --console=plain -q; then
  echo "   单元测试 OK"
else
  FAILED+=("unit tests")
fi

echo "== 2/5 打包（assembleDebug）"
if ./gradlew :app:assembleDebug --console=plain -q; then
  echo "   打包 OK"
else
  FAILED+=("assemble")
fi

echo "== 3/5 约定检查（prefs 读改写必须包在 editJson）"
if python tools/check_prefs_rmw.py; then
  :
else
  FAILED+=("prefs convention")
fi

echo "== 4/5 lint"
if ./gradlew :app:lintDebug --console=plain -q; then
  # gradle 任务成功不代表 0 error：以报告为准
  SUMMARY=$(tail -1 app/build/reports/lint-results-debug.txt 2>/dev/null || true)
  echo "   lint: ${SUMMARY:-无报告}"
  case "$SUMMARY" in
    0\ errors*) ;;
    *) FAILED+=("lint") ;;
  esac
else
  FAILED+=("lint")
fi

echo "== 5/5 AI 探针（模型：$MODEL）"
if python tools/ai_probe.py --model "$MODEL" --report "F:/cache/temp/probe-verify.md"; then
  echo "   探针 OK"
else
  FAILED+=("probe")
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "== 全部通过 =="
  exit 0
fi
echo "== 失败环节：${FAILED[*]} =="
exit 1
