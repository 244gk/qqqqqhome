#!/usr/bin/env bash
# Read-only recon: collects head-unit info without changing anything on the head unit.
# Usage:
#   tools/recon.sh                # use the only connected device
#   tools/recon.sh 192.168.1.50   # adb connect to this IP first (default port 5555)
#   tools/recon.sh 192.168.1.50:37123
# Output: several txt files and screen.png under recon/<timestamp>/.
# The first run's output is the "factory baseline" that all later restores are based on; do not delete it.
set -euo pipefail

ADB="${ADB:-adb}"
cd "$(dirname "$0")/.."

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "找不到 adb。Mac 上请先执行：brew install android-platform-tools" >&2
  exit 1
fi

if [ $# -ge 1 ]; then
  target="$1"
  case "$target" in *:*) ;; *) target="$target:5555" ;; esac
  "$ADB" connect "$target"
  export ANDROID_SERIAL="$target"
fi

"$ADB" wait-for-device
state="$("$ADB" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "设备状态异常：$state（检查车机上是否弹出了 USB 调试授权）" >&2
  exit 1
fi

out="recon/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$out"
echo "输出目录：$out"

# Run one read-only command on the head unit and write the result to a file; failures are logged, not fatal.
run() {
  local file="$1"; shift
  echo "  - $file"
  if ! "$ADB" shell "$@" 2>&1 | tr -d '\r' > "$out/$file"; then
    echo "(命令失败：$*)" >> "$out/$file"
  fi
}

echo "收集系统信息…"
run getprop.txt getprop
run wm-size.txt wm size
run wm-density.txt wm density
run display.txt dumpsys display
run window-displays.txt dumpsys window displays

echo "收集应用包信息（这几份就是恢复用的基线）…"
run packages-all.txt pm list packages -f -U
run packages-system.txt pm list packages -s
run packages-3rdparty.txt pm list packages -3
run packages-enabled.txt pm list packages -e
run packages-disabled.txt pm list packages -d

echo "收集桌面 / 可启动应用…"
run home-current.txt cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
run home-candidates.txt cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
run launchable-apps.txt cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

echo "收集运行状态…"
run activities.txt dumpsys activity activities
run services-running.txt dumpsys activity services
run processes.txt ps -A -o USER,PID,PPID,NAME
run input.txt dumpsys input
run settings-global.txt settings list global
run settings-secure.txt settings list secure
run settings-system.txt settings list system

echo "截图…"
"$ADB" exec-out screencap -p > "$out/screen.png" || echo "  截图失败（可忽略）"

# Pick out possibly relevant packages by keyword for manual analysis. These are only leads, not a list of things to disable.
{
  echo "# 当前默认桌面"
  cat "$out/home-current.txt"
  echo
  echo "# 前台 Activity"
  grep -E "mResumedActivity|topResumedActivity" "$out/activities.txt" || true
  echo
  echo "# 屏幕"
  cat "$out/wm-size.txt" "$out/wm-density.txt"
  echo
  echo "# 关键词命中的包（CarPlay / 原车 / CAN / 倒车 / 桌面 / 厂商）"
  grep -iE "zlink|carplay|diplay|autokit|carlink|wy|can|mcu|camera|avm|reverse|back|launcher|home|systemui|audi|radio|bt|bluetooth|media|music|setting" \
    "$out/packages-all.txt" | sed 's/^package://' | sort || true
} > "$out/summary.txt"

echo
echo "完成。先看 $out/summary.txt 和 $out/screen.png。"
echo "注意：recon/ 里含设备序列号等信息，已在 .gitignore 中排除，不要提交到公开仓库。"
