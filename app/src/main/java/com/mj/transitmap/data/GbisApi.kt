package com.mj.transitmap.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * 경기도 버스정보 v2 (공공데이터포털, apis.data.go.kr/6410000)
 *  - 주변정류소: busstationservice/v2/getBusStationAroundListv2
 *  - 정류소 검색: busstationservice/v2/getBusStationListv2
 *  - 도착정보:   busarrivalservice/v2/getBusArrivalListv2
 *  - 노선정류소: busrouteservice/v2/getBusRouteStationListv2
 *  - 버스위치:   buslocationservice/v2/getBusLocationListv2
 */
class GbisApi(private val serviceKey: String) {

    private val base = "https://apis.data.go.kr/6410000"

    /** 포털의 Encoding 키(이미 % 인코딩)와 Decoding 키를 모두 받는다. */
    private fun keyParam(): String =
        if (serviceKey.contains('%')) serviceKey else Http.enc(serviceKey)

    private suspend fun call(path: String, params: Map<String, String>): GbisEnvelope {
        val query = buildString {
            append("serviceKey=").append(keyParam())
            params.forEach { (k, v) -> append('&').append(k).append('=').append(Http.enc(v)) }
            append("&format=json")
        }
        return try {
            val res = Http.get("$base/$path?$query")
            GbisParser.parse(res.code, res.body)
        } catch (e: Exception) {
            GbisEnvelope.Error(Http.describe(e))
        }
    }

    /**
     * GBIS getBusStationAroundListv2 는 단일 좌표 반경 200m만 반환하므로,
     * 지도 화면에서 다른 지역으로 이동해 '대중교통 찾기'를 눌렀을 때 주변 정류장이 넓게 표시되도록
     * 중심점 + 상·하·좌·우(약 240m 간격) 5개 지점을 병렬 조회하여 합친다(반경 약 450m 커버).
     */
    suspend fun aroundStations(lat: Double, lng: Double): LoadResult<List<Stop>> = coroutineScope {
        val dLat = 0.0021 // 약 230m
        val dLng = 0.0027 // 약 240m
        val probes = listOf(
            lat to lng,
            (lat + dLat) to lng,
            (lat - dLat) to lng,
            lat to (lng + dLng),
            lat to (lng - dLng),
        )
        val envs = probes.map { (py, px) ->
            async {
                call("busstationservice/v2/getBusStationAroundListv2", mapOf("x" to px.toString(), "y" to py.toString()))
            }
        }.awaitAll()

        val merged = LinkedHashMap<String, Stop>()
        var firstError: GbisEnvelope.Error? = null
        for (env in envs) {
            when (env) {
                is GbisEnvelope.Items -> {
                    for (obj in env.items) {
                        val s = GbisParser.toStop(obj) ?: continue
                        if (s.id.isNotEmpty() && !merged.containsKey(s.id)) {
                            val dist = OverpassApi.distance(lat, lng, s.lat, s.lng)
                            merged[s.id] = s.copy(distanceM = dist)
                        }
                    }
                }
                is GbisEnvelope.Error -> if (firstError == null) firstError = env
                is GbisEnvelope.Empty -> Unit
            }
        }
        when {
            merged.isNotEmpty() -> LoadResult.Ok(merged.values.sortedBy { it.distanceM ?: Int.MAX_VALUE })
            firstError != null -> LoadResult.Failure(firstError.message, firstError.needsKey)
            else -> LoadResult.NoData("이 지역 주변 450m 내 경기버스 정류장이 없습니다")
        }
    }

    suspend fun searchStations(keyword: String): LoadResult<List<Stop>> =
        call("busstationservice/v2/getBusStationListv2", mapOf("keyword" to keyword))
            .map("'$keyword' 정류장을 찾지 못했습니다") { list -> list.mapNotNull(GbisParser::toStop) }

    suspend fun arrivals(stationId: String): LoadResult<List<Arrival>> =
        call("busarrivalservice/v2/getBusArrivalListv2", mapOf("stationId" to stationId))
            .map("도착 예정 버스 정보 없음 (운행 전·종료 또는 정보 미제공)") { list ->
                list.map(GbisParser::toArrival).sortedBy { it.seconds ?: Int.MAX_VALUE }
            }

    suspend fun routeStations(routeId: String): LoadResult<List<RouteStation>> =
        call("busrouteservice/v2/getBusRouteStationListv2", mapOf("routeId" to routeId))
            .map("노선 정류소 정보 없음") { list -> list.mapNotNull(GbisParser::toRouteStation).sortedBy { it.seq } }

