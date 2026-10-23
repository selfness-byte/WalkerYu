package com.mj.transitmap.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.mj.transitmap.R
import com.mj.transitmap.data.AppPrefs
import com.mj.transitmap.data.BoardItem
import com.mj.transitmap.data.StopType
import com.mj.transitmap.widget.WidgetUpdater

/**
 * 사용자가 지도에서 추가한 노선들을 추가한 순서 그대로 보여주는 화면.
 */
class DestinationActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destination)
        Insets.applySystemBars(findViewById(R.id.destRoot), top = true)
        prefs = AppPrefs(this)
        list = findViewById(R.id.destList)
        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnGoMap).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun changed() {
        render()
        WidgetUpdater.requestRefresh(this)
    }

    private fun render() {
        list.removeAllViews()
        val items = prefs.savedItems
        if (items.isEmpty()) {
            list.addView(text("아직 추가한 노선이 없습니다.\n지도에서 정류장·역을 눌러 노선 옆 ‘★ 노선 추가’를 누르면 추가한 순서대로 모두 표시됩니다.", 15f))
            return
        }
        items.forEachIndexed { idx, item ->
            list.addView(itemCard(idx, items.size, item))
        }
    }

    private fun itemCard(idx: Int, total: Int, item: BoardItem): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = 0xFFCBD5E1.toInt()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }
        card.addView(row)

        val typeEmoji = if (item.type == StopType.BUS) "🚌" else "🚇"
        val dirArrow = if (item.type == StopType.BUS) "➔" else if (item.direction.contains("상행") || item.direction.contains("내선")) "▲" else "▼"
        row.addView(TextView(this).apply {
            text = "${idx + 1}. $typeEmoji ${item.stopName}\n[${item.lineName}] $dirArrow ${item.direction.ifEmpty { "방향 미지정" }}"
            textSize = 15f
            setTextColor(0xFF102A43.toInt())
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                startActivity(
                    Intent(this@DestinationActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_STOP_TYPE, item.type.name)
                        .putExtra(MainActivity.EXTRA_STOP_ID, item.stopId)
                        .putExtra(MainActivity.EXTRA_STOP_NAME, item.stopName)
                        .putExtra(MainActivity.EXTRA_LAT, item.lat)
                        .putExtra(MainActivity.EXTRA_LNG, item.lng)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        })

        if (total > 1) {
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "▲"
                minWidth = 0
                isEnabled = idx > 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    prefs.moveSavedItem(item.key, -1)
                    changed()
                }
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "▼"
                minWidth = 0
                isEnabled = idx < total - 1
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener {
                    prefs.moveSavedItem(item.key, +1)
                    changed()
                }
            })
        }

        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "삭제"
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                prefs.toggleSavedItem(item)
                changed()
            }
        })
        return card
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s; textSize = size; setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
