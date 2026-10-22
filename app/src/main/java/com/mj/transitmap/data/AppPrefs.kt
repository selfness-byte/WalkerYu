package com.mj.transitmap.data

import android.content.Context
import com.mj.transitmap.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 앱 설정·추가한 노선·위젯 캐시 저장소. 정확한 위치는 저장하지 않는다. */
class AppPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("transit_prefs", Context.MODE_PRIVATE)

    var gbisKey: String
        get() = sp.getString(K_GBIS, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GBIS_SERVICE_KEY
        set(v) = sp.edit().putString(K_GBIS, v.trim()).apply()

    /** 사용자가 입력한 서울 지하철 키 (빈 값이면 sample 키 사용) */
    var subwayKey: String
        get() = sp.getString(K_SUBWAY, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SEOUL_SUBWAY_KEY
        set(v) = sp.edit().putString(K_SUBWAY, v.trim()).apply()

    val effectiveSubwayKey: String get() = subwayKey.ifBlank { "sample" }
    val usingSubwaySampleKey: Boolean get() = effectiveSubwayKey == "sample"

    /** 입력한 서울 지하철 키가 인증 실패(INFO-100)하여 sample 키로 대체 중인지 */
    var subwayKeyRejected: Boolean
        get() = sp.getBoolean("subway_key_rejected", false)
        set(v) = sp.edit().putBoolean("subway_key_rejected", v).apply()

    var sampleMode: Boolean
        get() = sp.getBoolean(K_SAMPLE, false)
        set(v) = sp.edit().putBoolean(K_SAMPLE, v).apply()

    /** 위젯이 갱신 시 현재 위치를 쓸지 여부 (위젯 버튼·설정에서 ON/OFF) */
    var widgetUseLocation: Boolean
        get() = sp.getBoolean(K_WIDGET_LOC, false)
        set(v) = sp.edit().putBoolean(K_WIDGET_LOC, v).apply()

    var activeDestinationId: String?
        get() = sp.getString(K_ACTIVE_DEST, null)
        set(v) = sp.edit().putString(K_ACTIVE_DEST, v).apply()

    var destinations: List<Destination>
        get() = Destination.listFromJson(sp.getString(K_DESTS, "[]"))
        set(v) = sp.edit().putString(K_DESTS, Destination.listToJson(v)).apply()

    val activeDestination: Destination?
        get() {
            val all = destinations
            return all.firstOrNull { it.id == activeDestinationId } ?: all.firstOrNull()
        }

    /**
     * 사용자가 지도에서 추가한 모든 노선 목록 (노선 추가하는 순서대로 그대로 저장·표시).
     * 반복적인 JSONArray 역직렬화로 인한 GC 압박을 막기 위해 메모리 캐시(cachedSavedItems)를 함께 운용한다.
     */
    var savedItems: List<BoardItem>
        get() {
            val raw = sp.getString(K_SAVED_ITEMS, null)
            if (raw != null) {
                if (raw == cachedSavedRaw) return cachedSavedItems
                val parsed = runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(BoardItem::fromJson) }
                }.getOrDefault(emptyList())
                updateSavedMemoryCache(raw, parsed)
                return parsed
            }
            // 최초 1회: 기존 destinations 에 들어 있던 모든 노선을 순서대로 통합
            val migrated = destinations.flatMap { it.items }.distinctBy { it.key }
            if (migrated.isNotEmpty()) {
                savedItems = migrated
            }
            return migrated
        }
        set(value) {
            val dedup = value.distinctBy { it.key }
            val raw = JSONArray().apply { dedup.forEach { put(it.toJson()) } }.toString()
            updateSavedMemoryCache(raw, dedup)
            sp.edit().putString(K_SAVED_ITEMS, raw).apply()
        }

    /** 추가된 정류장/역 키("BUS|stationId" 또는 "SUBWAY|stationName") 집합 (메모리 캐시 O(1) 반환) */
    fun starredStopKeys(): Set<String> {
        savedItems // 캐시 최신화 보장
        return cachedStarredStopKeys
    }

    /**
     * 노선이 추가된 모든 정류장·지하철역의 Stop 목록 (메모리 캐시 반환).
     * 다른 지역에서 대중교통 찾기를 하더라도 지도 위에 항상 별표(★) 마커로 유지하기 위해 사용한다.
     */
    fun starredStops(): List<Stop> {
        savedItems // 캐시 최신화 보장
        return cachedStarredStops
    }

    fun isStarredStop(type: StopType, stopId: String): Boolean =
        starredStopKeys().contains("$type|$stopId")

    fun isStarredItem(type: StopType, stopId: String, lineId: String, dirKey: String): Boolean =
        savedItems.any {
            it.type == type && it.stopId == stopId && it.lineId == lineId &&
                (it.dirKey.isEmpty() || dirKey.isEmpty() || it.dirKey == dirKey)
        }

    /** 노선 1개를 바로 추가/해제 토글한다. 추가되었으면 true, 해제되었으면 false 반환 */
    fun toggleSavedItem(item: BoardItem): Boolean {
        val current = savedItems
        val exists = current.any {
            it.type == item.type && it.stopId == item.stopId && it.lineId == item.lineId &&
                (it.dirKey.isEmpty() || item.dirKey.isEmpty() || it.dirKey == item.dirKey)
        }
        return if (exists) {
            savedItems = current.filterNot {
                it.type == item.type && it.stopId == item.stopId && it.lineId == item.lineId &&
                    (it.dirKey.isEmpty() || item.dirKey.isEmpty() || it.dirKey == item.dirKey)
            }
            false
        } else {
            savedItems = current + item
            true
        }
    }

    fun addSavedItem(item: BoardItem): Boolean {
        val current = savedItems
        if (current.any { it.key == item.key }) return false
        savedItems = current + item
        return true
    }

    fun removeStopItems(type: StopType, stopId: String) {
        savedItems = savedItems.filterNot { it.type == type && it.stopId == stopId }
    }

    var widgetSnapshotJson: String?
        get() = sp.getString(K_WIDGET_SNAP, null)
        set(v) = sp.edit().putString(K_WIDGET_SNAP, v).apply()

    // ---- 레거시 목적지 편집 호환 ----

    fun createDestination(name: String): Destination {
        val d = Destination(UUID.randomUUID().toString(), name.ifBlank { "목적지" }, emptyList())
        destinations = destinations + d
        if (activeDestinationId == null) activeDestinationId = d.id
        return d
    }

    fun addItem(destId: String, item: BoardItem): Boolean {
        addSavedItem(item)
        var added = false
        destinations = destinations.map { d ->
            if (d.id == destId && d.items.none { it.key == item.key }) {
                added = true
                d.copy(items = d.items + item)
            } else d
        }
        return added
    }

    fun removeItem(destId: String, itemKey: String) {
        savedItems = savedItems.filterNot { it.key == itemKey }
        destinations = destinations.map { d -> if (d.id == destId) d.copy(items = d.items.filterNot { it.key == itemKey }) else d }
    }

    fun moveItem(destId: String, itemKey: String, delta: Int) {
        destinations = destinations.map { d ->
            if (d.id != destId) return@map d
            val list = d.items.toMutableList()
            val i = list.indexOfFirst { it.key == itemKey }
            val j = i + delta
            if (i < 0 || j !in list.indices) return@map d
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
            d.copy(items = list)
        }
    }

    fun renameDestination(destId: String, name: String) {
        destinations = destinations.map { if (it.id == destId) it.copy(name = name.ifBlank { it.name }) else it }
    }

    fun deleteDestination(destId: String) {
        destinations = destinations.filterNot { it.id == destId }
        if (activeDestinationId == destId) activeDestinationId = destinations.firstOrNull()?.id
    }

    // ---- 열차 위치 표시용: 지금까지 지도에서 본 역 좌표 (역 좌표는 공개 정보) ----

    fun rememberStations(stops: List<Stop>) {
        if (stops.isEmpty()) return
        val o = runCatching { JSONObject(sp.getString(K_STATIONS, "{}") ?: "{}") }.getOrDefault(JSONObject())
        stops.filter { it.type == StopType.SUBWAY && !it.sample }.forEach {
            o.put(it.id, JSONArray().put(it.lat).put(it.lng))
        }
        // 너무 커지지 않게 최근 500개만 유지
        val keys = o.keys().asSequence().toList()
        if (keys.size > 500) keys.take(keys.size - 500).forEach { o.remove(it) }
        val raw = o.toString()
        cachedKnownStationsRaw = null
        sp.edit().putString(K_STATIONS, raw).apply()
    }

    fun knownStations(): Map<String, Pair<Double, Double>> {
        val raw = sp.getString(K_STATIONS, "{}") ?: "{}"
        if (raw == cachedKnownStationsRaw) return cachedKnownStations
        val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val out = HashMap<String, Pair<Double, Double>>()
        o.keys().forEach { k -> o.optJSONArray(k)?.let { out[k] = it.optDouble(0) to it.optDouble(1) } }
        cachedKnownStations = out
        cachedKnownStationsRaw = raw
        return out
    }

    fun saveLastLocation(lat: Double, lng: Double) {
        if (lat == 0.0 && lng == 0.0) return
        sp.edit()
            .putString(K_LAST_LOC, "$lat,$lng,${System.currentTimeMillis()}")
            .apply()
    }

    fun lastKnownLocation(): android.location.Location? {
        val raw = sp.getString(K_LAST_LOC, null) ?: return null
        val parts = raw.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        val t = parts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis()
        return android.location.Location("prefs").apply {
            latitude = lat
            longitude = lng
            time = t
        }
    }

    companion object {
        private const val K_GBIS = "gbis_key"
        private const val K_SUBWAY = "subway_key"
        private const val K_SAMPLE = "sample_mode"
        private const val K_WIDGET_LOC = "widget_use_location"
        private const val K_ACTIVE_DEST = "active_destination"
        private const val K_DESTS = "destinations"
        private const val K_SAVED_ITEMS = "saved_board_items"
        private const val K_WIDGET_SNAP = "widget_snapshot"
        private const val K_STATIONS = "known_stations"
        private const val K_LAST_LOC = "last_known_loc"

        @Volatile
        private var cachedSavedRaw: String? = null

        @Volatile
        private var cachedSavedItems: List<BoardItem> = emptyList()

        @Volatile
        private var cachedStarredStopKeys: Set<String> = emptySet()

        @Volatile
        private var cachedStarredStops: List<Stop> = emptyList()

        @Volatile
        private var cachedKnownStationsRaw: String? = null

        @Volatile
        private var cachedKnownStations: Map<String, Pair<Double, Double>> = emptyMap()

        @Synchronized
        private fun updateSavedMemoryCache(raw: String, items: List<BoardItem>) {
            cachedSavedItems = items
            cachedStarredStopKeys = items.map { "${it.type}|${it.stopId}" }.toSet()
            cachedStarredStops = items
                .filter { it.stopId.isNotEmpty() && it.lat != 0.0 && it.lng != 0.0 }
                .distinctBy { "${it.type}|${it.stopId}" }
                .map {
                    Stop(
                        type = it.type,
                        id = it.stopId,
                        name = it.stopName,
                        subText = it.stopSub,
                        lat = it.lat,
                        lng = it.lng,
                    )
                }
            cachedSavedRaw = raw
        }
    }
}
