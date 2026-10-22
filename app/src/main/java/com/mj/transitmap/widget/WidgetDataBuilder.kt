package com.mj.transitmap.widget

import android.content.Context
import android.location.Location
import com.mj.transitmap.data.Arrival
import com.mj.transitmap.data.BoardItem
import com.mj.transitmap.data.BusLocation
import com.mj.transitmap.data.LoadResult
import com.mj.transitmap.data.LocationHelper
import com.mj.transitmap.data.OverpassApi
import com.mj.transitmap.data.RouteStation
import com.mj.transitmap.data.SeoulParser
import com.mj.transitmap.data.StationDb
import com.mj.transitmap.data.StopType
import com.mj.transitmap.data.SubwayLines
import com.mj.transitmap.data.TrainPosition
import com.mj.transitmap.data.TransitRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/**
 * 위젯에 보여줄 내용을 만든다.
 *  - 별도의 '고정 목적지(묶음 관리)' 선택 없이 사용자가 지도에서 추가한 모든 노선(savedItems)을 그대로 표시한다.
 *  - 위치 갱신 OFF: 추가한 노선들을 추가한 순서 그대로 전부 표시 (위치 요청 없음)
 *  - 위치 갱신 ON : 추가한 모든 노선을 현재 위치에서 가까운 순으로 정렬 + 거리 표시.
 *                   추가한 노선이 없으면 현재 위치 주변 정류장·역을 자동 표시.
 */
class WidgetDataBuilder(private val context: Context) {

    private val repo = TransitRepository(context)
    private val prefs = repo.prefs

    suspend fun build(previous: WidgetSnapshot): WidgetSnapshot {
        val now = System.currentTimeMillis()
        val useLoc = prefs.widgetUseLocation
        val saved = prefs.savedItems

        var note = ""
        val loc = if (useLoc) {
            when {
                !LocationHelper.hasForeground(context) -> {
                    LocationHelper.current(context).also { if (it == null) note = "위치 권한 없음 — 앱 설정에서 허용" }
                }
                !LocationHelper.hasBackground(context) -> {
                    LocationHelper.current(context).also { if (it == null) note = "백그라운드 위치 권한 필요 (설정 > 항상 허용)" }
                }
                else -> LocationHelper.current(context).also { if (it == null) note = "현재 위치를 가져오지 못함" }
            }
        } else null

        val prevByKey = previous.rows.associateBy { it.key }

        // 1) 사용자가 추가한 노선이 있으면 추가한 노선 전체를 표시
        if (saved.isNotEmpty()) {
            var items = saved
            val dist = HashMap<String, Int>()
            if (loc != null) {
                items.forEach { dist[it.key] = OverpassApi.distance(loc.latitude, loc.longitude, it.lat, it.lng) }
                items = items.sortedBy { dist[it.key] ?: Int.MAX_VALUE }
            }
            val rows = fetchRows(items.take(MAX_ROWS), dist, prevByKey, loc, now)
            return WidgetSnapshot(
                header = "⭐ 내 노선 (${rows.size}개)",
                note = note.ifEmpty { if (prefs.sampleMode) "샘플 모드 — 실제 정보 아님" else "" },
                rows = rows,
                updatedAt = now,
                usedLocation = loc != null,
            )
        }

        // 2) 추가한 노선이 없고 위치가 있으면 주변 자동 표시
        if (loc != null) {
            val rows = nearbyRows(loc.latitude, loc.longitude, prevByKey, now)
            return WidgetSnapshot("📍 내 주변 노선", note.ifEmpty { "지도에서 노선 옆 ‘★ 노선 추가’를 누르면 고정됩니다" }, rows, now, true)
        }

        return WidgetSnapshot(
            header = "⭐ WalkerYu",
            note = note.ifEmpty { "지도에서 정류장·역을 눌러 ‘★ 노선 추가’를 누르거나 📍위치 ON을 켜세요" },
            rows = emptyList(),
            updatedAt = now,
            usedLocation = false,
        )
    }

