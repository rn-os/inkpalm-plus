package com.inkpalmplus

import android.content.Context
import android.content.Intent

/**
 * The apps used recently, newest first, as the side panel lists them (mockup: docs/mockups/11-side-panel.png).
 * Android doesn't let ordinary apps read its own recents, so, as in SuperBoox, the panel service records each
 * app that comes to the front. Home screens, InkPalm+ and system screens are left out. Kept in its own file
 * so it survives restarts and updates.
 */
class RecentApps(private val context: Context) {
    class Entry(val pkg: String, val at: Long)

    private val store = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
    private val entries = ArrayList<Entry>()

    private val homes: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // Settings' FallbackHome (priority -1000) is only a placeholder, not a launcher.
        context.packageManager.queryIntentActivities(home, 0).filter { it.priority > -1000 }.map { it.activityInfo.packageName }.toSet()
    }

    init {
        load()
    }

    /** Apps worth listing: they have a launcher entry and aren't a home screen. */
    private fun listable(pkg: String): Boolean =
        pkg != context.packageName && pkg !in homes && pkg !in NOT_APPS &&
            context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun record(pkg: String) {
        if (!listable(pkg)) return
        entries.removeAll { it.pkg == pkg }
        entries.add(0, Entry(pkg, System.currentTimeMillis()))
        trim()
        save()
    }

    fun remove(pkg: String) {
        entries.removeAll { it.pkg == pkg }
        save()
    }

    fun clear() {
        entries.clear()
        save()
    }

    /** Newest first. Cheap: uninstalled apps are dropped when Android reports them ([remove]), not here. */
    fun list(): List<Entry> = entries.toList()

    /** Loads the listed apps' names and icons into [Info] in the background, so the panel draws without looking them up. */
    fun warm() {
        val pkgs = entries.map { it.pkg }
        Shell.io.execute { pkgs.forEach { Info.load(context, it) } }
    }

    private fun trim() {
        val keep = Prefs(context).recentKept
        while (entries.size > keep) entries.removeAt(entries.size - 1)
    }

    private fun load() {
        entries.clear()
        store.getString("apps", null)?.lines()?.forEach { line ->
            val (pkg, at) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            if (pkg.isNotBlank()) entries.add(Entry(pkg, at))
        }
        trim()
    }

    /** Goes up with every change, so a panel built ahead of time knows it's out of date. */
    var version = 0
        private set

    private fun save() {
        version++
        store.edit().putString("apps", entries.joinToString("\n") { "${it.pkg} ${it.at}" }).apply()
    }

    /** App names and icons, cached across panel openings (the lookups are what made opening slow). */
    object Info {
        private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val icons = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

        fun load(context: Context, pkg: String) {
            if (labels.containsKey(pkg) && icons.containsKey(pkg)) return
            val pm = context.packageManager
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
            labels[pkg] = pm.getApplicationLabel(info).toString()
            runCatching { pm.getApplicationIcon(info) }.getOrNull()?.let { icons[pkg] = it }
        }

        fun label(context: Context, pkg: String): String = labels[pkg] ?: run { load(context, pkg); labels[pkg] ?: pkg }

        /** A fresh copy each time: one Drawable shouldn't be shared by two views. */
        fun icon(context: Context, pkg: String): android.graphics.drawable.Drawable? =
            (icons[pkg] ?: run { load(context, pkg); icons[pkg] })?.let { it.constantState?.newDrawable(context.resources) ?: it }

        fun forget(pkg: String) { labels.remove(pkg); icons.remove(pkg) }
    }

    companion object {
        private val NOT_APPS = setOf("com.android.systemui", "android", "com.android.settings.intelligence", "com.android.packageinstaller")

        /** "Open now", "2 min ago", "3 h ago", "Yesterday", "4 days ago". */
        fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
            val min = (now - at) / 60_000
            return when {
                at <= 0 -> ""
                min < 1 -> "Just now"
                min < 60 -> "$min min ago"
                min < 24 * 60 -> "${min / 60} h ago"
                min < 48 * 60 -> "Yesterday"
                else -> "${min / (24 * 60)} days ago"
            }
        }
    }
}
