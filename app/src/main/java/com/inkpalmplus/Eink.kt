package com.inkpalmplus

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.concurrent.Executors

/**
 * The three ways to reach the e-ink controller's properties (from InkControl, see docs/firmware.md, "KompaktOS"):
 *
 * - **Binder**: stock Moaan firmware. `eink` service, `IEinkManager.setProperty` (call 5), no permission check.
 *   InkPalm+'s stock features ([Live]) use it as they always have.
 * - **Direct**: a build signed with the system's platform key (a later own-signed KompaktOS build). It runs as
 *   platform_app, which may set `vendor_ebook_prop` (`sys.eink.*`) itself; readable `sys.eink.mode` tells.
 * - **Bridge**: KompaktOS 1.2 (release-keys GSI). A root helper baked into the image
 *   (`/system/phh/inkpalm/eink-bridge.sh`) watches this app's files dir and applies whitelisted `key=value` lines
 *   from request files (`eink-apply.sh`): eink.req, light.req, standby.req, power.req.
 *
 * The bridge also does what no app may do on any backend (front light, sleep and power-off images, power off), so
 * [bridgeAvailable] is asked on its own. Writes go through [worker], one at a time.
 */
object Eink {
    private const val TAG = "InkPalmPlus"
    const val PROP_MODE = "sys.eink.mode"
    private val BRIDGE = File("/system/phh/inkpalm/eink-bridge.sh")
    private val APPLY = File("/system/phh/inkpalm/eink-apply.sh")

    enum class Backend { BINDER, DIRECT, BRIDGE, NONE }

