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
    /** 탑승 감지 시 기존 노선 행 아래에 표시할 미니 노선 진행 바 1줄 문구 (비어 있으면 미표시) */
    val rideTrack: String = "",
    /** 탑승 감지 시 미니 노선 진행 바 2줄 보조 문구 (차량번호·상태·잔여석 등) */
    val rideSub: String = "",
    /** 직전 주기에 매칭된 탑승 차량 번호판 또는 열차 번호 (연속 추적용) */
    val rideVehicleId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("isBus", isBus).put("badge", badge).put("badgeColor", badgeColor)
        .put("title", title).put("direction", direction).put("main", main).put("second", second)
        .put("status", status.name).put("distanceM", distanceM ?: -1)
        .put("stopId", stopId).put("stopName", stopName).put("lat", lat).put("lng", lng)
        .put("fetchedAt", fetchedAt)
        .put("rideTrack", rideTrack)
        .put("rideSub", rideSub)
        .put("rideVehicleId", rideVehicleId)

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
            rideTrack = o.optString("rideTrack"),
            rideSub = o.optString("rideSub"),
            rideVehicleId = o.optString("rideVehicleId"),
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

        @Volatile
        private var lastRawJson: String? = null

        @Volatile
        private var lastParsedSnapshot: WidgetSnapshot = EMPTY

        fun fromJson(text: String?): WidgetSnapshot {
            if (text.isNullOrBlank()) return EMPTY
            if (text == lastRawJson) return lastParsedSnapshot
            val parsed = runCatching {
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
            lastParsedSnapshot = parsed
            lastRawJson = text
            return parsed
        }
    }
}
