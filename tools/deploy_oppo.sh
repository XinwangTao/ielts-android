#!/bin/bash
# 部署雅思 App 到 OPPO 手机 + 推送 MNN 本地大模型 + 联调自检
#
# 用法：
#   bash tools/deploy_oppo.sh <设备地址> [模型目录] [模型目录名]
#     <设备地址>   无线 192.168.236.x:<调试端口>，或 USB 序列号
#     [模型目录]   默认 F:/cache/models/qwen3-4b-instruct-mnn
#                  **本地路径必须 Windows 风格**（F:/...）：脚本开了 MSYS_NO_PATHCONV=1，
#                  否则 /tmp/x 不会被转换，adb 会报 cannot stat
#     [模型目录名] 默认 qwen3-4b-instruct —— 必须与 App 设置页/默认值同名，
#                  否则设置页选不到（App 按目录名列出可用模型）
#
# 例：
#   bash tools/deploy_oppo.sh 192.168.237.88:37123
#   bash tools/deploy_oppo.sh 192.168.237.88:37123 F:/cache/models/qwen35-2b-mnn qwen35-2b
#
# 只装 APK 不推模型（模型已在设备上）：MODEL=skip
# 只推模型不装 APK：SKIP_BUILD=1 SKIP_INSTALL=1
set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB=${ADB:-adb}
DEV=${1:?用法: bash tools/deploy_oppo.sh <设备地址> [模型目录] [模型目录名]}
MODEL_DIR=${2:-F:/cache/models/qwen3-4b-instruct-mnn}
MODEL_NAME=${3:-$(basename "$MODEL_DIR" | sed 's/-mnn$//')}
PKG=com.txw.ielts
APK_DIR=app/build/outputs/apk/debug
DST_EXT=/sdcard/Android/data/$PKG/files/models/mnn/$MODEL_NAME

adb_() { "$ADB" -s "$DEV" "$@"; }
say() { printf '\n==> %s\n' "$*"; }

# ---------- 0. 预检 ----------
say "0/5 设备预检 $DEV"
adb_ get-state >/dev/null 2>&1 || { echo "!! 设备未连接：先跑 tools/oppo_wireless_setup.sh connect <IP:端口>"; exit 1; }
MODEL_INFO=$(adb_ shell getprop ro.product.model | tr -d '\r')
ABI=$(adb_ shell getprop ro.product.cpu.abi | tr -d '\r')
SDK=$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')
RAM_MB=$(adb_ shell "grep MemTotal /proc/meminfo" | tr -dc '0-9')
echo "    机型=$MODEL_INFO  ABI=$ABI  SDK=$SDK  RAM=$((RAM_MB/1024))MB"
[ "$ABI" = "arm64-v8a" ] || { echo "!! ABI=$ABI，本 APK 只含 arm64-v8a，无法安装"; exit 1; }

# ---------- 1. 构建 ----------
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  say "1/5 构建 debug APK"
  ( cd "$(dirname "$0")/.." && ./gradlew assembleDebug --offline -q )
else
  say "1/5 跳过构建"
fi
APK=$(ls -t "$(dirname "$0")/../$APK_DIR"/*.apk | head -1)
echo "    $APK ($(du -h "$APK" | cut -f1))"

# ---------- 2. 安装 ----------
if [ "${SKIP_INSTALL:-0}" != "1" ]; then
  say "2/5 安装 APK（-r 覆盖保留数据，-g 直接授予运行时权限）"
  adb_ install -r -g "$APK" || {
    echo "!! 安装失败。OPPO/ColorOS 常见三道门："
    echo "   a) 开发者选项「通过 USB 安装」未开"
    echo "   b) 系统「权限监控」拦截 —— 设置→密码与安全→系统安全→权限监控，关掉再试"
    echo "   c) 安装器弹窗在手机上没点「继续安装/允许」"
    exit 1
  }
else
  say "2/5 跳过安装"
fi

# ---------- 3. 推模型 ----------
if [ "${MODEL:-}" = "skip" ]; then
  say "3/5 跳过模型投递"
else
  [ -f "$MODEL_DIR/config.json" ] || { echo "!! 模型目录缺 config.json: $MODEL_DIR"; exit 1; }
  WEIGHT_MB=$(( $(stat -c%s "$MODEL_DIR/llm.mnn.weight") / 1024 / 1024 ))
  # 注意：Android toybox 的 df 不支持 -m，只有 -k（单位 1K-block）
  FREE_MB=$(adb_ shell "df -k /data | tail -1" | tr -d '\r' | awk '{print int($4/1024)}')
  NEED_MB=$(( WEIGHT_MB * 24 / 10 ))   # 外部目录 + 内部拷贝，约 2.4x 权重
  say "3/5 推模型 $MODEL_NAME（权重 ${WEIGHT_MB}MB，预计占用 ${NEED_MB}MB）"
  echo "    设备 /data 可用 ${FREE_MB}MB"
  if [ "${FREE_MB:-0}" -lt "$NEED_MB" ]; then
    echo "!! 空间不足。改用小模型：bash tools/deploy_oppo.sh $DEV F:/cache/models/qwen35-2b-mnn qwen35-2b"
    exit 1
  fi
  adb_ shell "mkdir -p $DST_EXT"
  adb_ push "$MODEL_DIR"/config.json "$MODEL_DIR"/llm.mnn "$MODEL_DIR"/llm_config.json \
             "$MODEL_DIR"/tokenizer.txt "$DST_EXT"/ >/dev/null
  for f in llm.mnn.weight llm.mnn.json configuration.json visual.mnn visual.mnn.weight; do
    [ -f "$MODEL_DIR/$f" ] && adb_ push "$MODEL_DIR/$f" "$DST_EXT"/ >/dev/null
  done
  say "3b 校验（远端/本地权重字节数必须一致）"
  REMOTE=$(adb_ shell "stat -c%s $DST_EXT/llm.mnn.weight" | tr -d '\r')
  LOCAL=$(stat -c%s "$MODEL_DIR/llm.mnn.weight")
  echo "    local=$LOCAL remote=$REMOTE"
  [ "$REMOTE" = "$LOCAL" ] || { echo "!! 权重不完整，重推"; exit 1; }
fi

# ---------- 4. 自检 ----------
say "4/5 设备侧自检"
adb_ shell "ls -la $DST_EXT | head -12; echo; du -sm $DST_EXT 2>/dev/null"

# ---------- 5. 启动 ----------
say "5/5 启动 App"
adb_ shell am start -n $PKG/.MainActivity >/dev/null
echo
cat <<'EOF'
下一步（手机屏幕上操作，adb 无法代替）：
  1. 设置 → 评分通道选「仅本地(离线)」，避免云端 403 掩盖本地链路
  2. 设置 → 本地评分模型 → 选中刚推的模型；App 首次评分会自动拷到内部（约 1-3 分钟）
  3. 提交一篇写作，观察：评分中…→ 通知 → 结果页
  4. 抓日志： adb -s <地址> logcat -s LocalLlm ScoreService
EOF