    /** Everything that touches the bridge or the properties, in order, off the main thread. */
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "inkpalm-eink") }
    fun post(work: () -> Unit) = worker.execute { runCatching(work).onFailure { Log.w(TAG, "eink work failed: $it") } }

    @Volatile private var filesDir: File? = null

    /** Once per process (the service and activities call it; any context will do). */
    fun init(context: Context) {
        if (filesDir == null) filesDir = context.applicationContext.filesDir
    }

    /** The root bridge is in the system image (KompaktOS builds from the InkPalm-KompaktOS repo). */
    val bridgeInstalled: Boolean by lazy { runCatching { BRIDGE.exists() }.getOrDefault(false) }

    fun bridgeAvailable(): Boolean = filesDir != null && bridgeInstalled

    /** The bridge knows `power.req` (auto power-off); older images don't. */
    val bridgeKnowsPower: Boolean by lazy { bridgeAvailable() && runCatching { APPLY.readText().contains("power.req") }.getOrDefault(false) }

    val backend: Backend
        get() = when {
            // The bridge exists only on our GSIs, which have no Moaan eink service: don't look for it there
            // (each lookup logs an SELinux denial).
            bridgeInstalled -> if (directAllowed) Backend.DIRECT else Backend.BRIDGE
            binder() != null -> Backend.BINDER
            directAllowed -> Backend.DIRECT
            else -> Backend.NONE
        }

    /** KompaktOS / a GSI (not stock Moaan firmware): the left screen panel, Back gesture and the rest apply. */
    val isKompakt: Boolean get() = backend.let { it == Backend.BRIDGE || it == Backend.DIRECT }

    // ---- Binder (stock) -----------------------------------------------------------------------------

    @Volatile private var binder: IBinder? = null
    @Volatile private var missAt = -1L

    /** The `eink` service, or null. A miss is remembered for 5 minutes (stock may still be starting it). */
    fun binder(): IBinder? {
        binder?.takeIf { it.pingBinder() }?.let { return it }
        binder = null
        val now = SystemClock.elapsedRealtime()
        if (missAt >= 0 && now - missAt < 5 * 60_000L) return null
        val b = runCatching {
            HiddenApiBypass.invoke(Class.forName("android.os.ServiceManager"), null, "getService", "eink") as IBinder?
        }.onFailure { Log.w(TAG, "eink service lookup failed: $it") }.getOrNull()
        binder = b
        missAt = if (b == null) now else -1
        if (b == null) Log.i(TAG, "no eink binder service")
        return b
    }

    /** IEinkManager.setProperty (call 5). */
    fun binderSet(key: String, value: String): Boolean {
        val b = binder() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.os.IEinkManager")
            data.writeString(key)
            data.writeString(value)
            b.transact(5, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) {
            Log.w(TAG, "eink setProperty $key failed: $e")
            binder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ---- Direct (platform-signed) -------------------------------------------------------------------

    private val directAllowed: Boolean by lazy { Live.getProperty(PROP_MODE, "").isNotEmpty() }

    private fun directSet(key: String, value: String): Boolean = runCatching {
        HiddenApiBypass.invoke(Class.forName("android.os.SystemProperties"), null, "set", key, value)
        true
    }.onFailure { Log.w(TAG, "SystemProperties.set $key=$value failed: $it") }.getOrDefault(false)

    // ---- Bridge (KompaktOS) -------------------------------------------------------------------------

    private val lastWrite = HashMap<String, Long>()

    /**
     * Writes one request file for the bridge ([content]: `key=value` lines). The bridge reads it when it's closed;
     * a second write to the same file right after could replace the first before it's read, so writes to one file
     * are kept [GAP_MS] apart. Blocking: call on [worker].
     */
    fun bridgeWrite(file: String, content: String): Boolean {
        val dir = filesDir ?: return false
        if (!bridgeInstalled) return false
        synchronized(lastWrite) {
            val wait = (lastWrite[file] ?: 0L) + GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
            val ok = runCatching { File(dir, file).writeText(content, Charsets.US_ASCII); true }
                .onFailure { Log.w(TAG, "bridge request $file failed: $it") }.getOrDefault(false)
            lastWrite[file] = SystemClock.uptimeMillis()
            return ok
        }
    }

    private const val GAP_MS = 120L

    // ---- What the features use ----------------------------------------------------------------------

    /** Sets an e-ink property the best way there is. Blocking. */
    fun setProperty(key: String, value: String): Boolean = when (backend) {
        Backend.BINDER -> binderSet(key, value)
        Backend.DIRECT -> directSet(key, value)
        Backend.BRIDGE -> bridgeWrite("eink.req", "$key=$value\n")
        Backend.NONE -> false
    }

    /** Several at once (one bridge request). Blocking. */
    fun setProperties(vararg kv: Pair<String, String>): Boolean = when (backend) {
        Backend.BRIDGE -> bridgeWrite("eink.req", kv.joinToString("") { "${it.first}=${it.second}\n" })
        else -> kv.all { setProperty(it.first, it.second) }
    }

    /** The waveform for what's drawn from now on (an EPD mode, see [ScreenMode]). Blocking. */
    fun setMode(mode: Int): Boolean = setProperty(PROP_MODE, mode.toString())

    /**
     * One full refresh: the next frame is posted as a full one. Stock: a new `one_full_mode_timeline` value
     * ([Live.fullRefresh]); bridge: `eink.full=1` (it bumps the counter, which apps can't read); direct: read and bump.
     * Nothing changes on screen until something draws: see [ScreenModes.fullRefresh] for the repaint. Blocking.
     */
    fun requestFull(): Boolean = when (backend) {
        Backend.BRIDGE -> bridgeWrite("eink.req", "eink.full=1\n")
        Backend.DIRECT -> {
            val t = Live.getProperty("sys.eink.one_full_mode_timeline", "0").toLongOrNull() ?: 0
            directSet("sys.eink.one_full_mode_timeline", ((t + 1) % 1_000_000_000).toString())
        }
        else -> Live.fullRefreshStock()
    }

    /** One line for the status box on the Screen & light page. */
    fun describe(): String = when (backend) {
        Backend.BINDER -> "Moaan's e-ink service"
        Backend.DIRECT -> "Direct (system-signed build)"
        Backend.BRIDGE -> "KompaktOS bridge"
        Backend.NONE -> "Not available"
    }
}
