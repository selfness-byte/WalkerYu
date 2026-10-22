package com.mj.transitmap.data

import org.json.JSONArray
import org.json.JSONObject

object SubwayLines {
    data class Line(val id: String, val name: String, val short: String, val color: Int)

    val all: List<Line> = listOf(
        Line("1001", "1호선", "1", 0xFF0052A4.toInt()),
        Line("1002", "2호선", "2", 0xFF00A84D.toInt()),
        Line("1003", "3호선", "3", 0xFFEF7C1C.toInt()),
        Line("1004", "4호선", "4", 0xFF00A5DE.toInt()),
        Line("1005", "5호선", "5", 0xFF996CAC.toInt()),
        Line("1006", "6호선", "6", 0xFFCD7C2F.toInt()),
        Line("1007", "7호선", "7", 0xFF747F00.toInt()),
        Line("1008", "8호선", "8", 0xFFE6186C.toInt()),
        Line("1009", "9호선", "9", 0xFFBDB092.toInt()),
        Line("1061", "중앙선", "중앙", 0xFF77C4A3.toInt()),
        Line("1063", "경의중앙선", "경의", 0xFF77C4A3.toInt()),
        Line("1065", "공항철도", "공항", 0xFF0090D2.toInt()),
        Line("1067", "경춘선", "경춘", 0xFF0C8E72.toInt()),
        Line("1075", "수인분당선", "수인", 0xFFFABE00.toInt()),
        Line("1077", "신분당선", "신분", 0xFFD4003B.toInt()),
        Line("1092", "우이신설선", "우이", 0xFFB0CE18.toInt()),
        Line("1093", "서해선", "서해", 0xFF8FC31F.toInt()),
        Line("1081", "경강선", "경강", 0xFF003DA5.toInt()),
        Line("1032", "GTX-A", "GTX", 0xFF9A6292.toInt()),
    )

    fun byId(id: String): Line? = all.firstOrNull { it.id == id }
    fun name(id: String): String = byId(id)?.name ?: "지하철($id)"
    fun color(id: String): Int = byId(id)?.color ?: 0xFF555555.toInt()
}

/**
 * 서울 열린데이터광장 지하철 실시간 API (swopenapi.seoul.go.kr)
 *  - 실시간 도착: /api/subway/{KEY}/json/realtimeStationArrival/{start}/{end}/{역명}
 *  - 열차 위치:   /api/subway/{KEY}/json/realtimePosition/{start}/{end}/{호선명}
 * 키가 없으면 공식 'sample' 키(최대 5건)를 쓴다 — 실제 데이터지만 건수 제한이 있다.
 */
class SeoulSubwayApi(private val key: String) {

    private val isSample get() = key == "sample"
    private val base get() = "http://swopenapi.seoul.go.kr/api/subway/${Http.enc(key)}/json"
    private val end get() = if (isSample) 5 else 40

    suspend fun arrivals(stationName: String): LoadResult<List<Arrival>> {
        val first = arrivalsOnce(stationName)
        if (first !is LoadResult.NoData) return first
        // "총신대입구(이수)" 같은 이름이 실패하면 괄호를 뺀 이름으로 한 번 더
        if (stationName.contains('(')) {
            val alt = stationName.substringBefore('(').trim()
            if (alt.isNotEmpty()) return arrivalsOnce(alt)
        }
        // 지도 역명과 API 역명이 다른 경우 (부역명 포함 등)
        ALIASES[stationName]?.let { return arrivalsOnce(it) }
        return first
    }

    companion object {
        /** 지도(OSM/Wikidata) 역명 → 서울 실시간 API 역명 (확인되지 않은 항목은 실패 시에만 시도) */
        val ALIASES = mapOf(
            "총신대입구" to "총신대입구(이수)",
            "이수" to "총신대입구(이수)",
            "군자" to "군자(능동)",
            "천호" to "천호(풍납토성)",
            "어린이대공원" to "어린이대공원(세종대)",
            "쌍용" to "쌍용(나사렛대)",
            "신정" to "신정(은행정)",
            "오목교" to "오목교(목동운동장앞)",
            "상봉" to "상봉(시외버스터미널)",
            "몽촌토성" to "몽촌토성(평화의문)",
            "굽은다리" to "굽은다리(강동구민회관앞)",
            "아차산" to "아차산(어린이대공원후문)",
            "광나루" to "광나루(장신대)",
            "숭실대입구" to "숭실대입구(살피재)",
            "남한산성입구" to "남한산성입구(성남법원.검찰청)",
        )
    }

    private suspend fun arrivalsOnce(name: String): LoadResult<List<Arrival>> = try {
        val res = Http.get("$base/realtimeStationArrival/0/$end/${Http.enc(name)}")
        SeoulParser.parseArrivals(res.code, res.body)
    } catch (e: Exception) {
        LoadResult.Failure(Http.describe(e))
    }

    suspend fun positions(subwayId: String): LoadResult<List<TrainPosition>> {
        val lineName = SubwayLines.byId(subwayId)?.name ?: return LoadResult.NoData("위치 정보 미지원 노선")
        return try {
            val res = Http.get("$base/realtimePosition/0/${if (isSample) 5 else 100}/${Http.enc(lineName)}")
            SeoulParser.parsePositions(res.code, res.body)
        } catch (e: Exception) {
            LoadResult.Failure(Http.describe(e))
        }
    }
}

object SeoulParser {

