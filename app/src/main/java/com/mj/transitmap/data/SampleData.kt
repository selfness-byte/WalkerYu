package com.mj.transitmap.data

/**
 * '샘플 모드' 전용 가짜 데이터. 모든 이름 앞에 [샘플] 을 붙여 실시간 데이터로 오인하지 않게 한다.
 */
object SampleData {
    private const val TAG = "[샘플] "

    fun busStops(lat: Double, lng: Double): LoadResult<List<Stop>> = LoadResult.Ok(
        listOf(
            Stop(StopType.BUS, "SAMPLE-B1", TAG + "시청앞", lat + 0.0008, lng + 0.0006, "정류소번호 00001 (OO방향)", 90, sample = true),
            Stop(StopType.BUS, "SAMPLE-B2", TAG + "시청앞", lat + 0.0010, lng + 0.0011, "정류소번호 00002 (반대편)", 120, sample = true),
            Stop(StopType.BUS, "SAMPLE-B3", TAG + "중앙공원", lat - 0.0012, lng - 0.0004, "정류소번호 00003", 140, sample = true),
        ),
        sample = true,
    )

    fun stations(lat: Double, lng: Double): LoadResult<List<Stop>> = LoadResult.Ok(
        listOf(Stop(StopType.SUBWAY, "SAMPLE-S1", TAG + "중앙역", lat - 0.004, lng + 0.003, "지하철·전철역", 480, sample = true)),
        sample = true,
    )

    fun busArrivals(stationId: String): LoadResult<List<Arrival>> = LoadResult.Ok(
        listOf(
            Arrival(StopType.BUS, "S-R1", "${TAG}11", "OO역 방면", "3", "4분 (2정류장 전)", 240, "다음 15분", "잔여 12석", 0xFF33A23D.toInt(), true),
            Arrival(StopType.BUS, "S-R2", "${TAG}1100", "서울역 방면", "8", "곧 도착 (1정류장 전)", 50, "다음 22분", "직행좌석", 0xFFE60012.toInt(), true),
            Arrival(StopType.BUS, "S-R3", "${TAG}마을5", "시장 방면", "2", "도착 정보 없음", null, "", "", 0xFFF2B70A.toInt(), true),
        ).map { it.copy(lineId = "$stationId-${it.lineId}") },
        sample = true,
    )

    fun subwayArrivals(): LoadResult<List<Arrival>> = LoadResult.Ok(
        listOf(
            Arrival(StopType.SUBWAY, "1007", "${TAG}7호선", "상행 · 석남행 - 상동방면", "상행", "곧 도착", 50, "다음 6분 후", "", SubwayLines.color("1007"), true),
            Arrival(StopType.SUBWAY, "1007", "${TAG}7호선", "하행 · 장암행 - 신중동방면", "하행", "3분 후", 180, "다음 9분 후", "", SubwayLines.color("1007"), true),
        ),
        sample = true,
    )

    fun routeStations(routeId: String): LoadResult<List<RouteStation>> = LoadResult.Ok(
        listOf(
            RouteStation("SAMPLE-B0", "소풍터미널", 1, 37.5042, 126.7570),
            RouteStation("SAMPLE-B1", "시청앞", 2, 37.5043, 126.7666),
            RouteStation("SAMPLE-B2", "꿈마을사거리", 3, 37.5028, 126.7712),
            RouteStation("SAMPLE-B3", "신중동역", 4, 37.5030, 126.7760),
        ),
        sample = true,
    )

    fun busLocations(routeId: String): LoadResult<List<BusLocation>> = LoadResult.Ok(
        listOf(
            BusLocation("SAMPLE-B1", 2, "경기72바1042", 19),
        ),
        sample = true,
    )

    fun trainPositions(subwayId: String): LoadResult<List<TrainPosition>> = LoadResult.Ok(
        listOf(
            TrainPosition(
                subwayId = subwayId.ifEmpty { "1007" },
                trainNo = "7124",
                stationName = "부천시청",
                updn = "상행/내선",
                terminal = "석남",
                status = "출발",
                express = false,
                lastTrain = false,
            ),
        ),
        sample = true,
    )
}
