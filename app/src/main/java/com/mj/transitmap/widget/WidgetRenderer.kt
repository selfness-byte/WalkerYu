package com.mj.transitmap.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.mj.transitmap.R
import com.mj.transitmap.data.AppPrefs
import com.mj.transitmap.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 3가지 창 모드: 추가한 모든 노선이 잘리지 않고 그대로 다 보이도록 충분한 최대치를 허용한다 */
enum class WidgetMode(val maxGroups: Int, val maxItemsPerGroup: Int, val maxTotalItems: Int) {
    SMALL(maxGroups = 8, maxItemsPerGroup = 8, maxTotalItems = 8),
    MEDIUM(maxGroups = 20, maxItemsPerGroup = 20, maxTotalItems = 25),
    LARGE(maxGroups = 25, maxItemsPerGroup = 25, maxTotalItems = 30),
}

object WidgetRenderer {

    /** 추가한 정류장·역이 내 위치로부터 이 거리(m) 이내이면 흰색 네온 깜빡임 효과를 켠다 */
    private const val NEON_DISTANCE_M = 500
    private const val NEON_NEAREST_MAX_M = 1200

    fun hhmm(t: Long): String = if (t <= 0) "--:--" else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(t))

    /**
     * 버스·지하철 진행방향을 위젯에서 한눈에 읽을 수 있도록 불필요한 중복 어절을 정리하고 고대비 색상을 부여한다.
     * - 지하철 상행/내선: 선명한 스카이 블루 (#64D8FF) + ▲
     * - 지하철 하행/외선: 선명한 코랄 오렌지 (#FF9E80) + ▼
     * - 버스 방면:       선명한 에메랄드 민트 (#69F0AE) + ➔
     */
    fun styleDirection(isBus: Boolean, rawDir: String): Pair<String, Int> {
        val clean = rawDir.trim()
        if (clean.isEmpty()) return (if (isBus) "➔ 방향 미지정" else "▶ 방향 미지정") to 0xFFB0BEC5.toInt()
        if (isBus) {
            val formatted = if (clean.startsWith("➔") || clean.startsWith("▶")) clean else "➔ $clean"
            return formatted to 0xFF69F0AE.toInt()
        }
        val normalized = clean.replace(" - ", "·")
        return if (clean.contains("상행") || clean.contains("내선")) {
            "▲ $normalized" to 0xFF64D8FF.toInt()
        } else if (clean.contains("하행") || clean.contains("외선")) {
            "▼ $normalized" to 0xFFFF9E80.toInt()
        } else {
            "▶ $normalized" to 0xFFFFE082.toInt()
        }
    }

    /**
     * 위젯 행 우측의 도착정보 문구를 간결하게 다듬어 진행방향(gi_dir)이 잘리지 않고 한눈에 읽히도록 한다.
     */
    private fun compactArrival(row: WidgetRow): Pair<String, Int> {
        val raw = row.main.trim()
        if (row.status == RowStatus.NO_DATA || raw.contains("정보 없음")) {
            return "운행대기/없음" to 0xFF90A4AE.toInt()
        }
        if (row.status == RowStatus.ERROR || row.status == RowStatus.NEED_KEY) {
            return (statusPrefix(row) + raw) to 0xFFFF8A80.toInt()
        }
        val compact = raw
            .replace("정류장 전", "전")
            .replace("번째 전", "전")
        val text = statusPrefix(row) + compact
        val isSoon = raw.contains("곧") || raw.contains("진입") || raw.contains("도착")
        val color = if (isSoon) 0xFF00FFCC.toInt() else 0xFFFFD600.toInt()
        return text to color
    }

    fun build(ctx: Context, mode: WidgetMode, snap: WidgetSnapshot, refreshing: Boolean): RemoteViews {
        val prefs = AppPrefs(ctx)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val layout = if (mode == WidgetMode.SMALL) R.layout.widget_small else R.layout.widget_list
        val rv = RemoteViews(ctx.packageName, layout)

        // 정류장·지하철역 단위로 추가 순서를 그대로 유지하여 그룹화
        val stopGroups = snap.rows
            .groupBy { "${it.isBus}|${it.stopId.ifEmpty { it.title }}" }
            .values
            .toList()

        // 가장 가까운 정류장/역 거리 계산 (흰색 네온 깜빡임 판정용)
        val nearestDist = stopGroups.mapNotNull { it.firstOrNull()?.distanceM }.minOrNull()

        // 위젯 바깥 테두리는 항상 기본 고대비 다크 배경(widget_bg) 유지
        rv.setInt(R.id.w_root, "setBackgroundResource", R.drawable.widget_bg)
        rv.setTextViewText(R.id.w_title, snap.header)

        val locOn = prefs.widgetUseLocation
        val locLabel = if (mode == WidgetMode.SMALL) {
            if (locOn) "📍ON·1분" else "○위치 OFF"
        } else {
            if (locOn) "📍 위치 ON (1분)" else "○ 위치 OFF"
        }
        rv.setTextViewText(R.id.w_loc, locLabel)
        rv.setInt(R.id.w_loc, "setBackgroundResource", if (locOn) R.drawable.widget_chip_on else R.drawable.widget_chip_off)

        val refreshLabel = if (mode == WidgetMode.SMALL) {
            if (refreshing) "⏳갱신중" else "↻ 갱신"
        } else {
            if (refreshing) "⏳ 갱신중…" else "↻ 새로고침"
        }
        rv.setTextViewText(R.id.w_refresh, refreshLabel)

        val stale = snap.updatedAt > 0 && System.currentTimeMillis() - snap.updatedAt > 20 * 60 * 1000L
        val updated = when {
            refreshing -> "갱신 중…"
            snap.updatedAt <= 0 -> "아직 갱신 안 됨"
            stale -> "${hhmm(snap.updatedAt)} · 오래됨"
            locOn -> "${hhmm(snap.updatedAt)} · 1분주기 자동갱신"
            else -> "${hhmm(snap.updatedAt)} 기준"
        }
        rv.setTextViewText(R.id.w_updated, updated)

        rv.setOnClickPendingIntent(R.id.w_loc, actionIntent(ctx, WidgetActionReceiver.ACTION_TOGGLE_LOCATION, 1))
        rv.setOnClickPendingIntent(R.id.w_refresh, actionIntent(ctx, WidgetActionReceiver.ACTION_REFRESH, 2))
        rv.setOnClickPendingIntent(R.id.w_title, openAppIntent(ctx, null, 3))

        if (mode != WidgetMode.SMALL) {
            rv.setTextViewText(R.id.w_note, snap.note)
            rv.setViewVisibility(R.id.w_note, if (snap.note.isEmpty()) View.GONE else View.VISIBLE)
        }

        rv.removeAllViews(R.id.w_rows)
        if (stopGroups.isEmpty()) {
            val emptyGroup = RemoteViews(ctx.packageName, R.layout.widget_group)
            emptyGroup.setTextViewText(R.id.g_title, snap.note.ifEmpty { "지도에서 노선 옆 ‘★ 노선 추가’를 눌러 추가하세요" })
            emptyGroup.setTextViewText(R.id.g_meta, "앱 열기 ➔")
            emptyGroup.setOnClickPendingIntent(R.id.g_root, openAppIntent(ctx, null, 10))
            rv.addView(R.id.w_rows, emptyGroup)
            return rv
        }

        // 추가한 노선 수가 많을수록 패딩·글자크기를 자동 조절해 추가한 노선이 전부 한 화면에 들어오도록 한다
        val totalRows = snap.rows.size
        val isCompact = totalRows >= 5 || stopGroups.size >= 3
        val isUltraCompact = totalRows >= 8 || stopGroups.size >= 4

        var totalShown = 0
        var reqSeq = 20
        for (group in stopGroups.take(mode.maxGroups)) {
            if (totalShown >= mode.maxTotalItems) break
            val first = group.first()
            val dist = first.distanceM
            val isNear = dist != null && (dist <= NEON_DISTANCE_M || (dist == nearestDist && dist <= NEON_NEAREST_MAX_M))

            // 내 위치와 가까운 정류장/역은 천천히 깜빡이는 흰색 테두리 레이아웃(widget_group_neon_card)으로 렌더링
            val groupLayout = if (isNear) R.layout.widget_group_neon_card else R.layout.widget_group
            val groupView = RemoteViews(ctx.packageName, groupLayout)

            if (isUltraCompact) {
                val padContainer = if (isNear) R.id.g_content else R.id.g_root
                groupView.setViewPadding(padContainer, dp(6f), dp(3f), dp(6f), dp(3f))
                groupView.setViewPadding(R.id.g_header, dp(7f), dp(2.5f), dp(7f), dp(2.5f))
                groupView.setTextViewTextSize(R.id.g_title, TypedValue.COMPLEX_UNIT_SP, 13.5f)
                groupView.setTextViewTextSize(R.id.g_meta, TypedValue.COMPLEX_UNIT_SP, 11.5f)
            } else if (isCompact) {
                val padContainer = if (isNear) R.id.g_content else R.id.g_root
                groupView.setViewPadding(padContainer, dp(7f), dp(4f), dp(7f), dp(4f))
                groupView.setViewPadding(R.id.g_header, dp(8f), dp(3.5f), dp(8f), dp(3.5f))
                groupView.setTextViewTextSize(R.id.g_title, TypedValue.COMPLEX_UNIT_SP, 14.5f)
            }

            val distStr = dist?.let { if (it >= 1000) "%.1fkm".format(it / 1000.0) else "${it}m" }
            val titleText = first.title
            val metaText = when {
                isNear && distStr != null -> "✦ 내 주변 $distStr"
                distStr != null -> distStr
                else -> "${group.size}개 노선"
            }
            groupView.setTextViewText(R.id.g_title, titleText)
            groupView.setTextViewText(R.id.g_meta, metaText)
            groupView.setOnClickPendingIntent(R.id.g_header, openAppIntent(ctx, first, reqSeq++))

            groupView.removeAllViews(R.id.g_items)
            val limit = minOf(mode.maxItemsPerGroup, mode.maxTotalItems - totalShown)
            for (row in group.take(limit)) {
                val itemView = RemoteViews(ctx.packageName, R.layout.widget_group_item)
                if (isUltraCompact) {
                    itemView.setViewPadding(R.id.gi_root, dp(5f), dp(2f), dp(5f), dp(2f))
                    itemView.setTextViewTextSize(R.id.gi_badge, TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    itemView.setTextViewTextSize(R.id.gi_dir, TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    itemView.setTextViewTextSize(R.id.gi_main, TypedValue.COMPLEX_UNIT_SP, 13f)
                } else if (isCompact) {
                    itemView.setViewPadding(R.id.gi_root, dp(6f), dp(3f), dp(6f), dp(3f))
                    itemView.setTextViewTextSize(R.id.gi_badge, TypedValue.COMPLEX_UNIT_SP, 13f)
                    itemView.setTextViewTextSize(R.id.gi_dir, TypedValue.COMPLEX_UNIT_SP, 13f)
                    itemView.setTextViewTextSize(R.id.gi_main, TypedValue.COMPLEX_UNIT_SP, 13.5f)
                }

                itemView.setTextViewText(R.id.gi_badge, row.badge)
                itemView.setInt(R.id.gi_badge, "setBackgroundColor", row.badgeColor)

                val (dirText, dirColor) = styleDirection(row.isBus, row.direction)
                itemView.setTextViewText(R.id.gi_dir, dirText)
                itemView.setTextColor(R.id.gi_dir, dirColor)

                val (mainText, mainColor) = compactArrival(row)
                itemView.setTextViewText(R.id.gi_main, mainText)
                itemView.setTextColor(R.id.gi_main, mainColor)

                itemView.setOnClickPendingIntent(R.id.gi_root, openAppIntent(ctx, row, reqSeq++))
                groupView.addView(R.id.g_items, itemView)
                totalShown++
            }

            rv.addView(R.id.w_rows, groupView)
        }
        return rv
    }

    private fun statusPrefix(row: WidgetRow) = when (row.status) {
        RowStatus.SAMPLE -> "[샘플] "
        RowStatus.CACHED -> "(캐시) "
        RowStatus.ERROR, RowStatus.NEED_KEY -> "⚠ "
        else -> ""
    }

    private fun actionIntent(ctx: Context, action: String, req: Int): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openAppIntent(ctx: Context, row: WidgetRow?, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = "com.mj.transitmap.OPEN_$req"
            if (row != null && row.stopId.isNotEmpty()) {
                putExtra(MainActivity.EXTRA_STOP_TYPE, if (row.isBus) "BUS" else "SUBWAY")
                putExtra(MainActivity.EXTRA_STOP_ID, row.stopId)
                putExtra(MainActivity.EXTRA_STOP_NAME, row.stopName)
                putExtra(MainActivity.EXTRA_LAT, row.lat)
                putExtra(MainActivity.EXTRA_LNG, row.lng)
            }
        }
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

object WidgetUpdater {
    private val providers = listOf(
        SmallWidgetProvider::class.java to WidgetMode.SMALL,
        MediumWidgetProvider::class.java to WidgetMode.MEDIUM,
        LargeWidgetProvider::class.java to WidgetMode.LARGE,
    )

    fun hasWidgets(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.any { (cls, _) -> mgr.getAppWidgetIds(ComponentName(ctx, cls)).isNotEmpty() }
    }

    fun renderAll(ctx: Context, refreshing: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val snap = WidgetSnapshot.fromJson(AppPrefs(ctx).widgetSnapshotJson)
        providers.forEach { (cls, mode) ->
            mgr.getAppWidgetIds(ComponentName(ctx, cls)).forEach { id ->
                mgr.updateAppWidget(id, WidgetRenderer.build(ctx, mode, snap, refreshing))
            }
        }
    }

    private val refreshMutex = Mutex()

    suspend fun refreshAll(ctx: Context) {
        if (!hasWidgets(ctx)) return
        if (!refreshMutex.tryLock()) return
        try {
            val prefs = AppPrefs(ctx)
            val prev = WidgetSnapshot.fromJson(prefs.widgetSnapshotJson)
            val snap = runCatching { WidgetDataBuilder(ctx).build(prev) }
                .getOrElse { prev.copy(note = "갱신 실패 — 이전 정보 표시 중") }
            prefs.widgetSnapshotJson = snap.toJson()
            renderAll(ctx, refreshing = false)
        } finally {
            refreshMutex.unlock()
        }
    }

    /** 앱에서 노선·설정이 바뀌었을 때, 또는 위젯 ⟳ 버튼 */
    fun requestRefresh(ctx: Context) {
        WidgetRefreshJob.syncMinuteSchedule(ctx)
        if (!hasWidgets(ctx)) return
        renderAll(ctx, refreshing = true)
        WidgetRefreshJob.runNow(ctx)
    }
}
