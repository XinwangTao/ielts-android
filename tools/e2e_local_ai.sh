#!/bin/bash
# 本地 4B AI 无点击自测：一条命令等结果，不用在手机上点按钮。
# 用法: bash tools/e2e_local_ai.sh <adb设备> [outline|sample|vocab]
# 例子: bash tools/e2e_local_ai.sh adb-afa99023-q6q7Cb._adb-tls-connect._tcp outline
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}
DEVICE=${1:?用法: $0 <adb设备> [outline|sample|vocab]}
MODE=${2:-outline}

"$ADB" -s "$DEVICE" logcat -c
"$ADB" -s "$DEVICE" shell "am force-stop com.txw.ielts; am start -n com.txw.ielts/.MainActivity --es e2e_ai $MODE"

for _ in $(seq 1 120); do
  LINE=$("$ADB" -s "$DEVICE" logcat -d -s E2E:I 2>/dev/null | grep "E2E: RESULT ai" | tail -1 || true)
  if [ -n "$LINE" ]; then
    echo "$LINE"
    exit 0
  fi
  sleep 2
done

echo "!! 本地 AI 自测超时（约 4 分钟）：确认模型已投递、屏幕亮着、App 在前台"
exit 1
