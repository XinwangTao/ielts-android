#!/usr/bin/env bash
# 批次 0（免点击）：预热通知 + 深链直达页面 + 前台/崩溃检查。
# 用法：bash tools/e2e_batch0.sh [设备序列号] [--force]
#   --force：手机正被别的 App 占用时也强跑（会抢屏，默认拒绝）。
# 退出码：0 通过；1 失败；3 手机被占用（未执行）。
set -uo pipefail
cd "$(dirname "$0")/.."

PKG="com.txw.ielts"
OUT="${CLAUDE_JOB_DIR:-/tmp}/tmp"
mkdir -p "$OUT"
STAMP=$(date +%Y%m%d_%H%M%S)
LOG="$OUT/batch0_${STAMP}.log"
DEV="${1:-}"
FORCE="${2:-}"
FAILED=()

is_android() {
  local sdk
  sdk=$(adb -s "$1" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '[:space:]')
  case "$sdk" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

if [ -z "$DEV" ]; then
  for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    if is_android "$d"; then DEV="$d"; break; fi
  done
fi
if [ -z "$DEV" ] || ! is_android "$DEV"; then
  echo "没有可用的 Android 设备"
  exit 1
fi
echo "== 设备：$DEV（$(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '[:space:]')）"

FOCUS=$(adb -s "$DEV" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)
if [ "$FORCE" != "--force" ] && [ -n "$FOCUS" ] &&
   ! printf '%s' "$FOCUS" | grep -qE "mCurrentFocus=null|com.txw.ielts|launcher"; then
  echo "!! 手机当前在前台使用其它 App：$FOCUS"
  echo "   不抢屏；等空闲再跑，或加 --force 强跑。"
  exit 3
fi

wait_focus() {
  local want="$1" tries=20
  while [ $tries -gt 0 ]; do
    local f
    f=$(adb -s "$DEV" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus || true)
    case "$f" in *"$want"*) return 0 ;; esac
    sleep 0.5
    tries=$((tries - 1))
  done
  return 1
}

open_route() {
  local route="$1"
  adb -s "$DEV" shell "am force-stop $PKG" >/dev/null 2>&1
  adb -s "$DEV" shell "am start -n $PKG/.MainActivity --es route $route" >/dev/null 2>&1
  if ! wait_focus "com.txw.ielts"; then
    FAILED+=("focus:$route")
    echo "   $route：启动后不在前台"
    return 1
  fi
  return 0
}

echo "== 1) 写作页停留 5 秒 → 预热通知"
adb -s "$DEV" logcat -b all -c 2>/dev/null || true
if open_route "writing"; then
  # 预热是异步加载：每 0.5 秒看一次，整个加载窗口内出现即算通过（模型小/冷缓存快慢差很多）
  FOUND=0
  for _ in $(seq 1 24); do
    NOTES=$(adb -s "$DEV" shell dumpsys notification --noredact 2>/dev/null || true)
    # 用 ASCII 通道名判定：dumpsys 的中文标题在无线 adb 下常被截断/转义，channel=guard 稳定
    if printf '%s' "$NOTES" | grep -qE "pkg=com\.txw\.ielts.*channel=guard"; then
      FOUND=1
      break
    fi
    sleep 0.5
  done
  LOADED=0
  # 补一条直接证据：预热是否真的跑过（不依赖通知权限）
  if adb -s "$DEV" logcat -d 2>/dev/null | grep -q "loading local model"; then
    LOADED=1
    echo "   日志：LocalLlm 有加载记录"
  else
    echo "   日志：未见 LocalLlm 加载记录"
  fi
  if [ "$FOUND" = 1 ]; then
    echo "   通知：出现 Guard 前台服务通知（预热中）"
  elif [ "$LOADED" = 1 ]; then
    echo "   通知：未显示（可能未授予通知权限）；预热本身已执行，按通过计"
  else
    FAILED+=("preload-notification")
    echo "   通知：加载窗口内既无预热通知也无加载日志（模型可能未投递/开关关闭）"
  fi
  PID=$(adb -s "$DEV" shell pidof "$PKG" 2>/dev/null | tr -d '[:space:]')
  [ -n "$PID" ] || FAILED+=("process-dead:writing")

  # 返回后通知应消失（Guard.stop 在 preload 的 finally 里）；模型仍在加载时最多等 20 秒
  adb -s "$DEV" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  STUCK=1
  for _ in $(seq 1 20); do
    if ! adb -s "$DEV" shell dumpsys notification --noredact 2>/dev/null | grep -qE "pkg=com\.txw\.ielts.*channel=guard"; then
      STUCK=0
      break
    fi
    sleep 1
  done
  if [ "$STUCK" = 0 ]; then
    echo "   通知：返回后已消失"
  else
    # 加载没结束不算缺陷（前台服务本就该陪着加载），只记录不判失败
    echo "   ！通知：返回 20 秒后仍在（模型可能仍在后台加载）"
  fi
else
  FAILED+=("launch:writing")
fi

echo "== 2) 口语页深链直达"
if open_route "speaking"; then
  sleep 1
  echo "   已进入口语页"
else
  FAILED+=("launch:speaking")
fi

echo "== 3) 崩溃缓冲检查"
CRASHES=$(adb -s "$DEV" logcat -d -b crash 2>/dev/null | grep -c "$PKG" || true)
if [ "${CRASHES:-0}" -gt 0 ]; then
  FAILED+=("crash-buffer")
  echo "   崩溃缓冲区有 $CRASHES 条本应用记录"
else
  echo "   无崩溃记录"
fi

{
  echo "### focus"
  adb -s "$DEV" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus
  echo "### notification"
  adb -s "$DEV" shell dumpsys notification --noredact 2>/dev/null | grep -E "pkg=com\.txw\.ielts.*channel=guard" | head -5
  echo "### localllm"
  adb -s "$DEV" logcat -d 2>/dev/null | grep -E "LocalLlm|ForegroundServiceDidNotStart" | tail -20
  echo "### crash"
  adb -s "$DEV" logcat -d -b crash 2>/dev/null | tail -20
} > "$LOG" 2>&1

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "== 批次 0 通过 =="
  exit 0
fi
echo "== 批次 0 失败：${FAILED[*]}（日志 $LOG）=="
exit 1
