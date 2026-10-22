package com.mj.transitmap.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 지도에 표시되는 버스정류장·지하철역·실시간 차량/열차 마커 아이콘을 고해상도 벡터(Canvas)로 그린다.
 * 메모리 누수 및 반복 Bitmap 할당(GC 압박)을 방지하기 위해 LruCache(최대 4MB)에 캐싱하여 재사용한다.
 */
object MarkerIcons {

    // 버스정류장 시그니처 컬러 (지도 공원 녹색과 구분되는 선명한 에메랄드 그린)
    const val COLOR_BUS_STOP = 0xFF008952.toInt()
    const val COLOR_BUS_STOP_SELECTED = 0xFF00693E.toInt()

    // 지하철역 시그니처 컬러 (선명한 로열 네이비 블루 + 골드 포인트)
    const val COLOR_SUBWAY_STATION = 0xFF0D47A1.toInt()
    const val COLOR_SUBWAY_SELECTED = 0xFF082E6B.toInt()
    const val COLOR_SUBWAY_ACCENT = 0xFFFFD600.toInt()

    // 선택 강조 링 컬러 (선명한 앰버 오렌지) 및 즐겨찾기(별표) 컬러
    const val COLOR_SELECTED_RING = 0xFFFF6D00.toInt()
    const val COLOR_STAR_GOLD = 0xFFFFD600.toInt()
    const val COLOR_STAR_STROKE = 0xFFE65100.toInt()

