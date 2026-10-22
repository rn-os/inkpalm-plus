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

    /** Newest first; apps uninstalled since are dropped. */
    fun list(): List<Entry> {
        val gone = entries.filter { context.packageManager.getLaunchIntentForPackage(it.pkg) == null }
        if (gone.isNotEmpty()) { entries.removeAll(gone.toSet()); save() }
        return entries.toList()
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

    private fun save() {
        store.edit().putString("apps", entries.joinToString("\n") { "${it.pkg} ${it.at}" }).apply()
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
