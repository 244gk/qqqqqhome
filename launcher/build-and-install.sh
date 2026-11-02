#!/usr/bin/env bash
# Build the launcher and install it on the head unit. By default it only installs and opens it once for a trial, without changing the default launcher.
# Usage:
#   launcher/build-and-install.sh                 # build + install + open for a trial
#   launcher/build-and-install.sh --apk file.apk  # skip the build and install an existing APK (e.g. one downloaded from GitHub Actions)
#   launcher/build-and-install.sh --set-home      # make it the default launcher after installing (asks first and prints the rollback command)
set -euo pipefail

ADB="${ADB:-adb}"
PKG=com.x0finch.q5home
COMPONENT=$PKG/.MainActivity
cd "$(dirname "$0")"

apk=""
set_home=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apk="$2"; shift 2 ;;
    --set-home) set_home=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

if [ -z "$apk" ]; then
  echo "编译中…"
  ./gradlew -q :app:assembleRelease
  apk=app/build/outputs/apk/release/app-release.apk
fi

state="$("$ADB" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "没有连上车机（adb get-state：${state:-无}）。先按 README 连接。" >&2
  exit 1
fi

home_query() {
  "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
    -c android.intent.category.HOME | tr -d '\r' | tail -1
}

before="$(home_query)"
mkdir -p ../backups
echo "$before" > "../backups/home-before-$(date +%Y%m%d-%H%M%S).txt"
echo "当前默认桌面：$before（已记录到 backups/）"
case "$before" in
  *ResolverActivity*|"")
    if [ "$set_home" -eq 1 ]; then
      echo "车机当前没有固定的默认桌面，无法自动记录回滚目标。" >&2
      echo "请先用 tools/recon.sh 的 home-candidates.txt 找到原厂桌面组件，再手动设置。" >&2
      exit 1
    fi ;;
esac

echo "安装 $apk …"
"$ADB" install -r "$apk"

if [ "$set_home" -eq 0 ]; then
  "$ADB" shell am start -n "$COMPONENT" >/dev/null
  echo
  echo "已安装并打开。先在车上试用：CarPlay、原车、长按换应用、旋钮选中。"
  echo "没问题后再执行：launcher/build-and-install.sh --apk $apk --set-home"
  exit 0
fi

if [ "$before" = "$COMPONENT" ] || [ "$before" = "$PKG/$PKG.MainActivity" ]; then
  echo "已经是默认桌面，无需切换。"
  exit 0
fi

echo
echo "即将执行：$ADB shell cmd package set-home-activity $COMPONENT"
echo "原厂桌面不会被停用；出问题可以回滚：$ADB shell cmd package set-home-activity $before"
printf "确认切换默认桌面？(y/N) "
read -r answer
case "$answer" in y|Y) ;; *) echo "已取消。"; exit 0 ;; esac

"$ADB" shell cmd package set-home-activity "$COMPONENT"
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
after="$(home_query)"
echo "现在的默认桌面：$after"
case "$after" in
  "$PKG"/*) echo "切换成功。按 HOME 键确认回到新桌面。" ;;
  *)
    echo "切换没有生效（固件可能锁定了默认桌面），正在回滚…" >&2
    "$ADB" shell cmd package set-home-activity "$before" || true
    exit 1 ;;
esac
