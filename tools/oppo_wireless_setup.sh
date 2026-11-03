#!/bin/bash
# OPPO/ColorOS 无线调试连接助手（Android 11+ 原生无线调试，免 root）
#
# 用法：
#   bash oppo_wireless_setup.sh pair  <IP:配对端口> <六位配对码>
#   bash oppo_wireless_setup.sh connect <IP:调试端口>
#   bash oppo_wireless_setup.sh autoconnect <IP>
#   bash oppo_wireless_setup.sh list
#
# 端口去哪看：手机 设置 → 开发者选项 → 无线调试，页面上「使用配对码配对设备」
#   弹窗里是【配对端口】（pair 用）；主页面「IP 地址和端口」是【调试端口】（connect 用）。
#   两个端口号不同，且每次关闭/重开无线调试都会变。
#   autoconnect = 不手填端口：先试 mDNS，再扫 30000-50000 自动连（配对已做过时用这个）
#
# 铁律（来自库内实测档案，见 docs/oppo-wireless-deploy.md）：
#   1. MSYS_NO_PATHCONV=1 —— Git Bash 会把 /sdcard/x 改写成 D:/Program Files/Git/sdcard/x
#   2. 多设备在线时所有 adb 命令必须 -s <地址>
#   3. adb 版本 ≥ 30 才有 pair 子命令（本机 37.0.0 满足）
set -euo pipefail
export MSYS_NO_PATHCONV=1

ADB=${ADB:-adb}
CMD=${1:-}

case "$CMD" in
  pair)
    TARGET=${2:?用法: $0 pair <IP:配对端口> <六位配对码>}
    CODE=${3:?缺少六位配对码（手机弹窗里那 6 位数字）}
    echo "==> adb pair $TARGET  (code $CODE)"
    "$ADB" pair "$TARGET" "$CODE"
    echo
    echo "配对成功后再执行: bash $0 connect <IP:调试端口>"
    ;;
  connect)
    TARGET=${2:?用法: $0 connect <IP:调试端口>}
    echo "==> adb connect $TARGET"
    "$ADB" connect "$TARGET"
    echo
    "$ADB" devices -l
    ;;
  autoconnect)
    HOST=${2:?用法: $0 autoconnect <IP>}
    RANGE=${PORT_RANGE:-30000..50000}
    echo "==> $HOST 上找调试端口（段 $RANGE）"
    PORTS=$(SCAN_HOST="$HOST" SCAN_RANGE="$RANGE" pwsh -NoProfile -Command '
      $t = $env:SCAN_HOST
      Invoke-Expression ($env:SCAN_RANGE) | ForEach-Object -Parallel {
        $p = $_
        $c = [System.Net.Sockets.TcpClient]::new()
        try { $k = $c.ConnectAsync($using:t, $p); if ($k.Wait(600) -and $c.Connected) { $p } } catch {} finally { $c.Dispose() }
      } -ThrottleLimit 300 | Sort-Object' 2>/dev/null)
    if [ -z "${PORTS//[[:space:]]/}" ]; then
      echo "!! $HOST 在 $RANGE 无监听端口。典型原因："
      echo "   a) 手机「无线调试」开关已关 / 配对弹窗关掉后服务停了"
      echo "   b) 屏幕锁了（部分 ROM 锁屏即停 adb-over-wifi）"
      echo "   处理：手机解锁 → 开发者选项 → 打开「无线调试」→ 停在该页面，再重跑本命令"
      exit 2
    fi
    for P in $PORTS; do
      echo "--> adb connect $HOST:$P"
      "$ADB" connect "$HOST:$P" && "$ADB" devices -l && break
    done
    ;;
  list)
    echo "==> 已发现设备"
    "$ADB" devices -l
    echo
    echo "==> mDNS 服务（无线调试开启并同网段时才有条目）"
    "$ADB" mdns services || true
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac
