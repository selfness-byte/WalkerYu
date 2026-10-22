package com.mj.transitmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

data class HttpResponse(val code: Int, val body: String)

/**
 * 외부 라이브러리 없이 HttpURLConnection 으로 호출.
 * 예외 메시지에 URL(=API 키 포함)을 절대 넣지 않는다.
 */
object Http {
    private const val UA = "WalkerYu/1.0 (Android)"

    suspend fun get(url: String, timeoutMs: Int = 8000): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun postForm(url: String, form: Map<String, String>, timeoutMs: Int = 15000): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val payload = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                read(conn)
            } finally {
                conn.disconnect()
            }
        }

    private fun read(conn: HttpURLConnection): HttpResponse {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return HttpResponse(code, body)
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 사용자에게 보여줄 통신 오류 문구 (키·URL 미포함) */
    fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "통신 오류: 인터넷 연결 없음"
        is SocketTimeoutException -> "통신 오류: 응답 시간 초과"
        is IOException -> "통신 오류: ${e.javaClass.simpleName}"
        else -> "처리 오류: ${e.javaClass.simpleName}"
    }
}