    suspend fun busLocations(routeId: String): LoadResult<List<BusLocation>> =
        call("buslocationservice/v2/getBusLocationListv2", mapOf("routeId" to routeId))
            .map("현재 운행 중인 차량 정보 없음") { list -> list.map(GbisParser::toBusLocation) }
}

sealed interface GbisEnvelope {
    data class Items(val items: List<JSONObject>) : GbisEnvelope
    data class Empty(val message: String) : GbisEnvelope
    data class Error(val message: String, val needsKey: Boolean = false) : GbisEnvelope

    fun <T> map(emptyReason: String, transform: (List<JSONObject>) -> List<T>): LoadResult<List<T>> = when (this) {
        is Items -> {
            val out = runCatching { transform(items) }
            out.fold(
                onSuccess = { if (it.isEmpty()) LoadResult.NoData(emptyReason) else LoadResult.Ok(it) },
                onFailure = { LoadResult.Failure("응답 해석 오류") },
            )
        }
        is Empty -> LoadResult.NoData(emptyReason)
        is Error -> LoadResult.Failure(message, needsKey)
    }
}

object GbisParser {

    private val AUTH_MSG_REGEX = Regex("<returnAuthMsg>(.*?)</returnAuthMsg>")
    private val RESULT_MSG_REGEX = Regex("<resultMsg>(.*?)</resultMsg>")

    fun parse(httpCode: Int, body: String): GbisEnvelope {
        val t = body.trim()
        if (t.startsWith("<")) {
            // 공공데이터포털 게이트웨이 오류는 XML 로 온다 (키 미등록, 트래픽 초과 등)
            val auth = AUTH_MSG_REGEX.find(t)?.groupValues?.get(1)
                ?: RESULT_MSG_REGEX.find(t)?.groupValues?.get(1)
            return GbisEnvelope.Error(portalMessage(auth ?: "XML 응답"), needsKey = auth?.contains("KEY") == true)
        }
        if (httpCode == 401 || httpCode == 403 || t.equals("Unauthorized", ignoreCase = true)) {
            return GbisEnvelope.Error("경기버스 API 인증 실패 — 키 또는 활용신청 승인 상태를 확인하세요", needsKey = true)
        }
        if (!t.startsWith("{")) {
            return GbisEnvelope.Error(if (httpCode !in 200..299) "서버 오류 (HTTP $httpCode)" else "응답 형식 오류")
        }
        val root = runCatching { JSONObject(t) }.getOrElse { return GbisEnvelope.Error("응답 해석 오류") }
        val resp = root.optJSONObject("response") ?: root

        // 포털 공통 오류 JSON 형태 {"response":{"header":{"resultCode":"30","resultMsg":"..."}}}
        resp.optJSONObject("header")?.let { h ->
            val code = h.optString("resultCode")
            if (code.isNotEmpty() && code != "00" && code != "0") {
                val msg = h.optString("resultMsg")
                return GbisEnvelope.Error(portalMessage(msg), needsKey = msg.contains("KEY"))
            }
        }

        val header = resp.optJSONObject("msgHeader")
        val code = header?.opt("resultCode")?.toString()?.toIntOrNull()
        val message = header?.optString("resultMessage").orEmpty()
        return when (code) {
            0 -> GbisEnvelope.Items(firstList(resp.optJSONObject("msgBody")))
            4 -> GbisEnvelope.Empty(message)
            null -> GbisEnvelope.Error("응답 형식 오류")
            else -> GbisEnvelope.Error("경기버스 API 오류($code) $message".trim(), needsKey = code == 30 || code == 31)
        }
    }

