package com.x0finch.q5home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.x0finch.q5home.ui.Glass
import com.x0finch.q5home.ui.Menus
import com.x0finch.q5home.ui.Overlay
import com.x0finch.q5home.ui.Toggle
import com.x0finch.q5home.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-page settings: categories on the left, content on the right. Same visual style as the home screen, no system dialogs. */
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
    @Volatile private var report: Recon.Report? = null
    private var reconRunning = false
    private var share: LocalShare? = null
    private var submitting = false
    /** Submission progress text (generating report / uploading). */
    private var submitStage = ""
    /** The most recently generated report (the one shown on the phone's QR-code web page). */
    @Volatile private var lastMarkdown: String? = null
    private var submitError: String? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this).also { it.reload() }
        Ui.applyTheme(this)
        section = savedInstanceState?.getString(EXTRA_SECTION) ?: intent.getStringExtra(EXTRA_SECTION) ?: SECTION_WALLPAPER
        root = Ui.pageRoot(this)
        overlay = Overlay(root)

        val page = LinearLayout(this).apply {
            setPadding(dp(32), dp(24), dp(32), 0)
        }
        nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(nav, LinearLayout.LayoutParams(dp(230), -1))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Leave room on both sides for the card shadows.
            setPadding(dp(14), dp(6), dp(14), dp(40))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(18) })
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
            if (!signal.hasLocationPermission()) overlay.snackbar("没有位置权限就看不到卫星；可以在安卓设置 → 应用 → Q5 Home 里打开")
            stopSignal()
            render()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.isShowing) overlay.dismiss() else super.onBackPressed()
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

    // ---- Left-side categories ----

    private fun renderNav() {
        // Remember where the category list was scrolled to, and scroll back after recreation.
        val oldScroll = ((nav.getChildAt(1) as? FrameLayout)?.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        nav.removeAllViews()
        nav.addView(Ui.text(this, "桌面设置", 34f, Ui.TEXT, Ui.Font.LIGHT),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(16); marginStart = dp(6) })
        // Too many categories to fit on short screens: the category list scrolls, "返回桌面" (Back to home) stays pinned at the bottom.
        val items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        navItem(items, SECTION_WALLPAPER, "外观与壁纸", R.drawable.ui_wallpaper)
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
            setFadingEdgeLength(dp(24))
            addView(items)
        }
        // The category list sits on a frosted card so it stays readable over the wallpaper.
        val navCard = FrameLayout(this).apply {
            background = Ui.group(this@SettingsActivity, 20)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(itemScroll, FrameLayout.LayoutParams(-1, -1))
        }
        nav.addView(navCard, LinearLayout.LayoutParams(-1, 0, 1f))
        itemScroll.post { itemScroll.scrollTo(0, oldScroll) }
        nav.addView(Ui.pill(this, "返回桌面") { finish() },
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(10); bottomMargin = dp(20) })
    }

    private fun navItem(parent: LinearLayout, key: String, label: String, icon: Int) {
        val selected = key == section
        val color = if (selected) Ui.ACCENT else Ui.TEXT_DIM
        parent.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // 48dp: all six categories fit in the category card on a 480dp-tall screen without scrolling.
            minimumHeight = dp(48)
            setPadding(dp(14), 0, dp(14), 0)
            background = Ui.rowBackground(this@SettingsActivity, 16, selected)
            isFocusable = true
            isClickable = true
            addView(Ui.glyph(this@SettingsActivity, icon, 22, color).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14)
            })
            addView(Ui.text(this@SettingsActivity, label, 17f, if (selected) Ui.TEXT else Ui.TEXT_DIM,
                if (selected) Ui.Font.MEDIUM else Ui.Font.REGULAR))
            setOnClickListener {
                if (section == SECTION_RECON && key != SECTION_RECON) stopShare()
                if (section == SECTION_SIGNAL && key != SECTION_SIGNAL) stopSignal()
                section = key
                scroll.scrollTo(0, 0)
                render()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
    }

    // ---- Wallpaper ----

    private fun renderWallpaper() {
        header("外观与壁纸", "当前壁纸：${Wallpapers.label(this, config.wallpaper, config.wallpaperInterval)}")
        group(appearanceRow())
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

        val all = Wallpapers.available(this)
        val strip = LinearLayout(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        strip.addView(thumb(Wallpapers.DAILY, "自动轮换", null, collage = all.take(3)))
        all.forEach { strip.addView(thumb(it.key, it.name, it)) }
        strip.addView(thumb(Wallpapers.NONE, "纯黑", null))
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        })

        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        group(intervalRow(), sunRow())

        subheader("在线壁纸")
        val store = OnlineWallpapers(this)
        val photos = store.list()
        val bytes = store.dir.listFiles()?.sumOf { it.length() } ?: 0L
        group(
            toggleRow(R.drawable.ui_wifi, "连 Wi-Fi 自动更新", "每天最多 ${OnlineWallpapers.PER_RUN} 张，只用 Wi-Fi，新图加入自动轮换",
                config.onlineWallpapers) { on ->
                config.setOnlineWallpapers(on)
                if (on) WallpaperJob.schedule(this) else WallpaperJob.cancel(this)
            },
            row(R.drawable.ui_refresh, "立即更新", "下次连上 Wi-Fi 时马上在后台下载",
                Ui.pill(this, "更新") {
                    if (!config.onlineWallpapers) config.setOnlineWallpapers(true)
                    WallpaperJob.schedule(this)
                    WallpaperJob.runSoon(this)
                    overlay.snackbar("已安排：连上 Wi-Fi 后在后台下载")
                    render()
                }),
            storageRow(photos.size, bytes),
        )
    }

    /** Wallpaper thumbnail: the one currently in use gets a green outline and a check mark. */
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
            Wallpapers.decode(this, wp, w)?.let { bmp ->
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
                    Wallpapers.decode(this@SettingsActivity, wp, w / 2)?.let { setImageBitmap(it) }
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

    /** Appearance: auto (light by day, dark after sunset) / light / dark. Changing it recreates the page with the new theme. */
    private fun appearanceRow(): View {
        val chips = LinearLayout(this)
        listOf(Config.APPEARANCE_AUTO to "自动", Config.APPEARANCE_LIGHT to "浅色", Config.APPEARANCE_DARK to "深色")
            .forEach { (value, name) ->
                val selected = value == config.appearance
                chips.addView(Ui.pill(this, name, if (selected) Ui.PillStyle.PRIMARY else Ui.PillStyle.SECONDARY) {
                    if (value == config.appearance) return@pill
                    config.setAppearance(value)
                    if (Ui.applyTheme(this)) recreate() else render()
                }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            }
        val now = if (Ui.dark) "深色" else "浅色"
        val sub = when (config.appearance) {
            Config.APPEARANCE_AUTO -> "白天浅色、日落后深色（按日出日落自动切换；现在是$now）"
            else -> "固定$now"
        }
        return row(R.drawable.ui_palette, "外观", sub, chips)
    }

    /** Separate day / night wallpapers by sunrise and sunset (only applies with auto-rotate). The subtitle shows today's sunrise and sunset times. */
    private fun sunRow(): View {
        val loc = Sun.location(this)
        val times = loc?.let { Sun.times(it.first, it.second, System.currentTimeMillis()) }
        val fmt = SimpleDateFormat("H:mm", Locale.CHINA)
        val sub = when {
            times != null -> "今天日出 ${fmt.format(Date(times.first))} · 日落 ${fmt.format(Date(times.second))}（按最近一次 GPS 位置）"
            loc != null -> "这个位置今天没有日出 / 日落（极昼或极夜）"
            else -> "还没有 GPS 位置，先按 7:00–19:00 算白天；首页显示过车速后就会用 GPS"
        }
        return toggleRow(R.drawable.ui_wallpaper, "按日出日落分白天 / 夜晚", sub, config.wallpaperSun) { on ->
            config.setWallpaperSun(on)
            if (on && config.wallpaper != Wallpapers.DAILY) config.setWallpaper(Wallpapers.DAILY)
            Ui.refreshPageWallpaper(this, root)
            render()
        }
    }

    /** Auto-rotate interval: a row of pills, the current one highlighted. Picking one switches to auto-rotate. */
    private fun intervalRow(): View {
        val auto = config.wallpaper == Wallpapers.DAILY
        val chips = LinearLayout(this)
        Wallpapers.INTERVALS.forEach { (minutes, name) ->
            val selected = auto && minutes == config.wallpaperInterval
            chips.addView(Ui.pill(this, name, if (selected) Ui.PillStyle.PRIMARY else Ui.PillStyle.SECONDARY) {
                config.setWallpaperInterval(minutes)
                if (!auto) config.setWallpaper(Wallpapers.DAILY)
                Ui.refreshPageWallpaper(this, root)
                render()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
        }
        return row(R.drawable.ui_refresh, "轮换频率",
            if (auto) "到点自动换下一张（内置 + 在线下载的）" else "现在用的是固定壁纸，选一个频率就改成自动轮换", chips)
    }

    private fun storageRow(count: Int, bytes: Long): View {
        val mb = bytes / 1024.0 / 1024.0
        val maxMb = OnlineWallpapers.MAX_BYTES / 1024.0 / 1024.0
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, "已下载 $count 张", 17f))
        texts.addView(Ui.text(this, String.format(Locale.US, "占用 %.1f MB，上限 %.0f MB / %d 张，空间不足时自动停止",
            mb, maxMb, OnlineWallpapers.MAX_COUNT), 13f, Ui.TEXT_DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        texts.addView(Meter(this, (bytes.toFloat() / OnlineWallpapers.MAX_BYTES).coerceIn(0f, 1f)),
            LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
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
        return rowWith(R.drawable.ui_delete, texts, clear)
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

    // ---- CarPlay & OEM ----

    private fun renderCarPlay() {
        header("CarPlay 与原车", "首页两个固定入口打开哪个应用")
        group(
            appRow("carplay", "CarPlay 应用", config.carplay, "没找到 ZLINK / DiPlay，点更换手动选") { pick(REQ_CARPLAY) },
            toggleRow(R.drawable.ui_power, "开机自动进入 CarPlay", "开机后第一次回到桌面时自动打开", config.autoCarplay) { on ->
                config.setAutoCarplay(on)
            },
        )
        subheader("原车")
        group(
            appRow("oem", "原车入口", config.oem, "摸底查到切换方式后用 config.json 配置") { pick(REQ_OEM) },
        )
        hint("如果原车画面是用广播切换的，只能通过 config.json 的 oem 字段配置（写法见 README）。")
    }

    private fun appRow(iconKey: String, title: String, spec: String?, emptyHint: String, onChange: () -> Unit): View {
        val available = Targets.isAvailable(this, spec)
        val sub = when {
            spec == null -> emptyHint
            !available -> "找不到：$spec"
            else -> Targets.label(this, spec)?.let { "$it  ·  ${spec.substringBefore('/')}" } ?: spec
        }
        val icon: Drawable = Icons.badge(this, Icons.byKey(iconKey)!!)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, title, 17f))
        texts.addView(Ui.text(this, sub, 13f, if (spec != null && !available) Ui.DANGER else Ui.TEXT_DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        return rowWith(null, texts, Ui.pill(this, "更换") { onChange() }, leading = ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
    }

    private fun pick(requestCode: Int) {
        startActivityForResult(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_PICK, true), requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) {
            render()
            overlay.snackbar(if (HomeRole.isDefault(this)) "Q5 Home 已设为默认桌面" else "没有更改默认桌面")
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
            updates.lastError != null -> "出错了：${updates.lastError}"
            checkedAt == null -> "还没检查过"
            latest == null -> "还没有发布版本"
            updates.hasUpdate -> "有新版本 ${latest.versionName}"
            else -> "已是最新版本"
        }
        group(
            row(R.drawable.ui_info, "当前版本", updates.currentVersionName, null),
            row(R.drawable.ui_refresh, status, checkedAt ?: "连上网络后点右边检查",
                Ui.pill(this, if (checking) "检查中…" else "检查更新") { if (!checking) checkNow() }),
        )

        if (latest != null && updates.hasUpdate) {
            subheader("新版本 ${latest.versionName}")
            val notes = latest.notes.ifBlank { "（这个版本没有写更新说明）" }
            val mb = String.format(Locale.US, "%.1f MB", latest.apkSize / 1024.0 / 1024.0)
            val progress = downloadProgress
            val button = if (progress != null) {
                Ui.pill(this, "下载中 $progress%") {}.apply { isEnabled = false }
            } else {
                Ui.pill(this, "下载并安装（$mb）", Ui.PillStyle.PRIMARY) { downloadAndInstall(latest) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(this, notes, 15f, Ui.TEXT, singleLine = false).apply { maxLines = 8 })
            texts.addView(Ui.text(this, "发布于 ${latest.publishedAt.take(10)}", 13f, Ui.TEXT_DIM),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            group(rowWith(R.drawable.ui_download, texts, button))
        }

        subheader("自动检查")
        group(
            toggleRow(R.drawable.ui_power, "自动检查更新", "每天检查一次，有新版本时首页右上角会提示，不会自动安装",
                updates.autoCheck) { on ->
                updates.autoCheck = on
                if (on) UpdateJob.schedule(this) else UpdateJob.cancel(this)
            },
        )
        hint("安装时安卓会弹出系统确认；第一次需要允许 Q5 Home「安装未知应用」。所有版本用同一签名，设置会保留。")
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
                "第一次更新需要在系统设置里允许 Q5 Home「安装未知应用」。打开开关后返回，再点一次下载并安装。", "去设置") {
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
            group(row(R.drawable.ui_signal, "开始检查",
                "第一次会弹系统授权：位置信息（看卫星和定位）、电话状态（看 SIM 卡和网络类型）",
                Ui.pill(this, "开始检查", Ui.PillStyle.PRIMARY) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE), REQ_SIGNAL_PERMS)
                }))
        }
        // Satellite callbacks arrive about once per second: only record the data, signalTicker refreshes the UI every 2 seconds. The head-unit CPU is weak, don't redraw constantly.
        if (!signal.listening) signal.start {}
        root.removeCallbacks(signalTicker)
        root.postDelayed(signalTicker, 2000)

        signalViews.clear()
        subheader("GPS")
        group(toggleRow(R.drawable.ui_home, "首页显示车速、方向、海拔",
            "在时钟下面；有新鲜定位时才显示，进隧道 15 秒后自动隐藏", config.homeGps) { on -> config.setHomeGps(on) })
        group(*signal.gpsLines().map { (title, value) -> liveInfo(title, value) }.toTypedArray())
        hint("GPS 天线一般接在车机背后；在地库、隧道里收不到。车机熄火久了第一次定位会慢一些。")

        subheader("4G")
        group(*signal.cellLines().map { (title, value) -> liveInfo(title, value) }.toTypedArray(),
            row(R.drawable.ui_refresh, "4G 上网测试", probeResult ?: "只走移动网络访问一次 Google 测试地址，流量很小；不受 Wi-Fi / 手机热点影响",
                Ui.pill(this, if (probing) "测试中…" else "测试") { startProbe() }.apply {
                    isEnabled = !probing
                    alpha = if (probing) 0.5f else 1f
                }))
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
        header("车机摸底", "只读：查系统、调试线索和原车 / CarPlay 组件，不会改动车机")
        val r = report
        if (r == null) {
            if (!reconRunning) {
                reconRunning = true
                Thread {
                    val result = runCatching { Recon.collect(this, deep = false) }
                    runOnUiThread {
                        reconRunning = false
                        report = result.getOrNull()
                        if (result.isFailure) overlay.snackbar("摸底出错：${result.exceptionOrNull()?.message}")
                        if (section == SECTION_RECON) render()
                    }
                }.start()
            }
            group(row(R.drawable.ui_refresh, "正在摸底…", "读取系统信息和所有应用，几秒钟", null))
            return
        }

        val upload = ReconUpload(this)
        val status = when {
            submitting -> submitStage
            submitError != null -> "失败：$submitError"
            upload.lastIssueUrl != null -> "已提交：${upload.lastIssueUrl}"
            upload.hasToken -> "令牌已保存，点右边提交"
            else -> "需要 GitHub 令牌：点下面「生成二维码」，手机扫码后在网页里粘贴"
        }
        val canSubmit = upload.hasToken && !submitting
        group(
            row(R.drawable.ui_update, "提交重点报告", status,
                Ui.pill(this, if (submitting) "进行中…" else "提交", Ui.PillStyle.PRIMARY) { submitReport(full = false) }.apply {
                    isEnabled = canSubmit
                    alpha = if (isEnabled) 1f else 0.4f
                }),
            row(R.drawable.ui_info, "提交完整报告", "全部应用、系统属性、调试线索等，比较长；一般不需要",
                Ui.pill(this, "完整报告") { submitReport(full = true) }.apply {
                    isEnabled = canSubmit
                    alpha = if (isEnabled) 1f else 0.4f
                }),
            shareRow(),
            *listOfNotNull(if (upload.hasToken) row(R.drawable.ui_delete, "清除令牌", "删除车机上保存的 GitHub 令牌",
                Ui.pill(this, "清除", Ui.PillStyle.DANGER) {
                    upload.clearToken()
                    overlay.snackbar("已清除令牌")
                    render()
                }) else null).toTypedArray(),
        )
        upload.lastIssueUrl?.let { url ->
            subheader("Issue 链接（手机扫码打开）")
            group(qrRow(url, "已提交到 GitHub", url, null))
        }
        subheader("要点")
        group(*r.facts.map { (title, value) -> info(title, value) }.toTypedArray())

        subheader("可能的入口（点「打开」试试）")
        val shown = r.candidates.take(12)
        if (shown.isEmpty()) {
            group(row(R.drawable.ui_info, "没找到明显的候选", "完整列表在报告里", null))
        } else {
            group(*shown.map { candidateRow(it) }.toTypedArray())
            if (r.candidates.size > shown.size) hint("还有 ${r.candidates.size - shown.size} 个，见完整报告。")
        }

        hint("重点报告：只看仓库 config/recon-targets.json 指定的应用、代码和接口（提交时自动下载最新的，改分析方向不用更新应用），" +
            "只读，不改车机。提交到 GitHub Issue（${Updates.REPO}）。令牌只授权这个仓库的 Issues，存在车机上的 Q5 Home 里。" +
            "临时网页只在这一页开着时运行，15 分钟后自动关闭。")
    }

    private fun candidateRow(c: Recon.Candidate): View {
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(Ui.text(this, "${c.kind} · ${c.label}", 17f))
        texts.addView(Ui.text(this, c.spec, 13f, Ui.TEXT_DIM), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val buttons = LinearLayout(this)
        buttons.addView(Ui.pill(this, "打开") {
            if (!Targets.launch(this, c.spec)) overlay.snackbar("打不开：${c.spec}")
        })
        val setAs: (() -> Unit)? = when (c.kind) {
            "原车" -> { { config.setOem(c.spec); overlay.snackbar("原车入口已改为 ${c.label}") } }
            "CarPlay" -> { { config.setCarplay(c.spec); overlay.snackbar("CarPlay 已改为 ${c.label}") } }
            else -> null
        }
        setAs?.let { action ->
            buttons.addView(Ui.pill(this, "设为${c.kind}") { action(); render() }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
            })
        }
        return rowWith(null, texts, buttons)
    }

    private fun shareRow(): View {
        val s = share?.takeUnless { it.closed }
        val ip = DebugAccess.ipAddress(this)
        if (s == null || ip == null) {
            return row(R.drawable.ui_wifi, "手机扫码", if (ip == null) "车机没联网，手机打不开" else "车机开一个临时网页：看完整报告、粘贴令牌",
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
        share = runCatching {
            LocalShare(report = { lastMarkdown ?: report?.markdown }, onToken = { token ->
                ReconUpload(this).saveToken(token)
                // Already on the server thread: generate the key report and submit it directly; the phone web page is waiting for the result.
                val result = runCatching {
                    val r = Recon.focused(this, ReconTargets.load())
                    lastMarkdown = r.markdown
                    ReconUpload(this).submit(r.title, r.markdown)
                }
                runOnUiThread {
                    submitError = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                    if (section == SECTION_RECON) render()
                }
                result.fold({ "已提交：$it" }, { "令牌已保存，但提交失败：${it.message}" })
            })
        }.onFailure { overlay.snackbar("开不了临时网页：${it.message}") }.getOrNull()
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

    /** Generate the report, then submit: the key report downloads the analysis targets first; the full report reads every APK, which may take tens of seconds on the head unit. */
    private fun submitReport(full: Boolean) {
        if (submitting) return
        submitting = true
        submitError = null
        submitStage = if (full) "正在生成完整报告（要读所有应用，可能几十秒）…" else "正在下载分析目标、生成重点报告…"
        render()
        Thread {
            val result = runCatching {
                val r = if (full) Recon.collect(this) else Recon.focused(this, ReconTargets.load())
                lastMarkdown = r.markdown
                runOnUiThread { submitStage = "正在提交…"; if (section == SECTION_RECON) render() }
                ReconUpload(this).submit(r.title, r.markdown)
            }
            runOnUiThread {
                submitting = false
                submitError = result.exceptionOrNull()?.let { it.message ?: it.toString() }
                overlay.snackbar(if (result.isSuccess) "已提交到 GitHub" else "提交失败：$submitError")
                if (section == SECTION_RECON) render()
            }
        }.start()
    }

    // ---- Advanced ----

    private fun renderAdvanced() {
        header("高级", "系统设置、默认桌面、无线调试、配置文件")
        group(
            row(R.drawable.glyph_settings, "系统设置", "打开安卓自带的设置", chevron()) {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    .onFailure { overlay.snackbar("车机不支持这个页面") }
            },
            row(R.drawable.ui_restart, "恢复默认布局", "清空车上的手动设置，回到 config.json 和自动检测", chevron(), danger = true) {
                Menus.confirm(overlay, this, "恢复默认布局",
                    "会清空手动设置的 CarPlay、原车入口、常用应用和壁纸选择。", "恢复", danger = true) {
                    config.reset()
                    Ui.refreshPageWallpaper(this, root)
                    overlay.snackbar("已恢复默认")
                    render()
                }
            },
        )

        subheader("默认桌面")
        val isDefault = HomeRole.isDefault(this)
        val current = HomeRole.currentHomeLabel(this) ?: "没有固定（按 HOME 时会问）"
        group(
            row(R.drawable.ui_home, "设为默认桌面",
                if (isDefault) "Q5 Home 已经是默认桌面" else "当前：$current。系统会弹出确认框",
                if (isDefault) Ui.pill(this, "已是默认") {}.apply { isEnabled = false; alpha = 0.5f }
                else Ui.pill(this, "设为默认", Ui.PillStyle.PRIMARY) { requestDefaultHome() }),
            row(R.drawable.ui_undo, "恢复原厂桌面", "打开系统的「主屏幕应用」设置，选回原厂桌面（原厂桌面一直保留着）",
                Ui.pill(this, "去设置") { openHomeSettings() }),
        )
        hint("切换后请做一遍实车检查：倒车影像、原车画面切换、侧栏、CarPlay、熄火重启。")

        subheader("调试与连接")
        val ip = DebugAccess.ipAddress(this)
        val usb = if (DebugAccess.usbDebuggingOn(this)) "开" else "关"
        if (DebugAccess.hasWirelessDebugging()) {
            val wifi = if (DebugAccess.wirelessDebuggingOn(this)) "开" else "关"
            group(
                row(R.drawable.ui_wifi, "打开无线调试", "电脑通过 Wi-Fi 连车机用（adb）。打不开直达页面时，会打开开发者选项并高亮这一项",
                    Ui.pill(this, "打开") { overlay.snackbar(DebugAccess.openWirelessDebugging(this)) }),
                info("本机 IP · USB 调试 · 无线调试", "${ip ?: "未联网"}  ·  $usb  ·  $wifi"),
            )
            hint("无线调试打开后，点进它的页面能看到 IP 和端口；第一次在电脑上用「使用配对码配对设备」配对：" +
                "adb pair IP:配对端口，再 adb connect IP:端口。也可以在开发者选项 →「快捷设置开发者图块」里把「无线调试」加到下拉菜单。")
        } else {
            // Android 10 and earlier have no wireless debugging: connect once over USB first, then switch to network adb.
            group(
                row(R.drawable.ui_wifi, "打开开发者选项",
                    "这台车机实际是安卓 ${DebugAccess.realAndroidVersion()}，系统里没有「无线调试」，要先用 USB 线连电脑",
                    Ui.pill(this, "打开") { overlay.snackbar(DebugAccess.openWirelessDebugging(this)) }),
                info("本机 IP · USB 调试", "${ip ?: "未联网"}  ·  $usb"),
            )
            hint("USB 调试打开后，用 USB 线把车机背后引出的 USB 口（通常标 USB1 / OTG）接到电脑：adb devices 能看到车机后，" +
                "执行 adb tcpip 5555，拔掉线，以后用 adb connect ${ip ?: "车机IP"}:5555 无线连接（车机重启后要再用线做一次）。")
        }

        subheader("配置文件")
        val dir = getExternalFilesDir(null)?.absolutePath ?: "(不可用)"
        group(
            info("推送配置（adb push）", "$dir/${Config.FILE}"),
            info("当前生效（adb pull）", "$dir/${Config.CURRENT_FILE}"),
            info("CarPlay", config.carplay ?: "未设置"),
            info("原车", config.oem ?: "未设置"),
            info("在线壁纸来源", if (config.unsplashKey != null) "Unsplash API（${config.wallpaperQuery}）" else config.wallpaperFeed),
        )

        subheader("关于")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        group(
            info("Q5 Home", "版本 $version"),
            info("致谢", "壁纸 Unsplash · 图标 Material Symbols · 字体 Outfit · 风格参考 CleanYourTV"),
        )
    }

    // ---- Shared small widgets ----

    private fun header(title: String, sub: String) {
        content.addView(Ui.text(this, title, 30f, Ui.TEXT, Ui.Font.LIGHT))
        content.addView(Ui.text(this, sub, 14f, Ui.TEXT_DIM),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(18) })
    }

    private fun subheader(title: String) {
        content.addView(Ui.text(this, title, 13f, Ui.TEXT_DIM, Ui.Font.MEDIUM).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26); bottomMargin = dp(10); marginStart = dp(6) })
    }

    private fun hint(text: String) {
        content.addView(Ui.text(this, text, 13f, Ui.TEXT_FAINT, singleLine = false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); marginStart = dp(6) })
    }

    /** Rounded group with dividers between rows. */
    private fun group(vararg rows: View) {
        val g = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.group(this@SettingsActivity)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) g.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) },
                LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(14), dp(2), dp(14), dp(2)) })
            g.addView(r)
        }
        content.addView(g)
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

    private fun chevron(): View = Ui.glyph(this, R.drawable.ui_chevron, 24, Ui.TEXT_FAINT)

    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_SECTION = "section"
        const val SECTION_WALLPAPER = "wallpaper"
        const val SECTION_CARPLAY = "carplay"
        const val SECTION_ADVANCED = "advanced"
        const val SECTION_UPDATE = "update"
        const val SECTION_RECON = "recon"
        const val SECTION_SIGNAL = "signal"
        private const val REQ_SIGNAL_PERMS = 10
        private const val REQ_CARPLAY = 1
        private const val REQ_OEM = 2
        private const val REQ_HOME = 3
    }
}
