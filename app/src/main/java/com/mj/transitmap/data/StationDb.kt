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

    fun all(ctx: Context): List<Stop> {
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
        return list
    }

    fun nearby(ctx: Context, lat: Double, lng: Double, radiusM: Int = 1500): List<Stop> =
        all(ctx).map { it.copy(distanceM = OverpassApi.distance(lat, lng, it.lat, it.lng)) }
            .filter { (it.distanceM ?: Int.MAX_VALUE) <= radiusM }
            .sortedBy { it.distanceM }

    fun search(ctx: Context, keyword: String): List<Stop> {
        val k = OverpassApi.normalizeName(keyword.trim())
        if (k.isEmpty()) return emptyList()
        val all = all(ctx)
        return (all.filter { it.id == k } + all.filter { it.id != k && it.id.contains(k) }).take(30)
    }
}
