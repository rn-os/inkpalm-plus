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
    private var previousPkg: String? = null

    /** The app whose hold menu is showing, or null. */
    private var menuFor: String? = null
    private var menuLayer: View? = null

    private val landscape get() = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    /** The reader app picked on the home screen, and its last-read book, read when the panel is built. */
    private var reader: Readers.App? = null
    private var lastRead: Readers.Book? = null
    /** KOReader is picked but InkPalm+ can't read its history yet: the card asks for All files access. */
    private var needsAccess = false
    private val hasCard get() = lastRead != null || needsAccess
    /** Showing "Your books" (holding the Last read card) instead of recent apps. */
    private var showingBooks = false
    private var books: List<Readers.Book> = emptyList()
    private var bookPage = 0

    // The Last read card takes one row's room (portrait) or two rows (landscape's two columns).
    private val perPage get() = if (hasCard) (if (landscape) 4 else 8) else (if (landscape) 8 else 9)
    private val booksPerPage get() = if (landscape) 6 else 8

    /** When [prepare] built the rows, and for which app in front: a prepared panel is only used while both still hold. */
    var builtAt = 0L
        private set
    var builtFor: String? = null
        private set
    private var builtVersion = -1
    private var builtWidth = 0
    private var onRight = true
    private lateinit var lp: WindowManager.LayoutParams

    /**
     * Builds the panel (rows included, ~70 ms) without showing it. The service does this shortly after each
     * app switch, so a swipe only has to add the window.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun prepare(): SidePanel {
        onRight = Prefs(ctx).edgeRight
        val r = ctx.dp(18).toFloat()
        root = FrameLayout(ctx).apply {
            // Rounded on the inner side only, like a card slid in from the edge.
            background = GradientDrawable().apply {
                setColor(Ink.PAPER)
                setStroke(ctx.dp(3), Ink.INK)
                cornerRadii = if (onRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            setPadding(if (onRight) ctx.dp(3) else 0, ctx.dp(3), if (onRight) 0 else ctx.dp(3), ctx.dp(3))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onClose(); false }
        }
        content = ctx.vbox()
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        val width = currentWidth()
        lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START }
        reader = Readers.chosen(ctx)
        builtReader = readerState()
        needsAccess = reader?.let { !Readers.hasAccess(it) } ?: false
        lastRead = reader?.let { Readers.lastRead(ctx, it) }
        render()
        // Measure and lay out at the window's size now, so showing it doesn't have to.
        val h = ctx.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, h)
        builtAt = android.os.SystemClock.uptimeMillis()
        builtFor = service.foreground
        builtVersion = service.recents.version
        builtWidth = width
        return this
    }

    /**
     * Still matches what's on screen: same app in front, same list, same edge and width, built under a minute
     * ago (so the "2 min ago" times stay right).
     */
    fun fresh(): Boolean = builtAt > 0 && builtFor == service.foreground && builtVersion == service.recents.version &&
        onRight == Prefs(ctx).edgeRight && builtWidth == currentWidth() && builtReader == readerState() &&
        android.os.SystemClock.uptimeMillis() - builtAt < 60_000

    /** The reader picked and whether its history is readable: a prepared panel goes stale when either changes. */
    private var builtReader = ""
    private fun readerState() = Readers.chosen(ctx)?.let { "${it.key}:${Readers.hasAccess(it)}" } ?: "none"

    private fun currentWidth() = ctx.resources.displayMetrics.widthPixels * (if (landscape) 50 else Prefs(ctx).panelWidthPct) / 100

    fun show(wm: WindowManager, startedAt: Long = android.os.SystemClock.uptimeMillis()) {
        val prepared = builtAt > 0
        if (!prepared) prepare()
        val tAdd = android.os.SystemClock.uptimeMillis()
        wm.addView(root, lp)
        android.util.Log.i(QuickPanelService.TAG, "timing: ${if (prepared) "prepared panel" else "rows built on the swipe"}, window added in ${android.os.SystemClock.uptimeMillis() - tAdd} ms")
        // Timing: from the strip swipe to the panel's first frame.
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                android.util.Log.i(QuickPanelService.TAG, "timing: side panel drawn ${android.os.SystemClock.uptimeMillis() - startedAt} ms after the swipe")
                return true
            }
        })
    }

    fun dismiss(wm: WindowManager) {
        runCatching { wm.removeView(root) }
    }

    // ---- Actions ------------------------------------------------------------------------------

    private fun open(pkg: String) {
        if (pkg == service.foreground) return onClose()
        service.switchTo = pkg
        service.switchAt = android.os.SystemClock.uptimeMillis()
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

    /** The list as of this draw (read once per [render]). */
    private var entries: List<RecentApps.Entry> = emptyList()
    private fun all(): List<RecentApps.Entry> = entries
    private fun pages(): Int = ((all().size + perPage - 1) / perPage).coerceAtLeast(1)
    private fun visible(): List<RecentApps.Entry> = all().drop(page * perPage).take(perPage)

    private fun render() {
        if (showingBooks) return renderBooks()
        entries = service.recents.list()
        previousPkg = entries.firstOrNull { it.pkg != service.foreground }?.pkg
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

        lastRead?.let { content.addView(lastReadCard(it)) }
        if (needsAccess) content.addView(accessCard())

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

    // ---- Reader app (docs/mockups/13-last-read.png, 14-reader-app.png) --------------------------------------------

    /** Tap: the reader opens the book at its page. Hold: "Your books". */
    private fun lastReadCard(b: Readers.Book): View = ctx.hbox().apply {
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(3, 12, Ink.PRESSED))
            addState(intArrayOf(), ctx.outline(3, 12))
        }
        setPadding(ctx.dp(12), ctx.dp(if (landscape) 4 else 8), ctx.dp(12), ctx.dp(if (landscape) 4 else 8))
        contentDescription = "Last read in ${reader?.label}: ${b.title}. Tap to keep reading, hold for your books"
        addView(ctx.icon(ctx.getDrawable(R.drawable.ic_book), if (landscape) 28 else 34))
        addView(ctx.vbox().apply {
            addView(ctx.text("LAST READ · ${reader?.label?.uppercase()}", 10.5f, bold = true).apply { letterSpacing = 0.06f })
            addView(ctx.text(b.title, 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(ctx.text("${RecentApps.ago(b.at)} · tap to keep reading · hold for your books", 11.5f, color = Ink.MUTED).apply { maxLines = if (landscape) 1 else 2 })
        }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(12) })
        isClickable = true
        setOnClickListener { openBook(b.path) }
        setOnLongClickListener { showBooks(); true }
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(8)) } }

    /** KOReader picked without All files access: tap opens Android's setting for it. */
    private fun accessCard(): View = ctx.hbox().apply {
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(3, 12, Ink.PRESSED))
            addState(intArrayOf(), ctx.outline(3, 12))
        }
        setPadding(ctx.dp(12), ctx.dp(if (landscape) 4 else 8), ctx.dp(12), ctx.dp(if (landscape) 4 else 8))
        contentDescription = "Allow All files access so InkPalm+ can read ${reader?.label}'s history"
        addView(ctx.icon(ctx.getDrawable(R.drawable.ic_book), if (landscape) 28 else 34))
        addView(ctx.vbox().apply {
            addView(ctx.text("${reader?.label?.uppercase()} · ONE MORE STEP", 10.5f, bold = true).apply { letterSpacing = 0.06f })
            addView(ctx.text("Allow All files access", 16f, bold = true))
            addView(ctx.text("So InkPalm+ can read ${reader?.label}'s history. Tap to open the setting.", 11.5f, color = Ink.MUTED).apply { maxLines = if (landscape) 1 else 2 })
        }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(12) })
        isClickable = true
        setOnClickListener { runCatching { ctx.startActivity(Readers.accessIntent(ctx)) }; onClose() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ctx.dp(12), 0, ctx.dp(12), ctx.dp(8)) } }

    private fun openBook(path: String) {
        reader?.let { Readers.open(ctx, it, path) }
        onClose()
    }

    private fun showBooks() {
        showingBooks = true
        bookPage = 0
        books = emptyList()
        render()
        val app = reader ?: return
        Shell.background({ Readers.books(ctx, app) }) { list -> books = list; if (showingBooks) render() }
    }

    private fun bookPages() = ((books.size + booksPerPage - 1) / booksPerPage).coerceAtLeast(1)

    /** "Your books": what the reader has opened, newest first, in pages; tap one to open it. */
    private fun renderBooks() {
        content.removeAllViews()
        menuLayer?.let { root.removeView(it) }
        menuLayer = null
        bookPage = bookPage.coerceIn(0, bookPages() - 1)
        content.addView(ctx.hbox().apply {
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(4))
            addView(ctx.inkButton("◀", 15f) { showingBooks = false; render() }.apply { contentDescription = "Back to recent apps" }, lp(ctx.dp(48), WRAP).apply { marginEnd = ctx.dp(10) })
            addView(ctx.text("Your books", 19f, bold = true), lp(0, WRAP, 1f))
            addView(ctx.inkButton("✕", 16f) { onClose() }, lp(ctx.dp(48), WRAP))
        })
        content.addView(ctx.text("${reader?.label?.uppercase()} · NEWEST FIRST · TAP TO OPEN", 10.5f, bold = true).apply {
            letterSpacing = 0.06f; setPadding(ctx.dp(14), 0, ctx.dp(14), ctx.dp(6))
        })
        val shown = books.drop(bookPage * booksPerPage).take(booksPerPage)
        val list = SwipePager(ctx, onNext = { if (bookPage < bookPages() - 1) { bookPage++; render() } }, onPrev = { if (bookPage > 0) { bookPage--; render() } })
            .apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(8), 0, ctx.dp(8), 0) }
        if (shown.isEmpty()) {
            list.addView(ctx.text(if (books.isEmpty()) "Loading…" else "", 15f, color = Ink.MUTED).apply { setPadding(ctx.dp(8), ctx.dp(16), ctx.dp(8), 0) })
        } else if (landscape) {
            shown.chunked(2).forEach { pair ->
                list.addView(ctx.hbox().apply {
                    for (b in pair) addView(bookRow(b, compact = true), lp(0, MATCH, 1f))
                    if (pair.size == 1) addView(View(ctx), lp(0, MATCH, 1f))
                }, lp(MATCH, 0, 1f))
            }
            repeat(booksPerPage / 2 - (shown.size + 1) / 2) { list.addView(View(ctx), lp(MATCH, 0, 1f)) }
        } else {
            for (b in shown) list.addView(bookRow(b, compact = false), lp(MATCH, 0, 1f))
            repeat(booksPerPage - shown.size) { list.addView(View(ctx), lp(MATCH, 0, 1f)) }
        }
        content.addView(list, lp(MATCH, 0, 1f))
        if (bookPages() > 1) content.addView(ctx.hbox().apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            addView(ctx.inkButton("◀", 15f) { if (bookPage > 0) { bookPage--; render() } }.apply { isEnabled = bookPage > 0 }, lp(ctx.dp(56), WRAP))
            addView(ctx.text("${bookPage + 1} / ${bookPages()}", 15f, bold = true).apply { gravity = Gravity.CENTER }, lp(ctx.dp(72), WRAP))
            addView(ctx.inkButton("▶", 15f) { if (bookPage < bookPages() - 1) { bookPage++; render() } }.apply { isEnabled = bookPage < bookPages() - 1 }, lp(ctx.dp(56), WRAP))
        })
        // Browse files…: Android's file picker, for books the reader hasn't opened yet.
        content.addView(ctx.hbox().apply {
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(8), ctx.dp(8))
            addView(ctx.inkButton("Browse files…", 14f) { browse() }, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(6) })
            addView(ctx.inkButton("Recent apps", 14f) { showingBooks = false; render() }, lp(0, WRAP, 1f))
        })
    }

    private fun bookRow(b: Readers.Book, compact: Boolean): View = ctx.hbox().apply {
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ctx.outline(2, 12, Ink.PRESSED))
            addState(intArrayOf(), ctx.outline(2, 12))
        }
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        contentDescription = b.title
        addView(ctx.icon(ctx.getDrawable(R.drawable.ic_book), if (compact) 26 else 32))
        addView(ctx.vbox().apply {
            addView(ctx.text(b.title, if (compact) 14f else 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(ctx.text(listOf(b.format, RecentApps.ago(b.at)).filter { it.isNotEmpty() }.joinToString(" · "), 12f, color = Ink.MUTED).apply { maxLines = 1 })
        }, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(12) })
        isClickable = true
        setOnClickListener { openBook(b.path) }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        (it.layoutParams as LinearLayout.LayoutParams).setMargins(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
    }

    private fun browse() {
        ctx.startActivity(Intent(ctx, BrowseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onClose()
    }

    /** Test hooks. */
    fun testCard(hold: Boolean): Boolean {
        val b = lastRead ?: return false
        if (hold) showBooks() else openBook(b.path)
        return true
    }
    fun testBook(index: Int): Boolean = books.drop(bookPage * booksPerPage).getOrNull(index)?.let { openBook(it.path); true } ?: false
    fun testBrowse(): Boolean { browse(); return true }
    fun describeBooks(): String = "reader=${reader?.key} access=${!needsAccess} lastRead=${lastRead?.title} books=${books.size} showing=$showingBooks page=${bookPage + 1}/${bookPages()}"

    private fun row(e: RecentApps.Entry, compact: Boolean): View = ctx.hbox().apply {
        val label = RecentApps.Info.label(ctx, e.pkg)
        val now = e.pkg == service.foreground
        val previous = !now && e.pkg == previousPkg
        background = ctx.outline(if (now || previous) 3 else 2, 12)
        setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        contentDescription = label
        val iconSize = if (compact) 32 else 40
        val appIcon = ctx.icon(RecentApps.Info.icon(ctx, e.pkg), iconSize, tint = false)
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
        val label = RecentApps.Info.label(ctx, pkg)
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
