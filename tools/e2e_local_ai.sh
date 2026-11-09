#!/bin/bash
# 本地 4B AI 无点击自测：一条命令等结果，不用在手机上点按钮。
# 用法: bash tools/e2e_local_ai.sh <adb设备> [outline|sample|vocab|review|followup|variant|newtopic|gen_reading|gen_reading8|gen_listening|gen_listening8|score_writing|score_speaking] [--force]
# 例子: bash tools/e2e_local_ai.sh adb-afa99023-q6q7Cb._adb-tls-connect._tcp outline
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-adb}
DEVICE=${1:?用法: $0 <adb设备> [outline|sample|vocab|review|followup|variant|newtopic|gen_reading|gen_reading8|gen_listening|gen_listening8|score_writing|score_speaking]}
MODE=${2:-outline}
FORCE=${3:-}

# 预检：手机正被别的 App 占用时，启动后大概率被抢回，ColorOS 会冻结本地推理。
# 先快速失败，别让用户/脚本白等 8 分钟；确实要强跑时第三个参数加 --force。
BEFORE=$("$ADB" -s "$DEVICE" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null || true)
# 下拉通知栏/控制中心会顶在最上层，但并不是用户在用别的 App：先收起，别直接判失败。
if printf '%s' "$BEFORE" | grep -q "NotificationShade"; then
  "$ADB" -s "$DEVICE" shell cmd statusbar collapse >/dev/null 2>&1 || true
  sleep 1
  BEFORE=$("$ADB" -s "$DEVICE" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null || true)
fi
if [ "$FORCE" != "--force" ] && [ -n "$BEFORE" ] && ! printf '%s' "$BEFORE" | grep -qE "mCurrentFocus=null|com.txw.ielts|launcher"; then
  echo "!! 当前前台不是桌面/雅思：${BEFORE:-未知}" >&2
  echo "   启动后可能被该 App 抢回，ColorOS 会冻结本地推理。" >&2
  echo "   回桌面后重跑；确实要强跑：bash $0 <设备> $MODE --force" >&2
  exit 3
fi

# 不清空全局日志：并发会话（另一个 Claude 会话也在用这台手机）会互相清缓冲，
# 结果就是拿到对方模式的日志。记下设备时间，只看本轮启动之后的日志。
SINCE=$("$ADB" -s "$DEVICE" shell "date '+%m-%d %H:%M:%S'" 2>/dev/null | tr -d '\r' || true)
# logcat -T 要求带毫秒；不带 .000 会报 "not in time format" 并被当成从头开始。
if [ -n "$SINCE" ]; then SINCE="$SINCE.000"; fi
# force-stop 是异步的：紧接着 am start 时旧 Activity 可能还在，系统会把新 Intent 交给旧实例，
# 而 Compose 读的是 Activity 创建时的 intent —— 于是「上一轮的模式」又跑一遍，还被当成这一轮的结果。
# 必须等进程真的消失再启动。2026-11-09 实测 score_speaking 拿到了 gen_listening 的结果。
"$ADB" -s "$DEVICE" shell "am force-stop com.txw.ielts"
for _ in $(seq 1 30); do
  STILL=$("$ADB" -s "$DEVICE" shell pidof com.txw.ielts 2>/dev/null | tr -d '\r' || true)
  [ -z "$STILL" ] && break
  sleep 0.2
done
"$ADB" -s "$DEVICE" shell "am start -n com.txw.ielts/.MainActivity --es e2e_ai $MODE"

# 取本轮日志；设备 date 不可用时退回全缓冲（仍按模式精确匹配，不会串模式）。
read_logs() {
  if [ -n "$SINCE" ]; then
    "$ADB" -s "$DEVICE" logcat -d -T "$SINCE" -s E2E:I 2>/dev/null || true
  else
    "$ADB" -s "$DEVICE" logcat -d -s E2E:I 2>/dev/null || true
  fi
}

WARNED=0
LAST_FOCUS=""
# 本地 4B 冷加载可达 2-3 分钟，加生成 1-2 分钟；出题漏题时 App 会自动重发一次，
# 两轮生成可能到 8 分钟以上，所以放宽到 15 分钟。
for i in $(seq 1 450); do
  # 必须匹配本轮模式：旧实例/残留日志里的别的模式不能算通过（2026-11-09 假 PASS）。
  LINE=$(read_logs | grep -E "E2E: RESULT ai (${MODE}|mode=${MODE})( |$)" | tail -1 || true)
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
echo "!! 本地 AI 自测超时（15 分钟）。last focus: ${LAST_FOCUS:-未知} pid: ${PID:-无}"
exit 1
