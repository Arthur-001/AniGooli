package com.anigooli.player

import android.util.Log
import com.anigooli.downloads.HlsPlaylistParser
import com.anigooli.streaming.ExtractorConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalHeaderProxy"
private const val PROXY_BUFFER_SIZE = 32 * 1024
private const val HTTP_NOT_FOUND = 404
private const val HTTP_BAD_GATEWAY = 502

private data class ProxyStreamEntry(
    val targetUrl: String,
    val referer: String,
    val origin: String,
    val userAgent: String,
)

/**
 * Ephemeral `127.0.0.1` localhost HTTP proxy used solely when handing off an online stream
 * with custom `Referer`/`Origin` headers to external MPV (`is.xyz.mpv`).
 * Automatically rewrites HLS playlist URLs so child playlists and segments also receive headers.
 */
@Singleton
class LocalHeaderProxy @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val entries = ConcurrentHashMap<String, ProxyStreamEntry>()
    private val masterPlaylists = ConcurrentHashMap<String, String>()
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0

    /** Registers a target stream and returns a local `http://127.0.0.1:<port>/stream/<token>` URL. */
    @Synchronized
    fun registerStream(
        targetUrl: String,
        referer: String,
        origin: String,
        userAgent: String = ExtractorConfig.DEFAULT_USER_AGENT,
    ): String {
        ensureStarted()
        val token = UUID.randomUUID().toString()
        entries[token] = ProxyStreamEntry(targetUrl, referer, origin, userAgent)
        return "http://127.0.0.1:$port/stream/$token"
    }

    /**
     * Registers a split video + audio HLS stream pair and returns a virtual master `.m3u8` URL
     * so external players such as MPV automatically attach the audio track with required headers.
     */
    @Synchronized
    fun registerCombinedHlsStream(
        videoUrl: String,
        audioUrl: String,
        referer: String,
        origin: String,
        userAgent: String = ExtractorConfig.DEFAULT_USER_AGENT,
    ): String {
        ensureStarted()
        val videoProxyUrl = registerStream(videoUrl, referer, origin, userAgent)
        val audioProxyUrl = registerStream(audioUrl, referer, origin, userAgent)
        val masterToken = UUID.randomUUID().toString()
        val playlist = buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append(
                """#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Default",DEFAULT=YES,AUTOSELECT=YES,URI="$audioProxyUrl"""",
            )
            append("\n")
            append("""#EXT-X-STREAM-INF:BANDWIDTH=4000000,AUDIO="audio"""")
            append("\n")
            append(videoProxyUrl)
            append("\n")
        }
        masterPlaylists[masterToken] = playlist
        return "http://127.0.0.1:$port/master/$masterToken.m3u8"
    }

    private fun ensureStarted() {
        if (serverSocket != null && serverSocket?.isClosed == false) return
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        executor.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            runCatching {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val isHead = parts[0].equals("HEAD", ignoreCase = true)
                val pathAndQuery = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                val out = sock.getOutputStream()
                if (pathAndQuery.startsWith("/master/")) {
                    val token = pathAndQuery.removePrefix("/master/")
                        .substringBefore("?")
                        .removeSuffix(".m3u8")
                    val playlist = masterPlaylists[token]
                    if (playlist == null) {
                        writeSimpleResponse(out, HTTP_NOT_FOUND, "Not Found", isHead)
                    } else {
                        val bytes = playlist.toByteArray(Charsets.UTF_8)
                        val hdr = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: application/vnd.apple.mpegurl\r\n")
                            append("Content-Length: ${bytes.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        out.write(hdr.toByteArray(Charsets.US_ASCII))
                        if (!isHead) out.write(bytes)
                        out.flush()
                    }
                    return
                }

                val resolved = resolveTarget(pathAndQuery)
                if (resolved == null) {
                    writeSimpleResponse(out, HTTP_NOT_FOUND, "Not Found", isHead)
                    return
                }
                val (token, entry, upstreamUrl) = resolved
                forwardRequest(token, entry, upstreamUrl, rangeHeader, isHead, out)
            }.onFailure { err ->
                Log.d(TAG, "Proxy client closed: ${err.message}")
            }
        }
    }

    private fun resolveTarget(pathAndQuery: String): Triple<String, ProxyStreamEntry, String>? {
        return if (pathAndQuery.startsWith("/stream/")) {
            val token = pathAndQuery.removePrefix("/stream/").substringBefore("?")
            val entry = entries[token] ?: return null
            Triple(token, entry, entry.targetUrl)
        } else if (pathAndQuery.startsWith("/proxy")) {
            val query = pathAndQuery.substringAfter("?", "")
            val params = query.split("&").mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx <= 0) null else kv.substring(0, idx) to URLDecoder.decode(kv.substring(idx + 1), "UTF-8")
            }.toMap()
            val token = params["t"] ?: return null
            val targetUrl = params["u"] ?: return null
            val entry = entries[token] ?: return null
            Triple(token, entry, targetUrl)
        } else {
            null
        }
    }

    private fun forwardRequest(
        token: String,
        entry: ProxyStreamEntry,
        upstreamUrl: String,
        rangeHeader: String?,
        isHead: Boolean,
        out: OutputStream,
    ) {
        val builder = Request.Builder()
            .url(upstreamUrl)
            .header("User-Agent", entry.userAgent)
        if (entry.referer.isNotBlank()) builder.header("Referer", entry.referer)
        if (entry.origin.isNotBlank()) builder.header("Origin", entry.origin)
        if (!rangeHeader.isNullOrBlank()) builder.header("Range", rangeHeader)

        val resp = runCatching { okHttpClient.newCall(builder.build()).execute() }.getOrElse {
            writeSimpleResponse(out, HTTP_BAD_GATEWAY, "Upstream error", isHead)
            return
        }

        resp.use { r ->
            val contentType = r.header("Content-Type").orEmpty()
            val isM3u8 = upstreamUrl.substringBefore('?').endsWith(".m3u8", true) ||
                contentType.contains("mpegurl", ignoreCase = true)
            val body = r.body ?: run {
                writeSimpleResponse(out, r.code, "", isHead)
                return
            }

            if (isM3u8) {
                val rewritten = rewriteM3u8(body.string(), upstreamUrl, token)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val hdr = buildString {
                    append("HTTP/1.1 ${r.code} ${r.message.ifBlank { "OK" }}\r\n")
                    append("Content-Type: application/vnd.apple.mpegurl\r\n")
                    append("Content-Length: ${bytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (!isHead) out.write(bytes)
                out.flush()
            } else {
                val hdr = buildString {
                    append("HTTP/1.1 ${r.code} ${r.message.ifBlank { "OK" }}\r\n")
                    if (contentType.isNotBlank()) append("Content-Type: $contentType\r\n")
                    r.header("Content-Length")?.let { append("Content-Length: $it\r\n") }
                    r.header("Content-Range")?.let { append("Content-Range: $it\r\n") }
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (!isHead) {
                    val buf = ByteArray(PROXY_BUFFER_SIZE)
                    val input = body.byteStream()
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
                out.flush()
            }
        }
    }

    private fun rewriteM3u8(playlistText: String, baseUrl: String, token: String): String {
        val uriAttrRegex = Regex("""URI="([^"]+)"""")
        return playlistText.replace("\r", "").split("\n").joinToString("\n") { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> line
                line.startsWith("#") -> {
                    uriAttrRegex.replace(line) { m ->
                        val abs = HlsPlaylistParser.resolveUrl(m.groupValues[1], baseUrl)
                        val proxied = proxyUrlFor(abs, token)
                        """URI="$proxied""""
                    }
                }
                else -> {
                    val abs = HlsPlaylistParser.resolveUrl(line, baseUrl)
                    proxyUrlFor(abs, token)
                }
            }
        }
    }

    private fun proxyUrlFor(targetUrl: String, token: String): String {
        val encUrl = URLEncoder.encode(targetUrl, "UTF-8")
        return "http://127.0.0.1:$port/proxy?t=$token&u=$encUrl"
    }

    private fun writeSimpleResponse(
        out: OutputStream,
        code: Int,
        message: String,
        isHead: Boolean = false,
    ) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val hdr = "HTTP/1.1 $code $message\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!isHead) out.write(bytes)
        out.flush()
    }
}
