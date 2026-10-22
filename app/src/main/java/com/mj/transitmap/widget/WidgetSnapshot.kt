package com.mj.transitmap.widget

import org.json.JSONArray
import org.json.JSONObject

enum class RowStatus { OK, NO_DATA, ERROR, NEED_KEY, CACHED, SAMPLE }

data class WidgetRow(
    val key: String,
    val isBus: Boolean,
    val badge: String,
    val badgeColor: Int,
    val title: String,
    val direction: String,
    val main: String,
    val second: String,
    val status: RowStatus,
    val distanceM: Int? = null,
    /** 행 탭 시 앱에서 열 정류장/역 */
    val stopId: String = "",
    val stopName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 이 행의 실시간 값이 마지막으로 성공한 시각 (캐시 표시용) */
    val fetchedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("isBus", isBus).put("badge", badge).put("badgeColor", badgeColor)
        .put("title", title).put("direction", direction).put("main", main).put("second", second)
        .put("status", status.name).put("distanceM", distanceM ?: -1)
        .put("stopId", stopId).put("stopName", stopName).put("lat", lat).put("lng", lng)
        .put("fetchedAt", fetchedAt)

    companion object {
        fun fromJson(o: JSONObject) = WidgetRow(
            key = o.optString("key"),
            isBus = o.optBoolean("isBus"),
            badge = o.optString("badge"),
            badgeColor = o.optInt("badgeColor"),
            title = o.optString("title"),
            direction = o.optString("direction"),
            main = o.optString("main"),
            second = o.optString("second"),
            status = runCatching { RowStatus.valueOf(o.optString("status")) }.getOrDefault(RowStatus.NO_DATA),
            distanceM = o.optInt("distanceM", -1).takeIf { it >= 0 },
            stopId = o.optString("stopId"),
            stopName = o.optString("stopName"),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            fetchedAt = o.optLong("fetchedAt"),
        )
    }
}

data class WidgetSnapshot(
    val header: String,
    val note: String,
    val rows: List<WidgetRow>,
    val updatedAt: Long,
    val usedLocation: Boolean,
) {
    fun toJson(): String = JSONObject()
        .put("header", header).put("note", note).put("updatedAt", updatedAt).put("usedLocation", usedLocation)
        .put("rows", JSONArray().apply { rows.forEach { put(it.toJson()) } })
        .toString()

    companion object {
        val EMPTY = WidgetSnapshot("⭐ WalkerYu", "앱에서 정류장·역을 눌러 노선을 추가하세요", emptyList(), 0L, false)

        fun fromJson(text: String?): WidgetSnapshot {
            if (text.isNullOrBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(text)
                val arr = o.optJSONArray("rows") ?: JSONArray()
                WidgetSnapshot(
                    header = o.optString("header"),
                    note = o.optString("note"),
                    rows = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(WidgetRow::fromJson) },
                    updatedAt = o.optLong("updatedAt"),
                    usedLocation = o.optBoolean("usedLocation"),
                )
            }.getOrDefault(EMPTY)
        }
    }
}
