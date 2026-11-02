package com.x0finch.q5home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.x0finch.q5home.ui.BentoDrawable
import com.x0finch.q5home.ui.Glass
import com.x0finch.q5home.ui.MenuItem
import com.x0finch.q5home.ui.Menus
import com.x0finch.q5home.ui.Overlay
import com.x0finch.q5home.ui.RollingText
import com.x0finch.q5home.ui.Ui
import com.x0finch.q5home.ui.SunArcView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private enum class Slot { CARPLAY, OEM, DOCK, ADD, APPS }

    private class Tile(
        val slot: Slot,
        val index: Int,
        val spec: String?,
        val icon: Icons.Icon?,
        val title: String,
        val sub: String,
        val wide: Boolean,
    )


    private lateinit var config: Config
    private lateinit var root: View
    private lateinit var wallpaperView: ImageView
    private lateinit var scrim: View
    private lateinit var credit: TextView
    private lateinit var tiles: LinearLayout
    private lateinit var tileScroll: HorizontalScrollView
    private lateinit var greeting: TextView
    private lateinit var date: TextView
    private lateinit var gpsLabel: TextView
    private lateinit var clock: RollingText
    private lateinit var speed: RollingText
    private lateinit var speedRow: View
    private lateinit var gpsCard: View
    /** No location permission yet: a tap on the GPS card opens the GPS settings page. */
    private var gpsNeedsPermission = false
    private lateinit var speedUnit: TextView
    private lateinit var trip: TextView
    private lateinit var sunArc: SunArcView
    private lateinit var carplayCard: View
    private lateinit var art: ImageView
    private lateinit var carplaySub: TextView
    private lateinit var gps: HomeGps
    /** When the location goes stale (e.g. in a tunnel), show a hint instead: checked every 5 seconds. */
    private val tripTicker = object : Runnable {
        override fun run() {
            updateTrip()
            handler.postDelayed(this, 5000)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tileHeight = 0
    private var tileWidth = 0
    private var shownWallpaper: String? = null
    private lateinit var overlay: Overlay
    private var started = false
    /** The theme this page was built with; recreate when the theme changes after sunset / a settings change. */
    private var createdDark = true

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (themeChanged()) return
            updateClock(animate = true)
            updateGreeting()
            updateWallpaper()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            config.reload()
            rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This version crashed repeatedly recently: go into safe mode first so the launcher doesn't get stuck in a crash loop.
        if (CrashGuard.shouldEnterSafeMode(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        maybeCrashForTest(intent)
        Ui.applyTheme(this)
        createdDark = Ui.dark
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        wallpaperView = findViewById(R.id.wallpaper)
        scrim = findViewById(R.id.scrim)
        credit = findViewById(R.id.credit)
        tiles = findViewById(R.id.tiles)
        tileScroll = findViewById(R.id.tileScroll)
        greeting = findViewById(R.id.greeting)
        date = findViewById(R.id.date)
        gpsLabel = findViewById(R.id.gpsLabel)
        clock = RollingText(this).apply {
            setTextSize(64f); setTypeface(resources.getFont(R.font.outfit_light)); setLetterSpacing(-0.04f)
        }
        findViewById<FrameLayout>(R.id.clockHolder).addView(clock)
        speed = RollingText(this).apply {
            setTextSize(40f); setTypeface(resources.getFont(R.font.outfit_light)); setLetterSpacing(-0.03f)
        }
        findViewById<FrameLayout>(R.id.speedHolder).addView(speed)
        speedRow = findViewById(R.id.speedRow)
        gpsCard = findViewById(R.id.gpsCard)
        speedUnit = findViewById(R.id.speedUnit)
        trip = findViewById(R.id.trip)
        carplayCard = findViewById(R.id.firstTile)
        art = findViewById(R.id.art)
        carplaySub = findViewById(R.id.carplaySub)
        sunArc = SunArcView(this)
        findViewById<FrameLayout>(R.id.sunHolder).addView(sunArc, FrameLayout.LayoutParams(-1, -1))
        gps = HomeGps(this) { updateTrip() }

        overlay = Overlay(root as FrameLayout)
        config = Config(this)
        config.reload()?.let { toast("config.json 有误：$it") }

        styleDashboard()
        forwardSwipesToTiles()

        // Bottom-row card size follows that row's actual height: the head-unit resolution and the WY sidebar footprint are still unknown, so make it adaptive.
        tileScroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && h != tileHeight) {
                tileHeight = h
                handler.post { rebuild() }
            }
        }
        // Cover square: side length = content height of the CarPlay card.
        art.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height > 0 && v.layoutParams.width != v.height) {
                v.post { v.layoutParams = v.layoutParams.apply { width = v.height } }
            }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })

        if (config.onlineWallpapers) WallpaperJob.schedule(this)
        if (Updates(this).autoCheck) UpdateJob.schedule(this)
        findViewById<View>(R.id.pillUpdate).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_UPDATE))
        }

        maybeAutoStartCarPlay()
        started = true
    }

    /**
     * Used by CI to verify safe mode: crash on purpose when launched with the crash_test extra and a crash-test marker file exists in the external files dir (only adb can place it).
     */
    private fun maybeCrashForTest(intent: Intent?) {
        if (intent?.getBooleanExtra("crash_test", false) == true &&
            java.io.File(getExternalFilesDir(null), "crash-test").exists()) {
            throw IllegalStateException("crash_test：故意崩溃，用来测试安全模式")
        }
    }

    override fun onDestroy() {
        if (!started) { super.onDestroy(); return }
        unregisterReceiver(timeReceiver)
        unregisterReceiver(packageReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!started) return // Already redirected to safe mode; UI not initialized
        if (themeChanged()) return
        config.reload()?.let { toast("config.json 有误：$it") }
        updateClock(animate = false)
        updateGreeting()
        updateWallpaper()
        updateBadge()
        rebuild()
        if (config.homeGps) gps.start() else gps.stop()
        handler.removeCallbacks(tripTicker)
        handler.post(tripTicker)
    }

    override fun onPause() {
        super.onPause()
        if (!started) return
        gps.stop()
        handler.removeCallbacks(tripTicker)
    }

    /** The theme changed since this page was built: recreate it (dark only now, so this is a safety net). */
    private fun themeChanged(): Boolean {
        Ui.applyTheme(this)
        if (Ui.dark == createdDark) return false
        recreate()
        return true
    }

    /**
     * GPS card: large speed (rolls when it changes) and heading / altitude. Without a fresh fix the speed row collapses and a single
     * status line remains (no big dash); without location permission the line invites a tap, which opens the GPS settings page.
     * The sunrise/sunset arc is also refreshed here.
     */
    private fun updateTrip() {
        val now = System.currentTimeMillis()
        val reading = if (config.homeGps) HomeGps.reading(gps.last, now) else null
        val speedText = reading?.speedKmh?.toString()
        speedRow.visibility = if (speedText != null) View.VISIBLE else View.GONE
        if (speedText != null) speed.setText(speedText, animate = speed.text.isNotEmpty())
        val needPermission = reading == null && config.homeGps && !gps.hasPermission()
        val detail = when {
            reading != null -> reading.detail.ifEmpty { "GPS 已定位" }
            !config.homeGps -> "首页 GPS 已关闭"
            needPermission -> "点一下开启定位"
            else -> "正在等待 GPS 定位…"
        }
        if (trip.text != detail) trip.text = detail
        trip.setTextColor(if (needPermission) Ui.ACCENT else Ui.TEXT_DIM)
        trip.textSize = if (speedText == null) 15f else 13f
        gpsNeedsPermission = needPermission
        gpsCard.isFocusable = needPermission
        val loc = Sun.location(this)
        val times = loc?.let { Sun.times(it.first, it.second, now) }
        if (times != null) {
            sunArc.set(times.first, times.second, now, approximate = false)
        } else {
            val day = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val rise = day.apply { set(Calendar.HOUR_OF_DAY, 7) }.timeInMillis
            sunArc.set(rise, rise + 12 * 3_600_000L, now, approximate = true)
        }
    }

    /** Style the top-row cards and the page background (all colors come from Ui). */
    private fun styleDashboard() {
        root.setBackgroundColor(Ui.PAGE)
        // The background is the wallpaper with just a slight scrim; cards are liquid glass (Glass).
        scrim.setBackgroundColor(0x40000000)
        credit.setTextColor(Ui.TEXT_FAINT)
        // Launcher settings: only an icon in the clock card's top-right corner (no background); it fades while pressed and gets an
        // accent ring only when selected with the knob.
        findViewById<ImageView>(R.id.deskSettings).apply {
            background = null
            imageTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(Ui.TEXT_FAINT, Ui.TEXT_DIM))
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Ui.ACCENT)
                })
            }
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val clockCard = findViewById<View>(R.id.clockCard)
        clockCard.background = Ui.bento(this, 26, BentoDrawable.Style.LIFTED)
        gpsCard.background = Ui.bento(this, 26, BentoDrawable.Style.LIFTED)
        carplayCard.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), Ui.bento(this@MainActivity, 26, BentoDrawable.Style.RESTING))
            addState(intArrayOf(), Ui.bento(this@MainActivity, 26, BentoDrawable.Style.LIFTED))
        }
        carplayCard.foreground = Ui.focusRing(this, 26)
        Ui.allowShadows(findViewById(R.id.grid), findViewById(R.id.topRow), tileScroll, tiles)
        Glass.followScroll(tileScroll)
        // Card labels: normal case, no decorative dots.
        greeting.setTextColor(Ui.TEXT_DIM)
        gpsLabel.setTextColor(Ui.TEXT_DIM)
        clock.color = Ui.TEXT
        date.setTextColor(Ui.TEXT_DIM)
        speed.color = Ui.TEXT
        gpsCard.foreground = Ui.focusRing(this, 26)
        // Knob selects the GPS card (only focusable without location permission) and presses OK: open the GPS settings page.
        gpsCard.setOnKeyListener { _, keyCode, event ->
            val ok = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (ok && event.action == android.view.KeyEvent.ACTION_UP && gpsNeedsPermission) openGpsSettings()
            ok && gpsNeedsPermission
        }
        speedUnit.setTextColor(Ui.TEXT_DIM)
        trip.setTextColor(Ui.TEXT_DIM)
        gpsLabel.text = "行车"
        findViewById<TextView>(R.id.carplayTitle).setTextColor(Ui.TEXT)
        carplaySub.setTextColor(Ui.TEXT_DIM)
        // Cover: rounded-corner clipping (outline clipping under hardware acceleration; Android 8.1 supports rounded rects).
        art.background = Ui.rounded(this, Ui.TRACK, 18)
        art.clipToOutline = true
        findViewById<ImageView>(R.id.carplayGo).apply {
            background = Ui.rounded(this@MainActivity, Ui.TEXT, 24)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.PAGE)
        }
        findViewById<TextView>(R.id.pillUpdate).apply {
            background = Ui.pillBackground(this@MainActivity, Ui.PillStyle.PRIMARY)
            setTextColor(Ui.ON_ACCENT)
        }
        carplayCard.setOnClickListener {
            if (Targets.isAvailable(this, config.carplay)) {
                if (!Targets.launch(this, config.carplay)) toast("无法打开：${config.carplay}")
            } else {
                pick(REQ_CARPLAY)
            }
        }
        carplayCard.setOnLongClickListener { v -> onTileLongClick(carplayTile(), v); true }
        pressAnimation(carplayCard, 1.02f)
    }

    /** Wallpaper / page size changed: recompute the glass background before the next frame (no solid-card flash) and redraw the cards. */
    private fun refreshGlass(bitmap: android.graphics.Bitmap?, key: String?) {
        Glass.updateBeforeDraw(wallpaperView, bitmap, key, root)
    }

    /** Clock: the digits that changed roll up when the minute changes (replaced directly when the page is shown). */
    private fun updateClock(animate: Boolean) {
        clock.setText(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date()), animate)
    }

    private fun openGpsSettings() {
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_SIGNAL))
    }

    private fun carplayTile(): Tile {
        val available = Targets.isAvailable(this, config.carplay)
        return Tile(
            Slot.CARPLAY, 0, config.carplay, Icons.byKey("carplay"), getString(R.string.carplay),
            if (available) Targets.label(this, config.carplay) ?: "" else getString(R.string.not_set),
            wide = true,
        )
    }

    private fun updateCarplay() {
        val tile = carplayTile()
        val available = Targets.isAvailable(this, config.carplay)
        carplaySub.text = if (available) listOf(tile.sub, "轻触进入").filter { it.isNotEmpty() }.joinToString("  ·  ")
            else tile.sub
        carplayCard.contentDescription = "CarPlay"
    }

    /** Shrink slightly when pressed and spring back a little on release; grow slightly when selected (knob). */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressAnimation(v: View, focusScale: Float) {
        v.setOnFocusChangeListener { view, focused ->
            view.animate().scaleX(if (focused) focusScale else 1f).scaleY(if (focused) focusScale else 1f)
                .setDuration(150).start()
            if (focused && view.parent == tiles) keepVisible(view)
        }
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val s = if (view.isFocused) focusScale else 1f
                    view.animate().scaleX(s).scaleY(s).setDuration(220)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                }
            }
            false
        }
    }

    /** Show an accent-colored pill in the clock card when a new version is available. */
    private fun updateBadge() {
        val updates = Updates(this)
        val pill = findViewById<TextView>(R.id.pillUpdate)
        val latest = updates.latest
        pill.visibility = if (updates.hasUpdate && latest != null) View.VISIBLE else View.GONE
        if (latest != null) pill.text = "新版本 ${latest.versionName}"
    }

    /** HOME pressed while already on the launcher: scroll back to the far left and close popups. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeCrashForTest(intent)
        overlay.dismiss(animate = false)
        // Clear focus before scrolling back to the far left, otherwise the focused tile pulls the scroll position back.
        currentFocus?.clearFocus()
        tileScroll.scrollTo(0, 0)
        tileScroll.post {
            tileScroll.scrollTo(0, 0)
            // When using the knob / D-pad, focus lands on CarPlay after returning to the launcher.
            if (!root.isInTouchMode) findViewById<View>(R.id.firstTile)?.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back on the launcher only closes a popped-up card, nothing else.
        overlay.dismiss()
    }

    /**
     * The first time the launcher is shown after boot, open CarPlay automatically if enabled in settings. Only once per boot: record the system boot count,
     * so it doesn't auto-open again when the launcher restarts after a crash or update (if kept only in memory, it would be forgotten on process restart).
     */
    private fun maybeAutoStartCarPlay() {
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = getSharedPreferences("session", MODE_PRIVATE)
        if (boot >= 0) {
            if (prefs.getInt(KEY_AUTO_BOOT, -1) == boot) return
            prefs.edit().putInt(KEY_AUTO_BOOT, boot).apply()
        } else {
            if (bootHandled) return // System where the boot count can't be read: fall back to per-process tracking
            bootHandled = true
        }
        if (!config.autoCarplay || SystemClock.elapsedRealtime() > 5 * 60_000) return
        handler.postDelayed({ Targets.launch(this, config.carplay) }, 2500)
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (hour) {
            in 5..10 -> "早上好"
            in 11..12 -> "中午好"
            in 13..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "夜深了"
        }
        greeting.text = "$hello  ·  ${config.title}"
        date.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())
    }

    /** Only re-decode the wallpaper when it changes (checked every minute; auto-rotate switches when due; newly downloaded online wallpapers also join the rotation). */
    private fun updateWallpaper() {
        val wallpaper = Wallpapers.current(this, config.wallpaper, intervalMinutes = config.wallpaperInterval,
            daylight = if (config.wallpaperSun) Sun.isDaylight(this) else null)
        if (wallpaper?.key == shownWallpaper && (wallpaper != null || wallpaperView.drawable == null)) return
        shownWallpaper = wallpaper?.key
        val bitmap = wallpaper?.let { Wallpapers.decode(this, it, resources.displayMetrics.widthPixels) }
        wallpaperView.setImageBitmap(bitmap)
        refreshGlass(bitmap, wallpaper?.key)
        // With a solid-color wallpaper, the cover uses the CarPlay icon.
        if (bitmap != null) art.setImageBitmap(bitmap) else art.setImageDrawable(Icons.byKey("carplay")?.let { Icons.badge(this, it) })
        val author = wallpaper?.author
        credit.text = if (author.isNullOrBlank()) "" else "Photo · $author / Unsplash"
        credit.visibility = if (bitmap != null && !author.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    // ---- Tiles ----

    private fun buildTiles(): List<Tile> {
        val list = mutableListOf<Tile>()
        list += Tile(
            Slot.OEM, 0, config.oem, Icons.byKey("oem"), getString(R.string.oem),
            when {
                !Targets.isAvailable(this, config.oem) -> getString(R.string.not_set)
                // The WWC2 OEM home only goes as far as the "安卓盒子" (Android box) launcher; hint at the next step.
                Targets.packageOf(config.oem) == Targets.WWC2_HOME -> getString(R.string.oem_sub_wwc2)
                else -> getString(R.string.oem_sub)
            },
            wide = false,
        )
        config.dock.forEachIndexed { i, item ->
            val icon = Icons.byKey(item.icon)
            val label = Targets.label(this, item.target)
            // When the icon matches the app (e.g. a gear for Settings), use the unified Chinese name; when swapped for an unrelated icon,
            // the name still follows the app, so a tile with a "导航" (Navigation) icon doesn't actually open Settings.
            val iconFits = icon != null && Icons.guess(Targets.packageOf(item.target), label) == icon.key
            // Common apps with English system labels (e.g. "Settings") get the unified Chinese name ("系统设置"); Chinese labels stay as is.
            val chinese = label?.takeUnless { l -> l.any { it.code in 0x4E00..0x9FFF } }
                ?.let { Icons.byKey(Icons.guess(Targets.packageOf(item.target), it))?.name }
            val title = (if (iconFits) icon?.name else chinese ?: label) ?: icon?.name ?: item.target.substringBefore('/')
            // A second line only when it helps (not installed); no "文件 / Files" style repetition.
            val sub = if (!Targets.isAvailable(this, item.target)) "未安装 · 长按更换" else ""
            list += Tile(Slot.DOCK, i, item.target, icon, title, sub, wide = false)
        }
        list += Tile(Slot.APPS, 0, null, Icons.byKey("apps"), getString(R.string.all_apps), "", wide = false)
        // "添加" (Add) is always last. Launcher settings is the small icon in the clock card, not an app tile.
        list += Tile(Slot.ADD, 0, null, null, getString(R.string.add), "", wide = false)
        return list
    }

    private fun rebuild() {
        updateCarplay()
        if (tileHeight == 0) return
        tiles.removeAllViews()
        val gap = dp(16)
        val list = buildTiles()
        // App tiles are square (width = height, which follows the screen); more tiles scroll horizontally.
        tileWidth = tileHeight
        list.forEachIndexed { i, tile ->
            // Every tile in the row is square, "添加" (Add) included (it stays a quiet dashed outline).
            val lp = LinearLayout.LayoutParams(tileWidth, tileHeight)
            if (i > 0) lp.marginStart = gap
            tiles.addView(makeTileView(tile), lp)
        }
    }

    /** Tile icon: the unified icon (a white glyph, no colored square) first, otherwise the app's own icon. */
    private fun iconFor(tile: Tile): Drawable? =
        tile.icon?.let { Icons.glyph(this, it, Ui.TEXT) } ?: Targets.icon(this, tile.spec)

    private fun makeTileView(tile: Tile): View {
        val fixed = tile.slot == Slot.APPS
        val available = tile.slot == Slot.ADD || fixed || Targets.isAvailable(this, tile.spec)
        val icon = if (tile.slot == Slot.ADD) null else iconFor(tile)

        val card = FrameLayout(this)
        card.background = when {
            // Plus tile: no glass, just a dashed outline over a very faint dark fill (readable on busy wallpapers).
            // Apps that aren't installed still show, with faded content.
            tile.slot == Slot.ADD -> StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Ui.rounded(this@MainActivity, Ui.PRESSED, 22))
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(0x2E000000)
                    setStroke(dp(1).coerceAtLeast(2), Ui.TEXT_DIM, dp(6).toFloat(), dp(5).toFloat())
                })
            }
            // Bento card: raised normally, flush when pressed (as if pushed in).
            else -> StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Ui.bento(this@MainActivity, 22, BentoDrawable.Style.RESTING))
                addState(intArrayOf(), Ui.bento(this@MainActivity, 22, BentoDrawable.Style.LIFTED))
            }
        }
        card.foreground = Ui.focusRing(this, 22)
        card.isFocusable = true
        card.isClickable = true
        card.contentDescription = tile.title
        val pad = dp(18)

        if (tile.slot == Slot.ADD) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(Ui.glyph(this, R.drawable.ui_add, 28, Ui.TEXT), LinearLayout.LayoutParams(dp(28), dp(28)))
            column.addView(Ui.text(this, tile.title, 13f, Ui.TEXT).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            card.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        } else {
            if (icon != null) {
                // Every app tile's icon is the same size and position: unified white glyphs and apps' own icons alike.
                val size = (tileHeight * 0.2f).toInt().coerceIn(dp(28), dp(36))
                card.addView(ImageView(this).apply {
                    setImageDrawable(icon)
                    if (!available) alpha = 0.4f
                }, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                    setMargins(pad, pad, 0, 0)
                })
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this, tile.title, 17f, if (available) Ui.TEXT else Ui.TEXT_DIM, Ui.Font.MEDIUM))
            if (tile.sub.isNotEmpty()) {
                texts.addView(Ui.text(this, tile.sub, 12f, if (available) Ui.TEXT_DIM else Ui.TEXT_FAINT).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
            card.addView(texts, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(pad, 0, pad, pad)
            })
        }

        card.setOnClickListener { onTileClick(tile, available) }
        card.setOnLongClickListener { v -> onTileLongClick(tile, v); true }
        pressAnimation(card, 1.05f)
        return card
    }

    /**
     * Swiping left/right on empty areas of the home screen (clock, name, wallpaper) also scrolls the bottom tile list: in the car you don't have to hit that narrow strip.
     * Buttons and tiles handle their own touches; touches landing on empty areas are forwarded to the tile list (coordinates converted into the list).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun forwardSwipesToTiles() {
        val rootPos = IntArray(2)
        val scrollPos = IntArray(2)
        val cardPos = IntArray(2)
        var downX = 0f
        var downY = 0f
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        root.setOnTouchListener { _, event ->
            // A tap (no drag) on the GPS card without location permission opens the GPS settings page. The card itself stays
            // non-clickable so swipes on it still scroll the tiles.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                MotionEvent.ACTION_UP -> if (gpsNeedsPermission &&
                    Math.abs(event.rawX - downX) < slop && Math.abs(event.rawY - downY) < slop) {
                    gpsCard.getLocationOnScreen(cardPos)
                    if (event.rawX >= cardPos[0] && event.rawX < cardPos[0] + gpsCard.width &&
                        event.rawY >= cardPos[1] && event.rawY < cardPos[1] + gpsCard.height) openGpsSettings()
                }
            }
            root.getLocationInWindow(rootPos)
            tileScroll.getLocationInWindow(scrollPos)
            val forwarded = MotionEvent.obtain(event)
            forwarded.offsetLocation((rootPos[0] - scrollPos[0]).toFloat(), (rootPos[1] - scrollPos[1]).toFloat())
            tileScroll.onTouchEvent(forwarded)
            forwarded.recycle()
            true
        }
    }

    /** Keep the selected tile (scaled up 5% with an outline) fully on screen, with margins on both sides. */
    private fun keepVisible(v: View) {
        tileScroll.post {
            val margin = dp(40)
            val left = v.left - tileScroll.scrollX
            val right = v.right - tileScroll.scrollX
            when {
                left < margin -> tileScroll.smoothScrollTo((v.left - margin).coerceAtLeast(0), 0)
                right > tileScroll.width - margin -> tileScroll.smoothScrollTo(v.right + margin - tileScroll.width, 0)
            }
        }
    }

    private fun onTileClick(tile: Tile, available: Boolean) {
        when {
            tile.slot == Slot.ADD -> pick(REQ_ADD)
            tile.slot == Slot.APPS -> startActivity(Intent(this, LibraryActivity::class.java))
            !available -> pick(requestCodeFor(tile))
            !Targets.launch(this, tile.spec) -> toast("无法打开：${tile.spec}")
        }
    }

    private fun onTileLongClick(tile: Tile, view: View) {
        if (tile.slot == Slot.ADD) { pick(REQ_ADD); return }
        if (tile.slot == Slot.APPS) { onTileClick(tile, true); return }
        val items = mutableListOf(MenuItem("更换应用", R.drawable.ui_swap) { pick(requestCodeFor(tile)) })
        if (tile.slot == Slot.DOCK) {
            items += MenuItem("更换图标", R.drawable.ui_palette) { pickIcon(tile) }
            if (tile.index > 0) items += MenuItem("向左移", R.drawable.ui_left) { config.moveInDock(tile.index, -1); rebuild() }
            if (tile.index < config.dock.size - 1) {
                items += MenuItem("向右移", R.drawable.ui_right) { config.moveInDock(tile.index, 1); rebuild() }
            }
        }
        Targets.packageOf(tile.spec)?.let { pkg -> items += MenuItem("应用信息", R.drawable.ui_info) { openAppInfo(pkg) } }
        if (tile.slot == Slot.DOCK) {
            items += MenuItem("从桌面移除", R.drawable.ui_delete, danger = true) {
                config.removeFromDock(tile.index); rebuild()
                overlay.snackbar("已移除「${tile.title}」")
            }
        }
        Menus.menu(overlay, this, tile.title, items, anchor = view, titleIcon = iconFor(tile))
    }

    private fun pickIcon(tile: Tile) {
        Menus.iconPicker(overlay, this, tile.icon?.key, Targets.icon(this, tile.spec)) { key ->
            config.setIcon(tile.index, key)
            rebuild()
        }
    }

    private fun requestCodeFor(tile: Tile) = when (tile.slot) {
        Slot.CARPLAY -> REQ_CARPLAY
        Slot.OEM -> REQ_OEM
        Slot.DOCK -> REQ_REPLACE_BASE + tile.index
        Slot.ADD, Slot.APPS -> REQ_ADD
    }

    private fun pick(requestCode: Int) {
        startActivityForResult(
            Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_PICK, true),
            requestCode,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val spec = data?.getStringExtra(LibraryActivity.EXTRA_SPEC)
        if (resultCode != RESULT_OK || spec == null) return
        when {
            requestCode == REQ_CARPLAY -> config.setCarplay(spec)
            requestCode == REQ_OEM -> config.setOem(spec)
            requestCode == REQ_ADD -> config.addToDock(spec)
            requestCode >= REQ_REPLACE_BASE && requestCode - REQ_REPLACE_BASE < config.dock.size ->
                config.replaceTarget(requestCode - REQ_REPLACE_BASE, spec)
        }
        rebuild()
    }

    private fun openAppInfo(pkg: String) {
        launchOrToast(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
    }

    private fun launchOrToast(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("车机不支持这个页面")
        }
    }

    private fun toast(msg: String) = overlay.snackbar(msg)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Fallback when the boot count can't be read: per-process record. */
        private var bootHandled = false
        private const val KEY_AUTO_BOOT = "auto_carplay_boot"

        private const val REQ_CARPLAY = 1
        private const val REQ_OEM = 2
        private const val REQ_ADD = 3
        private const val REQ_REPLACE_BASE = 100
    }
}
