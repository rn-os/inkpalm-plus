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
 * Open-Meteo's geocoding; name and coordinates kept, no location permission). This is InkPalm+'s only use of the
 * internet: the coordinates go to api.open-meteo.com, a typed city name to geocoding-api.open-meteo.com.
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
        data class Failed(val why: String) : Located()
    }

    /** The longest wait for one fix, over all providers. */
    const val LOCATE_MS = 20_000L

    /**
     * "Use my location": ONE coarse fix, never tracking (no listener is left registered). Asks the network provider,
     * then fused, then GPS ([Home.providerOrder], those present and on) for a current location within [LOCATE_MS]
     * in all; else the newest last known fix under an hour old. Rounded to 0.01° ([Home.roundCoord]) before anything
     * else sees it, named by [Geocoder] when there is one. Needs ACCESS_COARSE_LOCATION. Blocking (not on the main thread).
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun locate(c: Context): Located {
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return Located.Failed("Location permission not given")
        val lm = c.getSystemService(android.location.LocationManager::class.java) ?: return Located.Failed("No location service on this phone")
        if (!lm.isLocationEnabled) return Located.Failed("Location is off in Android's settings")
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val order = Home.providerOrder(enabled)
        Log.i(TAG, "home: locate: providers on $enabled, asking $order")
        val deadline = SystemClock.elapsedRealtime() + LOCATE_MS
        var fix: android.location.Location? = null; var via = ""
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (p in order) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val got = java.util.concurrent.LinkedBlockingQueue<Any>(1)
                val cancel = android.os.CancellationSignal()
                val asked = runCatching { lm.getCurrentLocation(p, cancel, exec) { l -> got.offer(l ?: Unit) } }
                    .onFailure { Log.w(TAG, "home: locate $p: $it") }.isSuccess
                if (!asked) continue
                val r = got.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (r == null) cancel.cancel()
                if (r is android.location.Location) { fix = r; via = p; break }
            }
        } finally { exec.shutdown() }
        if (fix == null) {
            val now = SystemClock.elapsedRealtimeNanos()
            fix = (enabled + "passive").distinct().mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .filter { Home.freshFix((now - it.elapsedRealtimeNanos) / 1_000_000) }.maxByOrNull { it.elapsedRealtimeNanos }
            via = fix?.provider?.let { "last known ($it)" } ?: ""
        }
        val l = fix ?: return Located.Failed(if (order.isEmpty()) "No location provider is on" else "No location found within ${LOCATE_MS / 1000} s")
        val lat = Home.roundCoord(l.latitude); val lon = Home.roundCoord(l.longitude)
        val name = if (android.location.Geocoder.isPresent()) runCatching {
            @Suppress("DEPRECATION")
            android.location.Geocoder(c).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.onFailure { Log.w(TAG, "home: geocoder: $it") }.getOrNull() else null
        Log.i(TAG, "home: located via $via (named: ${name != null})")
        return Located.Found(Home.Place(Home.locationLabel(name, lat, lon), "", lat, lon), via)
    }

    /** Searches Open-Meteo's geocoding for [name]. Null: offline or it failed. Blocking. */
    fun search(name: String): List<Home.Place>? {
        val q = URLEncoder.encode(name.trim(), "UTF-8")
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json") ?: return null
        return Home.parsePlaces(body)
    }

    private fun get(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "InkPalmPlus")
        try {
            if (conn.responseCode != 200) { Log.w(TAG, "home: HTTP ${conn.responseCode}"); null }
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }.onFailure { Log.w(TAG, "home: $it") }.getOrNull()
}
