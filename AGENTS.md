# Project notes (q5home, for coding assistants)

A clean launcher and debloat for the Android widescreen head unit in a 2014 Audi Q5 (8R).
Head unit: Android 12, WY firmware (OS_v1.0.0_WY20_2, APP WY_006_20_1.0.63_230322_GSM,
MCU CM_23.02.08_02.21, CAN A81xAQ5_00_0033_N2105). The left side shows the original car MMI screen, the right side is an Android sidebar.
CarPlay currently uses ZLINK. The owner connects to the head unit from a Mac over adb.

**Measured (in-car recon, see `examples/wy20/recon-app-2026-10-30.md`)**: the system claims 12 but is actually **Android 8.1 (API 27)**,
MT6739, 32-bit armeabi-v7a, platform WWC2 (`com.wwc2.*`). `user` build, not debuggable, no su.
**No wireless debugging**; USB debugging is on (`sys.usb.state=adb`), so adb must first connect once over a USB cable, then `adb tcpip 5555`.
Do not rely on API 28+ behavior in code without a compatibility path (minSdk stays 26).

## Language (must follow)

- **Everything written into the repository is in English**: commit messages, code comments, docs, workflow
  files, release notes, AGENTS.md additions. Existing Chinese comments/docs get translated when touched.
- **Only conversation with the owner is in Chinese** (replies, progress updates, questions, summaries).
- Exception: user-facing UI strings in the app and CI demo captions stay Chinese (the owner reads them in the car).

## Commits and signing (must follow)

- **Every commit** uses exactly this author and committer:
  `244gk <9wh8m244gk@privaterelay.appleid.com>`, and **every commit is GPG-signed** with the owner's key
  (`GPG_SIGN_KEY`, key id `02F8A8A8051AB284`). Force OpenPGP for this repo
  (`git config gpg.format openpgp`, `user.signingkey 02F8A8A8051AB284`, `commit.gpgsign true`);
  the environment's default SSH signer is NOT the owner's key. Verify with `git log --format='%G? %GK %an <%ae>'`
  before pushing.
- **No AI-assistant traces anywhere in the repository**: never mention any AI assistant or its tools by name
  in commits, code, comments, docs, workflows or file names, and never add AI trailers (Co-Authored-By,
  session links, etc.). Project instructions live in this file, `AGENTS.md`.
- Project name is **q5home** (repo `244gk/qqqqqhome`). The package / applicationId stays `com.x0finch.q5home`;
  **never change it**, or the head unit treats it as a different app and cannot upgrade in place.
- Version sequence = `version-base` (200) + commit count, so new builds are newer than what is installed. Never lower it.

## GitHub usage (must follow; GitHub abuse detection is strict)

- **Do not open Issues**, PRs or comments.
- **Do not commit often**: finish a change, pass local tests and lint, then push it as one commit. Batch small
  changes; never push repeatedly to fix CI (reproduce and fix locally first).
- **Keep GitHub API calls to a minimum**: no polling of CI, releases or APIs; check rarely or wait for
  notifications. Do not re-run or trigger workflows unless the owner asks (releases are started by hand), and do not increase the
  `wallpapers.yml` schedule.

## Safety rules (must follow)

1. **Read-only by default**. Before running any command other than `tools/recon.sh` that changes the head unit's state
   (`pm disable-user`, `pm enable`, `cmd package set-home-activity`, `settings put`,
   `install`/`uninstall`, `am force-stop`, `pm clear`, etc.), show the user the full command
   and wait for explicit approval.
2. **Disable only, never uninstall**: only use `pm disable-user --user 0 <package>`; `pm uninstall`,
   `pm clear`, root, flashing and modifying partitions are forbidden.
3. **Baseline before changes**: the earliest output under `recon/` is the factory baseline and must never be deleted or overwritten.
   Run `tools/recon.sh` again before every change to keep a record.
4. **Small batches**: at most 3–5 packages per batch; after each batch, have the user verify the checklist below in the car, and only continue once it passes.
5. **Always keep the stock launcher**: the WY stock launcher must never be disabled; it is the fallback if our launcher breaks.
   Only switch the default launcher once the new launcher responds to HOME correctly.
6. Leave any package whose purpose is unclear untouched, and ask the user first.

## Categories that must never be disabled

Add package names here as they are identified:
- Reversing camera / 360 / camera related (driving safety): `com.baony.avm360`
- CAN protocol box, MCU communication, vehicle info services: `com.wwc2.canbusapk`, `com.wwc2.canmcusdk`, `com.wwc2.carmanager`,
  `com.wwc2.main` (core, includes the ACC power-on broadcast), `com.wwc2.poweroff`, `com.wwc2.mcuupdate`, `com.wwc2.canmcuupdate`
