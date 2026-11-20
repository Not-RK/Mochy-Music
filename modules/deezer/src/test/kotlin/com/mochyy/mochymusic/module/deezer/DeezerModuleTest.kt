package com.mochyy.mochymusic.module.deezer

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class DeezerModuleTest {

    private class FakeHost(val routes: (HttpRequest) -> HttpResponse) : ModuleHost {
        val requests = ArrayList<HttpRequest>()
        override val storage = object : ModuleStorage {
            val map = HashMap<String, String>()
            override fun get(key: String) = map[key]
            override fun set(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        override fun http(request: HttpRequest): HttpResponse {
            requests += request
            return routes(request)
        }
        override fun log(message: String) = Unit
        override val folder: File = createTempDirectory("deezer").toFile()
    }

    @Test
    fun testBlowfishKeyGeneration() {
        val trackId = "3135556"
        val key = DeezerCipher.createBlowfishKey(trackId)
        assertEquals(16, key.length)
        // verify deterministic output
        val key2 = DeezerCipher.createBlowfishKey(trackId)
        assertEquals(key, key2)
    }

    @Test
    fun testBlowfishDecryption() {
        val trackId = "123456"
        val key = DeezerCipher.createBlowfishKey(trackId)
        val dummy = ByteArray(2048) { (it % 256).toByte() }
        val decrypted = DeezerCipher.decryptBlowfish(dummy, key)
        assertEquals(2048, decrypted.size)
    }

    @Test
    fun testStripeDecryptingInputStream() {
        val trackId = "3135556"
        val key = DeezerCipher.createBlowfishKey(trackId)
        // 3 blocks of 2048 = 6144 bytes
        val totalBytes = 6144
        val data = ByteArray(totalBytes) { 0x42 }
        val input = ByteArrayInputStream(data)
        val stream = DeezerCipher.createDecryptedStream(input, key, 0, (totalBytes - 1).toLong())
        val output = stream.readBytes()
        assertEquals(totalBytes, output.size)
    }

    @Test
    fun testModuleResolveSuccess() = runBlocking {
        val fakeSearchJson = """
            {
              "data": [
                {
                  "id": "3135556",
                  "title": "Harder, Better, Faster, Stronger",
                  "artist": { "name": "Daft Punk" },
                  "album": { "title": "Discovery" },
                  "duration": 224,
                  "isrc": "GBDUW0100059"
                }
              ]
            }
        """.trimIndent()

        val fakeMediaJson = """
            {
              "data": [
                {
                  "media": [
                    {
                      "format": "FLAC",
                      "sources": [
                        { "url": "https://f-cdnt-stream.dzcdn.net/media/sample.flac" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val host = FakeHost { req ->
            when {
                req.url.contains("api.deezer.com/search") -> HttpResponse(200, fakeSearchJson.toByteArray())
                req.url.contains("lufts-dzmedia.fly.dev") -> HttpResponse(200, fakeMediaJson.toByteArray())
                else -> HttpResponse(404, ByteArray(0))
            }
        }

        val module = DeezerModule().also { it.attach(host) }
        val query = TrackQuery(
            title = "Harder, Better, Faster, Stronger",
            artists = listOf("Daft Punk"),
            durationMs = 224_000L
        )

        val result = module.resolve(query, emptyMap())
        assertTrue(result is ResolveResult.Found)
        val found = result as ResolveResult.Found
        assertEquals("Deezer", found.stream.sourceLabel)
        assertEquals("FLAC", found.stream.codec)
        assertTrue(found.stream.url.startsWith("http://127.0.0.1:"))
        assertTrue(found.stream.url.contains("3135556"))
    }

    @Test
    fun testAccountStateWithoutArl() {
        val host = FakeHost { HttpResponse(404, ByteArray(0)) }
        val module = DeezerModule().also { it.attach(host) }
        val state = module.accountState()
        assertTrue(state is AccountState.SignedIn)
        assertEquals("Free / Public (FLAC supported)", (state as AccountState.SignedIn).label)
    }
}
