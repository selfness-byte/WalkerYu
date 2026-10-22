package com.mj.transitmap.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.mj.transitmap.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

/**
 * www.inanimals.co.kr / inanimals-1977.web.app / GitHub 저장소에서 최신 버전 정보를 확인하고,
 * 새 버전이 있으면 APK 다운로드·SHA-256 무결성 검증·Android 패키지 설치 화면 실행까지 수행하는 자동 업데이트 매니저.
 */
object AppUpdater {

    private val UPDATE_URLS = listOf(
        "https://www.inanimals.co.kr/walkeryu/update.json",
        "https://www.inanimals.co.kr/transitmap/update.json",
        "https://inanimals-1977.web.app/walkeryu/update.json",
        "https://inanimals-1977.web.app/transitmap/update.json",
        "https://raw.githubusercontent.com/selfness-byte/WalkerYu/main/dist/update.json",
    )

    @Volatile
    private var checkedThisSession = false

    /** 앱 시작 시 임시 업데이트 APK 정리 및 백그라운드 자동 업데이트 확인 (세션당 1회) */
    fun checkOnStartup(activity: Activity, scope: CoroutineScope) {
        if (checkedThisSession) return
        checkedThisSession = true
        scope.launch {
            withContext(Dispatchers.IO) { cleanOldApks(activity.applicationContext) }
            val info = fetchLatestUpdate() ?: return@launch
            if (info.versionCode > BuildConfig.VERSION_CODE && !activity.isFinishing && !activity.isDestroyed) {
                showUpdateDialog(activity, scope, info)
            }
        }
    }

    /** 설정 화면에서 사용자가 직접 [최신 업데이트 확인] 버튼을 눌렀을 때 호출 */
    fun checkManual(activity: Activity, scope: CoroutineScope, onStatus: (String) -> Unit) {
        onStatus("서버에서 최신 버전 확인 중…")
        scope.launch {
            val info = fetchLatestUpdate()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (info == null) {
                onStatus("현재 버전 v${BuildConfig.VERSION_NAME} (서버 연결 실패 또는 오프라인)")
                Toast.makeText(activity, "업데이트 서버에 연결하지 못했습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (info.versionCode > BuildConfig.VERSION_CODE) {
                onStatus("새 버전 발견: v${info.versionName} (현재 v${BuildConfig.VERSION_NAME})")
                showUpdateDialog(activity, scope, info)
            } else {
                onStatus("최신 버전 사용 중: v${BuildConfig.VERSION_NAME} (서버 v${info.versionName})")
                Toast.makeText(activity, "현재 최신 버전(v${BuildConfig.VERSION_NAME})입니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun fetchLatestUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        for (url in UPDATE_URLS) {
            val info = runCatching { requestUpdateJson(url) }.getOrNull()
            if (info != null && info.versionCode >= 1 && info.apkUrl.startsWith("https://")) {
                return@withContext info
            }
        }
        null
    }

    private fun requestUpdateJson(urlStr: String): UpdateInfo? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "WalkerYu/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(body)
            UpdateInfo(
                versionCode = o.optInt("versionCode", o.optInt("version_code", 0)),
                versionName = o.optString("versionName", o.optString("latest_version", "")),
                apkUrl = o.optString("apkUrl", o.optString("download_url", "")),
                sha256 = o.optString("sha256", "").trim().lowercase(),
                notes = o.optString("notes", o.optString("release_notes", "성능 및 안정성 개선 업데이트")),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpdateDialog(activity: Activity, scope: CoroutineScope, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("🚀 새 버전 업데이트 (v${info.versionName})")
            .setMessage("현재 버전: v${BuildConfig.VERSION_NAME}\n최신 버전: v${info.versionName}\n\n${info.notes}")
            .setPositiveButton("지금 업데이트") { _, _ ->
                downloadAndInstall(activity, scope, info)
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, scope: CoroutineScope, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "업데이트 설치를 위해 ‘출처를 알 수 없는 앱 설치’ 권한을 허용해 주세요", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            return
        }

        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val statusTv = TextView(activity).apply {
            text = "APK 파일 다운로드 준비 중…"
            textSize = 14f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        box.addView(statusTv)
        box.addView(progressBar)

        var cancelled = false
        val dlg = AlertDialog.Builder(activity)
            .setTitle("업데이트 다운로드 중 (v${info.versionName})")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소") { d, _ ->
                cancelled = true
                d.dismiss()
            }
            .create()
        dlg.show()

        scope.launch {
            val outFile = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val target = File(dir, "WalkerYu-${info.versionName}.apk")
                    val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "WalkerYu/${BuildConfig.VERSION_NAME}")
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching null
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            FileOutputStream(target).use { output ->
                                val buf = ByteArray(16 * 1024)
                                var downloaded = 0L
                                while (!cancelled) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    downloaded += r
                                    if (total > 0) {
                                        val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                        withContext(Dispatchers.Main) {
                                            progressBar.progress = pct
                                            statusTv.text = "다운로드 중… $pct% (${downloaded / 1024}KB / ${total / 1024}KB)"
                                        }
                                    }
                                }
                            }
                        }
                        if (cancelled) {
                            target.delete()
                            return@runCatching null
                        }
                        // SHA-256 해시 무결성 검증
                        if (info.sha256.length == 64) {
                            val actualSha = sha256(target)
                            if (!actualSha.equals(info.sha256, ignoreCase = true)) {
                                target.delete()
                                throw IllegalStateException("SHA-256 해시 불일치")
                            }
                        }
                        target
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }

            if (dlg.isShowing) dlg.dismiss()
            if (cancelled || activity.isFinishing || activity.isDestroyed) return@launch

            if (outFile == null || !outFile.exists()) {
                Toast.makeText(activity, "업데이트 파일 다운로드 또는 무결성 검증에 실패했습니다", Toast.LENGTH_LONG).show()
                return@launch
            }

            launchInstaller(activity, outFile)
        }
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "설치 화면을 열지 못했습니다: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanOldApks(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }
}
