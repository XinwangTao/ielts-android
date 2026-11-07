#!/usr/bin/env bash
# 真机冒烟：安装 → 启动 → 截图 → 检查崩溃/前台进程。
# 用法：bash tools/e2e_smoke.sh [设备序列号]
#   不传设备时取 adb devices 里第一台状态为 device 的机器。
# 退出码：0 通过；1 失败（会打印原因与日志位置）。
set -uo pipefail
cd "$(dirname "$0")/.."

PKG="com.txw.ielts"
APK="app/build/outputs/apk/debug/app-debug.apk"
OUT="${CLAUDE_JOB_DIR:-/tmp}/tmp"
mkdir -p "$OUT" ".tmp-shots" 2>/dev/null || true
STAMP=$(date +%Y%m%d_%H%M%S)
SHOT=".tmp-shots/smoke_${STAMP}.png"
LOG="$OUT/smoke_${STAMP}.log"
FAILED=()

# 只认真正的 Android：历史遗留的 TCP 端点（如电脑自身 ICS 地址）也能出现在 adb devices 里，
# 但它没有 pm/am/logcat，选中会让冒烟出现「安装成功却启动失败」的假象。
is_android() {
  local sdk
  sdk=$(adb -s "$1" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '[:space:]')
  case "$sdk" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}

DEV="${1:-}"
if [ -z "$DEV" ]; then
  for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
    if is_android "$d"; then DEV="$d"; break; fi
  done
fi
if [ -z "$DEV" ]; then
  echo "没有已连接且授权的 Android 设备（adb devices 里状态需为 device）"
  exit 1
fi
if ! is_android "$DEV"; then
  echo "指定设备 $DEV 不像 Android（getprop 无 SDK 版本），换一个端点再试"
  exit 1
fi
echo "== 设备：$DEV（$(adb -s "$DEV" shell getprop ro.product.model 2>/dev/null | tr -d '[:space:]')）"

if ! adb -s "$DEV" get-state >/dev/null 2>&1; then
  echo "设备不可用：adb -s $DEV get-state 失败"
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "== 未找到 APK，先打包"
  if ! ./gradlew :app:assembleDebug --console=plain -q; then
    echo "打包失败，无法冒烟"
    exit 1
  fi
fi

echo "== 安装（覆盖安装，不清数据）"
if ! adb -s "$DEV" install -r "$APK"; then
  FAILED+=("install")
fi

echo "== 冷启动"
adb -s "$DEV" shell am force-stop "$PKG" >/dev/null 2>&1
adb -s "$DEV" logcat -c 2>/dev/null || true
START=$(adb -s "$DEV" shell am start -W -n "$PKG/.MainActivity" 2>&1)
echo "$START" | sed -n '1,6p'
if ! echo "$START" | grep -q "Status: ok"; then
  FAILED+=("launch")
fi

sleep 3
PID=$(adb -s "$DEV" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
if [ -z "$PID" ]; then
  FAILED+=("process-dead")
  echo "启动后进程不存在（可能闪退）"
else
  echo "   进程存活 pid=$PID"
fi

echo "== 截图：$SHOT"
adb -s "$DEV" exec-out screencap -p > "$SHOT" 2>/dev/null || FAILED+=("screenshot")

echo "== 抓日志：$LOG"
{
  echo "### start"
  echo "$START"
  echo "### logcat"
  adb -s "$DEV" logcat -d -v time 2>/dev/null | tail -n 400
  echo "### crash buffer"
  adb -s "$DEV" logcat -d -b crash 2>/dev/null | tail -n 100
} > "$LOG"

if adb -s "$DEV" logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then
  FAILED+=("crash-buffer")
  echo "崩溃缓冲区里有本应用记录（见 $LOG）"
fi

# 首页或首启引导至少有一个可见文本：只作提示，不作为失败条件（首启会有引导页）。
MSYS_NO_PATHCONV=1 adb -s "$DEV" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
UI=$(MSYS_NO_PATHCONV=1 adb -s "$DEV" exec-out cat /sdcard/window_dump.xml 2>/dev/null)
if echo "$UI" | grep -q "雅思练习册"; then
  echo "   界面：首页已渲染"
elif echo "$UI" | grep -qE "开始|引导|设置"; then
  echo "   界面：首启引导/设置页（首启正常）"
else
  echo "   界面文本未识别，请看截图确认"
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  echo "== 冒烟通过 =="
  exit 0
fi
echo "== 冒烟失败：${FAILED[*]}（截图 $SHOT，日志 $LOG）=="
exit 1
