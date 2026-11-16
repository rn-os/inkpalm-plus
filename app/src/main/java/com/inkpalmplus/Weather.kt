package com.inkpalmplus

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Home face's weather: Open-Meteo (free, no key, no account), for a city the user sets once (searched with
 * Open-Meteo's geocoding, or found once by "Use my location"; name and coordinates kept). This is InkPalm+'s only use
 * of the internet: the coordinates go to api.open-meteo.com, a typed city name to geocoding-api.open-meteo.com, and,
 * only when the user taps "Estimate from internet connection" after its explanation, one request to ipinfo.io
 * ([estimateFromIp]), which sees the public IP address.
 *
 * Fetched at most every [Home.REFRESH_MS] (30 min), only when a network is already up (Wi-Fi on; radios are never
 * turned on), with an [TIMEOUT_MS] limit and no retries: asleep it piggybacks on the Live update's alarm wake
 * ([Aod.update]: fetch, then render), awake on the minute tick. The answer is cached (files/weather.json) and shown
 * with its age when stale or offline ([Home.weatherView]).
 */
object Weather {
    private const val TAG = "InkPalmPlus"
    private const val K_NAME = "home_city"
    private const val K_LAT = "home_lat"
    private const val K_LON = "home_lon"
    private const val K_UNITS = "home_units"
    private const val K_FETCHED = "home_weather_at"
    private const val K_ATTEMPT = "home_weather_try"
    /** Connect and read, each. */
    const val TIMEOUT_MS = 4_000
    /** The whole fetch (DNS included, which the timeouts don't cover) is waited for this long, then given up. */
    const val DEADLINE_MS = 8_000L

    data class City(val name: String, val lat: Double, val lon: Double)

    fun city(c: Context): City? {
        val sp = Prefs(c).sp
        val name = sp.getString(K_NAME, null) ?: return null
        if (!sp.contains(K_LAT) || !sp.contains(K_LON)) return null
        return City(name, Double.fromBits(sp.getLong(K_LAT, 0)), Double.fromBits(sp.getLong(K_LON, 0)))
    }

    /** Sets (or with null, removes) the city; the old forecast goes with it. */
    fun setCity(c: Context, p: Home.Place?) {
        val e = Prefs(c).sp.edit().remove(K_FETCHED).remove(K_ATTEMPT)
        if (p == null) e.remove(K_NAME).remove(K_LAT).remove(K_LON)
        else e.putString(K_NAME, p.name).putLong(K_LAT, p.lat.toBits()).putLong(K_LON, p.lon.toBits())
        e.apply()
        cache(c).delete()
    }

    fun units(c: Context): Home.Units = Home.Units.of(Prefs(c).sp.getString(K_UNITS, null))
    fun setUnits(c: Context, u: Home.Units) = Prefs(c).sp.edit().putString(K_UNITS, u.name).apply()
    fun fahrenheit(c: Context) = Home.fahrenheit(units(c))

    private fun cache(c: Context) = File(c.filesDir, "weather.json")

    fun fetchedAt(c: Context): Long = Prefs(c).sp.getLong(K_FETCHED, 0L)

    /** The cached forecast, or null. */
    fun cached(c: Context): Home.Forecast? = runCatching { cache(c).takeIf { it.isFile }?.readText()?.let { Home.parseForecast(it) } }.getOrNull()

    /** A network is up that reaches the internet (never turns one on). */
    fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** A fetch is due: a city is set, the last try was 30 min ago or more, and a network is up. */
    fun due(c: Context): Boolean = city(c) != null && Home.due(System.currentTimeMillis(), Prefs(c).sp.getLong(K_ATTEMPT, 0L)) && online(c)

    private val busy = AtomicBoolean(false)