    /** msgBody 안의 첫 배열(또는 단일 객체)을 목록으로 */
    fun firstList(body: JSONObject?): List<JSONObject> {
        body ?: return emptyList()
        val keys = body.keys()
        while (keys.hasNext()) {
            when (val v = body.opt(keys.next())) {
                is JSONArray -> return (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                is JSONObject -> return listOf(v)
            }
        }
        return emptyList()
    }

    private fun portalMessage(raw: String): String = when {
        raw.contains("SERVICE_KEY_IS_NOT_REGISTERED") -> "경기버스 API 키가 등록되지 않았습니다 (활용신청·승인 확인)"
        raw.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") -> "경기버스 API 일일 호출 한도 초과"
        raw.contains("SERVICE_ACCESS_DENIED") -> "경기버스 API 접근 거부 (활용신청 확인)"
        raw.contains("SERVICE_TIMEOUT") || raw.contains("HTTP_ERROR") -> "통신 오류: 공공데이터포털 응답 지연"
        else -> "공공데이터포털 오류: $raw"
    }

    /** JSON null / "null" / 공백 → 빈 문자열 */
    fun str(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return ""
        val s = o.opt(key)?.toString()?.trim().orEmpty()
        return if (s == "null") "" else s
    }

    fun int(o: JSONObject, key: String): Int? = str(o, key).toIntOrNull()

    fun toStop(o: JSONObject): Stop? {
        val lat = str(o, "y").toDoubleOrNull() ?: return null
        val lng = str(o, "x").toDoubleOrNull() ?: return null
        val mobile = str(o, "mobileNo")
        val region = str(o, "regionName")
        val sub = listOf(if (mobile.isNotEmpty()) "정류소번호 $mobile" else "", region).filter { it.isNotEmpty() }.joinToString(" · ")
        return Stop(
            type = StopType.BUS,
            id = str(o, "stationId"),
            name = str(o, "stationName"),
            lat = lat, lng = lng,
            subText = sub,
            distanceM = int(o, "distance"),
        )
    }

    fun toArrival(o: JSONObject): Arrival {
        val p1 = int(o, "predictTime1")
        val p2 = int(o, "predictTime2")
        val s1 = int(o, "predictTimeSec1")
        val loc1 = int(o, "locationNo1")
        val loc2 = int(o, "locationNo2")
        val seat1 = int(o, "remainSeatCnt1")
        val flag = str(o, "flag")
        val dest = str(o, "routeDestName")
        val plate = str(o, "plateNo1")

        val first = when {
            flag == "STOP" -> "운행 종료"
            flag == "WAIT" && p1 == null -> "회차지 대기 중"
            p1 == null -> "도착 정보 없음"
            p1 <= 1 -> "곧 도착"
            else -> "${p1}분"
        } + if (p1 != null && loc1 != null) " (${loc1}정류장 전)" else ""
        val second = when {
            p2 == null -> ""
            else -> "다음 ${p2}분" + if (loc2 != null) " (${loc2}정류장 전)" else ""
        }
        val detail = listOfNotNull(
            seat1?.takeIf { it >= 0 }?.let { "잔여 ${it}석" },
            plate.takeIf { it.isNotEmpty() },
            routeType(int(o, "routeTypeCd")),
        ).joinToString(" · ")

        return Arrival(
            type = StopType.BUS,
            lineId = str(o, "routeId"),
            lineName = str(o, "routeName"),
            direction = if (dest.isNotEmpty()) "$dest 방면" else "",
            dirKey = str(o, "staOrder"),
            message = first,
            seconds = s1 ?: p1?.times(60),
            secondMessage = second,
            detail = detail,
            lineColor = routeColor(int(o, "routeTypeCd")),
        )
    }

    fun toRouteStation(o: JSONObject): RouteStation? {
        val lat = str(o, "y").toDoubleOrNull() ?: return null
        val lng = str(o, "x").toDoubleOrNull() ?: return null
        return RouteStation(str(o, "stationId"), str(o, "stationName"), int(o, "stationSeq") ?: 0, lat, lng)
    }

    fun toBusLocation(o: JSONObject) = BusLocation(
        stationId = str(o, "stationId"),
        seq = int(o, "stationSeq") ?: 0,
        plateNo = str(o, "plateNo"),
        remainSeat = int(o, "remainSeatCnt")?.takeIf { it >= 0 },
    )

    private fun routeType(cd: Int?): String? = when (cd) {
        11 -> "직행좌석"; 12 -> "좌석"; 13 -> "일반"; 14 -> "광역급행"; 15 -> "따복"
        16 -> "경기순환"; 21, 22, 23 -> "농어촌"; 30 -> "마을"; 41, 42, 43 -> "시외"
        51, 52, 53 -> "공항"; else -> null
    }

    private fun routeColor(cd: Int?): Int = when (cd) {
        11, 14, 16 -> 0xFFE60012.toInt()   // 빨간 광역
        12 -> 0xFF0068B7.toInt()           // 파랑 좌석
        30 -> 0xFFF2B70A.toInt()           // 노랑 마을
        else -> 0xFF33A23D.toInt()         // 초록 일반
    }
}
