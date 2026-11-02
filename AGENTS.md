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

- **Do not open Issues**, PRs or comments, and do not use the in-car recon "submit" to test Issue creation.
- **Do not commit often**: finish a change, pass local tests and lint, then push it as one commit. Batch small
  changes; never push repeatedly to fix CI (reproduce and fix locally first).
- **Keep GitHub API calls to a minimum**: no polling of CI, releases or APIs; check rarely or wait for
  notifications. Do not re-run or trigger workflows by hand, and do not increase the `wallpapers.yml` schedule.

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
  - Once recon finds the original car switching component, put it in the `oem` field of config.json; do not hardcode it.
  - Home screen icons are in `Icons.kt` (Material Symbols vectors); tile = icon key + target; switching apps only changes the target.
  - Wallpapers are in `res/drawable-nodpi/wall_*.webp`, sources recorded in `CREDITS.md`; update it when adding wallpapers.
  - Online wallpapers: `OnlineWallpapers.kt` + `WallpaperJob.kt` (Wi-Fi only, once a day); storage limit 8 images / 6 MB,
    no download when free space < 300 MB. Do not loosen these limits when making changes unless the user agrees. The manifest is `wallpapers/feed.json` at the repository root.
    The manifest is appended daily by the `wallpapers.yml` workflow + `wallpapers/update_feed.py` (Secret `UNSPLASH_ACCESS_KEY`, at most 60 entries).
    The auto-rotation key is still `daily` (backward compatible); the interval is stored in `wallpaper_interval_min` (minutes, default 1440).
  - UI style: minimal **Bento cards** (`ui/BentoDrawable.kt`): layered shadows + 1px border + inner glow at the top;
    two styles: `LIFTED` (large shadow offset downward: home tiles, popup cards, buttons at rest) and `RESTING` (slightly darker border, brighter fill: settings groups, pressed state).
    Shadows are drawn at 1/4 size as cached ALPHA_8 bitmaps (Android 8.1 hardware acceleration does not support blurred shape shadows); parent layouts need `Ui.allowShadows` to turn off clipping.
    Frosted glass (`ui/Glass.kt`): the page background is the wallpaper, card fills are translucent, and a blurred copy of the wallpaper is drawn behind them (Android 8.1 has no system blur:
    downscale to 1/10 and run a software box blur three times, recomputed only when the wallpaper changes); scroll containers need `Glass.followScroll`, otherwise the blurred background is misaligned.
    Appearance `appearance`: auto (light during the day, dark after sunset, via `Sun`) / light / dark; take all colors from the theme colors in `Ui`, never hardcode colors again.
  - UI components are in `ui/` (`Ui` style and theme colors, `Overlay` popup layer, `Menus` menus/confirmations/icon grid, `Toggle` switch);
    settings is the full-page `SettingsActivity`. Do not use system AlertDialog / Toast anymore.
  - Tests: `./gradlew :app:testDebugUnitTest` (Robolectric, including screenshot output to `app/build/screenshots`).
  - `fakeapps/` holds the fake ZLINK / original car apps used for CI recordings; never install them on the head unit (same package name as the real ZLINK).
  - In-app updates: `Updates.kt` (GitHub Release check / download / verify / PackageInstaller install), `UpdateJob.kt` (daily check).
    Version number = commit count (`app/build.gradle`); pushing to main publishes a Release automatically (`release` job in the workflow).
  - Head unit recon (no adb): `Recon.kt` (read-only collection), `ReconUpload.kt` (submits a GitHub Issue; the token is stored only in the head unit's private storage),
    `LocalShare.kt` (temporary web page opened by scanning a QR code with a phone; path contains a random string, closes after 15 minutes), `QrCode.kt` (hand-written QR code, tests check fixed hashes).
    `ApkStrings.kt` (reads string constants from vendor APKs), `DexScan.kt` (read-only disassembly: lists the call order in methods that use original-car-related strings;
    tests use `test/resources/oem-switch.dex`, built from `oem-switch.java.txt` in the same directory with javac + d8).
    Submit ("提交") defaults to a **focused report**: analysis targets come from `config/recon-targets.json` in the repository (downloaded when the head unit submits; the same copy is built into `ReconTargets.kt`,
    and tests check that the two match). **To change the analysis focus, edit only this file; no release needed**. It may only decide what to read; never add the ability to write to or switch anything on the head unit.
    Only Full report ("完整报告") lists all apps, system properties, etc.
    The recon page must stay read-only; never add any operation that changes the head unit's state (disabling, changing settings, etc.). Read recon Issues submitted by the user with GitHub tools.
  - Home screen = **Bento dashboard** (`activity_main.xml`): top row clock card | GPS card (big speed + `ui/SunArcView` sunrise/sunset arc) |
    big CarPlay card (id `firstTile`, the square on its left is the current wallpaper); bottom row app cards (original car, favorite apps, add, all apps, desktop settings).
    With few cards they fill one row; with more they scroll horizontally. The wallpaper is shown in full only inside the CarPlay card, with the page background faintly showing it. The clock / GPS cards are not clickable; swiping on them scrolls the bottom row.
  - Home GPS: `HomeGps.kt` (speed / heading / altitude, listens only while the home screen is shown); sunrise/sunset: `Sun.kt` (local formula, position stored only to 0.1°).
    In `wallpaper_sun` mode, wallpapers are split into day / night by `Wallpapers.isNight` (hardcoded for built-ins, judged by brightness for online ones).
  - Default launcher: `HomeRole.kt` (requests the HOME role via RoleManager; restore goes through the system Home app settings). The stock launcher is never disabled.
  - Crash protection: `CrashGuard.kt` (3 crashes of the same version within 10 minutes) → `SafeModeActivity.kt` (minimal, system widgets only; do not add dependencies to it).
    Rollback: `.github/workflows/rollback.yml` (old code + higher version number, republished). Releases wait for the emulator demo to pass.
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
