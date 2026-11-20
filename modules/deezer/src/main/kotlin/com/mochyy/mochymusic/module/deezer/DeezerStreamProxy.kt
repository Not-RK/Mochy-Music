package com.mochyy.mochymusic.module.deezer

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DeezerStreamProxy {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val running = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool { r ->
        Thread(r, "DeezerProxyWorker").apply { isDaemon = true }
    }

    @Volatile
    var customProxy: String? = null

    @Synchronized
    fun ensureStarted(): Int {
        if (running.get() && serverSocket != null && !serverSocket!!.isClosed) {
            return port
        }

        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        val acceptThread = Thread({
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    threadPool.submit { handleClient(client) }
                } catch (e: Exception) {
                    if (!running.get() || socket.isClosed) break
                }
            }
        }, "DeezerProxyAcceptor").apply { isDaemon = true }

        acceptThread.start()
        return port
    }

    fun getStreamUrl(trackId: String, cdnUrl: String, isFlac: Boolean): String {
        val currentPort = ensureStarted()
        val encUrl = URLEncoder.encode(cdnUrl, "UTF-8")
        return "http://127.0.0.1:$currentPort/stream?id=$trackId&flac=$isFlac&url=$encUrl"
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        port = 0
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val firstLine = reader.readLine() ?: return
                val parts = firstLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val pathAndQuery = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("range:")) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                if (!pathAndQuery.startsWith("/stream?")) {
                    sendError(s.getOutputStream(), 404, "Not Found")
                    return
                }

                val query = pathAndQuery.substringAfter("?")
                val params = parseQueryParams(query)
                val trackId = params["id"] ?: return
                val cdnUrl = params["url"] ?: return
                val isFlac = params["flac"]?.toBoolean() ?: true

                serveStream(
                    clientOut = s.getOutputStream(),
                    method = method,
                    trackId = trackId,
                    cdnUrl = cdnUrl,
                    isFlac = isFlac,
                    rangeHeader = rangeHeader
                )
            }
        } catch (_: Exception) {
            // client disconnected or aborted playback, ignore
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun openCdnConnection(cdnUrl: String): HttpURLConnection {
        val url = URI.create(cdnUrl).toURL()
        val proxyStr = customProxy
        val conn = if (!proxyStr.isNullOrBlank() && proxyStr != "none") {
            val host = proxyStr.substringBefore(":")
            val portNum = proxyStr.substringAfter(":", "3128").toIntOrNull() ?: 3128
            url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, portNum))) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }

        if (conn is javax.net.ssl.HttpsURLConnection) {
            conn.sslSocketFactory = DeezerApi.trustAllSocketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }

        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        return conn
    }

    private fun serveStream(
        clientOut: OutputStream,
        method: String,
        trackId: String,
        cdnUrl: String,
        isFlac: Boolean,
        rangeHeader: String?
    ) {
        val key = DeezerCipher.createBlowfishKey(trackId)
        val contentType = if (isFlac) "audio/flac" else "audio/mpeg"

        // Probe content length first if needed
        var totalLength = -1L
        try {
            val headConn = openCdnConnection(cdnUrl).apply { requestMethod = "HEAD" }
            headConn.connect()
            totalLength = headConn.contentLengthLong
            headConn.disconnect()
        } catch (_: Exception) {}

        var startByte = 0L
        var endByte = -1L

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val dash = rangeVal.indexOf('-')
            if (dash >= 0) {
                startByte = rangeVal.substring(0, dash).trim().toLongOrNull() ?: 0L
                val endStr = rangeVal.substring(dash + 1).trim()
                if (endStr.isNotEmpty()) {
                    endByte = endStr.toLongOrNull() ?: -1L
                }
            }
        }

        if (totalLength > 0 && endByte < 0) {
            endByte = totalLength - 1
        }

        val alignedStart = startByte - (startByte % DeezerCipher.BLOCK_SIZE)
        val fetchEnd = if (endByte < 0) -1L else {
            val lastBlock = endByte - (endByte % DeezerCipher.BLOCK_SIZE)
            lastBlock + DeezerCipher.BLOCK_SIZE - 1
        }

        val cdnConn = openCdnConnection(cdnUrl)
        if (alignedStart > 0 || fetchEnd > 0) {
            val cdnRange = if (fetchEnd < 0) "bytes=$alignedStart-" else "bytes=$alignedStart-$fetchEnd"
            cdnConn.setRequestProperty("Range", cdnRange)
        }

        cdnConn.connect()
        val cdnCode = cdnConn.responseCode
        val cdnInput: InputStream = if (cdnCode in 200..299) cdnConn.inputStream else cdnConn.errorStream

        if (totalLength <= 0) {
            val cr = cdnConn.getHeaderField("Content-Range")
            if (cr != null && cr.contains("/")) {
                totalLength = cr.substringAfter("/").trim().toLongOrNull() ?: -1L
            }
            if (totalLength <= 0) {
                totalLength = cdnConn.contentLengthLong
            }
        }

        val contentLen = if (endByte >= startByte) {
            endByte - startByte + 1
        } else if (totalLength > 0) {
            totalLength - startByte
        } else {
            -1L
        }

        val bufferedOut = BufferedOutputStream(clientOut, 16 * 1024)
        val isPartial = rangeHeader != null

        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
        bufferedOut.write(statusLine.toByteArray(Charsets.US_ASCII))
        bufferedOut.write("Content-Type: $contentType\r\n".toByteArray(Charsets.US_ASCII))
        bufferedOut.write("Accept-Ranges: bytes\r\n".toByteArray(Charsets.US_ASCII))
        if (contentLen > 0) {
            bufferedOut.write("Content-Length: $contentLen\r\n".toByteArray(Charsets.US_ASCII))
        }
        if (isPartial && totalLength > 0) {
            val effectiveEnd = if (endByte >= startByte) endByte else (totalLength - 1)
            bufferedOut.write("Content-Range: bytes $startByte-$effectiveEnd/$totalLength\r\n".toByteArray(Charsets.US_ASCII))
        }
        bufferedOut.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        bufferedOut.flush()

        if (method.equals("HEAD", ignoreCase = true)) {
            cdnConn.disconnect()
            return
        }

        val decryptedStream = DeezerCipher.createDecryptedStream(
            source = cdnInput,
            blowfishKey = key,
            startByte = startByte,
            endByte = endByte,
            onClose = { cdnConn.disconnect() }
        )

        decryptedStream.use { input ->
            val buf = ByteArray(16 * 1024)
            var bytesLeft = if (contentLen > 0) contentLen else Long.MAX_VALUE
            while (bytesLeft > 0) {
                val toRead = minOf(buf.size.toLong(), bytesLeft).toInt()
                val read = input.read(buf, 0, toRead)
                if (read < 0) break
                bufferedOut.write(buf, 0, read)
                bytesLeft -= read
            }
            bufferedOut.flush()
        }
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