- Original car MMI screen switching (left screen, the original car ("原车") entry): direct method not yet confirmed. Owner tested: tapping the stock Home ("主页") `com.wwc2.launcher` opens the Android box launcher,
  and Original car media ("原车媒体") inside it switches to the original car; clues: `content://com.wwc2.canmcusdk.CanbusProvider/original_screen`, `com.wwc2.APK_TO_MAIN`,
  main's `AvinLogic` / `VideoSource`. The related packages `com.wwc2.canmcusdk`, `com.wwc2.main`, `com.wwc2.mainui`, `com.wwc2.black` are all off limits
- Right sidebar (central locking, seatbelt icons) and SystemUI: `com.android.systemui`, `com.wwc2.mainui`
- Audio channel switching, Bluetooth, radio, steering wheel buttons, MMI knob mapping: `com.wwc2.bluetooth`, `com.goodocom.gocsdk` (Bluetooth stack)
- ZLINK (and DiPlay, which was tried): `com.zjinnova.zlink`
- WY stock launcher, system settings, factory settings: `com.wwc2.launcher`, `com.wwc2.settings`, `com.android.settings`, `com.wwc2.pq` (display)
- Leave system update packages alone for now: `com.wwc2.fota`, `com.wwc2.systemupdate_apk`, `com.wwc2.systempermission`
- All `android` and `com.android.*` core components (unless the user confirms each one individually)

## In-car checklist after every change

- [ ] Shift into reverse: reversing camera appears and exits correctly
- [ ] Original car MMI screen can be switched to, knob works
- [ ] Sidebar central locking and seatbelt icons work
- [ ] CarPlay (ZLINK) connects; audio, calls and Siri work
- [ ] Steering wheel buttons and volume work
- [ ] All of the above still work after ignition off and restart

## Layout