    private suspend fun fetchRows(
        items: List<BoardItem>,
        dist: Map<String, Int>,
        prev: Map<String, WidgetRow>,
        loc: Location?,
        now: Long,
    ): List<WidgetRow> = coroutineScope {
        // 같은 정류장/역은 한 번만 조회
        val stops = items.map { it.type to it.stopId }.distinct()
        val resultsDef = stops.map { (type, id) ->
            async {
                (type to id) to if (type == StopType.BUS) repo.busArrivals(id) else repo.subwayArrivals(id)
            }
        }

        // 위치 ON 상태일 때: 내 현재 위치와 운행 중인 버스·열차 위치를 비교해 탑승 이동 여부(방법 1 미니 노선 바)를 계산
        val busRouteIds = if (loc != null) {
            items.filter { it.type == StopType.BUS && it.lineId.isNotEmpty() }.map { it.lineId }.distinct()
        } else emptyList()
        val subwayLineIds = if (loc != null) {
            items.filter { it.type == StopType.SUBWAY && it.lineId.isNotEmpty() }.map { it.lineId }.distinct()
        } else emptyList()

        val busRouteDef = busRouteIds.map { rid ->
            async {
                val st = (repo.routeStations(rid) as? LoadResult.Ok)?.value.orEmpty()
                val bl = (repo.busLocations(rid) as? LoadResult.Ok)?.value.orEmpty()
                rid to (st to bl)
            }
        }
        val subwayPosDef = subwayLineIds.map { lid ->
            async {
                lid to (repo.trainPositions(lid) as? LoadResult.Ok)?.value.orEmpty()
            }
        }

        val results = resultsDef.awaitAll().toMap()
        val busRouteMap = busRouteDef.awaitAll().toMap()
        val subwayPosMap = subwayPosDef.awaitAll().toMap()
        val nearSubwayStations = if (loc != null && subwayLineIds.isNotEmpty()) {
            StationDb.nearby(context, loc.latitude, loc.longitude, radiusM = 650)
        } else emptyList()

        items.map { item ->
            val res = results[item.type to item.stopId]
            val prevRow = prev[item.key]
            val baseRow = rowFor(item, res, dist[item.key], prevRow, now)
            if (loc == null) {
                baseRow
            } else {
                val (track, vehicleId) = detectRideProgress(
                    item = item,
                    loc = loc,
                    prevVehicleId = prevRow?.rideVehicleId.orEmpty(),
                    busData = busRouteMap[item.lineId],
                    subwayTrains = subwayPosMap[item.lineId].orEmpty(),
                    nearSubwayStations = nearSubwayStations,
                )
                baseRow.copy(rideTrack = track, rideVehicleId = vehicleId)
            }
        }
    }

