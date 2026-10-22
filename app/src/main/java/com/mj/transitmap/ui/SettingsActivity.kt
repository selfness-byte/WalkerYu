package com.mj.transitmap.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.mj.transitmap.R
import com.mj.transitmap.data.AppPrefs
import com.mj.transitmap.data.LocationHelper
import com.mj.transitmap.update.AppUpdater
import com.mj.transitmap.widget.WidgetUpdater

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var swWidgetLoc: MaterialSwitch
    private lateinit var permText: TextView

    private val fgLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) askBackground() else {
            swWidgetLoc.isChecked = false
            Toast.makeText(this, "위치 권한이 없어 위젯 위치 갱신을 켤 수 없습니다", Toast.LENGTH_SHORT).show()
        }
        updatePermText()
    }

    private val bgLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermText()
        WidgetUpdater.requestRefresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Insets.applySystemBars(findViewById(R.id.settingsRoot), top = true)
        findViewById<MaterialButton>(R.id.btnBackSettings).setOnClickListener { finish() }
        prefs = AppPrefs(this)

        val gbis = findViewById<EditText>(R.id.inputGbisKey)
        val subway = findViewById<EditText>(R.id.inputSubwayKey)
        val swSample = findViewById<MaterialSwitch>(R.id.swSample)
        swWidgetLoc = findViewById(R.id.swWidgetLocation)
        permText = findViewById(R.id.permText)

        // 키는 화면에 그대로 보이지 않게 (눈 버튼으로 확인)
        gbis.setText(prefs.gbisKey)
        subway.setText(prefs.subwayKey)
        swSample.isChecked = prefs.sampleMode
        swWidgetLoc.isChecked = prefs.widgetUseLocation

        findViewById<MaterialButton>(R.id.btnShowKeys).setOnClickListener {
            val visible = gbis.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
            val type = InputType.TYPE_CLASS_TEXT or
                if (visible) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            gbis.inputType = type; subway.inputType = type
        }

        swWidgetLoc.setOnCheckedChangeListener { _, checked ->
            if (checked && !LocationHelper.hasForeground(this)) {
                fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else if (checked && !LocationHelper.hasBackground(this)) {
                askBackground()
            }
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.gbisKey = gbis.text.toString()
            prefs.subwayKey = subway.text.toString()
            prefs.sampleMode = swSample.isChecked
            prefs.widgetUseLocation = swWidgetLoc.isChecked
            Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
            WidgetUpdater.requestRefresh(this)
            finish()
        }

        findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val versionText = findViewById<TextView>(R.id.versionText)
        val curName = AppUpdater.installedVersionName(this)
        val curCode = AppUpdater.installedVersionCode(this)
        versionText.text = "WalkerYu v$curName (code $curCode) · 자동 업데이트 지원"
        findViewById<MaterialButton>(R.id.btnCheckUpdate).setOnClickListener {
            AppUpdater.checkManual(this, lifecycleScope) { msg ->
                versionText.text = msg
            }
        }
        updatePermText()
    }

    override fun onResume() {
        super.onResume()
        updatePermText()
        AppUpdater.resumePendingInstallIfAllowed(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun askBackground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || LocationHelper.hasBackground(this)) return
        AlertDialog.Builder(this)
            .setTitle("위젯 위치 갱신")
            .setMessage("앱이 닫혀 있을 때 위젯이 현재 위치 기준으로 가까운 탈 곳을 고르려면 위치 권한을 ‘항상 허용’으로 바꿔야 합니다.\n\n허용하지 않아도 위젯은 저장한 탈 곳 순서대로 동작합니다.")
            .setPositiveButton("권한 설정") { _, _ -> bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun updatePermText() {
        permText.text = when {
            !LocationHelper.hasForeground(this) -> "위치 권한: 없음"
            !LocationHelper.hasBackground(this) -> "위치 권한: 앱 사용 중에만 (위젯 위치 갱신은 제한될 수 있음)"
            else -> "위치 권한: 항상 허용"
        }
    }
}
