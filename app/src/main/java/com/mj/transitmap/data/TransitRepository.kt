package com.mj.transitmap.data

import android.content.Context
import android.os.SystemClock
import android.util.LruCache
import java.util.Locale

/** 화면·위젯이 공통으로 쓰는 데이터 진입점. 샘플 모드/키 미설정을 여기서 처리한다. */
class TransitRepository(context: Context) {
    private val appContext = context.applicationContext
    val prefs = AppPrefs(context)

    private val sample get() = prefs.sampleMode
    private fun gbis() = GbisApi(prefs.gbisKey)
    private fun subway() = SeoulSubwayApi(prefs.effectiveSubwayKey)

    private val noBusKey = LoadResult.Failure("경기버스 API 키가 없습니다 — 설정에서 입력하세요", needsKey = true)

    private fun isSampleId(id: String) = id.startsWith("SAMPLE-")

    suspend fun nearbyBusStops(lat: Double, lng: Double): LoadResult<List<Stop>> = when {
        sample -> SampleData.busStops(lat, lng)
        prefs.gbisKey.isBlank() -> noBusKey
        else -> {
            val gridKey = String.format(Locale.US, "%.3f,%.3f", lat, lng)
            aroundBusStopsCache.get(gridKey)?.let { return LoadResult.Ok(it) }
            val res = gbis().aroundStations(lat, lng)
            if (res is LoadResult.Ok && res.value.isNotEmpty()) {
                aroundBusStopsCache.put(gridKey, res.value)
            }
            res
        }
    }

    suspend fun nearbyStations(lat: Double, lng: Double): LoadResult<List<Stop>> {
        if (sample) return SampleData.stations(lat, lng)
        // 1) 내장 역 좌표 (네트워크 불필요)
        if (StationDb.all(appContext).isNotEmpty()) {
            val near = StationDb.nearby(appContext, lat, lng)
            return if (near.isEmpty()) LoadResult.NoData("반경 1.5km 내 지하철역이 없습니다") else LoadResult.Ok(near)
        }
        // 2) 내장 데이터가 없을 때만 Overpass
        val r = OverpassApi.nearbyStations(lat, lng)
        if (r is LoadResult.Ok) prefs.rememberStations(r.value)
        return r
    }

    fun knownStationCoords(): Map<String, Pair<Double, Double>> {
        val base = StationDb.coordsMap(appContext)
        val extra = prefs.knownStations()
        return if (extra.isEmpty()) base else base + extra
    }

    /** 전체 맵 복제(700+ 엔트리 복사) 없이 O(1)로 지하철역 좌표를 반환한다 */
    fun coordOfStation(stationName: String): Pair<Double, Double>? =
        StationDb.coordOf(appContext, stationName)
            ?: prefs.knownStations()[stationName]
            ?: prefs.knownStations()[OverpassApi.normalizeName(stationName)]

    suspend fun searchBusStops(keyword: String): LoadResult<List<Stop>> = when {
        sample -> SampleData.busStops(37.5035, 126.7660)
        prefs.gbisKey.isBlank() -> noBusKey
        else -> gbis().searchStations(keyword)
    }

    suspend fun searchStations(keyword: String): LoadResult<List<Stop>> {
        if (sample) return SampleData.stations(37.5035, 126.7660)
        val local = StationDb.search(appContext, keyword)
        if (local.isNotEmpty()) return LoadResult.Ok(local)
        if (StationDb.all(appContext).isNotEmpty()) return LoadResult.NoData("'$keyword' 역을 찾지 못했습니다")
        val r = OverpassApi.searchStations(keyword)
        if (r is LoadResult.Ok) prefs.rememberStations(r.value)
        return r
    }

    suspend fun busArrivals(stationId: String): LoadResult<List<Arrival>> = when {
        sample || isSampleId(stationId) -> SampleData.busArrivals(stationId)
        prefs.gbisKey.isBlank() -> noBusKey
        else -> {
            busArrivalsCache.get(stationId)?.let { return LoadResult.Ok(it) }
            val res = gbis().arrivals(stationId)
            if (res is LoadResult.Ok) busArrivalsCache.put(stationId, res.value)
            res
        }
    }

