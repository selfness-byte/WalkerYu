package com.mj.transitmap.data

import android.content.Context
import android.util.LruCache

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
        else -> gbis().aroundStations(lat, lng)
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
        else -> gbis().arrivals(stationId)
    }

    /** 원본(열차별) 목록. 화면에서는 SeoulParser.groupByDirection 으로 묶어서 표시 */
    suspend fun subwayArrivals(stationName: String): LoadResult<List<Arrival>> {
        if (sample || isSampleId(stationName)) return SampleData.subwayArrivals()
        val r = subway().arrivals(stationName)
        // 입력한 키가 서울 실시간 지하철 키가 아니면(INFO-100) 공식 sample 키(최대 5건)로 대신 조회
        if (r is LoadResult.Failure && r.needsKey && !prefs.usingSubwaySampleKey) {
            prefs.subwayKeyRejected = true
            return SeoulSubwayApi("sample").arrivals(stationName)
        }
        if (r is LoadResult.Ok && !prefs.usingSubwaySampleKey) prefs.subwayKeyRejected = false
        return r
    }

    suspend fun trainPositions(subwayId: String): LoadResult<List<TrainPosition>> {
        if (sample) return LoadResult.NoData("샘플 모드에서는 열차 위치를 제공하지 않습니다")
        val r = subway().positions(subwayId)
        if (r is LoadResult.Failure && r.needsKey && !prefs.usingSubwaySampleKey) {
            prefs.subwayKeyRejected = true
            return SeoulSubwayApi("sample").positions(subwayId)
        }
        return r
    }

    suspend fun routeStations(routeId: String): LoadResult<List<RouteStation>> = when {
        sample -> LoadResult.NoData("샘플 모드에서는 노선 위치를 제공하지 않습니다")
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
        sample -> LoadResult.NoData("샘플 모드")
        prefs.gbisKey.isBlank() -> noBusKey
        else -> gbis().busLocations(routeId)
    }

    companion object {
        /** 버스 노선별 경유 정류장 목록 메모리 캐시 (최대 24개 노선 보관 — 반복 네트워크 요청 및 메모리 할당 절감) */
        private val routeStationsCache = LruCache<String, List<RouteStation>>(24)
    }
}