    private val bitmapCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private inline fun cachedDrawable(ctx: Context, key: String, builder: () -> Bitmap): BitmapDrawable {
        val densityKey = "${ctx.resources.displayMetrics.densityDpi}|$key"
        val bmp = synchronized(bitmapCache) {
            bitmapCache.get(densityKey)?.takeIf { !it.isRecycled } ?: builder().also {
                bitmapCache.put(densityKey, it)
            }
        }
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun trimMemory() {
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
    }

    /**
     * 버스정류장 원형 핀 마커 (하단 꼬리가 정류장 좌표를 가리킴)
     */
    fun busStop(ctx: Context, selected: Boolean = false, starred: Boolean = false): BitmapDrawable =
        cachedDrawable(ctx, "busStop|$selected|$starred") {
            val d = ctx.resources.displayMetrics.density
            val circleDp = if (selected) 40f else 31f
            val tailDp = if (selected) 8f else 6f
            val padDp = if (selected) 4f else 2.5f
            val starHeaderDp = if (starred) 13f else 0f

            val w = ((circleDp + padDp * 2 + if (starred) 6f else 0f) * d).toInt()
            val h = ((starHeaderDp + circleDp + tailDp + padDp * 2) * d).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            val cx = w / 2f
            val cy = (padDp + starHeaderDp + circleDp / 2f) * d
            val r = (circleDp / 2f) * d
            val tipY = h - padDp * d

            // 1) 바닥 그림자
            p.style = Paint.Style.FILL
            p.color = 0x38000000
            c.drawOval(RectF(cx - r * 0.45f, tipY - 2 * d, cx + r * 0.45f, tipY + 1.5f * d), p)
            c.drawCircle(cx, cy + 1.5f * d, r, p)

            // 2) 선택 시 오렌지 링 / 별표(starred) 시 골드 링
            if (selected) {
                p.color = COLOR_SELECTED_RING
                c.drawCircle(cx, cy, r + 1.8f * d, p)
            } else if (starred) {
                p.color = COLOR_STAR_GOLD
                c.drawCircle(cx, cy, r + 1.5f * d, p)
            }

            // 3) 흰색 외곽 핀 (원 + 아래 삼각형 꼬리)
            p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            val outerTail = Path().apply {
                moveTo(cx - r * 0.38f, cy + r * 0.78f)
                lineTo(cx + r * 0.38f, cy + r * 0.78f)
                lineTo(cx, tipY)
                close()
            }
            c.drawPath(outerTail, p)

            // 4) 내부 에메랄드 그린 원 + 꼬리
            val border = (if (selected) 3.2f else 2.4f) * d
            val innerR = r - border
            val fillColor = if (selected) COLOR_BUS_STOP_SELECTED else COLOR_BUS_STOP
            p.color = fillColor
            c.drawCircle(cx, cy, innerR, p)
            val innerTail = Path().apply {
                moveTo(cx - innerR * 0.34f, cy + innerR * 0.80f)
                lineTo(cx + innerR * 0.34f, cy + innerR * 0.80f)
                lineTo(cx, tipY - border * 0.9f)
                close()
            }
            c.drawPath(innerTail, p)

            // 5) 정류장 내부에 흰색 버스 정면 픽토그램 그리기
            drawBusGlyph(c, cx, cy, innerR * 1.18f, Color.WHITE, fillColor)

            // 6) 별표 등록된 곳이면 핀 맨 위에 금빛 별표(★) 배지 그리기
            if (starred) {
                val starCy = cy - r - 2.5f * d
                drawStarCrownBadge(c, cx, starCy, 9.5f * d, d)
            }
            bmp
        }

    /**
     * 지하철역 캡슐형 핀 마커 (버스정류장보다 크고 전철 아이콘 + 역 이름을 함께 표시)
     */
    fun subwayStation(ctx: Context, stationName: String, selected: Boolean = false, starred: Boolean = false): BitmapDrawable =
        cachedDrawable(ctx, "subwayStation|${stationName.take(20)}|$selected|$starred") {
            val d = ctx.resources.displayMetrics.density
            val bodyH = (if (selected) 42f else 35f) * d
            val tailH = (if (selected) 8f else 6f) * d
            val pad = (if (selected) 4f else 2.5f) * d
            val starHeader = (if (starred) 13f else 0f) * d

            val baseLabel = stationName.ifBlank { "지하철역" }
            val label = if (starred) "★ $baseLabel" else baseLabel
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (starred) COLOR_STAR_GOLD else Color.WHITE
                textSize = (if (selected) 15f else 13.5f) * d
                typeface = Typeface.DEFAULT_BOLD
            }
            val textW = textPaint.measureText(label)
            val iconBadgeSize = bodyH - 8f * d
            val bodyW = max(bodyH * 1.6f, 6f * d + iconBadgeSize + 7f * d + textW + 12f * d)

            val w = (bodyW + pad * 2).toInt()
            val h = (starHeader + bodyH + tailH + pad * 2).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            val cx = w / 2f
            val left = pad
            val top = pad + starHeader
            val right = w - pad
            val bottom = top + bodyH
            val corner = bodyH / 2f
            val tipY = h - pad

            // 1) 그림자
            p.style = Paint.Style.FILL
            p.color = 0x45000000
            c.drawRoundRect(RectF(left, top + 2f * d, right, bottom + 2f * d), corner, corner, p)
            c.drawOval(RectF(cx - 8f * d, tipY - 2f * d, cx + 8f * d, tipY + 1.5f * d), p)

            // 2) 바깥 테두리 (선택 시 오렌지, 기본은 골드 옐로)
            p.color = if (selected) COLOR_SELECTED_RING else COLOR_SUBWAY_ACCENT
            c.drawRoundRect(RectF(left, top, right, bottom), corner, corner, p)
            val outerTail = Path().apply {
                moveTo(cx - 7f * d, bottom - 1f * d)
                lineTo(cx + 7f * d, bottom - 1f * d)
                lineTo(cx, tipY)
                close()
            }
            c.drawPath(outerTail, p)

            // 3) 내부 로열 네이비 블루 캡슐 + 꼬리
            val border = (if (selected) 3.2f else 2.5f) * d
            val bgColor = if (selected) COLOR_SUBWAY_SELECTED else COLOR_SUBWAY_STATION
            p.color = bgColor
            val innerRect = RectF(left + border, top + border, right - border, bottom - border)
            val innerCorner = innerRect.height() / 2f
            c.drawRoundRect(innerRect, innerCorner, innerCorner, p)
            val innerTail = Path().apply {
                moveTo(cx - 5f * d, bottom - border)
                lineTo(cx + 5f * d, bottom - border)
                lineTo(cx, tipY - border)
                close()
            }
            c.drawPath(innerTail, p)

            // 4) 왼쪽 원형 배지 + 전철 정면 픽토그램
            val badgeCx = innerRect.left + innerCorner
            val badgeCy = innerRect.centerY()
            val badgeR = innerCorner - 2f * d
            p.color = COLOR_SUBWAY_ACCENT
            c.drawCircle(badgeCx, badgeCy, badgeR, p)
            drawTrainGlyph(c, badgeCx, badgeCy, badgeR * 1.25f, bgColor, COLOR_SUBWAY_ACCENT)

            // 5) 오른쪽 역 이름 텍스트
            val textX = badgeCx + badgeR + 6f * d
            val textY = badgeCy - (textPaint.descent() + textPaint.ascent()) / 2f
            c.drawText(label, textX, textY, textPaint)

            // 6) 별표 등록된 역이면 캡슐 상단 중앙에 금빛 별표(★) 왕관 배지 표시
            if (starred) {
                val starCy = top - 2f * d
                drawStarCrownBadge(c, cx, starCy, 10f * d, d)
            }
            bmp
        }

