package com.inkpalmplus

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The side panel the strip opens (mockup: docs/mockups/11-side-panel.png): recent apps, newest first,
 * the app you came from marked Previous. Pages instead of scrolling (no smeared scroll on e-ink).
 * Hold an app for a small menu. The footer opens the e-ink panel for the app in front ([QuickPanel])
 * or Android's recents. Same look as the settings app: black on white, rounded outlines.
 */
class SidePanel(private val service: QuickPanelService, private val onClose: () -> Unit) {
    private val ctx = service
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var page = 0
    /** The app whose hold menu is showing, or null. */
    private var menuFor: String? = null
    private var menuLayer: View? = null

    private val landscape get() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val perPage get() = if (landscape) 8 else 9

    @SuppressLint("ClickableViewAccessibility")
    fun show(wm: WindowManager) {
        val right = Prefs(ctx).edgeRight
        val r = ctx.dp(18).toFloat()
        root = FrameLayout(ctx).apply {
            // Rounded on the inner side only, like a card slid in from the edge.
            background = GradientDrawable().apply {
                setColor(Ink.PAPER)
                setStroke(ctx.dp(3), Ink.INK)
                cornerRadii = if (right) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            setPadding(if (right) ctx.dp(3) else 0, ctx.dp(3), if (right) 0 else ctx.dp(3), ctx.dp(3))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose(); false }
        }
        content = ctx.vbox()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        val width = ctx.resources.displayMetrics.widthPixels * (if (landscape) 50 else Prefs(ctx).panelWidthPct) / 100
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START }
        wm.addView(root, lp)
        render()
    }

    fun dismiss(wm: WindowManager) {
        runCatching { wm.removeView(root) }
    }

    // ---- Actions ------------------------------------------------------------------------------

    private fun open(pkg: String) {
        if (pkg == service.foreground) return onClose()
        // Like Android's recents: brings the app's existing task forward, or starts it.
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
        onClose()
    }

    /**
     * Closes [pkg] and takes it off the list. It's in the background while the panel is open, so Android's
     * background kill ends it, as swiping it away in recents would (apps playing in the background may keep running).
     */
    private fun close(pkg: String) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        runCatching { am.killBackgroundProcesses(pkg) }
        service.recents.remove(pkg)
        android.util.Log.i(QuickPanelService.TAG, "closed $pkg")
        render()
    }

    /** Clear all: every listed app except the one you're in. */
    private fun clearAll() {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        all().filter { it.pkg != service.foreground }.forEach { e ->
            runCatching { am.killBackgroundProcesses(e.pkg) }
            service.recents.remove(e.pkg)
        }
        android.util.Log.i(QuickPanelService.TAG, "cleared all recent apps")
        page = 0
        render()
    }

    /** Test hooks: what the ✕ on the [index]th app of the page does, and Clear all. */
    fun testClose(index: Int): Boolean = visible().getOrNull(index)?.pkg?.takeIf { it != service.foreground }?.let { close(it); true } ?: false
    fun testClearAll(): Boolean { clearAll(); return true }
    fun testPage(next: Boolean): Boolean { if (next && page < pages() - 1) page++ else if (!next && page > 0) page--; render(); return true }

    /** Test hooks: what tapping the [index]th app on the current page does. */
    fun tap(index: Int): Boolean {
        val pkg = visible().getOrNull(index)?.pkg ?: return false
        open(pkg)
        return true
    }

    fun describe(): String = visible().joinToString(",") { it.pkg } + " page=${page + 1}/${pages()}"

    // ---- Views --------------------------------------------------------------------------------

    private fun all(): List<RecentApps.Entry> = service.recents.list()
    private fun pages(): Int = ((all().size + perPage - 1) / perPage).coerceAtLeast(1)
    private fun visible(): List<RecentApps.Entry> = all().drop(page * perPage).take(perPage)

    private fun render() {
        content.removeAllViews()
        menuLayer?.let { root.removeView(it) }
        menuLayer = null
        page = page.coerceIn(0, pages() - 1)

        // Header: title, ⚙ (InkPalm+ home), ✕.
        content.addView(ctx.hbox().apply {
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(10), ctx.dp(8))
            addView(ctx.text("Recent apps", 19f, bold = true), lp(0, WRAP, 1f))
            if (all().any { it.pkg != service.foreground }) addView(ctx.inkButton("Clear all", 14f) { clearAll() }, lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("⚙", 21f) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                onClose()
            }.apply { contentDescription = "InkPalm+ settings" }, lp(ctx.dp(48), WRAP).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("✕", 16f) { onClose() }, lp(ctx.dp(48), WRAP))
        })

        val apps = visible()
        val list = SwipePager(ctx, onNext = { if (page < pages() - 1) { page++; render() } }, onPrev = { if (page > 0) { page--; render() } })
            .apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
        if (apps.isEmpty()) {
            list.addView(ctx.text("Apps you open will show up here, newest first.", 15f, color = Ink.MUTED).apply {
                setPadding(ctx.dp(8), ctx.dp(16), ctx.dp(8), 0)
            })
        } else if (landscape) {
            // Two columns of four, so a page still shows eight.
            apps.chunked(2).forEach { pair ->
                list.addView(ctx.hbox().apply {
                    for (e in pair) addView(row(e, compact = true), lp(0, MATCH, 1f))
                    if (pair.size == 1) addView(View(ctx), lp(0, MATCH, 1f))
                }, lp(MATCH, 0, 1f))
            }
            repeat(perPage / 2 - (apps.size + 1) / 2) { list.addView(View(ctx), lp(MATCH, 0, 1f)) }
        } else {
            for (e in apps) list.addView(row(e, compact = false), lp(MATCH, 0, 1f))
            repeat(perPage - apps.size) { list.addView(View(ctx), lp(MATCH, 0, 1f)) }
        }
        content.addView(list, lp(MATCH, 0, 1f))

        // Pager: only when there's more than one page.
        if (pages() > 1) content.addView(ctx.hbox().apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            addView(ctx.inkButton("◀", 15f) { if (page > 0) { page--; render() } }.apply { isEnabled = page > 0 }, lp(ctx.dp(56), WRAP))
            addView(ctx.text("${page + 1} / ${pages()}", 15f, bold = true).apply { gravity = Gravity.CENTER }, lp(ctx.dp(72), WRAP))
            addView(ctx.inkButton("▶", 15f) { if (page < pages() - 1) { page++; render() } }.apply { isEnabled = page < pages() - 1 }, lp(ctx.dp(56), WRAP))
        })

        // Footer: the e-ink panel for the app in front, and Android's own recents.
        content.addView(ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(8))
            addView(ctx.inkButton("E-ink · this app", 14f) {
                val fg = service.foreground
                onClose()
                if (fg != null) service.openPanel()
            }, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("Android recents", 14f) {
                onClose()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            }, lp(0, WRAP, 1f))
        })

        menuFor?.let { showMenu(it) }
    }

    private fun row(e: RecentApps.Entry, compact: Boolean): View = ctx.hbox().apply {
        val pm = ctx.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString() }.getOrDefault(e.pkg)
        val now = e.pkg == service.foreground
        val previous = !now && e.pkg == all().firstOrNull { it.pkg != service.foreground }?.pkg
        background = ctx.outline(if (now || previous) 3 else 2, 12)
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        contentDescription = label
        val iconSize = if (compact) 32 else 40
        val appIcon = ctx.icon(runCatching { pm.getApplicationIcon(e.pkg) }.getOrNull(), iconSize, tint = false)
        if (previous) {
            // As in SuperBoox's "Previous app": the icon with a small round ← badge on its corner.
            addView(FrameLayout(ctx).apply {
                addView(appIcon, FrameLayout.LayoutParams(ctx.dp(iconSize), ctx.dp(iconSize), Gravity.BOTTOM or Gravity.END))
                addView(FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Ink.PAPER)
                        setStroke(ctx.dp(2), Ink.INK)
                    }
                    addView(ctx.icon(ctx.getDrawable(R.drawable.ic_back), 14), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                }, FrameLayout.LayoutParams(ctx.dp(22), ctx.dp(22), Gravity.TOP or Gravity.START))
            }, lp(ctx.dp(iconSize + 8), ctx.dp(iconSize + 8)))
        } else {
            addView(appIcon)
        }
        val sub = when {
            now -> "Open now"
            previous && compact -> "Previous · " + RecentApps.ago(e.at)
            else -> RecentApps.ago(e.at)
        }
        addView(ctx.vbox().apply {
            addView(ctx.text(label, if (compact) 15f else 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(ctx.text(sub, 12f, color = Ink.MUTED).apply { maxLines = 1 })
        }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(12) })
        if (!compact && (now || previous)) addView(ctx.text(if (now) "Open now" else "Previous", 11f, bold = true).apply {
            background = ctx.outline(2, 6)
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
        })
        // ✕: close the app and take it off the list (not the one you're in).
        if (!now) addView(ctx.inkButton("✕", 13f) { close(e.pkg) }.apply {
            minWidth = 0; minimumWidth = 0; minHeight = ctx.dp(34); minimumHeight = ctx.dp(34); setPadding(0, 0, 0, 0)
            contentDescription = "Close $label"
        }, lp(ctx.dp(34), ctx.dp(34)).apply { marginStart = ctx.dp(8) })
        // Pressed: the same instant gray as the settings app, inside the rounded outline.
        val normal = background
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(if (now || previous) 3 else 2, 12, Ink.PRESSED))
            addState(intArrayOf(), normal)
        }
        isClickable = true
        setOnClickListener { open(e.pkg) }
        setOnLongClickListener { menuFor = e.pkg; render(); true }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        (it.layoutParams as LinearLayout.LayoutParams).setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
    }

    /** Hold menu: a rounded card over the list. */
    private fun showMenu(pkg: String) {
        val pm = ctx.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val shade = FrameLayout(ctx).apply {
            isClickable = true
            setOnClickListener { menuFor = null; render() }
        }
        val card = ctx.vbox().apply {
            background = ctx.outline(3, 14)
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
            addView(ctx.text(label, 16f, bold = true).apply { setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6)) })
            fun item(title: String, action: () -> Unit) = addView(ctx.inkButton(title, 15f) { menuFor = null; action() },
                lp(MATCH, WRAP).apply { topMargin = ctx.dp(6) })
            item("Remove from list") { service.recents.remove(pkg); render() }
            item("Open its e-ink settings") {
                ctx.startActivity(Intent(ctx, AppActivity::class.java).putExtra(AppActivity.EXTRA_PKG, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onClose()
            }
            item("App info") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onClose()
            }
            item("Cancel") { render() }
        }
        shade.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(ctx.dp(20), 0, ctx.dp(20), 0) })
        root.addView(shade, FrameLayout.LayoutParams(MATCH, MATCH))
        menuLayer = shade
    }
}

/**
 * The recent-apps list: a swipe across it (left/right) or along it (up/down) turns the page; taps still reach
 * the rows. Swipe up or left for the next page, down or right for the previous one.
 */
private class SwipePager(context: android.content.Context, private val onNext: () -> Unit, private val onPrev: () -> Unit) : LinearLayout(context) {
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop * 2
    private val threshold = context.dp(48)
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) swiping = true
        }
        return swiping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val dx = e.x - downX
            val dy = e.y - downY
            when {
                kotlin.math.abs(dx) > threshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) -> if (dx < 0) onNext() else onPrev()
                kotlin.math.abs(dy) > threshold -> if (dy < 0) onNext() else onPrev()
            }
            swiping = false
        }
        return true
    }
}
