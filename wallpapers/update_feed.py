#!/usr/bin/env python3
"""Appends a few new Unsplash wallpapers to wallpapers/feed.json every day (run on a schedule by GitHub Actions, see .github/workflows/wallpapers.yml).

- Landscape only (aspect ratio ≥ 1.4); searches a few topics in rotation by date and skips photos already in the feed.
- Records the photographer's name and photo page (Unsplash requires attribution); the image URL is the original on Unsplash's image server (the head unit crops to screen size when downloading).
- Per the Unsplash API guidelines, calls download_location once when a photo is added.
- The feed keeps at most MAX_PHOTOS photos; old ones are dropped from the front (the head unit prefers the newer ones at the end).

Usage: UNSPLASH_ACCESS_KEY=... python3 wallpapers/update_feed.py [--count 3] [--feed wallpapers/feed.json]
Testing: python3 wallpapers/update_feed.py --fixture response.json (offline, uses a saved API response)
"""
import argparse
import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

API = "https://api.unsplash.com"
MAX_PHOTOS = 60
MIN_RATIO = 1.4
# Darker topics that suit viewing in a car, rotated by date.
QUERIES = ["night landscape", "mountain sunset", "city night", "road trip landscape", "aurora", "foggy forest", "ocean dusk"]


def api_get(path, key):
    req = urllib.request.Request(API + path, headers={"Authorization": f"Client-ID {key}", "Accept-Version": "v1"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def pick(photos, have, count):
    """Pick landscape photos from the API response, deduplicated, at most count."""
    out = []
    for p in photos:
        if p["id"] in have or any(o["id"] == p["id"] for o in out):
            continue
        if p.get("height") and p["width"] / p["height"] < MIN_RATIO:
            continue
        user = p.get("user") or {}
        out.append({
            "id": p["id"],
            "author": user.get("name", ""),
            "link": (p.get("links") or {}).get("html", "https://unsplash.com"),
            "url": p["urls"]["raw"],
            "added": datetime.date.today().isoformat(),
            "_track": (p.get("links") or {}).get("download_location"),
        })
        if len(out) >= count:
            break
    return out


def write_feed(path, feed):
    """One photo per line, for readable diffs."""
    head = {k: v for k, v in feed.items() if k != "photos"}
    lines = ["{"] + [f"  {json.dumps(k, ensure_ascii=False)}: {json.dumps(v, ensure_ascii=False)}," for k, v in head.items()]
    lines.append('  "photos": [')
    rows = [f"    {json.dumps(p, ensure_ascii=False, separators=(', ', ': '))}" for p in feed["photos"]]
    lines.append(",\n".join(rows))
    lines += ["  ]", "}"]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--feed", default=os.path.join(os.path.dirname(__file__), "feed.json"))
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--fixture", help="use a saved API response instead of the network (for testing)")
    args = ap.parse_args()

    with open(args.feed, encoding="utf-8") as f:
        feed = json.load(f)
    have = {p["id"] for p in feed["photos"]}

    key = os.environ.get("UNSPLASH_ACCESS_KEY", "")
    if args.fixture:
        with open(args.fixture, encoding="utf-8") as f:
            photos = json.load(f)
    elif not key:
        print("::notice::UNSPLASH_ACCESS_KEY is not configured, skipping wallpaper feed update")
        return
    else:
        query = QUERIES[datetime.date.today().toordinal() % len(QUERIES)]
        q = urllib.parse.urlencode({"query": query, "orientation": "landscape", "content_filter": "high",
                                    "count": 10})
        photos = api_get(f"/photos/random?{q}", key)
        print(f"topic: {query}, got {len(photos)} photo(s)")

    new = pick(photos, have, args.count)
    for p in new:
        track = p.pop("_track")
        if track and key and not args.fixture:
            try:
                api_get(urllib.parse.urlparse(track).path + "?" + urllib.parse.urlparse(track).query, key)
            except Exception as e:  # noqa: BLE001 - a failed tracking call must not block adding the photo
                print(f"download_location call failed: {e}")
        print(f"added {p['id']}  {p['author']}")

    feed["photos"] = (feed["photos"] + new)[-MAX_PHOTOS:]
    write_feed(args.feed, feed)
    print(f"{len(new)} new photo(s), feed now has {len(feed['photos'])}")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"added={len(new)}\n")


if __name__ == "__main__":
    sys.exit(main())
