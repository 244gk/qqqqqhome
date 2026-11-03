package com.x0finch.q5home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.x0finch.q5home.ui.BentoDrawable
import com.x0finch.q5home.ui.Glass
import com.x0finch.q5home.ui.Menus
import com.x0finch.q5home.ui.Overlay
import com.x0finch.q5home.ui.Toggle
import com.x0finch.q5home.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Full-page settings in the liquid-glass Bento style: a floating glass navigation column on the left, liquid-glass cards on the
 * right ([tile]: icon / title / big value / description / bottom button, toggles top-right and the whole tile clickable;
 * [tilesRow] equal-height rows; [grid]; [card] for lists, QR codes and the thumbnail strip).
 * No back button: swipe right from the left edge or press HOME. Switching sections cross-fades ([switchSection]).
 */
class SettingsActivity : Activity() {

    private lateinit var config: Config
    private lateinit var root: FrameLayout
    private lateinit var overlay: Overlay
    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var section = SECTION_WALLPAPER
    private var checking = false
    /** Download progress 0–100; null means not downloading. */
    private var downloadProgress: Int? = null
    // Head-unit survey
    private var share: LocalShare? = null
    // GPS & 4G
    private val signal by lazy { SignalCheck(this) }
    private val signalViews = mutableMapOf<String, TextView>()
    private var probeResult: String? = null
    private var probing = false
    private val signalTicker = object : Runnable {
        override fun run() {
            refreshSignal()
            if (section == SECTION_SIGNAL && signal.listening) root.postDelayed(this, 2000)
        }
    }
    // Left-edge swipe back
    private var edgeDownX = -1f
    private var edgeDownY = 0f
    private var switching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this).also { it.reload() }
        // Reports are no longer uploaded: forget any GitHub token an older version stored on the head unit.
        getSharedPreferences("secrets", MODE_PRIVATE).edit().remove("github_token").remove("recon_issue_url").apply()
        Ui.applyTheme(this)
        section = savedInstanceState?.getString(EXTRA_SECTION) ?: intent.getStringExtra(EXTRA_SECTION) ?: SECTION_WALLPAPER
        root = Ui.pageRoot(this)
        overlay = Overlay(root)

        val page = LinearLayout(this).apply {
            setPadding(dp(24), dp(20), dp(24), 0)
        }
        nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(nav, LinearLayout.LayoutParams(dp(208), -1).apply { bottomMargin = dp(20) })
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Leave room on both sides for the card shadows.
            setPadding(dp(14), dp(6), dp(14), dp(40))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14) })
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        Ui.allowShadows(page, scroll, content, nav)
        Glass.followScroll(scroll)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        config.reload()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_SECTION, section)
    }

    override fun onStop() {
        super.onStop()
        stopShare()
        stopSignal()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SIGNAL_PERMS && section == SECTION_SIGNAL) {
            if (!signal.hasLocationPermission()) overlay.snackbar("没有位置权限就看不到卫星；可以在安卓设置 → 应用 → q5home 里打开")
            stopSignal()
            render()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.isShowing) overlay.dismiss() else super.onBackPressed()
    }

    /**
     * Swipe right starting at the left edge returns to the launcher (there is no back button). Checked before the children see the
     * touch, so it works over the navigation column too; a normal tap near the edge still goes through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDownX = if (ev.x <= dp(EDGE_DP)) ev.x else -1f
                edgeDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (edgeDownX >= 0f) {
                val dx = ev.x - edgeDownX
                val dy = abs(ev.y - edgeDownY)
                if (dx > dp(SWIPE_DP) && dx > dy * 2) {
                    edgeDownX = -1f
                    if (!overlay.isShowing) {
                        // Cancel whatever the children were tracking, then leave.
                        super.dispatchTouchEvent(MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL })
                        finish()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeDownX = -1f
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun render() {
        renderNav()
        content.removeAllViews()
        when (section) {
            SECTION_CARPLAY -> renderCarPlay()
            SECTION_ADVANCED -> renderAdvanced()
            SECTION_UPDATE -> renderUpdate()
            SECTION_RECON -> renderRecon()
            SECTION_SIGNAL -> renderSignal()
            else -> renderWallpaper()
        }
    }

    /**
     * Switch sections with a short cross-fade (hides the rebuild on the slow head unit): fade the content out, rebuild while it is
     * invisible, then fade it in while moving up slightly.
     */
    internal fun switchSection(key: String) {
        if (key == section || switching) return
        if (section == SECTION_RECON && key != SECTION_RECON) stopShare()
        if (section == SECTION_SIGNAL && key != SECTION_SIGNAL) stopSignal()
        switching = true
        content.animate().cancel()
        content.animate().alpha(0f).setDuration(80).withEndAction {
            section = key
            scroll.scrollTo(0, 0)
            render()
            content.translationY = dp(12).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { switching = false }
                .start()
        }.start()
    }

    // ---- Left navigation (floating glass column) ----

    private fun renderNav() {
        // Remember where the category list was scrolled to, and scroll back after rebuilding.
        val oldScroll = ((nav.getChildAt(0) as? ViewGroup)?.getChildAt(1) as? ScrollView)?.scrollY ?: 0
        nav.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bento(this@SettingsActivity, 24, BentoDrawable.Style.LIFTED)
            setPadding(dp(8), dp(16), dp(8), dp(12))
        }
        card.addView(Ui.text(this, "设置", 26f, Ui.TEXT, Ui.Font.LIGHT),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12); marginStart = dp(12) })
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        navItem(items, SECTION_WALLPAPER, "壁纸", R.drawable.ui_wallpaper)
        navItem(items, SECTION_CARPLAY, "CarPlay 与原车", R.drawable.glyph_carplay)
        val badge = if (Updates(this).hasUpdate) "  ●" else ""
        navItem(items, SECTION_UPDATE, "版本更新$badge", R.drawable.ui_update)
        navItem(items, SECTION_SIGNAL, "GPS 与 4G", R.drawable.ui_signal)
        navItem(items, SECTION_RECON, "车机摸底", R.drawable.ui_info)
        navItem(items, SECTION_ADVANCED, "高级", R.drawable.ui_tune)
        val itemScroll = ScrollView(this).apply {
            Glass.followScroll(this)
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(items)
        }
        card.addView(itemScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(Ui.text(this, "左边缘右滑 / HOME 返回桌面", 11f, Ui.TEXT_FAINT),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); marginStart = dp(12) })
        nav.addView(card, LinearLayout.LayoutParams(-1, -1))
        itemScroll.post { itemScroll.scrollTo(0, oldScroll) }
    }

    private fun navItem(parent: LinearLayout, key: String, label: String, icon: Int) {
        val selected = key == section
        val color = if (selected) Ui.ACCENT else Ui.TEXT_DIM
        parent.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // 48dp: all six categories fit in the navigation card on a 480dp-tall screen without scrolling.
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.rowBackground(this@SettingsActivity, 16, selected)
            isFocusable = true
            isClickable = true
            addView(Ui.glyph(this@SettingsActivity, icon, 20, color).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
            })
            addView(Ui.text(this@SettingsActivity, label, 16f, if (selected) Ui.TEXT else Ui.TEXT_DIM,
                if (selected) Ui.Font.MEDIUM else Ui.Font.REGULAR))
            setOnClickListener { switchSection(key) }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
    }

    // ---- Wallpaper ----

    private fun renderWallpaper() {
        header("壁纸", "当前：${Wallpapers.label(this, config.wallpaper, config.wallpaperInterval)}")

        // Big card: thumbnails of every wallpaper, scroll horizontally to choose.
        val all = Wallpapers.available(this)
        val strip = LinearLayout(this).apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
        strip.addView(thumb(Wallpapers.DAILY, "自动轮换", null, collage = all.take(3)))
        all.forEach { strip.addView(thumb(it.key, it.name, it)) }
        strip.addView(thumb(Wallpapers.NONE, "纯黑", null))
        card(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }, title = "选择壁纸")

        // Rotation interval and "by sunrise / sunset" are one topic (how it changes automatically): one card, two columns.
        tilesRow(rotationCard() to 1f)

        val store = OnlineWallpapers(this)
        val photos = store.list()
        val bytes = store.dir.listFiles()?.sumOf { it.length() } ?: 0L
        tilesRow(
            toggleTile(R.drawable.ui_wifi, "在线壁纸", "连 Wi-Fi 每天最多下载 ${OnlineWallpapers.PER_RUN} 张，加入自动轮换",
                config.onlineWallpapers) { on ->
                config.setOnlineWallpapers(on)
                if (on) WallpaperJob.schedule(this) else WallpaperJob.cancel(this)
            } to 1f,
            storageTile(photos.size, bytes) to 1f,
            tile(R.drawable.ui_refresh, "立即更新", sub = "下次连上 Wi-Fi 时马上在后台下载",
                action = Ui.pill(this, "更新") {
                    if (!config.onlineWallpapers) config.setOnlineWallpapers(true)
                    WallpaperJob.schedule(this)
                    WallpaperJob.runSoon(this)
                    overlay.snackbar("已安排：连上 Wi-Fi 后在后台下载")
                    render()
                }).first to 1f,
        )
    }

    /** Wallpaper thumbnail: the one in use gets an accent outline and a check mark. */
    private fun thumb(key: String, name: String, wallpaper: Wallpapers.Wallpaper?,
                      collage: List<Wallpapers.Wallpaper> = emptyList()): View {
        val selected = key == config.wallpaper
        val w = dp(176); val h = w * 3 / 8
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = Ui.rowBackground(this@SettingsActivity, 18)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                config.setWallpaper(key)
                Ui.refreshPageWallpaper(this@SettingsActivity, root)
                render()
            }
        }
        val frame = FrameLayout(this).apply {
            background = Ui.rounded(this@SettingsActivity, Color.BLACK, 14, Ui.DIVIDER, 1)
            clipToOutline = true
            foreground = if (selected) Ui.rounded(this@SettingsActivity, Color.TRANSPARENT, 14, Ui.ACCENT, 3) else null
        }
        wallpaper?.let { wp ->
            thumbBitmap(wp, w)?.let { bmp ->
                frame.addView(ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
        // Auto-rotate: several wallpapers tiled together with a rotate icon in the middle, to set it apart from single wallpapers.
        if (collage.isNotEmpty()) {
            val strips = LinearLayout(this)
            collage.forEach { wp ->
                strips.addView(ImageView(this).apply {
                    thumbBitmap(wp, w / 2)?.let { setImageBitmap(it) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(2) })
            }
            frame.addView(strips, FrameLayout.LayoutParams(-1, -1))
            frame.addView(View(this).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(Ui.glyph(this, R.drawable.ui_refresh, 22).apply {
                background = Ui.rounded(this@SettingsActivity, 0x99000000.toInt(), 20)
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        if (selected) {
            frame.addView(Ui.glyph(this, R.drawable.ui_check, 16, Ui.ON_ACCENT).apply {
                background = Ui.rounded(this@SettingsActivity, Ui.ACCENT, 11)
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.END or Gravity.TOP).apply {
                setMargins(0, dp(8), dp(8), 0)
            })
        }
        cell.addView(frame, LinearLayout.LayoutParams(w, h))
        cell.addView(Ui.text(this, name, 13f, if (selected) Ui.ACCENT else Ui.TEXT_DIM,
            if (selected) Ui.Font.MEDIUM else Ui.Font.REGULAR),
            LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(8); bottomMargin = dp(2) })
        cell.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        return cell
    }

    /** Decoded thumbnails are cached, so refreshing the page (every tap re-renders it) doesn't decode every wallpaper again. */
    private fun thumbBitmap(wp: Wallpapers.Wallpaper, width: Int): Bitmap? {
        val key = "${wp.key}@$width"
        thumbCache.get(key)?.let { return it }
        return Wallpapers.decode(this, wp, width)?.also { thumbCache.put(key, it) }
    }

    /**
     * Auto-rotation card: left column = state in big type ("每小时" or "未开启") with the interval pills underneath (the chosen one is
     * highlighted); right column = "by sunrise / sunset" with its toggle. One card, so no half-empty tile next to it.
     */
    private fun rotationCard(): View {
        val auto = config.wallpaper == Wallpapers.DAILY
        val chips = LinearLayout(this)
        Wallpapers.INTERVALS.forEachIndexed { i, (minutes, name) ->
            val selected = auto && minutes == config.wallpaperInterval
            chips.addView(Ui.pill(this, name, if (selected) Ui.PillStyle.PRIMARY else Ui.PillStyle.SECONDARY) {
                config.setWallpaperInterval(minutes)
                if (!auto) config.setWallpaper(Wallpapers.DAILY)
                Ui.refreshPageWallpaper(this, root)
                render()
            }.apply { if (i > 0) (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
        }
        val current = Wallpapers.INTERVALS.firstOrNull { it.first == config.wallpaperInterval }?.second
        val left = tile(R.drawable.ui_refresh, "自动轮换", value = if (auto) current else "未开启",
            sub = if (auto) "到点换下一张（内置 + 在线下载的）" else "现在固定用一张；点一个频率就开始轮换",
            action = chips).first.apply { background = null; foreground = null }
        val row = LinearLayout(this).apply {
            background = Ui.bento(this@SettingsActivity, 22)
            isBaselineAligned = false
        }
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1.7f))
        row.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) },
            LinearLayout.LayoutParams(1, -1).apply { topMargin = dp(18); bottomMargin = dp(18) })
        row.addView(sunColumn(), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        return row
    }

    /** Right column of the rotation card: split day / night wallpapers by sunrise and sunset (whole column clickable, toggle top-right). */
    private fun sunColumn(): View {
        val loc = Sun.location(this)
        val times = loc?.let { Sun.times(it.first, it.second, System.currentTimeMillis()) }
        val fmt = SimpleDateFormat("H:mm", Locale.CHINA)
        val sub = when {
            times != null -> "今天白天 ${fmt.format(Date(times.first))} – ${fmt.format(Date(times.second))}"
            loc != null -> "今天没有日出 / 日落（极昼或极夜）"
            else -> "还没定位，先按 7–19 点算白天"
        }
        val toggle = Toggle(this).apply { setChecked(config.wallpaperSun, animate = false) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Ui.rowBackground(this@SettingsActivity, 16)
            isFocusable = true
            isClickable = true
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(Ui.glyph(this, R.drawable.ui_wallpaper, 22, Ui.TEXT_DIM))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(toggle)
        column.addView(top, LinearLayout.LayoutParams(-1, -2))
        column.addView(Ui.text(this, "按日出日落分白天 / 夜晚", 15f, Ui.TEXT, Ui.Font.MEDIUM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        column.addView(Ui.text(this, sub, 13f, Ui.TEXT_DIM, singleLine = false).apply { maxLines = 3 },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        column.setOnClickListener {
            toggle.setChecked(!toggle.checked)
            val on = toggle.checked
            config.setWallpaperSun(on)
            if (on && config.wallpaper != Wallpapers.DAILY) config.setWallpaper(Wallpapers.DAILY)
            Ui.refreshPageWallpaper(this, root)
            render()
        }
        return column
    }

    private fun storageTile(count: Int, bytes: Long): View {
        val mb = bytes / 1024.0 / 1024.0
        val maxMb = OnlineWallpapers.MAX_BYTES / 1024.0 / 1024.0
        val clear = Ui.pill(this, "清除", Ui.PillStyle.DANGER) {
            Menus.confirm(overlay, this, "清除在线壁纸", "删除已下载的 $count 张在线壁纸？内置壁纸不受影响。", "清除", danger = true) {
                OnlineWallpapers(this).clear()
                if (config.wallpaper.startsWith(OnlineWallpapers.KEY_PREFIX)) config.setWallpaper(Wallpapers.DAILY)
                Ui.refreshPageWallpaper(this, root)
                overlay.snackbar("已清除")
                render()
            }
        }
        clear.isEnabled = count > 0
        clear.alpha = if (count > 0) 1f else 0.4f
        val (t, _) = tile(R.drawable.ui_delete, "已下载", value = "$count 张",
            sub = String.format(Locale.US, "占用 %.1f MB / 上限 %.0f MB、%d 张", mb, maxMb, OnlineWallpapers.MAX_COUNT),
            action = clear)
        // Usage bar under the description.
        t.addView(Meter(this, (bytes.toFloat() / OnlineWallpapers.MAX_BYTES).coerceIn(0f, 1f)), t.childCount - 2,
            LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        return t
    }

    // ---- CarPlay & OEM ----

    private fun renderCarPlay() {
        header("CarPlay 与原车", "首页两个固定入口打开哪个应用")
        tilesRow(
            appTile("carplay", "CarPlay", config.carplay, "没找到 ZLINK / DiPlay，点更换手动选") { pick(REQ_CARPLAY) } to 1f,
            appTile("oem", "原车", config.oem, "摸底查到切换方式后用 config.json 配置") { pick(REQ_OEM) } to 1f,
        )
        tilesRow(
            toggleTile(R.drawable.ui_power, "开机自动进入 CarPlay", "开机后第一次回到桌面时自动打开", config.autoCarplay) { on ->
                config.setAutoCarplay(on)
            } to 1f,
        )
        hint("如果原车画面是用广播切换的，只能通过 config.json 的 oem 字段配置（写法见 README）。")
    }

    /** A target app tile: glyph, kind, big app name, package; "更换" (Change) button at the bottom. */
    private fun appTile(iconKey: String, kind: String, spec: String?, emptyHint: String, onChange: () -> Unit): View {
        val available = Targets.isAvailable(this, spec)
        val label = if (available) Targets.label(this, spec) else null
        val sub = when {
            spec == null -> emptyHint
            !available -> "找不到：$spec"
            else -> spec.substringBefore('/')
        }
        val (t, _) = tile(null, kind, value = label ?: "未设置", sub = sub,
            action = Ui.pill(this, "更换") { onChange() }, subColor = if (spec != null && !available) Ui.DANGER else null)
        Icons.byKey(iconKey)?.let { icon ->
            t.addView(ImageView(this).apply { setImageDrawable(Icons.glyph(this@SettingsActivity, icon, Ui.TEXT)) }, 0,
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(10) })
        }
        return t
    }

    private fun pick(requestCode: Int) {
        startActivityForResult(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_PICK, true), requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) {
            render()
            overlay.snackbar(if (HomeRole.isDefault(this)) "q5home 已设为默认桌面" else "没有更改默认桌面")
            return
        }
        val spec = data?.getStringExtra(LibraryActivity.EXTRA_SPEC)
        if (resultCode != RESULT_OK || spec == null) return
        when (requestCode) {
            REQ_CARPLAY -> config.setCarplay(spec)
            REQ_OEM -> config.setOem(spec)
        }
        overlay.snackbar("已更换为 ${Targets.label(this, spec) ?: spec}")
        render()
    }

    // ---- Version updates ----

    private fun renderUpdate() {
        val updates = Updates(this)
        header("版本更新", "从 GitHub Releases（${Updates.REPO}）检查新版本")
        // On entering the page, check automatically if more than 10 minutes have passed since the last check.
        if (!checking && System.currentTimeMillis() - updates.lastCheckedAt > 10 * 60_000) checkNow()

        val latest = updates.latest
        val checkedAt = updates.lastCheckedAt.takeIf { it > 0 }
            ?.let { "上次检查 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        val status = when {
            checking -> "正在检查…"
            updates.lastError != null -> "出错了"
            checkedAt == null -> "还没检查过"
            latest == null -> "还没有发布版本"
            updates.hasUpdate -> "有新版本"
            else -> "已是最新"
        }
        tilesRow(
            tile(R.drawable.ui_info, "当前版本", value = updates.currentVersionName).first to 1f,
            tile(R.drawable.ui_refresh, "检查更新", value = status,
                sub = updates.lastError ?: checkedAt ?: "连上网络后点检查", subColor = if (updates.lastError != null) Ui.DANGER else null,
                action = Ui.pill(this, if (checking) "检查中…" else "检查更新") { if (!checking) checkNow() }).first to 1f,
            toggleTile(R.drawable.ui_power, "自动检查", "每天一次，有新版本时首页提示，不会自动安装", updates.autoCheck) { on ->
                updates.autoCheck = on
                if (on) UpdateJob.schedule(this) else UpdateJob.cancel(this)
            } to 1f,
        )

        if (latest != null && updates.hasUpdate) {
            val notes = latest.notes.ifBlank { "（这个版本没有写更新说明）" }
            val mb = String.format(Locale.US, "%.1f MB", latest.apkSize / 1024.0 / 1024.0)
            val progress = downloadProgress
            val button = if (progress != null) {
                Ui.pill(this, "下载中 $progress%") {}.apply { isEnabled = false }
            } else {
                Ui.pill(this, "下载并安装（$mb）", Ui.PillStyle.PRIMARY) { downloadAndInstall(latest) }
            }
            tilesRow(tile(R.drawable.ui_download, "新版本", value = latest.versionName,
                sub = notes, note = "发布于 ${latest.publishedAt.take(10)}", subLines = 8, action = button).first to 1f)
        }
        hint("安装时安卓会弹出系统确认；第一次需要允许 q5home「安装未知应用」。所有版本用同一签名，设置会保留。")
    }

    private fun requestDefaultHome() {
        runCatching { startActivityForResult(HomeRole.requestIntent(this), REQ_HOME) }
            .onFailure { openHomeSettings() }
    }

    private fun openHomeSettings() {
        runCatching { startActivity(HomeRole.homeSettingsIntent(this)) }
            .onFailure { overlay.snackbar("车机不支持这个页面") }
    }

    private fun checkNow() {
        checking = true
        Thread {
            runCatching { Updates(this).check() }
            runOnUiThread {
                checking = false
                if (section == SECTION_UPDATE) render()
            }
        }.start()
    }

    private fun downloadAndInstall(release: Updates.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Menus.confirm(overlay, this, "需要允许安装更新",
                "第一次更新需要在系统设置里允许 q5home「安装未知应用」。打开开关后返回，再点一次下载并安装。", "去设置") {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.onFailure { overlay.snackbar("车机不支持这个页面，可以用电脑执行 README 里的 adb 命令") }
            }
            return
        }
        downloadProgress = 0
        render()
        Thread {
            val updates = Updates(this)
            val error = try {
                val apk = updates.download(release) { p ->
                    runOnUiThread { downloadProgress = p; if (section == SECTION_UPDATE) render() }
                }
                updates.verify(apk) ?: run { updates.install(apk); null }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            runOnUiThread {
                downloadProgress = null
                render()
                overlay.snackbar(error ?: "下载完成，请在系统弹窗里确认安装")
            }
        }.start()
    }

    // ---- GPS & 4G ----

    private fun renderSignal() {
        header("GPS 与 4G", "只读：看能不能收到卫星、有没有 4G 信号，不改车机设置")
        val granted = signal.hasLocationPermission() && signal.hasPhonePermission()
        if (!granted) {
            tilesRow(tile(R.drawable.ui_signal, "开始检查",
                sub = "第一次会弹系统授权：位置信息（看卫星和定位）、电话状态（看 SIM 卡和网络类型）",
                action = Ui.pill(this, "开始检查", Ui.PillStyle.PRIMARY) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE), REQ_SIGNAL_PERMS)
                }).first to 1f)
        }
        // Satellite callbacks arrive about once per second: only record the data; signalTicker refreshes the UI every 2 seconds.
        // The head-unit CPU is weak, don't redraw constantly.
        if (!signal.listening) signal.start {}
        root.removeCallbacks(signalTicker)
        root.postDelayed(signalTicker, 2000)

        signalViews.clear()
        tilesRow(toggleTile(R.drawable.ui_home, "首页显示车速", "车速、方向、海拔；有新鲜定位时才显示，进隧道 15 秒后自动隐藏",
            config.homeGps) { on -> config.setHomeGps(on) } to 1f)
        card(*signal.gpsLines().map { (title, value) -> liveInfo(title, value) }.toTypedArray(), title = "GPS")
        hint("GPS 天线一般接在车机背后；在地库、隧道里收不到。车机熄火久了第一次定位会慢一些。")

        card(*signal.cellLines().map { (title, value) -> liveInfo(title, value) }.toTypedArray(),
            row(R.drawable.ui_refresh, "4G 上网测试", probeResult ?: "只走移动网络访问一次 Google 测试地址，流量很小；不受 Wi-Fi / 手机热点影响",
                Ui.pill(this, if (probing) "测试中…" else "测试") { startProbe() }.apply {
                    isEnabled = !probing
                    alpha = if (probing) 0.5f else 1f
                }), title = "4G")
        hint("「现在上网走」是 Wi-Fi 时，平时上网用的是热点，4G 只是备用；上网测试能单独看 4G 通不通。")
    }

    /** A row with an updatable value: the value TextView is kept in [signalViews], and [refreshSignal] only changes the text without rebuilding the UI (so knob focus doesn't jump). */
    private fun liveInfo(title: String, value: String): View {
        val (row, valueView) = infoRow(title, value)
        signalViews[title] = valueView
        return row
    }

    private fun refreshSignal() {
        if (section != SECTION_SIGNAL) return
        (signal.gpsLines() + signal.cellLines()).forEach { (title, value) ->
            signalViews[title]?.let { if (it.text != value) it.text = value }
        }
    }

    private fun startProbe() {
        if (probing) return
        probing = true
        probeResult = "正在连接移动网络…"
        render()
        signal.probeCellular { msg ->
            runOnUiThread {
                probing = false
                probeResult = msg
                if (section == SECTION_SIGNAL) render()
            }
        }
    }

    private fun stopSignal() {
        root.removeCallbacks(signalTicker)
        signal.stop()
    }

    // ---- Head-unit survey ----

    private fun renderRecon() {
        header("车机摸底", "只读：后台只听不发，扫码拿一份只有新内容的报告")
        // Scanning the QR code and copying the report is the only way to share it (nothing is uploaded), so that card comes first.
        card(shareRow())
        val sentAt = reconPrefs().getLong(KEY_EVENTS_COPIED, 0L).takeIf { it > 0 }
        val events = EventLog.lines(this, sentAt)
        val eventStatus = when {
            events.isEmpty() -> "后台一直在听（只听不发）。在车上操作一轮后扫码，事件记录就在报告里"
            else -> "${if (sentAt != null) "上次复制报告之后" else "已"}记录 ${events.size} 条，从 ${events.first().substring(5, 16)} 起，都会写进报告"
        }
        val eventTile = tile(R.drawable.ui_info, "事件记录", sub = eventStatus, subLines = 3,
            action = Ui.pill(this, "清空") {
                Menus.confirm(overlay, this, "清空事件记录", "只删 q5home 自己记下的事件，车机不受影响。", "清空", danger = true) {
                    EventLog.clear(this)
                    reconPrefs().edit().remove(KEY_EVENTS_COPIED).apply()
                    render()
                }
            }.apply {
                isEnabled = EventLog.lines(this@SettingsActivity).isNotEmpty()
                alpha = if (isEnabled) 1f else 0.4f
            }).first
        if (EventRecorder.usageAllowed(this)) {
            tilesRow(eventTile to 1f, tile(R.drawable.ui_info, "前台应用记录", value = "已开启",
                sub = "报告里会写出每次切到前台的应用和页面").first to 1f)
        } else {
            tilesRow(eventTile to 1f, tile(R.drawable.ui_info, "前台应用记录", value = "没开启",
                sub = "在系统设置里给 q5home 打开「使用情况访问权限」，才能记下切原车时前台是哪个应用和页面。开不开由你决定",
                subLines = 4,
                action = Ui.pill(this, "打开系统权限页") {
                    runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        .onFailure { overlay.snackbar("车机没有这个设置页面") }
                }).first to 1f)
        }
        hint("只读，不改车机，不上传。报告只列之前没见过的内容；临时网页 15 分钟后自动关闭。")
    }

    private fun shareRow(): View {
        val s = share?.takeUnless { it.closed }
        val ip = DebugAccess.ipAddress(this)
        if (s == null || ip == null) {
            return row(R.drawable.ui_wifi, "手机扫码", if (ip == null) "车机没联网，手机打不开" else reportStatus(),
                Ui.pill(this, "生成二维码") {
                    if (ip == null) { overlay.snackbar("车机没联网"); return@pill }
                    startShare()
                })
        }
        return qrRow(s.url(ip), "手机扫码打开", s.url(ip), Ui.pill(this, "关闭") { stopShare(); render() },
            "手机要和车机在同一网络（比如车机连的就是手机热点）")
    }

    /** QR code on the left, description on the right. */
    private fun qrRow(content: String, title: String, sub: String, trailing: View?, note: String? = null): View {
        val qr = QrCode.encode(content, QrCode.Ecc.M)
        val sizePx = dp(200)
        val image = ImageView(this).apply {
            qr?.let { code ->
                val bmp = code.toBitmap(scale = (sizePx / (code.size + 8)).coerceAtLeast(1))
                setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(this@SettingsActivity, Color.WHITE, 12)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 17f))
        texts.addView(Ui.text(this, sub, 15f, Ui.ACCENT, singleLine = false).apply { setTextIsSelectable(true) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        note?.let {
            texts.addView(Ui.text(this, it, 13f, Ui.TEXT_DIM, singleLine = false),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        return rowWith(null, texts, trailing, leading = image).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
    }

    private fun startShare() {
        stopShare()
        // The whole report was prepared in the background (ReconReport): serving it only reads that file and the event log,
        // so the page shows at once. Copying / downloading moves the start of the next report's event log to now.
        val since = reconPrefs().getLong(KEY_EVENTS_COPIED, 0L).takeIf { it > 0 }
        share = runCatching {
            LocalShare(report = { ReconReport.serve(this, since) }, refreshing = { !ReconReport.isReady(this) }, onCopied = {
                reconPrefs().edit().putLong(KEY_EVENTS_COPIED, System.currentTimeMillis()).apply()
            })
        }.onFailure { overlay.snackbar("开不了临时网页：${it.message}") }.getOrNull()
        if (!ReconReport.isReady(this)) ReconReport.schedule(this, 0)
        // Close automatically when time is up (the web page side also rejects requests after the timeout).
        val started = share
        root.postDelayed({
            if (started != null && share === started) { stopShare(); if (section == SECTION_RECON) render() }
        }, LocalShare.TIMEOUT_MS)
        render()
    }

    private fun stopShare() {
        share?.close()
        share = null
    }

    private fun reconPrefs() = getSharedPreferences("recon", MODE_PRIVATE)

    /** Whether the report prepared in the background is ready. */
    private fun reportStatus(): String {
        val at = ReconReport.readyAt(this)
        return when {
            at > 0 -> "报告已在后台准备好（${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(at))}），扫码直接看"
            ReconReport.state == ReconReport.State.WORKING -> "报告正在后台准备：${ReconReport.progress}"
            ReconReport.state == ReconReport.State.FAILED -> ReconReport.progress
            else -> "车机启动后会自动在后台准备报告"
        }
    }

    /** Storage usage bar. */
    private class Meter(context: android.content.Context, private val fraction: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = Ui.TRACK
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = if (fraction > 0.85f) Ui.DANGER else Ui.ACCENT
            rect.right = width * fraction
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    // ---- Advanced ----

    private fun renderAdvanced() {
        header("高级", "系统设置、默认桌面、调试连接、配置文件")
        val isDefault = HomeRole.isDefault(this)
        val current = HomeRole.currentHomeLabel(this) ?: "没有固定（按 HOME 时会问）"
        grid(2, listOf(
            tile(R.drawable.glyph_settings, "系统设置", sub = "打开安卓自带的设置", action = Ui.pill(this, "打开") {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    .onFailure { overlay.snackbar("车机不支持这个页面") }
            }).first,
            tile(R.drawable.ui_restart, "恢复默认布局", sub = "清空车上的手动设置，回到 config.json 和自动检测", danger = true,
                action = Ui.pill(this, "恢复默认布局", Ui.PillStyle.DANGER) {
                    Menus.confirm(overlay, this, "恢复默认布局",
                        "会清空手动设置的 CarPlay、原车入口、常用应用和壁纸选择。", "恢复", danger = true) {
                        config.reset()
                        Ui.refreshPageWallpaper(this, root)
                        overlay.snackbar("已恢复默认")
                        render()
                    }
                }).first,
            tile(R.drawable.ui_home, "默认桌面", value = if (isDefault) "q5home" else current,
                sub = if (isDefault) "q5home 已经是默认桌面" else "系统会弹出确认框",
                action = if (isDefault) Ui.pill(this, "已是默认") {}.apply { isEnabled = false; alpha = 0.5f }
                else Ui.pill(this, "设为默认", Ui.PillStyle.PRIMARY) { requestDefaultHome() }).first,
            tile(R.drawable.ui_undo, "恢复原厂桌面", sub = "打开系统的「主屏幕应用」设置，选回原厂桌面（原厂桌面一直保留着）",
                action = Ui.pill(this, "去设置") { openHomeSettings() }).first,
        ))
        hint("切换默认桌面后请做一遍实车检查：倒车影像、原车画面切换、侧栏、CarPlay、熄火重启。")

        val ip = DebugAccess.ipAddress(this)
        val usb = if (DebugAccess.usbDebuggingOn(this)) "开" else "关"
        if (DebugAccess.hasWirelessDebugging()) {
            val wifi = if (DebugAccess.wirelessDebuggingOn(this)) "开" else "关"
            card(
                row(R.drawable.ui_wifi, "打开无线调试", "电脑通过 Wi-Fi 连车机用（adb）。打不开直达页面时，会打开开发者选项并高亮这一项",
                    Ui.pill(this, "打开") { overlay.snackbar(DebugAccess.openWirelessDebugging(this)) }),
                info("本机 IP · USB 调试 · 无线调试", "${ip ?: "未联网"}  ·  $usb  ·  $wifi"),
                title = "调试与连接",
            )
            hint("无线调试打开后，点进它的页面能看到 IP 和端口；第一次在电脑上用「使用配对码配对设备」配对：" +
                "adb pair IP:配对端口，再 adb connect IP:端口。也可以在开发者选项 →「快捷设置开发者图块」里把「无线调试」加到下拉菜单。")
        } else {
            // Android 10 and earlier have no wireless debugging: connect once over USB first, then switch to network adb.
            card(
                row(R.drawable.ui_wifi, "打开开发者选项",
                    "这台车机实际是安卓 ${DebugAccess.realAndroidVersion()}，系统里没有「无线调试」，要先用 USB 线连电脑",
                    Ui.pill(this, "打开") { overlay.snackbar(DebugAccess.openWirelessDebugging(this)) }),
                info("本机 IP · USB 调试", "${ip ?: "未联网"}  ·  $usb"),
                title = "调试与连接",
            )
            hint("USB 调试打开后，用 USB 线把车机背后引出的 USB 口（通常标 USB1 / OTG）接到电脑：adb devices 能看到车机后，" +
                "执行 adb tcpip 5555，拔掉线，以后用 adb connect ${ip ?: "车机IP"}:5555 无线连接（车机重启后要再用线做一次）。")
        }

        val dir = getExternalFilesDir(null)?.absolutePath ?: "(不可用)"
        card(
            info("推送配置（adb push）", "$dir/${Config.FILE}"),
            info("当前生效（adb pull）", "$dir/${Config.CURRENT_FILE}"),
            info("CarPlay", config.carplay ?: "未设置"),
            info("原车", config.oem ?: "未设置"),
            info("在线壁纸来源", if (config.unsplashKey != null) "Unsplash API（${config.wallpaperQuery}）" else config.wallpaperFeed),
            title = "配置文件",
        )

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        card(
            info("q5home", "版本 $version"),
            info("致谢", "壁纸 Unsplash · 图标 Material Symbols · 字体 Outfit · 风格参考 CleanYourTV"),
            title = "关于",
        )
    }

    // ---- Shared building blocks ----

    private fun header(title: String, sub: String) {
        content.addView(Ui.text(this, title, 30f, Ui.TEXT, Ui.Font.LIGHT))
        content.addView(Ui.text(this, sub, 14f, Ui.TEXT_DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(18) })
    }

    private fun hint(text: String) {
        content.addView(Ui.text(this, text, 13f, Ui.TEXT_FAINT, singleLine = false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = gap; marginStart = dp(6) })
    }

    private val gap get() = dp(14)

    /** One row of equal-height tiles with [weight]s. */
    private fun tilesRow(vararg tiles: Pair<View, Float>) {
        val row = LinearLayout(this).apply { isBaselineAligned = false }
        tiles.forEachIndexed { i, (v, w) ->
            row.addView(v, LinearLayout.LayoutParams(0, -1, w).apply { if (i > 0) marginStart = gap })
        }
        Ui.allowShadows(row)
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
    }

    /** A grid of tiles with [cols] columns; a short last row leaves the rest empty. */
    private fun grid(cols: Int, views: List<View>) {
        views.chunked(cols).forEach { chunk ->
            tilesRow(*(chunk.map { it to 1f } + List(cols - chunk.size) { View(this) to 1f }).toTypedArray())
        }
    }

    /**
     * A liquid-glass tile: icon top-left (a toggle can sit top-right), title, big value, description, button at the bottom.
     * Returns the tile and its value TextView (for values updated live).
     */
    private fun tile(icon: Int?, title: String, value: String? = null, sub: String? = null, note: String? = null,
                     action: View? = null, corner: View? = null, danger: Boolean = false,
                     onClick: (() -> Unit)? = null, valueSize: Float = 24f, valueLines: Int = 1,
                     subLines: Int = 3, subColor: Int? = null): Pair<LinearLayout, TextView?> {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            minimumHeight = dp(116)
            background = if (onClick != null || corner != null) android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Ui.bento(this@SettingsActivity, 22, BentoDrawable.Style.RESTING))
                addState(intArrayOf(), Ui.bento(this@SettingsActivity, 22))
            } else Ui.bento(this@SettingsActivity, 22)
            foreground = Ui.focusRing(this@SettingsActivity, 22)
        }
        if (icon != null || corner != null) {
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            icon?.let { top.addView(Ui.glyph(this, it, 22, if (danger) Ui.DANGER else Ui.TEXT_DIM)) }
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            corner?.let { top.addView(it) }
            t.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        t.addView(Ui.text(this, title, 14f, if (danger) Ui.DANGER else Ui.TEXT_DIM, Ui.Font.MEDIUM))
        val valueView = value?.let {
            Ui.text(this, it, valueSize, Ui.TEXT, Ui.Font.LIGHT, singleLine = valueLines == 1).apply {
                if (valueLines > 1) maxLines = valueLines
            }.also { v -> t.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }) }
        }
        sub?.let {
            t.addView(Ui.text(this, it, 13f, subColor ?: Ui.TEXT_DIM, singleLine = false).apply { maxLines = subLines },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        note?.let {
            t.addView(Ui.text(this, it, 12f, Ui.TEXT_FAINT), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        // The button sits at the bottom of the tile.
        t.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        action?.let {
            t.addView(it)
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        } ?: t.addView(View(this), LinearLayout.LayoutParams(1, 0))
        if (onClick != null) {
            t.isFocusable = true
            t.isClickable = true
            t.setOnClickListener { onClick() }
        }
        return t to valueView
    }

    /** A tile with a toggle in its top-right corner; tapping anywhere on the tile flips it. */
    private fun toggleTile(icon: Int, title: String, sub: String, checked: Boolean, value: String? = null,
                           onChange: (Boolean) -> Unit): View {
        val toggle = Toggle(this).apply { setChecked(checked, animate = false) }
        return tile(icon, title, value = value, sub = sub, corner = toggle, valueSize = 20f, onClick = {
            toggle.setChecked(!toggle.checked)
            onChange(toggle.checked)
        }).first
    }

    /** A big card holding rows (lists, QR codes, the thumbnail strip), with an optional title. */
    private fun card(vararg rows: View, title: String? = null) {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.bento(this@SettingsActivity, 22)
            setPadding(dp(8), dp(if (title != null) 14 else 8), dp(8), dp(8))
        }
        title?.let {
            g.addView(Ui.text(this, it, 14f, Ui.TEXT_DIM, Ui.Font.MEDIUM),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); bottomMargin = dp(8) })
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) g.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(14), dp(2), dp(14), dp(2)) })
            g.addView(r)
        }
        // Content inside a big card (the sideways thumbnail strip, QR codes, long lists) must not draw outside the card:
        // clip it to a rounded rectangle inside the card. The card's own shadow is drawn by its background, unaffected.
        rows.forEach { roundClip(it, 16) }
        content.addView(g, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
    }

    /** Clips [v]'s content to a rounded rectangle (thumbnails don't show outside the card while scrolling). */
    private fun roundClip(v: View, radiusDp: Int) {
        val r = dp(radiusDp).toFloat()
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        v.clipToOutline = true
        (v as? ViewGroup)?.clipToPadding = true
    }

    private fun row(icon: Int?, title: String, sub: String?, trailing: View?, danger: Boolean = false,
                    onClick: (() -> Unit)? = null): View {
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 17f, if (danger) Ui.DANGER else Ui.TEXT))
        sub?.let {
            texts.addView(Ui.text(this, it, 13f, Ui.TEXT_DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        return rowWith(icon, texts, trailing, onClick = onClick, danger = danger)
    }

    private fun rowWith(icon: Int?, texts: View, trailing: View?, leading: View? = null,
                        onClick: (() -> Unit)? = null, danger: Boolean = false): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lead = leading ?: icon?.let { Ui.glyph(this@SettingsActivity, it, 24, if (danger) Ui.DANGER else Ui.TEXT_DIM) }
            lead?.let {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(16)
            }
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            trailing?.let {
                addView(it)
                (it.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(12)
            }
            if (onClick != null) {
                background = Ui.rowBackground(this@SettingsActivity, 14)
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

    private fun toggleRow(icon: Int, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle(this).apply { setChecked(checked, animate = false) }
        return row(icon, title, sub, toggle) {
            toggle.setChecked(!toggle.checked)
            onChange(toggle.checked)
        }
    }

    private fun info(title: String, value: String): View = infoRow(title, value).first

    /** An info row and its value TextView (for values that update live). */
    private fun infoRow(title: String, value: String): Pair<View, TextView> {
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 13f, Ui.TEXT_DIM))
        val valueView = Ui.text(this, value, 15f, Ui.TEXT, singleLine = false).apply { setTextIsSelectable(true) }
        texts.addView(valueView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        return rowWith(null, texts, null).apply { minimumHeight = dp(56) } to valueView
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_WALLPAPER = "wallpaper"
        const val SECTION_CARPLAY = "carplay"
        const val SECTION_ADVANCED = "advanced"
        const val SECTION_UPDATE = "update"
        const val SECTION_RECON = "recon"
        /** When the owner last copied the recon report: the next report's event log starts there. */
        private const val KEY_EVENTS_COPIED = "events_copied_ms"
        const val SECTION_SIGNAL = "signal"
        private const val REQ_SIGNAL_PERMS = 10
        private const val REQ_CARPLAY = 1
        private const val REQ_OEM = 2
        private const val REQ_HOME = 3
        /** A swipe back must start this close to the left edge (dp)… */
        private const val EDGE_DP = 28
        /** …and travel at least this far to the right (dp). */
        private const val SWIPE_DP = 96

        /** Decoded wallpaper thumbnails, at most about 6 MB. */
        private val thumbCache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