    /**
     * 노선 조회 시 표시되는 실시간 버스 차량 아이콘 (빨간 캡슐 + 버스 심볼 + 노선번호)
     */
    fun busVehicle(ctx: Context, routeName: String): BitmapDrawable {
        val text = routeName.take(6).ifBlank { "버스" }
        return cachedDrawable(ctx, "busVehicle|$text") {
            val d = ctx.resources.displayMetrics.density
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f * d
                typeface = Typeface.DEFAULT_BOLD
            }
            val h = (28f * d).toInt()
            val iconSpace = 20f * d
            val w = (iconSpace + tp.measureText(text) + 14f * d).toInt().coerceAtLeast(h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            val r = h / 2f
            p.color = Color.WHITE
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
            val bg = 0xFFD32F2F.toInt()
            p.color = bg
            c.drawRoundRect(RectF(2f * d, 2f * d, w - 2f * d, h - 2f * d), r - 2f * d, r - 2f * d, p)

            drawBusGlyph(c, 12f * d, h / 2f, 13f * d, Color.WHITE, bg)
            val ty = h / 2f - (tp.descent() + tp.ascent()) / 2f
            c.drawText(text, iconSpace + 2f * d, ty, tp)
            bmp
        }
    }

    /**
     * 실시간 지하철 열차 위치 아이콘 (호선 색상 캡슐 + 노란 테두리 + 열차 심볼 + 호선/상하행)
     */
    fun trainVehicle(ctx: Context, lineColor: Int, label: String): BitmapDrawable =
        cachedDrawable(ctx, "trainVehicle|$lineColor|${label.take(16)}") {
            val d = ctx.resources.displayMetrics.density
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12.5f * d
                typeface = Typeface.DEFAULT_BOLD
            }
            val h = (29f * d).toInt()
            val iconSpace = 20f * d
            val w = (iconSpace + tp.measureText(label) + 14f * d).toInt().coerceAtLeast(h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            val r = h / 2f
            p.color = COLOR_SUBWAY_ACCENT
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
            p.color = lineColor
            c.drawRoundRect(RectF(2.2f * d, 2.2f * d, w - 2.2f * d, h - 2.2f * d), r - 2f * d, r - 2f * d, p)

            drawTrainGlyph(c, 12f * d, h / 2f, 13f * d, Color.WHITE, lineColor)
            val ty = h / 2f - (tp.descent() + tp.ascent()) / 2f
            c.drawText(label, iconSpace + 2f * d, ty, tp)
            bmp
        }