    /**
     * 내 현재 위치(loc)와 실시간 운행 버스/열차 위치를 비교하여,
     * 해당 노선에 탑승해 이동 중이라고 판단되면 기존 노선 행 아래에 표시할
     * '방법 1 미니 노선 진행 바(● 현재/이전 ━━🚌/🚇━━▶ ◎ 다음 역)' 문구와 차량 ID를 반환한다.
     */
    private fun detectRideProgress(
        item: BoardItem,
        loc: Location,
        prevVehicleId: String,
        busData: Pair<List<RouteStation>, List<BusLocation>>?,
        subwayTrains: List<TrainPosition>,
        nearSubwayStations: List<com.mj.transitmap.data.Stop>,
    ): Pair<String, String> {
        if (item.type == StopType.BUS) {
            val (stations, buses) = busData ?: return "" to ""
            if (stations.isEmpty() || buses.isEmpty()) return "" to ""
            val bySeq = stations.associateBy { it.seq }

            // 1) 내 현재 위치에서 가장 가까운 노선 경유 정류장 찾기
            var nearestSt: RouteStation? = null
            var minDist = Int.MAX_VALUE
            for (st in stations) {
                val d = OverpassApi.distance(loc.latitude, loc.longitude, st.lat, st.lng)
                if (d < minDist) {
                    minDist = d
                    nearestSt = st
                }
            }
            val userSt = nearestSt ?: return "" to ""
            // 노선 경로에서 400m 넘게 벗어나 있으면 미탑승
            if (minDist > 400) return "" to ""

            val savedStopSeq = item.dirKey.toIntOrNull()
                ?: stations.firstOrNull { it.stationId == item.stopId }?.seq

            // 2) 직전에 추적 중이던 버스(prevVehicleId)가 여전히 내 주변(±2 정류장)에 있으면 우선 유지,
            //    없으면 내 현재 정류장 구간(±1 정류장)을 함께 지나고 있는 버스 차량 매칭
            val matchedBus = buses.firstOrNull {
                prevVehicleId.isNotEmpty() && it.plateNo == prevVehicleId && abs(it.seq - userSt.seq) <= 2
            } ?: buses
                .filter { b ->
                    val nearUser = abs(b.seq - userSt.seq) <= 1 && minDist <= 300
                    val afterBoardStop = savedStopSeq == null || b.seq >= savedStopSeq
                    nearUser && afterBoardStop
                }
                .minByOrNull { abs(it.seq - userSt.seq) }
                ?: return "" to ""

            val currName = bySeq[matchedBus.seq]?.name ?: userSt.name
            val nextName = bySeq[matchedBus.seq + 1]?.name ?: "종점"
            val plateShort = matchedBus.plateNo.takeLast(4)
            return "🟢 탑승이동($plateShort)  ● $currName ━━🚌━━▶ ◎ $nextName" to matchedBus.plateNo
        } else {
            if (subwayTrains.isEmpty()) return "" to ""
            val nearNames = nearSubwayStations.map { OverpassApi.normalizeName(it.id) }.toSet()

            // 방향 일치 여부 확인 (상행/내선 vs 하행/외선)
            fun matchesDir(t: TrainPosition): Boolean {
                if (item.dirKey.isEmpty()) return true
                val k = item.dirKey.take(2)
                return t.updn.contains(k) || k.contains(t.updn.take(2))
            }

            // 1) 직전에 매칭된 열차 번호(prevVehicleId)가 여전히 운행 중이고 내 근처 역이거나 지하 이동 중이면 유지
            val continuedTrain = if (prevVehicleId.isNotEmpty()) {
                subwayTrains.firstOrNull { it.trainNo == prevVehicleId && matchesDir(it) }
            } else null

            // 2) 내 현재 위치 주변 역(650m 이내)에 진입/도착/출발 중인 같은 방향 열차 매칭
            val nearbyTrain = if (nearNames.isNotEmpty()) {
                subwayTrains.firstOrNull { t ->
                    matchesDir(t) && nearNames.contains(OverpassApi.normalizeName(t.stationName))
                }
            } else null

            val matched = nearbyTrain ?: continuedTrain ?: return "" to ""
            val currStation = matched.stationName
            // 알려진 역 좌표 목록에서 진행방향의 다음 역 추정 (없으면 종착역 표시)
            val nextLabel = "${matched.terminal}행 이동중"
            return "🟢 탑승이동(${matched.trainNo}호)  ● ${currStation}(${matched.status}) ━━🚇━━▶ ◎ $nextLabel" to matched.trainNo
        }
    }

    private fun rowFor(item: BoardItem, res: LoadResult<List<Arrival>>?, distance: Int?, prev: WidgetRow?, now: Long): WidgetRow {
        val isBus = item.type == StopType.BUS
        val typeEmoji = if (isBus) "🚌" else "🚇"
        val isStarred = prefs.isStarredStop(item.type, item.stopId)
        val starPrefix = if (isStarred) "★ " else ""
        val base = WidgetRow(
            key = item.key, isBus = isBus,
            badge = if (isBus) item.lineName else SubwayLines.byId(item.lineId)?.name ?: item.lineName,
            badgeColor = if (isBus) 0xFF008952.toInt() else SubwayLines.color(item.lineId),
            title = "$starPrefix$typeEmoji ${item.stopName}",
            direction = item.direction,
            main = "", second = "", status = RowStatus.NO_DATA, distanceM = distance,
            stopId = item.stopId, stopName = item.stopName, lat = item.lat, lng = item.lng,
        )
        return when (res) {
            is LoadResult.Ok -> {
                val match = pick(item, res.value)
                if (match != null) base.copy(
                    badgeColor = match.lineColor ?: base.badgeColor,
                    main = match.message,
                    second = match.secondMessage,
                    status = if (res.sample) RowStatus.SAMPLE else RowStatus.OK,
                    fetchedAt = now,
                ) else base.copy(
                    main = if (isBus) "도착 예정 정보 없음" else "이 방향 도착 정보 없음",
                    second = "운행 전·종료이거나 정보 미제공",
                    status = RowStatus.NO_DATA, fetchedAt = now,
                )
            }
            is LoadResult.NoData -> base.copy(main = "정보 없음", second = res.reason, status = RowStatus.NO_DATA, fetchedAt = now)
            is LoadResult.Failure -> failureRow(base, res, prev, now)
            null -> base.copy(main = "조회 안 됨", status = RowStatus.ERROR)
        }
    }

