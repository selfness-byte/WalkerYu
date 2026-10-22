package com.mj.transitmap.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

data class HttpResponse(val code: Int, val body: String)

/**
 * 외부 라이브러리 없이 HttpURLConnection 으로 호출.
 * - GZIP 압축 응답 지원으로 네트워크 트래픽 및 파싱 메모리 절감
 * - 비정상 대형 응답으로 인한 OOM 방지를 위해 최대 512KB 읽기 상한 적용
 * - 예외 메시지에 URL(=API 키 포함)을 절대 넣지 않는다.
 */
object Http {
    private const val UA = "WalkerYu/1.0 (Android)"
    private const val MAX_BODY_CHARS = 512 * 1024

    suspend fun get(url: String, timeoutMs: Int = 8000): HttpResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
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
                conn.useCaches = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept-Encoding", "gzip")
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
        val rawStream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (rawStream == null) return HttpResponse(code, "")
        val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
            GZIPInputStream(rawStream)
        } else {
            rawStream
        }
        val body = InputStreamReader(stream, Charsets.UTF_8).use { reader ->
            val sb = StringBuilder(4096)
            val buf = CharArray(4096)
            var total = 0
            while (total < MAX_BODY_CHARS) {
                val n = reader.read(buf, 0, minOf(buf.size, MAX_BODY_CHARS - total))
                if (n < 0) break
                sb.append(buf, 0, n)
                total += n
            }
            sb.toString()
        }
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
