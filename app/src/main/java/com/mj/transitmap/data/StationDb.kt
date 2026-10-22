package com.mj.transitmap.data

import android.content.Context
import org.json.JSONArray

/**
 * 앱에 내장한 수도권 지하철·전철역 좌표 (assets/stations.json, 출처: OpenStreetMap, ODbL).
 * 공개 Overpass 서버가 자주 과부하라 주변 역 찾기는 내장 데이터를 먼저 쓴다.
 * 형식: [["역명", 위도, 경도], ...]  (역명은 서울 실시간 API 조회명으로 정규화된 값)
 */
object StationDb {
    @Volatile private var cache: List<Stop>? = null
    @Volatile private var coordsCache: Map<String, Pair<Double, Double>>? = null

    fun all(ctx: Context): List<Stop> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val list = runCatching {
                val text = ctx.applicationContext.assets.open("stations.json").bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i ->
                    val row = arr.optJSONArray(i) ?: return@mapNotNull null
                    val name = row.optString(0)
                    if (name.isEmpty()) null
                    else Stop(StopType.SUBWAY, name, "${name}역", row.optDouble(1), row.optDouble(2), subText = "지하철·전철역")
                }
            }.getOrDefault(emptyList())
            cache = list
            coordsCache = list.associate { it.id to (it.lat to it.lng) }
            return list
        }
    }

    fun coordsMap(ctx: Context): Map<String, Pair<Double, Double>> {
        coordsCache?.let { return it }
        all(ctx)
        return coordsCache ?: emptyMap()
    }

    /** 맵 복사 없이 O(1)로 지하철역 좌표를 조회한다 */
    fun coordOf(ctx: Context, rawOrNormName: String): Pair<Double, Double>? {
        if (rawOrNormName.isEmpty()) return null
        val m = coordsMap(ctx)
        m[rawOrNormName]?.let { return it }
        val norm = OverpassApi.normalizeName(rawOrNormName)
        m[norm]?.let { return it }
        val withoutParen = norm.substringBefore('(').trim()
        return if (withoutParen.isNotEmpty()) m[withoutParen] else null
    }

    fun nearby(ctx: Context, lat: Double, lng: Double, radiusM: Int = 1500): List<Stop> {
        // 위·경도 바운딩 박스로 먼저 걸러내어 불필요한 Stop.copy 객체 할당 및 거리 계산을 98% 이상 줄인다.
        val dLat = radiusM / 110_000.0
        val dLng = radiusM / 88_000.0
        val out = ArrayList<Stop>(8)
        for (s in all(ctx)) {
            if (kotlin.math.abs(s.lat - lat) > dLat || kotlin.math.abs(s.lng - lng) > dLng) continue
            val d = OverpassApi.distance(lat, lng, s.lat, s.lng)
            if (d <= radiusM) {
                out.add(s.copy(distanceM = d))
            }
        }
        out.sortBy { it.distanceM ?: Int.MAX_VALUE }
        return out
    }

    fun search(ctx: Context, keyword: String): List<Stop> {
        val k = OverpassApi.normalizeName(keyword.trim())
        if (k.isEmpty()) return emptyList()
        val all = all(ctx)
        return (all.filter { it.id == k } + all.filter { it.id != k && it.id.contains(k) }).take(30)
    }
}