- `tools/recon.sh`: read-only recon, outputs to `recon/<timestamp>/` (gitignored).
- `launcher/`: launcher APK, Kotlin + classic Views (no Compose, the head unit CPU is weak), no third-party dependencies.
  - `build-and-install.sh`: build and install; `--set-home` confirms, records and supports rollback before switching the default launcher.
  - Configuration: SharedPreferences + a `config.json` that can be pushed via adb (see `Config.kt`, `Targets.kt`).
  - The OEM ("原车") tile opens the stock home "主页" (`com.wwc2.launcher`) by default (the owner's choice: its "原车媒体" switches
    to the OEM screen); `Targets.findOem` tries it before any app whose name contains 原车 / oem, and `Config` moved an entry stored
    by the old detection to it once (`oem_home_fix_v1`). Once recon finds the original car switching component, put it in the `oem`
    field of config.json; do not hardcode it.
  - Home screen icons are in `Icons.kt` (Material Symbols vectors); tile = icon key + target; switching apps only changes the target.
  - Wallpapers are in `res/drawable-nodpi/wall_*.webp`, sources recorded in `CREDITS.md`; update it when adding wallpapers.
  - Online wallpapers: `OnlineWallpapers.kt` + `WallpaperJob.kt` (Wi-Fi only, once a day); storage limit 8 images / 6 MB,
    no download when free space < 300 MB. Do not loosen these limits when making changes unless the user agrees. The manifest is `wallpapers/feed.json` at the repository root.
    The manifest is appended once a week (Sundays) by the `wallpapers.yml` workflow + `wallpapers/update_feed.py` (Secret `UNSPLASH_ACCESS_KEY`, at most 60 entries).
    The auto-rotation key is still `daily` (backward compatible); the interval is stored in `wallpaper_interval_min` (minutes, default 1440).
  - UI style: **dark liquid-glass Bento cards** (`ui/BentoDrawable.kt` + `ui/Glass.kt`, modelled on iOS 26 Liquid Glass). Cards keep
    layered shadows (drawn at 1/4 size as cached ALPHA_8 bitmaps, because Android 8.1 hardware acceleration has no blurred shape
    shadows; parent layouts need `Ui.allowShadows` to turn off clipping). The page background is the wallpaper; behind each card
    `Glass` draws a lightly softened, more saturated copy of it (no system blur on 8.1: downscale to 1/6, two software box-blur
    passes, recomputed only when the wallpaper changes, computed before the first frame via `Glass.updateBeforeDraw`).
    `BentoDrawable.drawGlass` adds the rest: one layer of that background exactly behind the card (no lens / magnified rim bands
    and no stacked outlines: they read as an inner frame), a single diagonal edge highlight, a very transparent tint that darkens over bright backgrounds (`Glass.luma`), and a soft glow
    under the finger while pressed (`setHotspot`). Scroll containers need `Glass.followScroll`, otherwise the glass is misaligned.
    Only cards are glass. Anything that sits ON a card (buttons, the selected category, menu rows) uses `Ui.inner`: a light
    translucent layer with one thin rim, no shadow and no glass of its own. Glass-on-glass shows the background shifted, and a
    button's shadow draws a dark ring that reads as an uneven transition around it.
    **Dark only** (the owner's choice; `Ui.resolveDark` always returns true); the `appearance` setting is still read and written
    but no longer applied. Take all colors from `Ui`, never hardcode them.
    All pages are full screen (theme `windowFullscreen`, status bar hidden).
    Animations (to hide jank on the slow head unit): the launcher's own pages fade in growing slightly / fade out shrinking
    (`Anim.Q5Home`, run by the system compositor); launching CarPlay, the OEM screen or other apps has no animation
    (task* animations are @null).
  - UI components are in `ui/` (`Ui` style and colors, `Overlay` popup layer, `Menus` menus/confirmations/icon grid, `Toggle` switch,
    `RollingText` rolling digits). Settings is the full-page `SettingsActivity`: a floating glass navigation column on the left and
    liquid-glass cards on the right (`tile`: icon / title / big value / description / bottom button, toggles top-right with the whole
    tile clickable; `tilesRow` equal-height rows; `grid`; `card` for lists, QR codes and the thumbnail strip, its content clipped to
    the rounded card with `roundClip`). No back button: swipe right from the left edge (`dispatchTouchEvent`) or press HOME.
    Switching sections cross-fades (`switchSection`: fade out, rebuild while invisible, fade in moving up). Wallpaper thumbnails are
    cached (`thumbBitmap`); don't decode them again on every refresh. Do not use system AlertDialog / Toast.
  - Tests: `./gradlew :app:testDebugUnitTest` (Robolectric, including screenshot output to `app/build/screenshots`).
  - `fakeapps/` holds the fake ZLINK / original car apps used for CI recordings; never install them on the head unit (same package name as the real ZLINK).
  - In-app updates: `Updates.kt` (GitHub Release check / download / verify / PackageInstaller install), `UpdateJob.kt` (daily check).
    Version sequence = `version-base` + commit count (`app/build.gradle`). **Pushing does not build**: the `launcher` workflow only runs
    when the owner starts it by hand (Actions → launcher → Run workflow on `main`); it builds, lints, runs the unit and screenshot
    tests, checks the signature, then publishes the Release (notes = commits since the previous release). The emulator demo is a
    separate workflow (`demo.yml`, Actions → demo), run only when a recording is wanted; both reuse `build.yml`.
    Only start either yourself when the owner asks.
  - Head unit recon (no adb): the recon page has only the QR code, the event log (count, clear) and the usage-access status; nothing
    already known is shown there. `Recon.kt` (the read-only report), `LocalShare.kt` (temporary web page opened by scanning a QR code with
    a phone: the one report, ready to copy and send; path contains a random string, closes after 15 minutes). **Reports are never uploaded and no GitHub token is used**: the owner copies
    the text and sends it. `QrCode.kt` (hand-written QR code, tests check fixed hashes).
    `ApkStrings.kt` (reads string constants from vendor APKs), `DexScan.kt` (read-only disassembly: call order in methods that use
    matching strings, call / access matching methods or fields, or are named that way; call sites such as sendBroadcast /
    startActivity with their strings; constant tables with values; tests use `test/resources/oem-switch.dex` and `recon-extra.dex`,
    built from the `.java.txt` files next to them with javac + d8), `ElfScan.kt` (reads a native library's exported functions and
    device-path strings as bytes, never loads it; test library `libavin-test.so.bin` built from `libavin-test.c.txt` with gcc).
    Background recording: `EventRecorder.kt` starts with the process (`Q5App`) and only listens: broadcasts (built-in plus
    action-like names found in the vendor apps' code), change notifications of vendor content providers, System / Global / Secure
    settings changes and polled system properties named in vendor code; `EventLog.kt` writes them to q5home's own file in batches
    (every 30 s / 100 lines, at once on process start, screen off and shutdown; 2 MB cap, oldest dropped), with every process start,
    so gaps show. Events known not to matter ("ignoreEvents" in `config/recon-known.json`, built-in copy `ReconKnown.DEFAULT_IGNORE`,
    tests check they match; the copy downloaded with a report replaces it) are never registered or written. It must never send, write or switch anything on the head unit.
    Usage access ("使用情况访问权限"), if the owner turns it on in system settings, adds which app / page came to the front.
    **There is exactly one report** (the owner's rule: no focused / full split) and it shows **only what is new**:
    `config/recon-known.json` lists what earlier reports already showed (system build, vendor app versions, disassembled methods,
    provider query results, code strings, known event noise); it is downloaded when the report is generated and everything in it is
    left out. **After reading each report, add what it taught us to that file (no release needed).** The report has: changes
    (system, vendor apps added / removed / updated with their components and new strings), GPS, the event log since the owner last
    copied a report (the web page tells the app on copy), and the read-only analysis set by `config/recon-targets.json`
    (same copy built into `ReconTargets.kt`, tests check they match; change the analysis focus there, no release needed).
    These files may only decide what to read; never add the ability to write to or switch anything on the head unit.
    The recon page must stay read-only; never add any operation that changes the head unit's state (disabling, changing settings, etc.). The owner pastes reports into the conversation.
  - Home screen = **Bento dashboard** (`activity_main.xml`): top row clock card | GPS card (big speed + `ui/SunArcView`
    sunrise/sunset arc) | big CarPlay card (id `firstTile`, the square on its left is the current wallpaper, no label above its title);
    bottom row square app tiles (width = height: OEM, favorite apps, all apps), then a square dashed "添加" (Add) tile, always last.
    Launcher settings is the small icon in the clock card's top-right corner (id `deskSettings`, icon only, no background, an accent
    ring only when selected with the knob; it never takes an app tile). Common apps with English labels (e.g. Settings) show the
    unified Chinese name. More tiles scroll horizontally; swiping on the clock / GPS cards scrolls them too.
    **Design rules** (from the stageshow Bento rules): icons are white filled glyphs placed directly on the glass (`Icons.glyph`, no
    colored squares); apps' own icons keep their look but have the same size and position. Say each thing once (no "Files"-style
    duplicate subtitles; only the OEM hint and "not installed" lines). Labels in normal case, no decorative dots. Only the clock is
    the biggest number. Without a GPS fix the speed row collapses to one status line; without location permission a tap on the GPS
    card opens "GPS 与 4G" (handled in `forwardSwipesToTiles`; the card itself stays non-clickable so swipes still scroll).
    Clock and speed use `ui/RollingText`: changed digits roll instead of being swapped. Cards shrink when pressed and spring back.
  - Home GPS: `HomeGps.kt` (speed / heading / altitude, listens only while the home screen is shown); sunrise/sunset: `Sun.kt` (local formula, position stored only to 0.1°).
    In `wallpaper_sun` mode, wallpapers are split into day / night by `Wallpapers.isNight` (hardcoded for built-ins, judged by brightness for online ones).
  - Default launcher: `HomeRole.kt` (requests the HOME role via RoleManager; restore goes through the system Home app settings). The stock launcher is never disabled.
  - Crash protection: `CrashGuard.kt` (3 crashes of the same version within 10 minutes) → `SafeModeActivity.kt` (minimal, system widgets only; do not add dependencies to it).
    Rollback: `.github/workflows/rollback.yml` (old code + higher version number, republished). Releases wait for the build, lint and tests to pass.
  - **Backward-compatible data**: SharedPreferences / config.json only gain fields and never change the meaning of existing ones; to change a format, use a new key (e.g. `dock_v2`)
    and keep reading the old key, so settings still work after rolling back to an older version.
  - Signing: the owner's private key, not in the repository. CI decodes it from Secrets `Q5HOME_KEYSTORE` (base64) / `Q5HOME_KEYSTORE_PASSWORD`;
    locally it reads `Q5HOME_KEYSTORE` / `Q5HOME_KEYSTORE_PASSWORD` from `~/.gradle/gradle.properties`; the alias is always `q5home`.
    Without the key you only get a debug signature. Never commit any key or password to the repository, and do not change how the signing config is read, or the head unit cannot install updates over the existing app.
- `ci/demo.py`: CI emulator demo script (Chinese step captions + tap log + screenshots marking tap positions); `ci/render_video.py` puts the captions into a caption bar below the video
  and draws rings at tap positions. When UI text changes, update the steps in demo.py that find elements by text. Check the recorded video frame by frame (see below).

## Recording review

Whenever CI produces a new recording, extract frames every 0.5 seconds, tile them into overview sheets and go through each one; view full-size frames at key moments. Check: captions in sync with actions,
tap ring positions, whether popup cards cover anything, truncated text, focus and scrolling, any system dialogs or flicker. Write up issues and improvements with video timestamps.
- Future plans: `tools/disable.sh` / `tools/restore.sh`, `examples/wy20/debloat.txt`. Structure modeled on CleanYourTV (MIT).
