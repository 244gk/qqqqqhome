#!/usr/bin/env python3
"""CI demo: install the launcher and fake apps on the emulator, tap through the steps, take screenshots and record the screen.

- Each step writes a Chinese caption (captions.srt); key presses (HOME, Back, D-pad) get captions too.
- Every tap / long press is logged to taps.json; the emulator does not show system touch dots for script-injected taps, so ci/render_video.py draws rings instead.
- Each screenshot marks this step's tap position with a red circle (needs Pillow; skipped without it).

Usage: python3 ci/demo.py --launcher app-release.apk --fake zlink.apk --fake oem.apk --out demo-out
Fails if any step cannot find the text to tap (screenshots and the recording are still kept for debugging).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.x0finch.q5home"
HOME = f"{PKG}/.MainActivity"


def adb(*args, check=True, capture=False):
    result = subprocess.run(["adb", *args], check=check, capture_output=capture, text=capture)
    return result.stdout if capture else None


def shell(cmd, check=True):
    return adb("shell", cmd, check=check, capture=True)


def srt_time(t):
    t = max(t, 0)
    h, rem = divmod(int(t * 1000), 3600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


class Demo:
    def __init__(self, out):
        self.out = out
        self.shots = 0
        self.t0 = None
        self.captions = []  # (start seconds, text)
        self.last_touch = None  # (x, y, label)
        self.taps = []  # used by render_video.py to draw rings
        os.makedirs(out, exist_ok=True)

    # ---- Captions ----

    def caption(self, text):
        print(f"[caption] {text}")
        if self.t0 is not None:
            self.captions.append((time.time() - self.t0, text))

    def write_srt(self, end):
        lines = []
        for i, (start, text) in enumerate(self.captions):
            stop = self.captions[i + 1][0] if i + 1 < len(self.captions) else end
            lines += [str(i + 1), f"{srt_time(start)} --> {srt_time(stop)}", text, ""]
        with open(os.path.join(self.out, "captions.srt"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines))

    # ---- Screenshots (with tap position marked) ----

    def shot(self, name):
        self.shots += 1
        path = os.path.join(self.out, f"{self.shots:02d}-{name}.png")
        with open(path, "wb") as f:
            subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, check=True)
        if self.last_touch:
            mark(path, *self.last_touch)
        print(f"  screenshot {path}")

    # ---- UI lookup and actions ----

    def nodes(self):
        """All elements with text on the current screen: (text, center x, center y)."""
        for _ in range(5):
            shell("uiautomator dump /sdcard/ui.xml", check=False)
            xml = shell("cat /sdcard/ui.xml", check=False)
            if xml and xml.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("uiautomator dump failed")
        found = []
        for node in ET.fromstring(xml).iter("node"):
            # Icon-only buttons (e.g. the launcher settings icon in the clock card) use their content-desc as text.
            text = node.get("text") or node.get("content-desc") or ""
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if text and m:
                x1, y1, x2, y2 = map(int, m.groups())
                found.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
        return found

    def exists(self, text):
        return any(n[0] == text for n in self.nodes())

    def find(self, text, which="last", prefix=False, tries=5):
        """The same text may appear more than once (top name and tile title): last takes the lowest, first the highest."""
        for _ in range(tries):
            hits = [n for n in self.nodes() if (n[0].startswith(text) if prefix else n[0] == text)]
            if hits:
                hits.sort(key=lambda n: n[2])
                return hits[-1] if which == "last" else hits[0]
            time.sleep(1)
        raise RuntimeError(f"not found on screen: {text}")

    def tap(self, text, caption, **kw):
        _, x, y = self.find(text, **kw)
        self.caption(caption)
        time.sleep(0.9)  # give the caption and ring a moment so it is clear what will be tapped
        self.last_touch = (x, y, "点击")
        self.log_tap(x, y, "tap")
        shell(f"input tap {x} {y}")
        time.sleep(1.8)

    def long_press(self, text, caption, **kw):
        _, x, y = self.find(text, **kw)
        self.caption(caption)
        time.sleep(0.9)
        self.last_touch = (x, y, "长按")
        self.log_tap(x, y, "hold")
        shell(f"input swipe {x} {y} {x} {y} 1200")
        time.sleep(1.8)

    def swipe(self, x1, y1, x2, y2, caption, ms=500):
        self.caption(caption)
        time.sleep(0.9)
        self.last_touch = (x1, y1, "滑动")
        if self.t0 is not None:
            self.taps.append({"t": round(time.time() - self.t0, 2), "x": x1, "y": y1, "x2": x2, "y2": y2,
                              "dur": ms / 1000, "kind": "swipe"})
        shell(f"input swipe {x1} {y1} {x2} {y2} {ms}")
        time.sleep(1.8)

    def scroll_x(self, text):
        """X coordinate of a tile's text (used to confirm the tile list scrolled)."""
        return self.find(text)[1]

    def log_tap(self, x, y, kind):
        if self.t0 is not None:
            self.taps.append({"t": round(time.time() - self.t0, 2), "x": x, "y": y, "kind": kind})

    def write_taps(self):
        with open(os.path.join(self.out, "taps.json"), "w") as f:
            json.dump(self.taps, f)

    def key(self, code, caption, times=1):
        self.caption(caption)
        self.last_touch = None
        for _ in range(times):
            shell(f"input keyevent {code}")
            time.sleep(0.7)
        time.sleep(0.8)

    def home(self):
        self.key("KEYCODE_HOME", "按键：HOME，回到桌面")


