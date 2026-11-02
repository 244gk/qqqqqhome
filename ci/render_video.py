#!/usr/bin/env python3
"""Turn the raw recording from demo.py into an annotated demo video.

- Adds a black caption strip below the video; the Chinese step captions go in the strip so they don't cover the UI.
- Draws a ring at every tap / long-press position (the emulator shows no system touch dots for script-injected taps, so we draw our own):
  taps get a white ring, long presses an orange ring that stays longer.

Usage: python3 ci/render_video.py demo-out   (reads demo-raw.mp4, captions.srt, taps.json; writes demo.mp4)
"""
import json
import os
import subprocess
import sys

from PIL import Image, ImageDraw

STRIP = 120  # caption strip height (pixels)
RING = 120   # ring diameter
FONT = os.environ.get("CAPTION_FONT", "Noto Sans CJK SC")


def ring(path, color):
    """Semi-transparent filled circle + thick ring + black outline, visible on any background."""
    im = Image.new("RGBA", (RING, RING), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((4, 4, RING - 4, RING - 4), fill=color[:3] + (70,), outline=(0, 0, 0, 200), width=10)
    d.ellipse((8, 8, RING - 8, RING - 8), outline=color, width=6)
    d.ellipse((RING // 2 - 9, RING // 2 - 9, RING // 2 + 9, RING // 2 + 9), fill=color)
    im.save(path)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "demo-out"
    raw = os.path.join(out, "demo-raw.mp4")
    srt = os.path.join(out, "captions.srt")
    taps_path = os.path.join(out, "taps.json")
    if not (os.path.exists(raw) and os.path.exists(srt)):
        print("no raw recording or captions, skipping")
        return
    taps = json.load(open(taps_path)) if os.path.exists(taps_path) else []

    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height", "-of", "csv=p=0", raw], capture_output=True, text=True, check=True)
    width, height = map(int, probe.stdout.strip().split(","))

    tap_png = os.path.join(out, ".ring-tap.png")
    hold_png = os.path.join(out, ".ring-hold.png")
    ring(tap_png, (255, 255, 255, 255))
    ring(hold_png, (255, 159, 10, 255))

    # Swipes use a white ring moving from start to end.
    taps_short = [t for t in taps if t["kind"] in ("tap", "swipe")]
    taps_long = [t for t in taps if t["kind"] == "hold"]
    # screenrecord uses a variable frame rate (no frames while the screen is static); convert to a fixed 30fps first so caption and ring timing line up.
    parts = [f"[0:v]fps=30,pad=iw:ih+{STRIP}:0:0:color=black[v0]"]
    if taps_short:
        parts.append(f"[1:v]split={len(taps_short)}" + "".join(f"[t{i}]" for i in range(len(taps_short))))
    if taps_long:
        parts.append(f"[2:v]split={len(taps_long)}" + "".join(f"[h{i}]" for i in range(len(taps_long))))
    cur = "v0"
    n = 0
    # The ring appears 0.7 s before the tap (the UI changes as soon as it's tapped, so drawing later would land on the next screen) and stays visible during a long press.
    for label, group, before, after in (("t", taps_short, 0.7, 0.25), ("h", taps_long, 0.7, 1.3)):
        for i, t in enumerate(group):
            n += 1
            nxt = f"v{n}"
            x, y = t["x"] - RING // 2, t["y"] - RING // 2
            start, end = max(t["t"] - before, 0), t["t"] + after
            if t["kind"] == "swipe":
                dur = t["dur"]
                p = f"clip((t-{t['t']:.2f})/{dur:.2f},0,1)"
                x = f"'{x}+({t['x2'] - t['x']})*{p}'"
                y = f"'{y}+({t['y2'] - t['y']})*{p}'"
                end += dur
            parts.append(f"[{cur}][{label}{i}]overlay={x}:{y}:shortest=1:enable='between(t,{start:.2f},{end:.2f})'[{nxt}]")
            cur = nxt
    # Caption font size and margin are in 288-line coordinates (libass default), converted from real pixels: glyphs about 52px tall, centered in the bottom strip.
    scale = 288 / (height + STRIP)
    font_px, margin_px = 52, (STRIP - 52) // 2
    style = (f"FontName={FONT},FontSize={font_px * scale:.1f},PrimaryColour=&H00FFFFFF,BorderStyle=1,"
             f"Outline=0,Shadow=0,Alignment=2,MarginV={margin_px * scale:.1f}")
    srt_escaped = srt.replace(":", "\\:")
    parts.append(f"[{cur}]subtitles={srt_escaped}:original_size={width}x{height + STRIP}:force_style='{style}'[out]")
    script = os.path.join(out, ".filter.txt")
    with open(script, "w") as f:
        f.write(";\n".join(parts))

    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", raw,
           "-loop", "1", "-i", tap_png, "-loop", "1", "-i", hold_png,
           "-filter_complex_script", script, "-map", "[out]",
           "-r", "30", "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p",
           os.path.join(out, "demo.mp4")]
    subprocess.run(cmd, check=True)
    for f in (tap_png, hold_png, script):
        os.remove(f)
    print(f"generated {out}/demo.mp4 ({len(taps)} tap markers)")


if __name__ == "__main__":
    main()
