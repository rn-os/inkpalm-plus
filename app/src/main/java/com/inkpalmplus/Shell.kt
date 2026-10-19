package com.inkpalmplus

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * ADB-level commands run on the device itself through Shizuku (started with
 * Wireless debugging, no computer). Used for the writes Moaan's firmware keeps
 * from ordinary apps. Same approach as SuperBoox's AdvancedAccess.
 */
object Shell {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 41

    val io = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    /** Shizuku is running (it has been started since the last restart). */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Running, and this app is allowed to use it. */
    fun allowed(): Boolean = running() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** Runs a shell command as ADB would, on the calling (background) thread. */
    fun run(command: String): Pair<Int, String> {
        if (!allowed()) return -1 to "Shizuku isn't connected"
        return runCatching {
            // Shizuku keeps newProcess private in its API; it's the documented way to run a command.
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val text = (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
            p.waitFor() to text
        }.getOrElse { -1 to "${it.javaClass.simpleName}: ${(it.cause ?: it).message}" }
    }

    /** Runs [work] off the main thread and hands its result back on it. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        io.execute {
            val result = work()
            main.post { done(result) }
        }
    }
}