class Recorder:
    """screenrecord segments are at most 180 s: start the next segment as soon as one ends, then join them into one file with ffmpeg."""

    SEGMENT = 170

    def __init__(self):
        self.parts = []
        self.stopping = False
        self.proc = None
        self.thread = None

    def start(self):
        import threading
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        while not self.stopping:
            path = f"/sdcard/demo-{len(self.parts)}.mp4"
            self.parts.append(path)
            self.proc = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", str(self.SEGMENT),
                                          "--bit-rate", "8000000", path])
            self.proc.wait()

    def stop(self):
        self.stopping = True
        shell("pkill -INT screenrecord", check=False)
        if self.thread:
            self.thread.join(timeout=20)
        # In case we stopped right between segments: stop the freshly started one too (INT lets the mp4 finalize properly).
        shell("pkill -INT screenrecord", check=False)
        time.sleep(2)

    def pull(self, target):
        out_dir = os.path.dirname(target)
        local = []
        for i, part in enumerate(self.parts):
            dst = os.path.join(out_dir, f".part-{i}.mp4")
            adb("pull", part, dst, check=False)
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                local.append(dst)
        if len(local) == 1:
            os.replace(local[0], target)
        elif local:
            lst = os.path.join(out_dir, ".parts.txt")
            with open(lst, "w") as f:
                f.writelines(f"file '{os.path.abspath(p)}'\n" for p in local)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
                            "-c", "copy", target], check=False)
            for p in local + [lst]:
                os.remove(p)
        print(f"recording: {len(local)} segment(s)")


def check_share(demo, out):
    """Decode the QR code from the screenshot, open the head unit's temporary web page via adb forward, and confirm the page and report are reachable (the report is saved as a sample)."""
    import urllib.request
    import urllib.parse
    import urllib.error
    try:
        import cv2
    except ImportError:
        print("OpenCV not available, skipping QR code decoding")
        return
    shot = sorted(f for f in os.listdir(out) if f.endswith("settings-recon-qr.png"))[-1]
    url = ""
    for detector in (cv2.QRCodeDetector(), getattr(cv2, "QRCodeDetectorAruco", cv2.QRCodeDetector)()):
        url = detector.detectAndDecode(cv2.imread(os.path.join(out, shot)))[0]
        if url:
            break
    if not url:
        raise RuntimeError("could not decode a QR code from the screenshot")
    print(f"  QR code: {url}")
    parsed = urllib.parse.urlparse(url)
    adb("forward", "tcp:18765", f"tcp:{parsed.port}")
    local = f"http://127.0.0.1:18765{parsed.path}"
    # The page shows the focused report at the top; it takes a while to generate (503 until ready).
    report = ""
    for _ in range(30):
        try:
            report = urllib.request.urlopen(local + "report.md", timeout=10).read().decode("utf-8")
            break
        except urllib.error.HTTPError as e:
            if e.code != 503:
                raise
            time.sleep(2)
    page = urllib.request.urlopen(local, timeout=10).read().decode("utf-8")
    if "车机摸底" not in page or "重点报告" not in page or "## 版本" not in report:
        raise RuntimeError("temporary web page content is wrong")
    with open(os.path.join(out, "recon-page.html"), "w", encoding="utf-8") as f:
        f.write(page)
    with open(os.path.join(out, "recon-report.md"), "w", encoding="utf-8") as f:
        f.write(report)
    print(f"  temporary web page OK, report is {len(report)} characters")