    /**
     * 버스 노선 진행방향 화살표 아이콘 (15도 단위로 양자화하여 캐시 효율 극대화)
     */
    fun routeDirectionArrow(ctx: Context, routeColor: Int, bearingDeg: Float): BitmapDrawable {
        val norm = ((bearingDeg % 360f) + 360f) % 360f
        val quantized = ((norm / 15f).roundToInt() * 15) % 360
        return cachedDrawable(ctx, "routeArrow|$routeColor|$quantized") {
            val d = ctx.resources.displayMetrics.density
            val size = (22f * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val cx = size / 2f
            val cy = size / 2f
            val r = size / 2f - 1.5f * d

            p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            p.color = routeColor
            c.drawCircle(cx, cy, r - 1.8f * d, p)

            c.save()
            c.rotate(quantized.toFloat(), cx, cy)
            p.color = Color.WHITE
            val arrow = Path().apply {
                moveTo(cx, cy - r * 0.55f)
                lineTo(cx + r * 0.48f, cy + r * 0.42f)
                lineTo(cx, cy + r * 0.18f)
                lineTo(cx - r * 0.48f, cy + r * 0.42f)
                close()
            }
            c.drawPath(arrow, p)
            c.restore()
            bmp
        }
    }

    /**
     * 지도를 이동해 다른 위치에 고정했을 때 화면 정중앙에 표시할 조준 핀 아이콘
     */
    fun centerTargetPin(ctx: Context): BitmapDrawable =
        cachedDrawable(ctx, "centerTargetPin") {
            val d = ctx.resources.displayMetrics.density
            val size = (38f * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = size / 2f
            val cy = size / 2f

            // 바깥 반투명 흰색 원 + 파란 조준 링
            p.style = Paint.Style.FILL
            p.color = 0x55FFFFFF
            c.drawCircle(cx, cy, 14f * d, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f * d
            p.color = Color.WHITE
            c.drawCircle(cx, cy, 11f * d, p)

            p.strokeWidth = 2f * d
            p.color = 0xFF1565C0.toInt()
            c.drawCircle(cx, cy, 11f * d, p)

            // 십자선
            c.drawLine(cx - 16f * d, cy, cx - 6f * d, cy, p)
            c.drawLine(cx + 6f * d, cy, cx + 16f * d, cy, p)
            c.drawLine(cx, cy - 16f * d, cx, cy - 6f * d, p)
            c.drawLine(cx, cy + 6f * d, cx, cy + 16f * d, p)

            // 중앙 빨간/주황 포인트 점
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawCircle(cx, cy, 4.2f * d, p)
            p.color = 0xFFFF3D00.toInt()
            c.drawCircle(cx, cy, 3f * d, p)
            bmp
        }

    /**
     * 하단 도착 시트 제목 옆에 놓을 정류장/역 구분 배지 아이콘
     */
    fun sheetHeaderBadge(ctx: Context, isBus: Boolean): BitmapDrawable =
        cachedDrawable(ctx, "sheetHeaderBadge|$isBus") {
            val d = ctx.resources.displayMetrics.density
            val size = (40f * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = size / 2f
            val cy = size / 2f
            if (isBus) {
                p.color = COLOR_BUS_STOP
                c.drawCircle(cx, cy, size / 2f - 1f * d, p)
                drawBusGlyph(c, cx, cy, size * 0.56f, Color.WHITE, COLOR_BUS_STOP)
            } else {
                p.color = COLOR_SUBWAY_STATION
                c.drawRoundRect(RectF(1f * d, 1f * d, size - 1f * d, size - 1f * d), 11f * d, 11f * d, p)
                drawTrainGlyph(c, cx, cy, size * 0.58f, COLOR_SUBWAY_ACCENT, COLOR_SUBWAY_STATION)
            }
            bmp
        }

    /** 마커 상단에 그리는 금빛 오각 별표(★) 왕관 배지 */
    private fun drawStarCrownBadge(c: Canvas, cx: Float, cy: Float, radius: Float, d: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        c.drawCircle(cx, cy, radius + 1.6f * d, p)
        p.color = 0xFF1A237E.toInt()
        c.drawCircle(cx, cy, radius + 0.3f * d, p)

        val starPath = Path()
        val outerR = radius * 0.92f
        val innerR = outerR * 0.42f
        for (i in 0 until 10) {
            val angleRad = Math.toRadians((-90.0 + i * 36.0))
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + (r * cos(angleRad)).toFloat()
            val y = cy + (r * sin(angleRad)).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()

        p.style = Paint.Style.FILL
        p.color = COLOR_STAR_GOLD
        c.drawPath(starPath, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.9f * d
        p.color = COLOR_STAR_STROKE
        c.drawPath(starPath, p)
    }

    /** 버스 정면 픽토그램 (차체 + 앞창문 + 헤드라이트 2개 + 바퀴 2개) */
    private fun drawBusGlyph(c: Canvas, cx: Float, cy: Float, size: Float, fg: Int, cutout: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val bw = size * 0.78f
        val bh = size * 0.76f
        val left = cx - bw / 2f
        val top = cy - bh / 2f - size * 0.04f
        val right = cx + bw / 2f
        val bottom = top + bh

        p.color = fg
        c.drawRoundRect(RectF(left - size * 0.09f, top + bh * 0.22f, left, top + bh * 0.45f), size * 0.04f, size * 0.04f, p)
        c.drawRoundRect(RectF(right, top + bh * 0.22f, right + size * 0.09f, top + bh * 0.45f), size * 0.04f, size * 0.04f, p)

        c.drawRoundRect(RectF(left, top, right, bottom), size * 0.14f, size * 0.14f, p)

        val wheelW = bw * 0.20f
        val wheelH = size * 0.14f
        c.drawRoundRect(RectF(left + bw * 0.12f, bottom - wheelH * 0.3f, left + bw * 0.12f + wheelW, bottom + wheelH), size * 0.04f, size * 0.04f, p)
        c.drawRoundRect(RectF(right - bw * 0.12f - wheelW, bottom - wheelH * 0.3f, right - bw * 0.12f, bottom + wheelH), size * 0.04f, size * 0.04f, p)

        p.color = cutout
        c.drawRoundRect(RectF(left + bw * 0.22f, top + bh * 0.10f, right - bw * 0.22f, top + bh * 0.20f), size * 0.03f, size * 0.03f, p)
        c.drawRoundRect(RectF(left + bw * 0.12f, top + bh * 0.26f, right - bw * 0.12f, top + bh * 0.62f), size * 0.06f, size * 0.06f, p)

        val lightR = size * 0.075f
        val lightY = top + bh * 0.78f
        c.drawCircle(left + bw * 0.24f, lightY, lightR, p)
        c.drawCircle(right - bw * 0.24f, lightY, lightR, p)
    }

    /** 전철(지하철) 정면 픽토그램 (둥근 전철 차체 + 앞유리 + 전조등 + 하단 철로 침목) */
    private fun drawTrainGlyph(c: Canvas, cx: Float, cy: Float, size: Float, fg: Int, cutout: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val tw = size * 0.74f
        val th = size * 0.72f
        val left = cx - tw / 2f
        val top = cy - th / 2f - size * 0.05f
        val right = cx + tw / 2f
        val bottom = top + th

        p.color = fg
        c.drawRoundRect(RectF(cx - tw * 0.18f, top - size * 0.07f, cx + tw * 0.18f, top + size * 0.02f), size * 0.03f, size * 0.03f, p)

        c.drawRoundRect(RectF(left, top, right, bottom), size * 0.18f, size * 0.18f, p)

        val railPath = Path().apply {
            moveTo(left + tw * 0.18f, bottom)
            lineTo(left + tw * 0.04f, bottom + size * 0.15f)
            lineTo(left + tw * 0.20f, bottom + size * 0.15f)
            lineTo(left + tw * 0.30f, bottom)
            close()
            moveTo(right - tw * 0.18f, bottom)
            lineTo(right - tw * 0.04f, bottom + size * 0.15f)
            lineTo(right - tw * 0.20f, bottom + size * 0.15f)
            lineTo(right - tw * 0.30f, bottom)
            close()
        }
        c.drawPath(railPath, p)

        p.color = cutout
        c.drawRoundRect(RectF(left + tw * 0.13f, top + th * 0.16f, right - tw * 0.13f, top + th * 0.56f), size * 0.07f, size * 0.07f, p)
        val lightR = size * 0.075f
        val lightY = top + th * 0.76f
        c.drawCircle(left + tw * 0.26f, lightY, lightR, p)
        c.drawCircle(right - tw * 0.26f, lightY, lightR, p)
    }
}
