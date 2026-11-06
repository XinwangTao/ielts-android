#!/bin/bash
# 本地 4B AI 无点击自测：一条命令等结果，不用在手机上点按钮。
# 用法: bash tools/e2e_local_ai.sh <adb设备> [outline|sample|vocab|score_writing|score_speaking]
# 例子: bash tools/e2e_local_ai.sh adb-afa99023-q6q7Cb._adb-tls-connect._tcp outline
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}
DEVICE=${1:?用法: $0 <adb设备> [outline|sample|vocab|score_writing|score_speaking]}
MODE=${2:-outline}

"$ADB" -s "$DEVICE" logcat -c
"$ADB" -s "$DEVICE" shell "am force-stop com.txw.ielts; am start -n com.txw.ielts/.MainActivity --es e2e_ai $MODE"

WARNED=0
LAST_FOCUS=""
# 本地 4B 冷加载可达 2-3 分钟，加生成 1-2 分钟，4 分钟不够；放宽到 8 分钟。
for i in $(seq 1 240); do
  LINE=$("$ADB" -s "$DEVICE" logcat -d -s E2E:I 2>/dev/null | grep "E2E: RESULT ai" | tail -1 || true)
  if [ -n "$LINE" ]; then
    echo "$LINE"
    exit 0
  fi
  # 每 6 秒看一次前台：被其它 App/实例顶掉时 ColorOS 会冻结本地推理，先告警。
  if [ $((i % 3)) -eq 0 ]; then
    LAST_FOCUS=$("$ADB" -s "$DEVICE" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null || true)
    case "$LAST_FOCUS" in
      *com.txw.ielts*) ;;
      *)
        if [ "$WARNED" -eq 0 ]; then
          echo "!! App 不在前台：$LAST_FOCUS；本地推理可能被系统冻结" >&2
          WARNED=1
        fi
        ;;
    esac
  fi
  sleep 2
done

PID=$("$ADB" -s "$DEVICE" shell "pidof com.txw.ielts" 2>/dev/null || true)
echo "!! 本地 AI 自测超时（8 分钟）。last focus: ${LAST_FOCUS:-未知} pid: ${PID:-无}"
exit 1