    private fun failureRow(base: WidgetRow, res: LoadResult.Failure, prev: WidgetRow?, now: Long): WidgetRow {
        val canUseCache = prev != null && prev.fetchedAt > 0 &&
            (prev.status == RowStatus.OK || prev.status == RowStatus.CACHED) &&
            now - prev.fetchedAt < CACHE_MAX_AGE_MS
        return if (canUseCache) {
            prev!!.copy(
                distanceM = base.distanceM,
                status = RowStatus.CACHED,
                second = "⚠ ${res.reason} · ${WidgetRenderer.hhmm(prev.fetchedAt)} 기준",
            )
        } else base.copy(
            main = if (res.needsKey) "API 키 확인 필요" else "통신 오류",
            second = res.reason,
            status = if (res.needsKey) RowStatus.NEED_KEY else RowStatus.ERROR,
        )
    }

    /** 저장한 노선/방향에 맞는 도착 정보 선택 */
    private fun pick(item: BoardItem, list: List<Arrival>): Arrival? = if (item.type == StopType.BUS) {
        val same = list.filter { it.lineId == item.lineId }
        same.firstOrNull { item.dirKey.isEmpty() || it.dirKey == item.dirKey } ?: same.firstOrNull()
    } else {
        SeoulParser.groupByDirection(list.filter { it.lineId == item.lineId })
            .firstOrNull { item.dirKey.isEmpty() || it.dirKey == item.dirKey }
    }

    private suspend fun nearbyRows(lat: Double, lng: Double, prev: Map<String, WidgetRow>, now: Long): List<WidgetRow> = coroutineScope {
        val busStops = async { repo.nearbyBusStops(lat, lng) }
        val stations = async { repo.nearbyStations(lat, lng) }
        val rows = mutableListOf<WidgetRow>()

        when (val st = stations.await()) {
            is LoadResult.Ok -> st.value.firstOrNull()?.let { s ->
                val arr = repo.subwayArrivals(s.id)
                if (arr is LoadResult.Ok) {
                    SeoulParser.groupByDirection(arr.value).take(4).forEach { a ->
                        val item = BoardItem(StopType.SUBWAY, s.id, s.name, s.subText, s.lat, s.lng, a.lineId, a.lineName, a.direction, a.dirKey)
                        rows += rowFor(item, arr, s.distanceM, prev[item.key], now)
                    }
                } else {
                    val item = BoardItem(StopType.SUBWAY, s.id, s.name, s.subText, s.lat, s.lng, "", "지하철", "", "")
                    rows += rowFor(item, arr, s.distanceM, prev[item.key], now)
                }
            }
            else -> Unit
        }

        when (val bs = busStops.await()) {
            is LoadResult.Ok -> bs.value.take(3).forEach { s ->
                val arr = repo.busArrivals(s.id)
                if (arr is LoadResult.Ok) {
                    arr.value.take(4).forEach { a ->
                        val item = BoardItem(StopType.BUS, s.id, s.name, s.subText, s.lat, s.lng, a.lineId, a.lineName, a.direction, a.dirKey)
                        rows += rowFor(item, arr, s.distanceM, prev[item.key], now)
                    }
                } else {
                    val item = BoardItem(StopType.BUS, s.id, s.name, s.subText, s.lat, s.lng, "", "버스", "", "")
                    rows += rowFor(item, arr, s.distanceM, prev[item.key], now)
                }
            }
            is LoadResult.Failure -> rows += WidgetRow(
                key = "nearby-bus-error", isBus = true, badge = "버스", badgeColor = 0xFF777777.toInt(),
                title = "주변 버스정류장", direction = "", main = if (bs.needsKey) "API 키 확인 필요" else "통신 오류",
                second = bs.reason, status = if (bs.needsKey) RowStatus.NEED_KEY else RowStatus.ERROR,
            )
            is LoadResult.NoData -> Unit
        }
        rows
    }

    companion object {
        const val MAX_ROWS = 30
        const val CACHE_MAX_AGE_MS = 30 * 60 * 1000L
    }
}
