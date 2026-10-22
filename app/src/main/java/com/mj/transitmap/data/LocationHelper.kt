package com.mj.transitmap.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 빠른 현재 위치 조회 헬퍼:
 * 1) peekSync / peekFast: 메모리·LocationManager·Fused lastLocation 캐시를 즉시(0~150ms) 반환
 * 2) fresh: Fused(High/Balanced) + LocationManager(Network/GPS) 동시 경쟁(race)으로 가장 먼저 도착한 위치 반환
 * 3) startWarmup / stopWarmup: 앱 화면이 열려 있는 동안 위치를 미리 워밍업하여 '내 위치' 버튼 클릭 시 0ms 반응
 */
object LocationHelper {

    @Volatile
    private var latestMemoryLocation: Location? = null

    private var warmupCallback: LocationCallback? = null

    fun hasForeground(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** 위치의 경과 시간(ms) */
    fun ageMs(loc: Location): Long {
        val elapsed = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        return if (elapsed >= 0L) elapsed else (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
    }

    private fun remember(loc: Location?): Location? {
        if (loc != null) {
            val cur = latestMemoryLocation
            if (cur == null || ageMs(loc) <= ageMs(cur) + 5_000L) {
                latestMemoryLocation = loc
            }
        }
        return loc
    }

    /**
     * 대기 시간 0ms: 메모리 캐시 및 Android LocationManager(fused/network/gps/passive)의
     * 마지막 위치 중 가장 최신·정확한 위치를 즉시 반환한다.
     */
    @SuppressLint("MissingPermission")
    fun peekSync(ctx: Context): Location? {
        if (!hasForeground(ctx)) return null
        val candidates = mutableListOf<Location>()
        latestMemoryLocation?.let { candidates += it }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (p in providers) {
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { candidates += it }
            }
        }
        if (candidates.isEmpty()) return null
        // 2분 이내 위치 중 정확도가 좋은 것을 우선, 없으면 가장 최근 위치 선택
        val recent = candidates.filter { ageMs(it) <= 120_000L }
        val chosen = if (recent.isNotEmpty()) {
            recent.minByOrNull { it.accuracy }
        } else {
            candidates.minByOrNull { ageMs(it) }
        }
        return remember(chosen)
    }

    /**
     * 최대 150ms 이내에 즉시 사용 가능한 최근 위치를 반환한다.
     */
    @SuppressLint("MissingPermission")
    suspend fun peekFast(ctx: Context): Location? {
        if (!hasForeground(ctx)) return null
        val syncLoc = peekSync(ctx)
        if (syncLoc != null && ageMs(syncLoc) <= 60_000L) {
            return syncLoc
        }
        val fusedLast = withTimeoutOrNull(150L) {
            suspendCancellableCoroutine<Location?> { cont ->
                LocationServices.getFusedLocationProviderClient(ctx.applicationContext).lastLocation
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }
        remember(fusedLast)
        return when {
            fusedLast == null -> syncLoc
            syncLoc == null -> fusedLast
            ageMs(fusedLast) <= ageMs(syncLoc) -> fusedLast
            else -> syncLoc
        }
    }

    /**
     * 여러 위치 제공자(Fused High, Fused Balanced, LocationManager Network/GPS)를 동시에 요청하여
     * 가장 먼저 도착하는 위치를 즉시 반환한다.
     */
    @SuppressLint("MissingPermission")
    suspend fun fresh(ctx: Context, timeoutMs: Long = 3000L): Location? {
        if (!hasForeground(ctx)) return null
        val appCtx = ctx.applicationContext
        val client = LocationServices.getFusedLocationProviderClient(appCtx)

        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val winner = CompletableDeferred<Location>()

                // 1) Fused Balanced Power (Wi-Fi/ 기지국 기반 — 실내·도심에서 0.1~0.5초 내 응답)
                val jobBalanced = launch {
                    val loc = requestFusedCurrent(client, Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L, timeoutMs)
                    if (loc != null) winner.complete(loc)
                }

                // 2) Fused High Accuracy (GPS + 센서 융합)
                val jobHigh = launch {
                    val loc = requestFusedCurrent(client, Priority.PRIORITY_HIGH_ACCURACY, 15_000L, timeoutMs)
                    if (loc != null) winner.complete(loc)
                }

                // 3) Android LocationManager 직접 요청 (Network + GPS 동시 리스너)
                val jobLm = launch {
                    val loc = requestLocationManagerSingle(appCtx)
                    if (loc != null) winner.complete(loc)
                }

                val result = runCatching { winner.await() }.getOrNull()
                jobBalanced.cancel()
                jobHigh.cancel()
                jobLm.cancel()
                remember(result)
            }
        }
    }

    /**
     * 위젯(1분 주기 위치 ON 갱신) 및 일반 호출용:
     * 35초 이내의 최신 캐시가 있으면 즉시 반환하고, 그보다 오래되었으면 새 위치를 빠르게 조회한다.
     */
    suspend fun current(ctx: Context, timeoutMs: Long = 2500L): Location? {
        if (!hasForeground(ctx)) return null
        val fast = peekFast(ctx)
        if (fast != null && ageMs(fast) <= 35_000L) {
            return fast
        }
        return fresh(ctx, timeoutMs) ?: fast
    }

    /**
     * 앱이 포그라운드에 있는 동안 위치를 가볍게 워밍업하여 '내 위치' 버튼을 누를 때 지연 없이 즉시 이동하게 한다.
     */
    @SuppressLint("MissingPermission")
    fun startWarmup(ctx: Context) {
        if (!hasForeground(ctx) || warmupCallback != null) return
        val appCtx = ctx.applicationContext
        val client = LocationServices.getFusedLocationProviderClient(appCtx)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { remember(it) }
            }
        }
        warmupCallback = cb
        runCatching {
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        }
    }

    fun stopWarmup(ctx: Context) {
        val cb = warmupCallback ?: return
        warmupCallback = null
        runCatching {
            LocationServices.getFusedLocationProviderClient(ctx.applicationContext).removeLocationUpdates(cb)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFusedCurrent(
        client: com.google.android.gms.location.FusedLocationProviderClient,
        priority: Int,
        maxAgeMs: Long,
        durationMs: Long,
    ): Location? = suspendCancellableCoroutine { cont ->
        val cts = CancellationTokenSource()
        val req = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(maxAgeMs)
            .setDurationMillis(durationMs)
            .build()
        client.getCurrentLocation(req, cts.token)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { cts.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestLocationManagerSingle(ctx: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            try {
                for (p in providers) {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                }
            } catch (_: Exception) {
                runCatching { lm.removeUpdates(listener) }
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                runCatching { lm.removeUpdates(listener) }
            }
        }
}