    private sealed interface Env {
        data class Items(val arr: JSONArray) : Env
        data class Fail(val result: LoadResult<Nothing>) : Env
    }

    private fun envelope(httpCode: Int, body: String, listKey: String): Env {
        val t = body.trim()
        if (!t.startsWith("{")) {
            return Env.Fail(LoadResult.Failure(if (httpCode in 200..299) "지하철 API 응답 형식 오류" else "지하철 서버 오류 (HTTP $httpCode)"))
        }
        val root = runCatching { JSONObject(t) }.getOrElse { return Env.Fail(LoadResult.Failure("지하철 응답 해석 오류")) }
        val arr = root.optJSONArray(listKey)
        val err = root.optJSONObject("errorMessage") ?: root
        val code = err.optString("code")
        val msg = err.optString("message")
        if (arr != null && arr.length() > 0) return Env.Items(arr)
        return Env.Fail(
            when {
                code == "INFO-200" -> LoadResult.NoData("지하철 도착 정보 없음 (운행 전·종료 또는 역명 불일치)")
                code == "INFO-100" -> LoadResult.Failure("서울 지하철 API 인증키 오류", needsKey = true)
                code == "ERROR-337" -> LoadResult.Failure("서울 지하철 API 일일 호출 한도 초과", needsKey = true)
                code == "ERROR-336" -> LoadResult.Failure("sample 키는 최대 5건만 조회됩니다")
                code.startsWith("ERROR") -> LoadResult.Failure("지하철 API 오류 $code $msg".trim())
                else -> LoadResult.NoData("지하철 도착 정보 없음")
            }
        )
    }

    private fun s(o: JSONObject, k: String): String {
        if (!o.has(k) || o.isNull(k)) return ""
        val v = o.opt(k)?.toString()?.trim().orEmpty()
        return if (v == "null") "" else v
    }

    fun parseArrivals(httpCode: Int, body: String): LoadResult<List<Arrival>> =
        when (val env = envelope(httpCode, body, "realtimeArrivalList")) {
            is Env.Fail -> env.result
            is Env.Items -> {
                val list = (0 until env.arr.length()).mapNotNull { env.arr.optJSONObject(it) }.map { o ->
                    val subwayId = s(o, "subwayId")
                    val updn = s(o, "updnLine")
                    val lineNm = s(o, "trainLineNm")              // "성수행 - 시청방면"
                    val sec = s(o, "barvlDt").toIntOrNull()?.takeIf { it > 0 }
                    val msg2 = s(o, "arvlMsg2")
                    val msg3 = s(o, "arvlMsg3")
                    val kind = s(o, "btrainSttus")                // 일반/급행/ITX/특급
                    val last = s(o, "lstcarAt") == "1"
                    val message = when {
                        sec != null && !msg2.contains("도착") && !msg2.contains("출발") -> "${(sec + 30) / 60}분 후" + if (msg3.isNotEmpty()) " ($msg3)" else ""
                        msg2.isNotEmpty() -> msg2
                        else -> "정보 없음"
                    }
                    Arrival(
                        type = StopType.SUBWAY,
                        lineId = subwayId,
                        lineName = SubwayLines.name(subwayId),
                        direction = listOf(updn, lineNm).filter { it.isNotEmpty() }.joinToString(" · "),
                        dirKey = updn,
                        message = message,
                        seconds = sec,
                        detail = listOfNotNull(
                            kind.takeIf { it.isNotEmpty() && it != "일반" },
                            if (last) "막차" else null,
                            s(o, "recptnDt").takeIf { it.length >= 16 }?.let { "수신 ${it.substring(11, 16)}" },
                        ).joinToString(" · "),
                        lineColor = SubwayLines.color(subwayId),
                    )
                }
                if (list.isEmpty()) LoadResult.NoData("지하철 도착 정보 없음") else LoadResult.Ok(list)
            }
        }

    fun parsePositions(httpCode: Int, body: String): LoadResult<List<TrainPosition>> =
        when (val env = envelope(httpCode, body, "realtimePositionList")) {
            is Env.Fail -> env.result
            is Env.Items -> LoadResult.Ok((0 until env.arr.length()).mapNotNull { env.arr.optJSONObject(it) }.map { o ->
                TrainPosition(
                    subwayId = s(o, "subwayId"),
                    trainNo = s(o, "trainNo"),
                    stationName = s(o, "statnNm"),
                    updn = when (s(o, "updnLine")) { "0" -> "상행/내선"; "1" -> "하행/외선"; else -> s(o, "updnLine") },
                    terminal = s(o, "statnTnm"),
                    status = when (s(o, "trainSttus")) { "0" -> "진입"; "1" -> "도착"; "2" -> "출발"; "3" -> "전역출발"; else -> "운행" },
                    express = s(o, "directAt") == "1",
                    lastTrain = s(o, "lstcarAt") == "1",
                )
            })
        }

    /** 같은 호선·방향 열차들을 한 줄로 묶어 '이번/다음' 표시 */
    fun groupByDirection(list: List<Arrival>): List<Arrival> =
        list.groupBy { it.lineId to it.dirKey }.values.map { g ->
            // API 응답 순서(ordkey 기준)가 '가까운 열차 먼저' 이므로 그대로 사용
            val sorted = g
            val head = sorted.first()
            if (sorted.size > 1) head.copy(secondMessage = "다음 " + sorted[1].message) else head
        }.sortedWith(compareBy({ it.lineId }, { it.dirKey }))
}