CJK_FONTS = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
]


def mark(path, x, y, label):
    """Draw a red circle on the screenshot marking where this step tapped."""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        return
    font = None
    for f in CJK_FONTS:
        if os.path.exists(f):
            font = ImageFont.truetype(f, 26)
            break
    if font is None:  # no CJK font: use English labels
        label = {"点击": "TAP", "长按": "HOLD", "滑动": "SWIPE"}.get(label, label)
        font = ImageFont.load_default()
    im = Image.open(path).convert("RGB")
    d = ImageDraw.Draw(im)
    r = 36
    d.ellipse((x - r - 2, y - r - 2, x + r + 2, y + r + 2), outline=(0, 0, 0), width=8)
    d.ellipse((x - r, y - r, x + r, y + r), outline=(255, 59, 48), width=5)
    d.ellipse((x - 7, y - 7, x + 7, y + 7), fill=(255, 59, 48))
    box = d.textbbox((0, 0), label, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    lx = x + r + 10 if x + r + 10 + w + 20 <= im.width else x - r - 30 - w
    d.rounded_rectangle((lx, y - h // 2 - 8, lx + w + 20, y + h // 2 + 10), radius=8, fill=(255, 59, 48))
    d.text((lx + 10, y - h // 2 - box[1]), label, fill=(255, 255, 255), font=font)
    im.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--launcher", required=True)
    ap.add_argument("--fake", action="append", default=[])
    ap.add_argument("--out", default="demo-out")
    args = ap.parse_args()

    adb("wait-for-device")
    adb("install", "-r", args.launcher)
    for apk in args.fake:
        adb("install", "-r", apk)
    # Typical head-unit density; the resolution comes from the emulator's -skin 1920x720.
    shell("wm density 240")
    shell("settings put system screen_off_timeout 1800000")
    shell("svc power stayon true")
    # Show touches in the recording.
    shell("settings put system show_touches 1")
    # The safe-mode demo crashes on purpose; hide the system "app has stopped" dialog.
    shell("settings put global hide_error_dialogs 1", check=False)
    # Make it the default launcher: the HOME role on Android 10+, with the old method as fallback; otherwise HOME pops up the system chooser.
    # Pre-grant the two permissions the "GPS & 4G" page needs so no permission dialog shows in the recording; give the emulator a GPS fix (Toronto).
    for perm in ("ACCESS_FINE_LOCATION", "READ_PHONE_STATE"):
        shell(f"pm grant {PKG} android.permission.{perm}", check=False)
    adb("emu", "geo", "fix", "-79.3832", "43.6532", "76", "9", check=False)
    shell(f"cmd role add-role-holder --user 0 android.app.role.HOME {PKG}", check=False)
    shell(f"cmd package set-home-activity {HOME}", check=False)
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard", check=False)
    shell("input keyevent KEYCODE_HOME")
    for _ in range(10):  # wait until the launcher is really in the foreground before recording
        if PKG in (shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'", check=False) or ""):
            break
        time.sleep(1)
    else:
        print("warning: the launcher did not come to the foreground", file=sys.stderr)
    time.sleep(2)

    demo = Demo(args.out)
    recorder = Recorder()
    recorder.start()
    demo.t0 = time.time()  # recording starts a fraction of a second after this, so captions appear slightly before the actions
    failed = None
    try:
        time.sleep(1)
        demo.caption("① 首页 Bento 仪表盘：深色液态玻璃卡片；时钟卡（换分钟时数字上滚）、GPS 卡、CarPlay 大卡；下排应用卡，最后是虚线「添加」")
        time.sleep(2)
        demo.shot("home")

        demo.tap("CarPlay", "② 点 CarPlay 大卡 → 打开 ZLINK（模拟）")
        demo.find("ZLINK（模拟）")
        demo.shot("carplay-opened")
        demo.home()

        demo.tap("原车", "③ 点「原车」卡片 → 打开原车画面（模拟）")
        demo.find("原车 MMI 画面（模拟）")
        demo.shot("oem-opened")
        demo.home()

        demo.long_press("系统设置", "④ 长按「系统设置」卡片 → 弹出操作菜单")
        demo.shot("tile-menu")
        demo.tap("更换图标", "⑤ 点「更换图标」→ 图标网格")
        demo.shot("icon-picker")
        demo.tap("应用自带", "⑥ 选「应用自带」图标：只换图标，打开的还是系统设置")
        demo.shot("icon-changed")

        demo.tap("全部应用", "⑦ 点「全部应用」卡片 → 全部应用列表")
        demo.shot("library")
        demo.key("KEYCODE_BACK", "按键：返回")

        demo.long_press("CarPlay", "⑧ 长按 CarPlay 大卡 → 菜单：更换应用、应用信息")
        demo.shot("carplay-menu")
        demo.key("KEYCODE_BACK", "按键：返回，关闭菜单")

        demo.tap("桌面设置", "⑨ 点时钟卡右上角的「桌面设置」小图标 → 整页设置（桌面自己的设置，不是安卓系统设置）")
        demo.shot("settings-wallpaper")
        demo.tap("每小时", "⑩ 「自动轮换」选「每小时」→ 每小时换一张（还有 30 分钟 / 3 小时 / 每天）")
        demo.find("当前：自动轮换 · 每小时")
        demo.shot("settings-wallpaper-hourly")
        demo.tap("按日出日落分白天 / 夜晚", "⑪ 打开「按日出日落分白天 / 夜晚」→ 白天轮换白天的壁纸，日落后换夜景（按 GPS 位置算）")
        demo.shot("settings-wallpaper-sun")
        demo.tap("城市夜景", "⑫ 点缩略图「城市夜景」换壁纸")
        demo.shot("settings-wallpaper-city")
        demo.tap("CarPlay 与原车", "⑬ 左侧切到「CarPlay 与原车」")
        demo.shot("settings-carplay")
        demo.tap("开机自动进入 CarPlay", "⑭ 打开「开机自动进入 CarPlay」开关")
        demo.shot("settings-toggle")
        demo.tap("高级", "⑮ 左侧切到「高级」")
        demo.shot("settings-advanced")
        demo.tap("恢复默认布局", "⑯ 点「恢复默认布局」→ 确认卡片")
        demo.shot("confirm")
        demo.tap("取消", "⑰ 点「取消」")
        demo.tap("版本更新", "⑱ 左侧切到「版本更新」→ 检查 GitHub Release", prefix=True)
        time.sleep(3)  # wait for the check result
        demo.shot("settings-update")
        demo.tap("车机摸底", "⑲ 左侧切到「车机摸底」→ 只读查系统、调试线索、原车 / CarPlay 组件")
        demo.find("要点")  # recon runs in the background; the summary appearing means it finished
        demo.shot("settings-recon")
        demo.tap("生成二维码", "⑳ 点「生成二维码」→ 车机开临时网页：手机扫码直接看重点报告，可以复制发出去")
        demo.shot("settings-recon-qr")
        check_share(demo, args.out)
        demo.tap("GPS 与 4G", "㉑ 左侧切到「GPS 与 4G」→ 卫星、定位、SIM 卡、4G 信号（只读）")
        demo.find("卫星")
        time.sleep(2)  # wait for satellite / location callbacks to refresh
        demo.shot("settings-signal")
        demo.swipe(1100, 620, 1100, 160, "往下翻到 4G：SIM 卡、网络类型、信号、现在上网走哪条路")
        demo.shot("settings-signal-4g")
        for _ in range(3):  # the test button is at the bottom of the 4G group; keep scrolling until it shows
            if demo.exists("测试"):
                break
            demo.swipe(1100, 620, 1100, 160, "继续往下翻")
        demo.tap("测试", "㉒ 点「4G 上网测试」→ 临时只走移动网络访问一次测试地址")
        for _ in range(10):  # wait up to 20 s for the result
            if not demo.exists("测试中…"):
                break
            time.sleep(2)
        demo.shot("settings-signal-probe")
        demo.swipe(4, 360, 700, 360, "㉓ 从屏幕左边缘往右滑 → 回到桌面（设置页没有返回按钮，按 HOME 也行）")
        demo.find("CarPlay")
        demo.shot("home-city")

        # Rotary knob / D-pad: focus moves between tiles.
        demo.key("KEYCODE_DPAD_DOWN", "㉔ 方向键 ↓（模拟旋钮）：焦点从 CarPlay 大卡开始")
        demo.key("KEYCODE_DPAD_DOWN", "㉕ 方向键 ↓ →：焦点移到下排应用卡")
        demo.key("KEYCODE_DPAD_RIGHT", "㉕ 方向键 ↓ →：焦点移到下排应用卡", times=2)
        demo.shot("dpad-focus")
        demo.home()

        # Safe mode: make the launcher crash 3 times in a row on purpose (it only crashes when the marker file exists, see MainActivity.maybeCrashForTest)
        marker = f"/sdcard/Android/data/{PKG}/files/crash-test"
        shell(f"touch {marker}")
        demo.caption("㉖ 测试：让桌面故意连续崩溃 3 次")
        for _ in range(3):
            shell(f"am start -n {HOME} --ez crash_test true", check=False)
            time.sleep(2.5)
        shell(f"rm -f {marker}")
        demo.caption("㉗ 第 3 次崩溃后，桌面自动进入安全模式（不会卡在反复崩溃里）")
        time.sleep(2)
        # When the default launcher keeps crashing, Android itself clears the default and asks the user to choose again (same in the car, where the factory launcher can be picked instead).
        if demo.exists("Select a Home app") or demo.exists("q5home"):
            demo.tap("q5home", "安卓发现桌面反复崩溃，让你重新选桌面：选 q5home")
            for label in ("Always", "始终", "总是"):
                if demo.exists(label):
                    demo.tap(label, "选「始终」")
                    break
        demo.find("安全模式")
        time.sleep(1)
        demo.shot("safe-mode")
        demo.tap("再试一次正常启动", "㉘ 点「再试一次正常启动」→ 回到正常桌面")
        demo.find("CarPlay")
        demo.shot("home-after-safe-mode")
        demo.caption("演示结束")
        time.sleep(2)
    except Exception as e:  # noqa: BLE001 - keep the recording even on failure
        failed = e
        demo.caption(f"失败：{e}")
        demo.shot("failure")
    finally:
        end = time.time() - demo.t0
        recorder.stop()
        recorder.pull(os.path.join(args.out, "demo-raw.mp4"))
        demo.write_srt(end)
        demo.write_taps()

    crashes = shell("logcat -d -b crash", check=False) or ""
    with open(os.path.join(args.out, "crash.log"), "w") as f:
        f.write(crashes)
    # Split on "FATAL EXCEPTION"; the deliberate crash_test crashes don't count, any other launcher crash is a failure.
    real = [b for b in crashes.split("FATAL EXCEPTION")[1:] if PKG in b and "crash_test" not in b]
    if real:
        print(f"the launcher crashed unexpectedly {len(real)} time(s), see crash.log", file=sys.stderr)
        sys.exit(1)
    if failed:
        print(f"demo failed: {failed}", file=sys.stderr)
        sys.exit(1)
    print("demo finished")


if __name__ == "__main__":
    main()