    /** One fetch when [due] (else nothing). Blocking, at most [DEADLINE_MS]: a slower one is left to finish (or time
     *  out) on its own thread and is used by the next draw. True when a new forecast was cached in time. */
    fun fetchIfDue(c: Context): Boolean {
        if (!due(c) || busy.get()) return false
        val task = java.util.concurrent.FutureTask { fetch(c) }
        Thread(task, "weather").start()
        return runCatching { task.get(DEADLINE_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .onFailure { Log.w(TAG, "home: weather fetch still running after $DEADLINE_MS ms, not waiting") }.getOrDefault(false)
    }

    private fun fetch(c: Context): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            val city = city(c) ?: return false
            Prefs(c).sp.edit().putLong(K_ATTEMPT, System.currentTimeMillis()).apply()
            val t0 = SystemClock.uptimeMillis()
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.US, city.lat)}&longitude=${"%.4f".format(Locale.US, city.lon)}" +
                "&current=temperature_2m,weather_code&hourly=precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&timezone=auto&timeformat=unixtime&forecast_days=2"
            val body = get(url)
            if (body == null || Home.parseForecast(body) == null) { Log.w(TAG, "home: weather fetch failed in ${SystemClock.uptimeMillis() - t0} ms"); return false }
            val tmp = File(c.filesDir, "weather.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(cache(c))) return false
            Prefs(c).sp.edit().putLong(K_FETCHED, System.currentTimeMillis()).apply()
            Log.i(TAG, "home: weather fetched in ${SystemClock.uptimeMillis() - t0} ms (${body.length} B)")
            return true
        } finally { busy.set(false) }
    }

    /** Awake: a fetch off the main thread when due, then [then] (on the worker) if it got one. */
    fun fetchInBackground(c: Context, then: () -> Unit) {
        val app = c.applicationContext
        if (!due(app)) return
        if (busy.get()) return
        Thread({ if (fetch(app)) then() }, "weather").start()
    }

    /** Why "Use my location" found nothing, or the place it found. */
    sealed class Located {
        data class Found(val place: Home.Place, val via: String) : Located()
        /** [precise]: precise location was granted (else only approximate, which Android rate-limits). */
        data class Failed(val fail: Home.LocateFail, val precise: Boolean) : Located()
    }

    /** The longest wait for one fix, over all providers. */
    const val LOCATE_MS = 45_000L

    fun precise(c: Context) = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun approximate(c: Context) = c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * "Use my location": ONE fix, never tracking (no listener is left registered). The providers that are on
     * ([Home.providerOrder]: network, fused, GPS; on the InkPalm microG's network and fused, no GPS) are each asked
     * at once for a current location (API 31+: a single high-accuracy request when precise location is granted, so
     * microG scans Wi-Fi now instead of after Android's ~10 min limit for approximate apps); a provider with no
     * current fix is listened to (minTime 0) until the first one. The first fix within [LOCATE_MS] wins and
     * everything is cancelled and removed; else the newest last known fix under an hour old. Rounded to 0.01°
     * ([Home.roundCoord]) before anything else sees it, named by [Geocoder] when there is one. Each step is logged
     * ("home: locate: …"). Blocking (not on the main thread).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun locate(c: Context): Located {
        val precise = precise(c)
        if (!precise && !approximate(c)) return Located.Failed(Home.LocateFail.NO_PERMISSION, false)
        val lm = c.getSystemService(android.location.LocationManager::class.java)
            ?: return Located.Failed(Home.LocateFail.NO_SERVICE, precise)
        if (!lm.isLocationEnabled) { Log.i(TAG, "home: locate: location is off"); return Located.Failed(Home.LocateFail.LOCATION_OFF, precise) }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = Home.providerOrder(enabled)
        Log.i(TAG, "home: locate: providers on $enabled, asking $order, ${if (precise) "precise" else "approximate only"}, up to ${LOCATE_MS / 1000} s")
        val t0 = SystemClock.elapsedRealtime()
        val got = java.util.concurrent.LinkedBlockingQueue<Pair<String, android.location.Location>>()
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val lock = Any()
        var done = false
        val cancels = mutableListOf<android.os.CancellationSignal>()
        val listeners = mutableListOf<android.location.LocationListener>()
        fun listen(p: String) = synchronized(lock) {
            if (done) return@synchronized
            val l = android.location.LocationListener { loc -> got.offer(p to loc) }
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, exec, l); listeners += l }
                .onSuccess { Log.i(TAG, "home: locate: $p: listening for the first update") }
                .onFailure { Log.w(TAG, "home: locate: $p: can't listen: $it") }
        }
        val l = try {
            for (p in order) {
                val cancel = android.os.CancellationSignal()
                synchronized(lock) { cancels += cancel }
                val answer = java.util.function.Consumer<android.location.Location?> { loc ->
                    if (loc != null) got.offer(p to loc)
                    else { Log.i(TAG, "home: locate: $p: no current fix after ${(SystemClock.elapsedRealtime() - t0) / 1000} s"); listen(p) }
                }
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        val req = android.location.LocationRequest.Builder(0L)
                            .setQuality(if (precise) android.location.LocationRequest.QUALITY_HIGH_ACCURACY else android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                            .setDurationMillis(LOCATE_MS).build()
                        lm.getCurrentLocation(p, req, cancel, exec, answer)
                    } else lm.getCurrentLocation(p, cancel, exec, answer)
                }.onSuccess { Log.i(TAG, "home: locate: $p: asked for one ${if (precise) "high-accuracy" else "approximate"} fix") }
                    .onFailure { Log.w(TAG, "home: locate: $p: getCurrentLocation failed: $it"); listen(p) }
            }
            if (order.isEmpty()) null else got.poll(LOCATE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            synchronized(lock) {
                done = true
                cancels.forEach { runCatching { it.cancel() } }
                listeners.forEach { runCatching { lm.removeUpdates(it) } }
            }
            exec.shutdown()
        }
        var fix = l?.second
        var via = l?.first.orEmpty()
        if (fix != null) Log.i(TAG, "home: locate: $via: fix after ${(SystemClock.elapsedRealtime() - t0) / 1000} s (accuracy ~${fix.accuracy.toInt()} m)")
        else {
            Log.i(TAG, "home: locate: no fix within ${(SystemClock.elapsedRealtime() - t0) / 1000} s, trying last known fixes")
            val now = SystemClock.elapsedRealtimeNanos()
            fix = (enabled + "passive").distinct().mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { Home.freshFix((now - it.elapsedRealtimeNanos) / 1_000_000) }.maxByOrNull { it.elapsedRealtimeNanos }
            via = fix?.provider?.let { "last known ($it)" } ?: ""
        }
        if (fix == null) {
            Log.i(TAG, "home: locate: nothing found")
            return Located.Failed(if (order.isEmpty()) Home.LocateFail.NO_PROVIDER else Home.LocateFail.NO_FIX, precise)
        }
        val lat = Home.roundCoord(fix.latitude); val lon = Home.roundCoord(fix.longitude)
        val name = if (android.location.Geocoder.isPresent()) runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.onFailure { Log.w(TAG, "home: locate: geocoder: $it") }.getOrNull() else null
        Log.i(TAG, "home: locate: located via $via (named: ${name != null})")
        return Located.Found(Home.Place(Home.locationLabel(name, lat, lon), "", lat, lon), via)
    }

    /** "Estimate from internet connection", only when the user taps it after reading [Home.IP_NOTE]: one HTTPS
     *  request to [Home.IP_SERVICE], which sees the public IP address and answers with a city. The place (rounded) or
     *  why not. Blocking. */
    fun estimateFromIp(): Pair<Home.Place?, String?> {
        Log.i(TAG, "home: locate: ip estimate: asking ${Home.IP_SERVICE}")
        val (code, body) = request("https://${Home.IP_SERVICE}/json")
        val place = body?.let { Home.parseIpPlace(it) }
        val why = when {
            place != null -> null
            code == 429 -> "${Home.IP_SERVICE} is busy (too many requests). Try again later, or search for your city."
            code == -1 -> "Couldn't reach ${Home.IP_SERVICE}. Is Wi\u2011Fi on?"
            else -> "${Home.IP_SERVICE} didn't give a city. Search for your city instead."
        }
        Log.i(TAG, "home: locate: ip estimate: HTTP $code, ${if (place != null) "got a city" else "no city"}")
        return place to why
    }

    /** True when microG provides location (its settings screen has the Wi-Fi location switch). */
    fun microg(c: Context): Boolean = runCatching {
        c.packageManager.getActivityInfo(android.content.ComponentName(GMS, "org.microg.gms.ui.SettingsActivity"), 0); true
    }.getOrDefault(false)

    /** microG's location settings (Wi-Fi location), else its settings, else Android's location settings. */
    fun locationSettingsIntents(): List<android.content.Intent> = listOf(
        android.content.Intent("com.google.android.gms.location.settings.GOOGLE_LOCATION_SETTINGS").setPackage(GMS),
        android.content.Intent().setClassName(GMS, "org.microg.gms.ui.SettingsActivity"),
        android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    )

    private const val GMS = "com.google.android.gms"

    /** Searches Open-Meteo's geocoding for [name]. Null: offline or it failed. Blocking. */
    fun search(name: String): List<Home.Place>? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json") ?: return null
        return Home.parsePlaces(body)
    }

    private fun get(url: String): String? = request(url).second

    /** One GET: the HTTP status (-1 when unreachable) and the body when 200. */
    private fun request(url: String): Pair<Int, String?> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "InkPalmPlus")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) { Log.w(TAG, "home: HTTP $code"); code to null }
            else code to conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.onFailure { Log.w(TAG, "home: $it") }.getOrDefault(-1 to null)
}
