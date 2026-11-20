package com.mochyy.mochymusic.module.deezer

import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.TrackMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object DeezerApi {

    private val json = Json { ignoreUnknownKeys = true }

    val trustAllSocketFactory: SSLSocketFactory by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }

    /**
     * Executes an HTTP request, routing through the configured proxy (e.g. uk1.proxy.murglar.app:3128)
     * when enabled to bypass regional geo-blocking, with fallback to direct host.http.
     */
    fun executeHttp(
        req: HttpRequest,
        host: ModuleHost,
        proxyStr: String? = DeezerStreamProxy.customProxy
    ): HttpResponse {
        if (proxyStr.isNullOrBlank() || proxyStr == "none") {
            return host.http(req)
        }
        return runCatching {
            val url = URI.create(req.url).toURL()
            val hostName = proxyStr.substringBefore(":")
            val port = proxyStr.substringAfter(":", "3128").toIntOrNull() ?: 3128
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(hostName, port))
            val conn = url.openConnection(proxy) as HttpURLConnection

            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = trustAllSocketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }

            conn.requestMethod = req.method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true

            // Set headers
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (req.headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            // Write body if present
            val reqBody = req.body
            if (reqBody != null && reqBody.isNotEmpty() && req.method != "GET" && req.method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(reqBody) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val bytes = stream.use { it.readBytes() }
            val headerMap = HashMap<String, String>()
            conn.headerFields.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) {
                    headerMap[k.lowercase()] = v.first()
                }
            }
            HttpResponse(code, bytes, headerMap)
        }.getOrElse {
            // Fallback to host.http if proxy has temporary connection glitch
            host.http(req)
        }
    }

    data class MediaResult(
        val url: String,
        val isFlac: Boolean,
        val bitrate: Int
    )

    data class UserData(
        val id: String,
        val name: String,
        val licenseToken: String?
    )

    /** searches deezer catalog for the query string */
    fun search(query: String, host: ModuleHost): List<CatalogTrack> {
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.deezer.com/search?q=$enc&limit=15"
        val resp = executeHttp(HttpRequest(url = url), host)
        if (!resp.ok) return emptyList()

        return runCatching {
            val root = json.parseToJsonElement(resp.text).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { item ->
                val obj = item.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val artistObj = obj["artist"]?.jsonObject
                val artist = artistObj?.get("name")?.jsonPrimitive?.content ?: "Unknown"
                val albumObj = obj["album"]?.jsonObject
                val album = albumObj?.get("title")?.jsonPrimitive?.content
                val durationSec = obj["duration"]?.jsonPrimitive?.longOrNull
                val durationMs = durationSec?.let { it * 1000L }
                val isrc = obj["isrc"]?.jsonPrimitive?.content

                CatalogTrack(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    isrc = isrc
                )
            }
        }.getOrDefault(emptyList())
    }

    /** gets media stream url using lufts-dzmedia or fallback to media.deezer.com */
    fun getMediaUrl(
        trackId: String,
        quality: String,
        host: ModuleHost,
        arl: String? = null
    ): MediaResult? {
        val formats = when (quality.lowercase()) {
            "flac" -> listOf("FLAC", "MP3_320", "MP3_128")
            "320" -> listOf("MP3_320", "MP3_128")
            else -> listOf("MP3_128")
        }

        // Try lufts-dzmedia endpoint (supports free public FLAC and 320)
        runCatching {
            val bodyObj = buildJsonObject {
                put("formats", buildJsonArray { formats.forEach { add(it) } })
                put("ids", buildJsonArray { add(trackId.toLong()) })
            }
            val req = HttpRequest(
                url = "https://lufts-dzmedia.fly.dev/get_url",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = bodyObj.toString().toByteArray(Charsets.UTF_8)
            )
            // Try direct first (dzmedia is on Fly.io VPS), fallback to proxy
            val resp = runCatching { host.http(req) }.getOrNull()?.takeIf { it.ok } ?: executeHttp(req, host)
            if (resp.ok) {
                val root = json.parseToJsonElement(resp.text).jsonObject
                val data = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                val mediaList = data?.get("media")?.jsonArray
                if (mediaList != null) {
                    for (m in mediaList) {
                        val mObj = m.jsonObject
                        val format = mObj["format"]?.jsonPrimitive?.content ?: ""
                        val sources = mObj["sources"]?.jsonArray
                        val sourceUrl = sources?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                        if (!sourceUrl.isNullOrBlank()) {
                            val isFlac = format.contains("FLAC", ignoreCase = true)
                            val bitrate = if (isFlac) 1411 else if (format.contains("320")) 320 else 128
                            return MediaResult(url = sourceUrl, isFlac = isFlac, bitrate = bitrate)
                        }
                    }
                }
            }
        }

        // If ARL is provided, attempt media.deezer.com/v1/get_url
        if (!arl.isNullOrBlank()) {
            runCatching {
                val userData = getUserData(arl, host)
                val licenseToken = userData?.licenseToken ?: ""
                val trackToken = getTrackToken(trackId, arl, host)
                if (trackToken != null) {
                    val bodyObj = buildJsonObject {
                        put("license_token", licenseToken)
                        put("media", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "FULL")
                                put("formats", buildJsonArray {
                                    add(buildJsonObject {
                                        put("cipher", "BF_CBC_STRIPE")
                                        put("format", if (quality == "flac") "FLAC" else if (quality == "320") "MP3_320" else "MP3_128")
                                    })
                                })
                            })
                        })
                        put("track_tokens", buildJsonArray { add(trackToken) })
                    }
                    val req = HttpRequest(
                        url = "https://media.deezer.com/v1/get_url",
                        method = "POST",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Cookie" to "arl=$arl",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        ),
                        body = bodyObj.toString().toByteArray(Charsets.UTF_8)
                    )
                    val resp = executeHttp(req, host)
                    if (resp.ok) {
                        val root = json.parseToJsonElement(resp.text).jsonObject
                        val data = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                        val media = data?.get("media")?.jsonArray?.firstOrNull()?.jsonObject
                        val source = media?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject
                        val streamUrl = source?.get("url")?.jsonPrimitive?.content
                        if (!streamUrl.isNullOrBlank()) {
                            val isFlac = quality.equals("flac", ignoreCase = true)
                            return MediaResult(url = streamUrl, isFlac = isFlac, bitrate = if (isFlac) 1411 else 320)
                        }
                    }
                }
            }
        }

        return null
    }

    private fun getTrackToken(trackId: String, arl: String, host: ModuleHost): String? = runCatching {
        val url = "https://www.deezer.com/ajax/gw-light.php?method=song.getData&input=3&api_version=1.0&api_token=null"
        val body = """{"sng_id":"$trackId"}""".toByteArray(Charsets.UTF_8)
        val req = HttpRequest(
            url = url,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Cookie" to "arl=$arl",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            ),
            body = body
        )
        val resp = executeHttp(req, host)
        if (resp.ok) {
            val root = json.parseToJsonElement(resp.text).jsonObject
            val results = root["results"]?.jsonObject
            results?.get("TRACK_TOKEN")?.jsonPrimitive?.content
        } else null
    }.getOrNull()

    /** retrieves user profile from deezer using arl cookie */
    fun getUserData(arl: String, host: ModuleHost): UserData? = runCatching {
        val url = "https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData&input=3&api_version=1.0&api_token=null"
        val req = HttpRequest(
            url = url,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Cookie" to "arl=$arl",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            ),
            body = "{}".toByteArray(Charsets.UTF_8)
        )
        val resp = executeHttp(req, host)
        if (!resp.ok) return null
        val root = json.parseToJsonElement(resp.text).jsonObject
        val user = root["results"]?.jsonObject?.get("USER")?.jsonObject ?: return null
        val id = user["USER_ID"]?.jsonPrimitive?.content ?: return null
        val name = user["BLOG_NAME"]?.jsonPrimitive?.content ?: "Deezer User"
        val licenseToken = user["OPTIONS"]?.jsonObject?.get("license_token")?.jsonPrimitive?.content
        UserData(id = id, name = name, licenseToken = licenseToken)
    }.getOrNull()

    /** gets synchronized lyrics in lrc format */
    fun getLyrics(trackId: String, host: ModuleHost): String? = runCatching {
        // Deezer GraphQL endpoint
        val authUrl = "https://auth.deezer.com/login/anonymous?jo=p&rto=c&i=c"
        val authResp = executeHttp(HttpRequest(url = authUrl), host)
        if (!authResp.ok) return null
        val authJson = json.parseToJsonElement(authResp.text).jsonObject
        val jwt = authJson["jwt"]?.jsonPrimitive?.content ?: return null

        val query = """query SynchronizedTrackLyrics(${'$'}trackId: String!) { track(trackId: ${'$'}trackId) { id lyrics { id text synchronizedLines { line milliseconds duration } } } }"""
        val queryBody = buildJsonObject {
            put("operationName", "SynchronizedTrackLyrics")
            put("query", query)
            put("variables", buildJsonObject {
                put("trackId", trackId)
            })
        }

        val pipeReq = HttpRequest(
            url = "https://pipe.deezer.com/api",
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $jwt"
            ),
            body = queryBody.toString().toByteArray(Charsets.UTF_8)
        )
        val pipeResp = executeHttp(pipeReq, host)
        if (!pipeResp.ok) return null

        val root = json.parseToJsonElement(pipeResp.text).jsonObject
        val track = root["data"]?.jsonObject?.get("track")?.jsonObject
        val lyricsObj = track?.get("lyrics")?.jsonObject ?: return null
        val syncLines = lyricsObj["synchronizedLines"]?.jsonArray

        if (syncLines != null && syncLines.isNotEmpty()) {
            buildString {
                for (item in syncLines) {
                    val lineObj = item.jsonObject
                    val line = lineObj["line"]?.jsonPrimitive?.content.orEmpty()
                    val ms = lineObj["milliseconds"]?.jsonPrimitive?.longOrNull ?: 0L
                    val totalSec = ms / 1000
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    val centis = (ms % 1000) / 10
                    append("[%02d:%02d.%02d]%s\n".format(min, sec, centis, line))
                }
            }.trim().takeIf { it.isNotEmpty() }
        } else {
            lyricsObj["text"]?.jsonPrimitive?.content
        }
    }.getOrNull()

    /** gets track metadata: release date, contributors, album, isrc, cover */
    fun getMetadata(trackId: String, host: ModuleHost): TrackMetadata? = runCatching {
        val url = "https://api.deezer.com/track/$trackId"
        val resp = executeHttp(HttpRequest(url = url), host)
        if (!resp.ok) return null

        val obj = json.parseToJsonElement(resp.text).jsonObject
        val albumObj = obj["album"]?.jsonObject
        val album = albumObj?.get("title")?.jsonPrimitive?.content
        val coverUrl = albumObj?.get("cover_xl")?.jsonPrimitive?.content
            ?: albumObj?.get("cover_big")?.jsonPrimitive?.content
        val releaseDate = obj["release_date"]?.jsonPrimitive?.content
        val isrc = obj["isrc"]?.jsonPrimitive?.content

        val contributors = obj["contributors"]?.jsonArray
        val writers = ArrayList<String>()
        val producers = ArrayList<String>()
        contributors?.forEach { c ->
            val cObj = c.jsonObject
            val name = cObj["name"]?.jsonPrimitive?.content ?: return@forEach
            val role = cObj["role"]?.jsonPrimitive?.content.orEmpty().lowercase()
            if (role.contains("author") || role.contains("composer") || role.contains("writer")) {
                writers += name
            } else if (role.contains("producer")) {
                producers += name
            }
        }

        TrackMetadata(
            album = album,
            releaseDate = releaseDate,
            writers = writers.distinct(),
            producers = producers.distinct(),
            coverUrl = coverUrl,
            isrc = isrc
        )
    }.getOrNull()
}
