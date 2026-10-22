package com.mj.transitmap.data

import android.location.Location
import org.json.JSONObject

/**
 * 지하철역 좌표는 서울 실시간 API 에 없으므로 OpenStreetMap(Overpass API, 키 불필요)에서 조회한다.
 * 결과 역명은 서울 API 조회명에 맞게 정규화한다 ("부천역" → "부천").
 */
object OverpassApi {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    // 수도권 대략 범위 (남,서,북,동)
    private const val CAPITAL_BBOX = "36.80,126.30,38.20,127.90"

    suspend fun nearbyStations(lat: Double, lng: Double, radiusM: Int = 1500): LoadResult<List<Stop>> {
        val q = """
            [out:json][timeout:20];
            (
              node["railway"="station"](around:$radiusM,$lat,$lng);
              node["public_transport"="station"]["subway"="yes"](around:$radiusM,$lat,$lng);
              node["public_transport"="station"]["train"="yes"](around:$radiusM,$lat,$lng);
            );
            out body;
        """.trimIndent()
        return run(q, lat, lng, "반경 ${radiusM / 1000.0}km 내 지하철역이 없습니다")
    }

    suspend fun searchStations(keyword: String): LoadResult<List<Stop>> {
        val safe = keyword.replace(Regex("[^0-9A-Za-z가-힣·]"), "").removeSuffix("역")
        if (safe.isEmpty()) return LoadResult.NoData("검색어가 비었습니다")
        val q = """
            [out:json][timeout:20];
            node["railway"="station"]["name"~"^$safe(역)?( \\(.*\\))?$"]($CAPITAL_BBOX);
            out body;
        """.trimIndent()
        return run(q, null, null, "'$keyword' 역을 찾지 못했습니다")
    }

    private suspend fun run(query: String, lat: Double?, lng: Double?, emptyReason: String): LoadResult<List<Stop>> {
        var last: LoadResult<List<Stop>> = LoadResult.Failure("지도 데이터(OSM) 서버 응답 없음")
        // 공개 Overpass 서버는 자주 과부하(429/502/504)가 나므로 미러를 차례로 시도
        for (endpoint in ENDPOINTS) {
            last = try {
                val res = Http.postForm(endpoint, mapOf("data" to query))
                when {
                    res.code in 200..299 -> {
                        val stops = parse(res.body, lat, lng)
                        return if (stops.isEmpty()) LoadResult.NoData(emptyReason) else LoadResult.Ok(stops)
                    }
                    res.code == 429 -> LoadResult.Failure("지도 데이터(OSM) 요청이 많습니다. 잠시 후 다시 시도하세요")
                    else -> LoadResult.Failure("지도 데이터(OSM) 서버 오류 (HTTP ${res.code})")
                }
            } catch (e: Exception) {
                LoadResult.Failure(Http.describe(e))
            }
        }
        return last
    }

    fun parse(body: String, lat: Double?, lng: Double?): List<Stop> {
        val arr = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val byName = LinkedHashMap<String, Stop>()
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val raw = tags.optString("name:ko").ifEmpty { tags.optString("name") }
            if (raw.isEmpty()) continue
            // 화물역·폐역 제외
            if (tags.optString("usage") == "freight" || tags.has("disused")) continue
            val sLat = el.optDouble("lat", Double.NaN)
            val sLng = el.optDouble("lon", Double.NaN)
            if (sLat.isNaN() || sLng.isNaN()) continue
            val name = normalizeName(raw)
            val dist = if (lat != null && lng != null) distance(lat, lng, sLat, sLng) else null
            val stop = Stop(StopType.SUBWAY, name, "${name}역", sLat, sLng, subText = "지하철·전철역", distanceM = dist)
            val prev = byName[name]
            if (prev == null || (dist != null && (prev.distanceM ?: Int.MAX_VALUE) > dist)) byName[name] = stop
        }
        return byName.values.sortedBy { it.distanceM ?: 0 }
    }

    /** OSM 역명 → 서울 API 역명 */
    fun normalizeName(raw: String): String {
        var n = raw.trim()
        n = n.replace(Regex("\\s*\\((?:\\d+호선|[가-힣A-Za-z]+선)\\)$"), "")   // "부천 (1호선)" 류 제거
        if (n.length > 2 && n.endsWith("역")) n = n.dropLast(1)
        return n.trim()
    }

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Int {
        val out = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, out)
        return out[0].toInt()
    }
}
