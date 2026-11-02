package com.x0finch.q5home

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Safe mode: entered when the same launcher version crashes repeatedly within a short time.
 * Deliberately minimal — no wallpaper, tiles, fonts or online features, only system widgets, so that it is as unlikely as possible to crash itself.
 */
class SafeModeActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(28)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        column.addView(text("安全模式", 30f, Color.WHITE))
        column.addView(text("q5home $version 刚才连续崩溃了 ${CrashGuard.LIMIT} 次，所以先用这个简单页面。" +
            "可以直接打开 CarPlay 或原车，或者安装修复 / 回退版本。", 16f, 0xB3FFFFFF.toInt()).apply {
            setPadding(0, dp(8), 0, dp(20))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val config = runCatching { Config(this).also { it.reload() } }.getOrNull()
        buttons.addView(button("打开 CarPlay", 0xFF1E9E55.toInt()) { open(config?.carplay) })
        buttons.addView(button("打开原车", 0xFF4A4E57.toInt()) { open(config?.oem) })
        buttons.addView(button("回到原厂桌面", 0xFF4A4E57.toInt()) { openStockHome() })
        column.addView(buttons)

        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        buttons2.addView(button("安装修复 / 回退版本", 0xFF0A84FF.toInt()) { checkAndInstall() })
        buttons2.addView(button("再试一次正常启动", 0xFF3A3A3C.toInt()) {
            CrashGuard.reset(this)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        column.addView(buttons2)

        status = text("", 15f, 0xFF8EF0B0.toInt()).apply { setPadding(0, dp(16), 0, 0) }
        column.addView(status)
        column.addView(text("没有可安装的版本时：在手机上打开 GitHub 仓库 → Actions → rollback → Run workflow，" +
            "填要回退到的版本号（例如 1.0.12），几分钟后点上面的按钮安装。", 14f, 0x80FFFFFF.toInt()).apply {
            setPadding(0, dp(16), 0, 0)
        })
        CrashGuard.lastError(this)?.let {
            column.addView(text("错误信息：\n$it", 11f, 0x66FFFFFF).apply { setPadding(0, dp(16), 0, 0) })
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(column) })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Safe mode is the launcher; the back key doesn't exit.
    }

    private fun open(spec: String?) {
        if (!Targets.launch(this, spec)) status.text = "打不开：${spec ?: "未设置"}"
    }

    /** Open the head unit's original launcher (any HOME other than this app). */
    private fun openStockHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        if (other == null) {
            status.text = "没找到原厂桌面"
            return
        }
        runCatching {
            startActivity(Intent(home).setComponent(
                ComponentName(other.activityInfo.packageName, other.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { status.text = "打不开原厂桌面：${it.message}" }
    }

    private fun checkAndInstall() {
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "需要先在系统设置里允许 q5home「安装未知应用」，或用电脑执行 README 里的 adb 命令。"
            return
        }
        status.text = "正在检查 GitHub…"
        Thread {
            val updates = Updates(this)
            val message = try {
                val latest = updates.check()
                if (latest == null || !updates.hasUpdate) {
                    "还没有比当前更新的版本（修复或回退版本发布后再点一次）。"
                } else {
                    val apk = updates.download(latest) { p -> runOnUiThread { status.text = "下载中 $p%" } }
                    updates.verify(apk)?.let { "无法安装：$it" } ?: run {
                        updates.install(apk)
                        "下载完成，请在系统弹窗里确认安装 ${latest.versionName}。"
                    }
                }
            } catch (e: Exception) {
                "出错了：${e.message}"
            }
            runOnUiThread { status.text = message }
        }.start()
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minHeight = dp(64)
        setPadding(dp(20), 0, dp(20), 0)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
