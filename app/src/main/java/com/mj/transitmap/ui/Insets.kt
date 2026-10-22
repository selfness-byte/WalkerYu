package com.mj.transitmap.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15(targetSdk 35) Edge-to-Edge 환경에서 상단 상태바·컷아웃 및 하단 내비게이션바가
 * 화면 상단 고정바나 메뉴를 가리지 않도록 기존 XML 패딩에 시스템 바 영역을 더해 준다.
 */
object Insets {
    fun applySystemBars(view: View, top: Boolean = true) {
        val initLeft = view.paddingLeft
        val initTop = view.paddingTop
        val initRight = view.paddingRight
        val initBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                initLeft + b.left,
                initTop + if (top) b.top else 0,
                initRight + b.right,
                initBottom + b.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
