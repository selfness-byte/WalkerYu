package com.mj.transitmap.ui

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.mj.transitmap.R
import com.mj.transitmap.data.Arrival
import com.mj.transitmap.data.BoardItem
import com.mj.transitmap.data.LoadResult
import com.mj.transitmap.data.LocationHelper
import com.mj.transitmap.data.OverpassApi
import com.mj.transitmap.data.SeoulParser
import com.mj.transitmap.data.Stop
import com.mj.transitmap.data.StopType
import com.mj.transitmap.data.SubwayLines
import com.mj.transitmap.data.TrainPosition
import com.mj.transitmap.data.TransitRepository
import com.mj.transitmap.update.AppUpdater
import com.mj.transitmap.widget.WidgetUpdater
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var repo: TransitRepository
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var chipBus: Chip
    private lateinit var chipSubway: Chip
    private lateinit var chipTrains: Chip
    private lateinit var sheet: MaterialCardView
    private lateinit var sheetTypeIcon: ImageView
    private lateinit var sheetTitle: TextView
    private lateinit var sheetSub: TextView
    private lateinit var sheetDirBanner: TextView
    private lateinit var sheetRows: LinearLayout
    private lateinit var fabColumn: LinearLayout
    private lateinit var btnClearRoute: MaterialButton
    private lateinit var btnFindTransitHere: MaterialButton
    private lateinit var centerTargetPin: ImageView
    private lateinit var searchInput: EditText

    private var myLocOverlay: MyLocationNewOverlay? = null
    private val stopMarkers = mutableListOf<Marker>()
    private val trainOverlays = mutableListOf<Overlay>()
    private val routeOverlays = mutableListOf<Overlay>()
    private var busStops: List<Stop> = emptyList()
    private var stations: List<Stop> = emptyList()
    private var selected: Stop? = null
    private var selectedArrivals: List<Arrival> = emptyList()
    private var selectedLines: List<String> = emptyList()
    private var searchCenter: GeoPoint? = null

    private var loadJob: Job? = null
    private var sheetJob: Job? = null
    private var routeJob: Job? = null
    private var trainJob: Job? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            enableMyLocationOverlay()
            goToMyLocation()
        } else {
            showStatus("위치 권한이 없어 기본 위치(부천시청 부근)를 표시합니다.\n이름 검색 또는 지도를 길게 눌러 원하는 곳을 고르세요.")
            moveAndLoad(DEFAULT_CENTER, 15.5)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            // 지도 타일 디코딩 메모리 및 디스크 캐시 상한 설정 (메모리 최적화 및 OOM 방지)
            cacheMapTileCount = 16.toShort()
            cacheMapTileOvershoot = 4.toShort()
            tileFileSystemCacheMaxBytes = 32L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 24L * 1024 * 1024
        }
        setContentView(R.layout.activity_main)
        Insets.applySystemBars(findViewById(R.id.root))
        repo = TransitRepository(this)

        map = findViewById(R.id.map)
        statusText = findViewById(R.id.statusText)
        chipBus = findViewById(R.id.chipBus)
        chipSubway = findViewById(R.id.chipSubway)
        chipTrains = findViewById(R.id.chipTrains)
        sheet = findViewById(R.id.sheet)
        sheetTypeIcon = findViewById(R.id.sheetTypeIcon)
        sheetTitle = findViewById(R.id.sheetTitle)
        sheetSub = findViewById(R.id.sheetSub)
        sheetDirBanner = findViewById(R.id.sheetDirBanner)
        sheetRows = findViewById(R.id.sheetRows)
        fabColumn = findViewById(R.id.fabColumn)
        btnClearRoute = findViewById(R.id.btnClearRoute)
        btnFindTransitHere = findViewById(R.id.btnFindTransitHere)
        centerTargetPin = findViewById(R.id.centerTargetPin)
        centerTargetPin.setImageDrawable(MarkerIcons.centerTargetPin(this))
        searchInput = findViewById(R.id.searchInput)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(16.0)
        map.controller.setCenter(DEFAULT_CENTER)
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                hideKeyboard()
                if (sheet.visibility == View.VISIBLE) hideSheet()
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                moveAndLoad(p, null)
                return true
            }
        }))

        // 지도를 드래그해 다른 위치에 고정하면 중앙 조준선과 '🚌🚇 이 위치 대중교통 찾기' 버튼을 표시한다.
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateMovedCenterUi()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                updateMovedCenterUi()
                return false
            }
        })

        chipBus.setOnCheckedChangeListener { _, _ -> refreshStopMarkers() }
        chipSubway.setOnCheckedChangeListener { _, _ -> refreshStopMarkers() }
        chipTrains.setOnCheckedChangeListener { _, checked ->
            if (checked) loadTrainPositions() else clearOverlays(trainOverlays)
        }

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnFindTransitHere.setOnClickListener {
            findTransitAtMapCenter()
        }
        findViewById<MaterialButton>(R.id.btnMyLocation).setOnClickListener {
            if (LocationHelper.hasForeground(this)) goToMyLocation() else requestLocation()
        }
        btnClearRoute.setOnClickListener {
            clearOverlays(routeOverlays); btnClearRoute.visibility = View.GONE
        }
        findViewById<View>(R.id.sheetClose).setOnClickListener { hideSheet() }
        findViewById<MaterialButton>(R.id.sheetRefresh).setOnClickListener { selected?.let { openStop(it) } }
        findViewById<MaterialButton>(R.id.sheetAddBundle).setOnClickListener { addSelectedStopBundle() }
        statusText.setOnClickListener { statusText.visibility = View.GONE }

        searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(v.text.toString().trim()); true
            } else false
        }

        if (!handleWidgetIntent(intent)) {
            if (LocationHelper.hasForeground(this)) {
                enableMyLocationOverlay(); goToMyLocation()
            } else requestLocation()
        }
        AppUpdater.checkOnStartup(this, lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (LocationHelper.hasForeground(this)) {
            myLocOverlay?.enableMyLocation()
            LocationHelper.startWarmup(this)
        }
        refreshStopMarkers()
        updateModeBanner()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        myLocOverlay?.disableMyLocation()
        LocationHelper.stopWarmup(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_UI_HIDDEN) {
            MarkerIcons.trimMemory()
            runCatching { map.tileProvider?.clearTileCache() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MarkerIcons.trimMemory()
        runCatching { map.tileProvider?.clearTileCache() }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        sheetJob?.cancel()
        routeJob?.cancel()
        trainJob?.cancel()
        hideJob?.cancel()
        myLocOverlay?.disableMyLocation()
        myLocOverlay = null
        LocationHelper.stopWarmup(this)
        detachAndClearMarkers(stopMarkers)
        clearOverlays(trainOverlays, invalidate = false)
        clearOverlays(routeOverlays, invalidate = false)
        runCatching { map.onDetach() }
        super.onDestroy()
    }

    // ---------------- 위치 ----------------

    private fun requestLocation() {
        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun enableMyLocationOverlay() {
        if (!LocationHelper.hasForeground(this)) return
        LocationHelper.startWarmup(this)
        if (myLocOverlay != null) return
        val provider = GpsMyLocationProvider(this).apply {
            addLocationSource(LocationManager.NETWORK_PROVIDER)
            addLocationSource(LocationManager.PASSIVE_PROVIDER)
            locationUpdateMinTime = 2000L
            locationUpdateMinDistance = 5f
        }
        myLocOverlay = MyLocationNewOverlay(provider, map).also {
            it.enableMyLocation()
            map.overlays.add(it)
        }
    }

    private fun goToMyLocation() {
        // 1단계: 메모리·OS 캐시 위치가 있으면 0ms(즉시) 지도를 먼저 이동하고 주변 정류장·역을 불러온다.
        val syncLoc = LocationHelper.peekSync(this)
        val overlayPt = myLocOverlay?.myLocation
        var movedPt: GeoPoint? = when {
            syncLoc != null -> GeoPoint(syncLoc.latitude, syncLoc.longitude)
            overlayPt != null -> GeoPoint(overlayPt.latitude, overlayPt.longitude)
            else -> null
        }
        if (movedPt != null) {
            moveAndLoad(movedPt, 17.0, instant = true)
        } else {
            showStatus("현재 위치 빠르게 확인 중…")
        }

        // 2단계: 코루틴에서 Fused 캐시(150ms) 및 실시간 정밀 위치(Multi-provider 경쟁)를 확인해 보정한다.
        lifecycleScope.launch {
            if (movedPt == null) {
                val fast = LocationHelper.peekFast(this@MainActivity)
                if (fast != null) {
                    val pt = GeoPoint(fast.latitude, fast.longitude)
                    movedPt = pt
                    moveAndLoad(pt, 17.0, instant = true)
                }
            }

            // 캐시 위치가 없거나 15초 이상 지났으면 백그라운드에서 최신 위치를 경쟁 조회
            val needFresh = movedPt == null || (syncLoc != null && LocationHelper.ageMs(syncLoc) > 15_000L)
            if (needFresh) {
                val fresh = LocationHelper.fresh(this@MainActivity, timeoutMs = 2500L)
                if (fresh != null) {
                    val freshPt = GeoPoint(fresh.latitude, fresh.longitude)
                    val prev = movedPt
                    if (prev == null) {
                        moveAndLoad(freshPt, 17.0, instant = true)
                    } else if (OverpassApi.distance(prev.latitude, prev.longitude, freshPt.latitude, freshPt.longitude) > 35) {
                        // 캐시 위치와 실제 새 위치가 35m 이상 차이 날 때만 부드럽게 보정
                        moveAndLoad(freshPt, null, instant = false)
                    }
                } else if (movedPt == null) {
                    showStatus("현재 위치를 가져오지 못했습니다. 지도를 길게 누르거나 이름으로 검색하세요.")
                    moveAndLoad(GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude), null)
                }
            }
        }
    }

    // ---------------- 주변 정류장·역 ----------------

    /**
     * 지도가 마지막 조회 중심점(searchCenter)에서 일정 거리(90m) 이상 이동해 다른 위치에 놓이면
     * 화면 중앙 조준 핀과 '🚌🚇 이 위치 대중교통 찾기' 플로팅 버튼을 표시한다.
     */
    private fun updateMovedCenterUi() {
        val sc = searchCenter ?: return
        val cur = map.mapCenter
        val dist = OverpassApi.distance(sc.latitude, sc.longitude, cur.latitude, cur.longitude)
        val moved = dist >= 90
        centerTargetPin.visibility = if (moved) View.VISIBLE else View.GONE
        btnFindTransitHere.visibility = if (moved) View.VISIBLE else View.GONE
    }

    /**
     * 현재 지도가 가리키는 중앙 위치의 주변 대중교통(버스정류장 + 지하철역)을 즉시 조회해 표시한다.
     */
    private fun findTransitAtMapCenter() {
        val c = map.mapCenter
        val target = GeoPoint(c.latitude, c.longitude)
        if (!chipBus.isChecked && !chipSubway.isChecked) {
            chipBus.isChecked = true
            chipSubway.isChecked = true
        }
        val targetZoom = if (map.zoomLevelDouble < 15.2) 16.0 else null
        moveAndLoad(target, targetZoom, instant = true)
    }

    private fun moveAndLoad(p: GeoPoint, zoom: Double?, instant: Boolean = false) {
        zoom?.let { map.controller.setZoom(it) }
        val cur = map.mapCenter
        val dist = OverpassApi.distance(cur.latitude, cur.longitude, p.latitude, p.longitude)
        if (instant || dist > 800) {
            map.controller.setCenter(p)
        } else {
            map.controller.animateTo(p)
        }
        loadAround(p)
    }

    private fun loadAround(center: GeoPoint, then: (() -> Unit)? = null) {
        searchCenter = center
        centerTargetPin.visibility = View.GONE
        btnFindTransitHere.visibility = View.GONE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            showStatus("주변 버스정류장·지하철역 불러오는 중…")
            val b = async { repo.nearbyBusStops(center.latitude, center.longitude) }
            // 내장 지하철역 DB는 즉시(<2ms) 조회되므로 버스 API 응답을 기다리지 않고 먼저 지도에 표시한다.
            val stRes = repo.nearbyStations(center.latitude, center.longitude)
            val msgs = mutableListOf<String>()
            stations = when (stRes) {
                is LoadResult.Ok -> stRes.value
                is LoadResult.NoData -> { msgs += "지하철: ${stRes.reason}"; emptyList() }
                is LoadResult.Failure -> { msgs += "지하철역 위치: ${stRes.reason}"; emptyList() }
            }
            refreshStopMarkers()

            val busRes = b.await()
            busStops = when (busRes) {
                is LoadResult.Ok -> busRes.value
                is LoadResult.NoData -> { msgs += "버스: ${busRes.reason}"; emptyList() }
                is LoadResult.Failure -> { msgs += "버스: ${busRes.reason}"; emptyList() }
            }
            refreshStopMarkers()
            if (msgs.isEmpty()) {
                showStatus("버스정류장 ${busStops.size}곳 · 지하철역 ${stations.size}곳" + if (repo.prefs.sampleMode) " [샘플]" else "", autoHide = true)
            } else showStatus(msgs.joinToString("\n"))
            then?.invoke()
        }
    }

    private fun refreshStopMarkers() {
        detachAndClearMarkers(stopMarkers)
        val sel = selected
        val starredKeys = repo.prefs.starredStopKeys()
        // 다른 지역에서 대중교통 찾기를 해도 노선이 추가된 별표(★) 정류장·역은 항상 지도에 유지한다.
        val starredStops = repo.prefs.starredStops()
        val busDefaultIcon = MarkerIcons.busStop(this, selected = false, starred = false)
        val busSelectedIcon = MarkerIcons.busStop(this, selected = true, starred = false)
        val busStarredIcon = MarkerIcons.busStop(this, selected = false, starred = true)
        val busStarredSelectedIcon = MarkerIcons.busStop(this, selected = true, starred = true)

        val starredMarkers = mutableListOf<Marker>()
        val selectedMarkers = mutableListOf<Marker>()

        if (chipBus.isChecked) {
            val allBusStops = (busStops + starredStops.filter { it.type == StopType.BUS })
                .distinctBy { it.id }
            allBusStops.forEach { stop ->
                val isSel = sel?.type == StopType.BUS && sel.id == stop.id
                val isStar = starredKeys.contains("${stop.type}|${stop.id}")
                val icon = when {
                    isSel && isStar -> busStarredSelectedIcon
                    isSel -> busSelectedIcon
                    isStar -> busStarredIcon
                    else -> busDefaultIcon
                }
                val m = makeStopMarker(stop, icon)
                when {
                    isSel -> selectedMarkers += m
                    isStar -> starredMarkers += m
                    else -> stopMarkers += m
                }
            }
        }
        if (chipSubway.isChecked) {
            val allStations = (stations + starredStops.filter { it.type == StopType.SUBWAY })
                .distinctBy { it.id }
            allStations.forEach { stop ->
                val isSel = sel?.type == StopType.SUBWAY && sel.id == stop.id
                val isStar = starredKeys.contains("${stop.type}|${stop.id}")
                val icon = MarkerIcons.subwayStation(this, stop.name, selected = isSel, starred = isStar)
                val m = makeStopMarker(stop, icon)
                when {
                    isSel -> selectedMarkers += m
                    isStar -> starredMarkers += m
                    else -> stopMarkers += m
                }
            }
        }
        // 고정 목적지(★) 마커가 일반 마커 위에, 현재 선택된 마커가 가장 위에 보이도록 순서 배치
        stopMarkers += starredMarkers
        stopMarkers += selectedMarkers
        // 정류장·역 마커는 내 위치 표시보다 아래, 열차·노선보다 아래에
        val insertAt = if (map.overlays.isEmpty()) 0 else 1
        map.overlays.addAll(insertAt.coerceAtMost(map.overlays.size), stopMarkers)
        map.invalidate()
    }

    private fun makeStopMarker(stop: Stop, icon: android.graphics.drawable.Drawable): Marker = Marker(map).apply {
        position = GeoPoint(stop.lat, stop.lng)
        setIcon(icon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = stop.name
        setOnMarkerClickListener { _, _ -> openStop(stop); true }
    }

    // ---------------- 하단 카드 (도착 정보) ----------------

    private fun openStop(stop: Stop) {
        selected = stop
        selectedArrivals = emptyList()
        refreshStopMarkers()
        hideKeyboard()
        sheet.visibility = View.VISIBLE
        // 하단 시트가 열릴 때 우측 메뉴가 상단 고정바 아래로 밀려 올라가 가려지지 않도록 숨김 처리
        fabColumn.translationY = 0f
        fabColumn.visibility = View.GONE
        sheetTypeIcon.setImageDrawable(MarkerIcons.sheetHeaderBadge(this, isBus = stop.type == StopType.BUS))
        val isStarStop = repo.prefs.isStarredStop(stop.type, stop.id)
        sheetTitle.text = if (isStarStop) "⭐ ${stop.name}" else stop.name
        findViewById<MaterialButton>(R.id.sheetAddBundle).text = if (isStarStop) "⭐ 전체 해제" else "★ 전체 추가"
        val dist = searchCenter?.let { OverpassApi.distance(it.latitude, it.longitude, stop.lat, stop.lng) }
        sheetSub.text = listOfNotNull(
            if (isStarStop) "★ 위젯 노선 등록됨" else null,
            if (stop.type == StopType.BUS) "버스정류장" else "지하철역",
            stop.subText.takeIf { it.isNotEmpty() && stop.type == StopType.BUS },
            dist?.let { "기준점에서 ${it}m" },
        ).joinToString(" · ")
        sheetDirBanner.visibility = View.GONE
        sheetRows.removeAllViews()
        sheetRows.addView(infoText("도착 정보 불러오는 중…"))

        sheetJob?.cancel()
        sheetJob = lifecycleScope.launch {
            if (stop.type == StopType.BUS) {
                val res = repo.busArrivals(stop.id)
                selectedArrivals = (res as? LoadResult.Ok)?.value.orEmpty()
                updateDirectionBanner(stop, res)
                renderArrivals(stop, res)
            } else {
                val res = repo.subwayArrivals(stop.id)
                selectedLines = (res as? LoadResult.Ok)?.value?.map { it.lineId }?.distinct().orEmpty()
                val grouped = if (res is LoadResult.Ok) LoadResult.Ok(SeoulParser.groupByDirection(res.value), res.sample) else res
                selectedArrivals = (grouped as? LoadResult.Ok)?.value.orEmpty()
                updateDirectionBanner(stop, grouped)
                renderArrivals(stop, grouped)
                if (repo.prefs.subwayKeyRejected && !repo.prefs.sampleMode) {
                    sheetRows.addView(infoText("⚠ 입력한 서울 지하철 키가 인증되지 않아(INFO-100) sample 키로 조회했습니다 — 최대 5건. 서울 열린데이터광장의 ‘실시간 지하철 인증키’인지 확인하세요."))
                } else if (repo.prefs.usingSubwaySampleKey && !repo.prefs.sampleMode) {
                    sheetRows.addView(infoText("ℹ 서울 지하철 sample 키 사용 중 — 실제 데이터지만 최대 5건만 조회됩니다. 설정에서 키를 넣으면 전체가 보입니다."))
                }
                if (chipTrains.isChecked) loadTrainPositions()
            }
        }
    }

    private fun updateDirectionBanner(stop: Stop, res: LoadResult<List<Arrival>>) {
        if (res !is LoadResult.Ok || res.value.isEmpty()) {
            sheetDirBanner.visibility = View.GONE
            return
        }
        if (stop.type == StopType.BUS) {
            val dirs = res.value.mapNotNull { it.direction.removeSuffix(" 방면").trim().takeIf { s -> s.isNotEmpty() } }
                .distinct()
                .take(3)
            if (dirs.isNotEmpty()) {
                sheetDirBanner.text = "➔ 주요 진행방향: ${dirs.joinToString(" · ")} 방면"
                sheetDirBanner.background.mutate().setTint(0xFFE0F2F1.toInt())
                sheetDirBanner.setTextColor(0xFF004D40.toInt())
                sheetDirBanner.visibility = View.VISIBLE
            } else {
                sheetDirBanner.visibility = View.GONE
            }
        } else {
            sheetDirBanner.text = "▲ 상행/내선(파랑) · ▼ 하행/외선(자주) 진행방향 구분 표시"
            sheetDirBanner.background.mutate().setTint(0xFFE8EAF6.toInt())
            sheetDirBanner.setTextColor(0xFF1A237E.toInt())
            sheetDirBanner.visibility = View.VISIBLE
        }
    }

    private fun renderArrivals(stop: Stop, res: LoadResult<List<Arrival>>) {
        sheetRows.removeAllViews()
        when (res) {
            is LoadResult.Ok -> {
                if (res.sample) sheetRows.addView(infoText("⚠ 샘플 데이터입니다 — 실제 도착 정보가 아닙니다"))
                res.value.forEach { sheetRows.addView(arrivalRow(stop, it)) }
            }
            is LoadResult.NoData -> sheetRows.addView(infoText("ℹ ${res.reason}"))
            is LoadResult.Failure -> {
                sheetRows.addView(infoText("⚠ ${res.reason}\n(조회 실패 — 도착/운행 종료 여부를 알 수 없음)"))
                if (res.needsKey) sheetRows.addView(MaterialButton(this).apply {
                    text = "설정에서 API 키 입력"
                    setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
                })
            }
        }
    }

    private fun arrivalRow(stop: Stop, a: Arrival): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_arrival, sheetRows, false)
        val isStarredItem = repo.prefs.isStarredItem(stop.type, stop.id, a.lineId, a.dirKey)

        val badge = v.findViewById<TextView>(R.id.badge)
        val baseBadge = if (a.type == StopType.BUS) a.lineName else SubwayLines.byId(a.lineId)?.name ?: a.lineName
        badge.text = if (isStarredItem) "★ $baseBadge" else baseBadge
        badge.background.mutate().setTint(a.lineColor ?: Color.DKGRAY)

        // 진행방향(dir) 배지: 버스/지하철(상행·하행)별로 뚜렷한 배경색·글자색·화살표 부여
        val dirView = v.findViewById<TextView>(R.id.dir)
        val rawDir = a.direction.trim()
        if (rawDir.isEmpty()) {
            dirView.text = if (a.type == StopType.BUS) "➔ 방향 정보 없음" else "▶ 방향 정보 없음"
            dirView.background.mutate().setTint(0xFFECEFF1.toInt())
            dirView.setTextColor(0xFF455A64.toInt())
        } else if (a.type == StopType.BUS) {
            dirView.text = if (rawDir.startsWith("➔")) rawDir else "➔ $rawDir"
            dirView.background.mutate().setTint(0xFFE0F2F1.toInt())
            dirView.setTextColor(0xFF004D40.toInt())
        } else {
            val formatted = rawDir.replace(" - ", " ➔ ")
            if (rawDir.contains("상행") || rawDir.contains("내선")) {
                dirView.text = "▲ $formatted"
                dirView.background.mutate().setTint(0xFFE3F2FD.toInt())
                dirView.setTextColor(0xFF0D47A1.toInt())
            } else if (rawDir.contains("하행") || rawDir.contains("외선")) {
                dirView.text = "▼ $formatted"
                dirView.background.mutate().setTint(0xFFFCE4EC.toInt())
                dirView.setTextColor(0xFFAD1457.toInt())
            } else {
                dirView.text = "▶ $formatted"
                dirView.background.mutate().setTint(0xFFFFF8E1.toInt())
                dirView.setTextColor(0xFFE65100.toInt())
            }
        }

        val msgView = v.findViewById<TextView>(R.id.msg)
        msgView.text = (if (a.sample) "[샘플] " else "") + a.message
        val isSoon = a.message.contains("곧") || a.message.contains("진입") || a.message.contains("도착") || (a.seconds != null && a.seconds <= 180)
        msgView.setTextColor(if (isSoon) 0xFFC62828.toInt() else 0xFF1565C0.toInt())

        v.findViewById<TextView>(R.id.sub).apply {
            val t = listOf(a.secondMessage, a.detail).filter { it.isNotEmpty() }.joinToString(" · ")
            text = t
            visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<MaterialButton>(R.id.btnAdd).apply {
            text = if (isStarredItem) "⭐ 추가됨 ✕" else "★ 노선 추가"
            if (isStarredItem) {
                setBackgroundColor(0xFFFFF3E0.toInt())
                setTextColor(0xFFE65100.toInt())
            }
            setOnClickListener {
                addToDestination(
                    BoardItem(stop.type, stop.id, stop.name, stop.subText, stop.lat, stop.lng, a.lineId, a.lineName, a.direction, a.dirKey)
                )
            }
        }
        v.findViewById<MaterialButton>(R.id.btnRoute).apply {
            visibility = if (a.type == StopType.BUS && !a.sample) View.VISIBLE else View.GONE
            setOnClickListener { showBusRoute(a) }
        }
        return v
    }

    private fun infoText(s: String) = TextView(this).apply {
        text = s
        textSize = 15.5f
        setPadding(8, 12, 8, 12)
        setTextColor(0xFF334E68.toInt())
    }

    private fun hideSheet() {
        sheet.visibility = View.GONE
        selected = null
        selectedArrivals = emptyList()
        refreshStopMarkers()
        fabColumn.translationY = 0f
        fabColumn.visibility = View.VISIBLE
        clearOverlays(trainOverlays)
    }

    // ---------------- 노선 바로 추가/해제 (별도 목적지 폴더 선택 없이 즉시 반영) ----------------

    private fun addSelectedStopBundle() {
        val stop = selected ?: return
        val prefs = repo.prefs
        if (prefs.isStarredStop(stop.type, stop.id)) {
            prefs.removeStopItems(stop.type, stop.id)
            Toast.makeText(this, "✕ ‘${stop.name}’ 노선이 위젯에서 모두 해제되었습니다", Toast.LENGTH_SHORT).show()
            refreshStopMarkers()
            openStop(stop)
            WidgetUpdater.requestRefresh(this)
            return
        }
        if (selectedArrivals.isEmpty()) {
            Toast.makeText(this, "추가할 도착 노선 정보가 아직 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        var addedCount = 0
        selectedArrivals.forEach { a ->
            val item = BoardItem(stop.type, stop.id, stop.name, stop.subText, stop.lat, stop.lng, a.lineId, a.lineName, a.direction, a.dirKey)
            if (prefs.addSavedItem(item)) addedCount++
        }
        Toast.makeText(
            this,
            if (addedCount > 0) "⭐ ‘${stop.name}’ ${addedCount}개 노선 위젯에 추가됨" else "이미 모두 추가되어 있습니다",
            Toast.LENGTH_SHORT,
        ).show()
        refreshStopMarkers()
        openStop(stop)
        WidgetUpdater.requestRefresh(this)
    }

    private fun addToDestination(item: BoardItem) {
        val added = repo.prefs.toggleSavedItem(item)
        Toast.makeText(
            this,
            if (added) "⭐ ‘${item.stopName}’ [${item.lineName}] 위젯에 추가됨" else "✕ ‘${item.stopName}’ [${item.lineName}] 해제됨",
            Toast.LENGTH_SHORT,
        ).show()
        refreshStopMarkers()
        selected?.let { openStop(it) }
        WidgetUpdater.requestRefresh(this)
    }

    // ---------------- 버스 노선 위치 ----------------

    private fun showBusRoute(a: Arrival) {
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            showStatus("${a.lineName} 노선·진행방향·차량 위치 불러오는 중…")
            val st = async { repo.routeStations(a.lineId) }
            val loc = async { repo.busLocations(a.lineId) }
            val stRes = st.await()
            if (stRes !is LoadResult.Ok) {
                showStatus("노선 정보: " + when (stRes) {
                    is LoadResult.NoData -> stRes.reason
                    is LoadResult.Failure -> stRes.reason
                    else -> ""
                })
                return@launch
            }
            clearOverlays(routeOverlays)
            val routeColor = a.lineColor ?: 0xFF008952.toInt()
            val points = stRes.value.map { GeoPoint(it.lat, it.lng) }
            val line = Polyline(map).apply {
                setPoints(points)
                outlinePaint.color = routeColor and 0x00FFFFFF or (0xCC shl 24)
                outlinePaint.strokeWidth = 12f
            }
            routeOverlays += line

            // 노선 진행방향을 한눈에 알 수 있도록 정류장 구간마다 화살표 마커 표시
            val step = (points.size / 14).coerceAtLeast(3)
            for (i in 0 until points.size - 1 step step) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val bearing = p1.bearingTo(p2).toFloat()
                val midLat = (p1.latitude + p2.latitude) / 2.0
                val midLng = (p1.longitude + p2.longitude) / 2.0
                routeOverlays += Marker(map).apply {
                    position = GeoPoint(midLat, midLng)
                    setIcon(MarkerIcons.routeDirectionArrow(this@MainActivity, routeColor, bearing))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "${a.lineName} 진행방향: ${stRes.value[i].name} ➔ ${stRes.value[i + 1].name}"
                }
            }

            val bySeq = stRes.value.associateBy { it.seq }
            val busIcon = MarkerIcons.busVehicle(this@MainActivity, a.lineName)
            var count = 0
            when (val lr = loc.await()) {
                is LoadResult.Ok -> lr.value.forEach { b ->
                    val s = bySeq[b.seq] ?: stRes.value.firstOrNull { it.stationId == b.stationId } ?: return@forEach
                    val nextS = bySeq[b.seq + 1]
                    count++
                    routeOverlays += Marker(map).apply {
                        position = GeoPoint(s.lat, s.lng)
                        setIcon(busIcon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "🚌 ${a.lineName} (${b.plateNo})"
                        snippet = "${s.name}" + (nextS?.let { " ➔ ${it.name} 진행 중" } ?: " 부근") + (b.remainSeat?.let { " · 잔여 ${it}석" } ?: "")
                    }
                }
                else -> Unit
            }
            map.overlays.addAll(routeOverlays)
            hideSheet()
            if (points.size >= 2) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, 80)
            map.invalidate()
            btnClearRoute.visibility = View.VISIBLE
            showStatus("${a.lineName}: 운행 차량 ${count}대 및 노선 진행방향(▲) 표시", autoHide = true)
        }
    }

    // ---------------- 지하철 열차 위치 ----------------

    private fun loadTrainPositions() {
        val stop = selected
        if (stop == null || stop.type != StopType.SUBWAY || selectedLines.isEmpty()) {
            if (chipTrains.isChecked) showStatus("지하철역을 선택하면 그 역 노선의 열차 위치를 표시합니다", autoHide = true)
            return
        }
        trainJob?.cancel()
        trainJob = lifecycleScope.launch {
            clearOverlays(trainOverlays)
            val known = repo.knownStationCoords() + stations.associate { it.id to (it.lat to it.lng) }
            val lines = mutableListOf<String>()
            var shown = 0
            for (lineId in selectedLines) {
                when (val r = repo.trainPositions(lineId)) {
                    is LoadResult.Ok -> {
                        val lineShort = SubwayLines.byId(lineId)?.short ?: "?"
                        r.value.forEach { t ->
                            val p = known[t.stationName] ?: return@forEach
                            shown++
                            trainOverlays += trainMarker(t, p, lineShort)
                        }
                        val near = r.value.filter { known.containsKey(it.stationName) }.take(6)
                        lines += "${SubwayLines.name(lineId)} 운행 ${r.value.size}대" +
                            if (near.isNotEmpty()) "\n  " + near.joinToString("\n  ") { "${it.stationName} ${it.status} · ${it.updn} · ${it.terminal}행" + if (it.express) " (급행)" else "" } else ""
                    }
                    is LoadResult.NoData -> lines += "${SubwayLines.name(lineId)}: ${r.reason}"
                    is LoadResult.Failure -> lines += "${SubwayLines.name(lineId)}: ⚠ ${r.reason}"
                }
            }
            map.overlays.addAll(trainOverlays)
            map.invalidate()
            if (selected == stop) {
                sheetRows.addView(infoText("🚆 열차 위치 (지도에는 알려진 역 ${shown}곳에 표시)\n" + lines.joinToString("\n")))
            }
        }
    }

    private fun trainMarker(t: TrainPosition, p: Pair<Double, Double>, lineShort: String): Marker = Marker(map).apply {
        position = GeoPoint(p.first, p.second)
        val arrow = if (t.updn.startsWith("상")) "▲" else "▼"
        setIcon(MarkerIcons.trainVehicle(this@MainActivity, SubwayLines.color(t.subwayId), "$lineShort$arrow"))
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "${SubwayLines.name(t.subwayId)} ${t.trainNo}"
        snippet = "${t.stationName} ${t.status} · ${t.updn} · ${t.terminal}행" + (if (t.lastTrain) " · 막차" else "")
    }

    // ---------------- 검색 ----------------

    private fun search(keyword: String) {
        if (keyword.isEmpty()) return
        hideKeyboard()
        lifecycleScope.launch {
            showStatus("‘$keyword’ 검색 중…")
            val b = async { repo.searchBusStops(keyword) }
            val s = async { repo.searchStations(keyword) }
            val results = mutableListOf<Stop>()
            val msgs = mutableListOf<String>()
            listOf(s.await(), b.await()).forEach { r ->
                when (r) {
                    is LoadResult.Ok -> results += r.value.take(30)
                    is LoadResult.NoData -> Unit
                    is LoadResult.Failure -> msgs += r.reason
                }
            }
            if (results.isEmpty()) {
                showStatus(("검색 결과 없음" + if (msgs.isNotEmpty()) "\n" + msgs.joinToString("\n") else ""))
                return@launch
            }
            statusText.visibility = View.GONE
            val labels = results.map { (if (it.type == StopType.BUS) "🚌 " else "🚇 ") + it.name + if (it.subText.isNotEmpty() && it.type == StopType.BUS) "  (${it.subText})" else "" }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("검색 결과")
                .setItems(labels.toTypedArray()) { _, which ->
                    val stop = results[which]
                    val p = GeoPoint(stop.lat, stop.lng)
                    map.controller.setZoom(17.0)
                    map.controller.animateTo(p)
                    loadAround(p) { openStop(stop) }
                }
                .show()
        }
    }

    // ---------------- 위젯에서 진입 ----------------

    private fun handleWidgetIntent(intent: Intent?): Boolean {
        intent ?: return false
        val id = intent.getStringExtra(EXTRA_STOP_ID) ?: return false
        val type = if (intent.getStringExtra(EXTRA_STOP_TYPE) == "SUBWAY") StopType.SUBWAY else StopType.BUS
        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        val stop = Stop(type, id, intent.getStringExtra(EXTRA_STOP_NAME).orEmpty(), lat, lng)
        intent.removeExtra(EXTRA_STOP_ID)
        if (LocationHelper.hasForeground(this)) enableMyLocationOverlay()
        if (lat != 0.0 && lng != 0.0) {
            val p = GeoPoint(lat, lng)
            map.controller.setZoom(17.0)
            map.controller.setCenter(p)
            loadAround(p) { openStop(stop) }
        } else openStop(stop)
        return true
    }

    // ---------------- 공통 ----------------

    private fun updateModeBanner() {
        val p = repo.prefs
        when {
            p.sampleMode -> showStatus("⚠ 샘플 모드 — 지도·도착 정보는 실제가 아닙니다 (설정에서 끌 수 있음)")
            p.gbisKey.isBlank() -> showStatus("경기버스 API 키가 없습니다. ⚙ 설정에서 입력하면 버스 정보가 표시됩니다.")
        }
    }

    private var hideJob: Job? = null
    private fun showStatus(msg: String, autoHide: Boolean = false) {
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        hideJob?.cancel()
        if (autoHide) hideJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            statusText.visibility = View.GONE
        }
    }

    private fun detachAndClearMarkers(list: MutableList<Marker>) {
        if (list.isEmpty()) return
        list.forEach { m ->
            runCatching {
                m.closeInfoWindow()
                m.setOnMarkerClickListener(null)
            }
        }
        map.overlays.removeAll(list.toSet())
        list.clear()
    }

    private fun clearOverlays(list: MutableList<Overlay>, invalidate: Boolean = true) {
        if (list.isEmpty()) return
        list.forEach { ov ->
            if (ov is Marker) {
                runCatching {
                    ov.closeInfoWindow()
                    ov.setOnMarkerClickListener(null)
                }
            }
        }
        map.overlays.removeAll(list.toSet())
        list.clear()
        if (invalidate) map.invalidate()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    companion object {
        const val EXTRA_STOP_TYPE = "stop_type"
        const val EXTRA_STOP_ID = "stop_id"
        const val EXTRA_STOP_NAME = "stop_name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        /** 위치 권한이 없을 때 기본 지도 중심 (부천시청 부근, 공개 장소) */
        val DEFAULT_CENTER = GeoPoint(37.5035, 126.7660)
    }
}
