package com.mj.transitmap.data

import org.json.JSONArray
import org.json.JSONObject

enum class StopType { BUS, SUBWAY }

/** 지도에 표시하는 정류장 또는 지하철역 */
data class Stop(
    val type: StopType,
    /** 버스: GBIS stationId / 지하철: 서울 API 조회용 역명 */
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** 버스: 정류소번호·지역 (같은 이름의 반대편 정류장 구분용) */
    val subText: String = "",
    val distanceM: Int? = null,
    val sample: Boolean = false,
)

/** 한 정류장/역에 대한 도착 정보 한 줄 */
data class Arrival(
    val type: StopType,
    /** 버스: routeId / 지하철: subwayId */
    val lineId: String,
    val lineName: String,
    /** 사람이 읽는 방향 (예: "OO 방면", "상행 · 시청방면") */
    val direction: String,
    /** 방향 구분 키. 버스: staOrder / 지하철: updnLine(상행·하행·내선·외선) */
    val dirKey: String,
    val message: String,
    val seconds: Int? = null,
    val secondMessage: String = "",
    val detail: String = "",
    val lineColor: Int? = null,
    val sample: Boolean = false,
)

data class TrainPosition(
    val subwayId: String,
    val trainNo: String,
    val stationName: String,
    val updn: String,
    val terminal: String,
    val status: String,
    val express: Boolean,
    val lastTrain: Boolean,
)

data class RouteStation(val stationId: String, val name: String, val seq: Int, val lat: Double, val lng: Double)

data class BusLocation(val stationId: String, val seq: Int, val plateNo: String, val remainSeat: Int?)

/** 조회 결과. '정보 없음'과 '통신 오류'를 반드시 구분한다. */
sealed interface LoadResult<out T> {
    data class Ok<T>(val value: T, val sample: Boolean = false) : LoadResult<T>
    data class NoData(val reason: String) : LoadResult<Nothing>
    data class Failure(val reason: String, val needsKey: Boolean = false) : LoadResult<Nothing>
}

/** 고정 목적지에서 '탈 곳' 하나 (정류장+노선+방향 또는 역+호선+방향) */
data class BoardItem(
    val type: StopType,
    val stopId: String,
    val stopName: String,
    val stopSub: String,
    val lat: Double,
    val lng: Double,
    val lineId: String,
    val lineName: String,
    val direction: String,
    val dirKey: String,
) {
    val key: String get() = "$type|$stopId|$lineId|$dirKey"

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.name).put("stopId", stopId).put("stopName", stopName)
        .put("stopSub", stopSub).put("lat", lat).put("lng", lng)
        .put("lineId", lineId).put("lineName", lineName)
        .put("direction", direction).put("dirKey", dirKey)

    companion object {
        fun fromJson(o: JSONObject) = BoardItem(
            type = runCatching { StopType.valueOf(o.getString("type")) }.getOrDefault(StopType.BUS),
            stopId = o.optString("stopId"),
            stopName = o.optString("stopName"),
            stopSub = o.optString("stopSub"),
            lat = o.optDouble("lat", 0.0),
            lng = o.optDouble("lng", 0.0),
            lineId = o.optString("lineId"),
            lineName = o.optString("lineName"),
            direction = o.optString("direction"),
            dirKey = o.optString("dirKey"),
        )
    }
}

data class Destination(val id: String, val name: String, val items: List<BoardItem>) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Destination {
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(BoardItem::fromJson) }
            return Destination(o.optString("id"), o.optString("name"), items)
        }

        fun listFromJson(text: String?): List<Destination> = runCatching {
            val arr = JSONArray(text ?: "[]")
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<Destination>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}