    /** 원본(열차별) 목록. 화면에서는 SeoulParser.groupByDirection 으로 묶어서 표시 */
    suspend fun subwayArrivals(stationName: String): LoadResult<List<Arrival>> {
        if (sample || isSampleId(stationName)) return SampleData.subwayArrivals()
        subwayArrivalsCache.get(stationName)?.let { return LoadResult.Ok(it) }
        val r = subway().arrivals(stationName)
        // 입력한 키가 서울 실시간 지하철 키가 아니면(INFO-100) 공식 sample 키(최대 5건)로 대신 조회
        if (r is LoadResult.Failure && r.needsKey && !prefs.usingSubwaySampleKey) {
            prefs.subwayKeyRejected = true
            val fallback = SeoulSubwayApi("sample").arrivals(stationName)
            if (fallback is LoadResult.Ok) subwayArrivalsCache.put(stationName, fallback.value)
            return fallback
        }
        if (r is LoadResult.Ok) {
            if (!prefs.usingSubwaySampleKey) prefs.subwayKeyRejected = false
            subwayArrivalsCache.put(stationName, r.value)
        }
        return r
    }

    suspend fun trainPositions(subwayId: String): LoadResult<List<TrainPosition>> {
        if (sample) return SampleData.trainPositions(subwayId)
        trainPositionsCache.get(subwayId)?.let { return LoadResult.Ok(it) }
        val r = subway().positions(subwayId)
        if (r is LoadResult.Failure && r.needsKey && !prefs.usingSubwaySampleKey) {
            prefs.subwayKeyRejected = true
            val fallback = SeoulSubwayApi("sample").positions(subwayId)
            if (fallback is LoadResult.Ok) trainPositionsCache.put(subwayId, fallback.value)
            return fallback
        }
        if (r is LoadResult.Ok) trainPositionsCache.put(subwayId, r.value)
        return r
    }

    suspend fun routeStations(routeId: String): LoadResult<List<RouteStation>> = when {
        sample || isSampleId(routeId) -> SampleData.routeStations(routeId)
        prefs.gbisKey.isBlank() -> noBusKey
        else -> {
            synchronized(routeStationsCache) { routeStationsCache.get(routeId) }?.let {
                return LoadResult.Ok(it)
            }
            val res = gbis().routeStations(routeId)
            if (res is LoadResult.Ok && res.value.isNotEmpty()) {
                synchronized(routeStationsCache) { routeStationsCache.put(routeId, res.value) }
            }
            res
        }
    }

    suspend fun busLocations(routeId: String): LoadResult<List<BusLocation>> = when {
        sample || isSampleId(routeId) -> SampleData.busLocations(routeId)
        prefs.gbisKey.isBlank() -> noBusKey
        else -> {
            busLocationsCache.get(routeId)?.let { return LoadResult.Ok(it) }
            val res = gbis().busLocations(routeId)
            if (res is LoadResult.Ok) busLocationsCache.put(routeId, res.value)
            res
        }
    }

    private class TimedCache<V : Any>(maxEntries: Int, private val ttlMs: Long) {
        private data class Entry<V>(val value: V, val savedAtMs: Long)
        private val lru = LruCache<String, Entry<V>>(maxEntries)

        @Synchronized
        fun get(key: String): V? {
            val e = lru.get(key) ?: return null
            if (SystemClock.elapsedRealtime() - e.savedAtMs > ttlMs) {
                lru.remove(key)
                return null
            }
            return e.value
        }

        @Synchronized
        fun put(key: String, value: V) {
            lru.put(key, Entry(value, SystemClock.elapsedRealtime()))
        }
    }

    companion object {
        /** 버스 노선별 경유 정류장 목록 메모리 캐시 (최대 24개 노선 보관 — 반복 네트워크 요청 및 메모리 할당 절감) */
        private val routeStationsCache = LruCache<String, List<RouteStation>>(24)
        /** 주변 버스정류장 단기 캐시 (45초 TTL — 동일 위치 중복 5지점 병렬 조회 방지) */
        private val aroundBusStopsCache = TimedCache<List<Stop>>(maxEntries = 16, ttlMs = 45_000L)
        /** 실시간 도착·차량 위치 단기 디바운스 캐시 (8초 TTL — 위젯/화면 동시 호출 및 API 일일 한도 소진 방지) */
        private val busArrivalsCache = TimedCache<List<Arrival>>(maxEntries = 24, ttlMs = 8_000L)
        private val subwayArrivalsCache = TimedCache<List<Arrival>>(maxEntries = 24, ttlMs = 8_000L)
        private val busLocationsCache = TimedCache<List<BusLocation>>(maxEntries = 16, ttlMs = 8_000L)
        private val trainPositionsCache = TimedCache<List<TrainPosition>>(maxEntries = 16, ttlMs = 8_000L)
    }
}
