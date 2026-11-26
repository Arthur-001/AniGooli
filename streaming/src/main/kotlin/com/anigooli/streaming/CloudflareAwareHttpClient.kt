package com.anigooli.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val CONNECT_TIMEOUT_SEC = 15L
private const val READ_TIMEOUT_SEC = 20L
private const val HTTP_OK = 200
private const val HTTP_FORBIDDEN = 403
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val HTTP_OK_MAX = 299
private const val CF_CHALLENGE_TITLE = "just a moment"
private const val WEBVIEW_TIMEOUT_MS = 12_000L
private const val WEBVIEW_POLL_MS = 500L
private const val MIN_POLL_ATTEMPTS_BEFORE_DOM = 3

/** Result of an HTTP GET fetch, potentially resolved through Cloudflare bypass. */
data class HttpFetchResult(
    /** HTTP status code (200 when fetched cleanly via WebView fallback). */
    val statusCode: Int,
    /** Response body string. */
    val body: String,
)

/**
 * Lightweight OkHttp client with per-host cookie jar and transparent headless WebView fallback
 * when a provider URL presents a Cloudflare challenge page.
 */
@Singleton
class CloudflareAwareHttpClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val existing = cookieStore.getOrPut(host) { mutableListOf() }
            synchronized(existing) {
                cookies.forEach { incoming ->
                    existing.removeAll { it.name == incoming.name }
                    existing.add(incoming)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val list = cookieStore[url.host] ?: return emptyList()
            synchronized(list) {
                list.removeAll { it.expiresAt < now }
                return list.toList()
            }
        }
    }

    /** Shared [OkHttpClient] configured for streaming & extractor requests. */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Performs an HTTP GET request, automatically falling back to an off-screen WebView if Cloudflare blocks it. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        allowCloudflareBypass: Boolean = true,
    ): HttpFetchResult = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        val code = response.code
        val body = response.body?.string().orEmpty()
        response.close()

        val isCloudflareBlock = (code == HTTP_FORBIDDEN || code == HTTP_SERVICE_UNAVAILABLE) ||
            body.contains(CF_CHALLENGE_TITLE, ignoreCase = true)

        if (isCloudflareBlock && allowCloudflareBypass) {
            val userAgent = headers["User-Agent"] ?: ExtractorConfig.DEFAULT_USER_AGENT
            val resolvedHtml = fetchViaHiddenWebView(url, userAgent)
            if (!resolvedHtml.isNullOrBlank()) {
                return@withContext HttpFetchResult(statusCode = HTTP_OK, body = resolvedHtml)
            }
        }
        if (code !in HTTP_OK..HTTP_OK_MAX && body.isBlank()) {
            throw IOException("HTTP $code from $url")
        }
        HttpFetchResult(statusCode = code, body = body)
    }

    /** Performs an HTTP form POST request and returns the response status and body. */
    suspend fun postForm(
        url: String,
        formParams: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): HttpFetchResult = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
        formParams.forEach { (k, v) -> formBuilder.add(k, v) }
        val requestBuilder = Request.Builder().url(url).post(formBuilder.build())
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        val code = response.code
        val body = response.body?.string().orEmpty()
        response.close()
        if (code !in HTTP_OK..HTTP_OK_MAX && body.isBlank()) {
            throw IOException("HTTP $code from $url")
        }
        HttpFetchResult(statusCode = code, body = body)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchViaHiddenWebView(targetUrl: String, userAgent: String): String? {
        return withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                val webView = WebView(context)
                try {
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.userAgentString = userAgent
                    var pageFinished = false
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageFinished = true
                        }
                    }
                    webView.loadUrl(targetUrl)

                    var attempts = 0
                    val maxAttempts = (WEBVIEW_TIMEOUT_MS / WEBVIEW_POLL_MS).toInt()
                    var capturedHtml: String? = null
                    while (attempts < maxAttempts) {
                        delay(WEBVIEW_POLL_MS)
                        attempts++
                        if (!pageFinished && attempts < MIN_POLL_ATTEMPTS_BEFORE_DOM) continue
                        val html = evaluateDomHtml(webView)
                        if (html != null && !html.contains(CF_CHALLENGE_TITLE, ignoreCase = true)) {
                            capturedHtml = html
                            syncWebViewCookies(targetUrl)
                            break
                        }
                    }
                    capturedHtml
                } finally {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
    }

    private suspend fun evaluateDomHtml(webView: WebView): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                val unescaped = raw
                    ?.takeIf { it != "null" && it.length >= 2 }
                    ?.let { decodeJsJsonString(it) }
                if (cont.isActive) cont.resume(unescaped)
            }
        }

    private fun decodeJsJsonString(quoted: String): String {
        val inner = if (quoted.startsWith("\"") && quoted.endsWith("\"")) {
            quoted.substring(1, quoted.length - 1)
        } else {
            quoted
        }
        return inner
            .replace("\\u003C", "<")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }

    private fun syncWebViewCookies(targetUrl: String) {
        val rawCookieHeader = CookieManager.getInstance().getCookie(targetUrl) ?: return
        val httpUrl = runCatching { okhttp3.HttpUrl.Builder().scheme("https").host(java.net.URI(targetUrl).host).build() }
            .getOrNull() ?: return
        val parsed = rawCookieHeader.split(";").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            Cookie.Builder().domain(httpUrl.host).name(name).value(value).build()
        }
        cookieJar.saveFromResponse(httpUrl, parsed)
    }
}
