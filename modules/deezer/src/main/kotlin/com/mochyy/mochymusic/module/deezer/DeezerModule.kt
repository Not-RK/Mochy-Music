package com.mochyy.mochymusic.module.deezer

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.LyricsModule
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.MetadataModule
import com.mochyy.mochymusic.module.MochyModule
import com.mochyy.mochymusic.module.ModuleFeature
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackMetadata
import com.mochyy.mochymusic.module.TrackQuery
import java.util.concurrent.ConcurrentHashMap

class DeezerModule : SourceModule, LyricsModule, MetadataModule, ModuleFields {

    companion object {
        const val Id = "deezer"
        private const val FieldArl = "arl"
        private const val FieldQuality = "quality"
        private const val FieldProxy = "proxy"
    }

    override val info = ModuleInfo(
        id = Id,
        name = "Deezer",
        version = "1.0",
        description = "Lossless FLAC and high bitrate MP3 streaming from Deezer with proxy & account sync",
        sourceUrl = "https://github.com/LuftVerbot/echo-deezer-extension",
        author = "Antigravity & LuftVerbot"
    )

    override val adds: Set<ModuleFeature> = setOf(
        ModuleFeature.AudioSource,
        ModuleFeature.Lyrics,
        ModuleFeature.Metadata
    )

    private lateinit var host: ModuleHost

    @Volatile
    private var arl: String = ""

    @Volatile
    private var quality: String = "flac"

    @Volatile
    private var proxy: String = ""

    private val matches = ConcurrentHashMap<String, CatalogTrack>()

    override fun attach(host: ModuleHost) {
        this.host = host
        arl = host.storage.get(FieldArl).orEmpty()
        quality = host.storage.get(FieldQuality)?.ifBlank { "flac" } ?: "flac"
        proxy = host.storage.get(FieldProxy).orEmpty()
        DeezerStreamProxy.customProxy = proxy.ifBlank { null }
        DeezerStreamProxy.ensureStarted()
    }

    override val fields: List<ModuleField> = listOf(
        ModuleField(
            id = FieldArl,
            label = "Deezer ARL Cookie",
            kind = FieldKind.Text,
            hint = "Paste your 192-char ARL cookie",
            description = "Deezer ARL cookie for account favorites and higher bitrate access. Leave blank for public streaming."
        ),
        ModuleField(
            id = FieldQuality,
            label = "Audio Quality",
            kind = FieldKind.Select,
            options = listOf(
                StreamOption("flac", "Lossless FLAC", "16-bit / 44.1kHz FLAC"),
                StreamOption("320", "MP3 320kbps", "High quality MP3"),
                StreamOption("128", "MP3 128kbps", "Standard MP3")
            ),
            default = "flac",
            description = "Target audio streaming quality"
        ),
        ModuleField(
            id = FieldProxy,
            label = "Deezer Proxy Host:Port",
            kind = FieldKind.Text,
            hint = "e.g. uk1.proxy.murglar.app:3128",
            default = "",
            description = "Custom HTTP proxy for Deezer API and streaming if region restricted"
        )
    )

    override fun onFields(values: Map<String, String>) {
        values[FieldArl]?.let {
            arl = it.trim()
            host.storage.set(FieldArl, arl)
        }
        values[FieldQuality]?.let {
            quality = it.trim()
            host.storage.set(FieldQuality, quality)
        }
        values[FieldProxy]?.let {
            proxy = it.trim()
            host.storage.set(FieldProxy, proxy)
            DeezerStreamProxy.customProxy = proxy.ifBlank { null }
        }
    }

    override fun accountState(): AccountState {
        if (arl.isBlank()) {
            return AccountState.SignedIn("Free / Public (FLAC supported)")
        }
        val savedName = host.storage.get("user_name")
        return if (!savedName.isNullOrBlank()) {
            AccountState.SignedIn("Deezer · $savedName")
        } else {
            AccountState.SignedIn("Deezer (ARL configured)")
        }
    }

    override suspend fun verifyAccount(): AccountState {
        if (arl.isBlank()) {
            return AccountState.SignedIn("Free / Public (FLAC supported)")
        }
        val userData = DeezerApi.getUserData(arl, host)
        return if (userData != null) {
            host.storage.set("user_name", userData.name)
            AccountState.SignedIn("Deezer · ${userData.name}")
        } else {
            AccountState.Expired("Deezer ARL cookie expired or invalid")
        }
    }

    override fun signOut() {
        arl = ""
        host.storage.set(FieldArl, null)
        host.storage.set("user_name", null)
        matches.clear()
    }

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(
        track: TrackQuery,
        picks: Map<String, String>,
        report: (DiagnosticStep) -> Unit
    ): ResolveResult {
        val queryStr = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
        report(DiagnosticStep("Search Deezer", null, "Searching for: $queryStr"))

        val candidates = DeezerApi.search(queryStr, host)
        if (candidates.isEmpty()) {
            report(DiagnosticStep("Search Deezer", false, "No matching tracks found on Deezer"))
            return ResolveResult.NotFound("Not on Deezer")
        }

        report(DiagnosticStep("Search Deezer", true, "Found ${candidates.size} candidates"))

        val ranked = TrackMatcher.ranked(track, candidates)
        val best = ranked.firstOrNull()
        if (best == null) {
            report(DiagnosticStep("Match track", false, "No candidate met similarity threshold"))
            return ResolveResult.NotFound("No close match on Deezer")
        }

        val chosenTrack = best.track
        matches[cacheKey(track)] = chosenTrack
        report(DiagnosticStep("Match track", true, "Matched: ${chosenTrack.title} by ${chosenTrack.artist} (score: ${(best.score * 100).toInt()}%)"))

        val targetQuality = picks[FieldQuality] ?: quality
        report(DiagnosticStep("Resolve audio stream", null, "Fetching stream for quality: $targetQuality"))

        val media = DeezerApi.getMediaUrl(chosenTrack.id, targetQuality, host, arl.ifBlank { null })
        if (media == null) {
            report(DiagnosticStep("Resolve audio stream", false, "Stream URL resolution failed"))
            return ResolveResult.Unavailable("Stream unavailable on Deezer")
        }

        report(DiagnosticStep("Resolve audio stream", true, "Retrieved stream (${if (media.isFlac) "FLAC" else "MP3 ${media.bitrate}kbps"})"))

        val localStreamUrl = DeezerStreamProxy.getStreamUrl(
            trackId = chosenTrack.id,
            cdnUrl = media.url,
            isFlac = media.isFlac
        )

        val stream = SourceStream(
            url = localStreamUrl,
            format = StreamFormat.Progressive,
            sourceLabel = "Deezer",
            qualityLabel = if (media.isFlac) "FLAC · lossless" else "MP3 · ${media.bitrate}kbps",
            matched = MatchedTrack(
                id = chosenTrack.id,
                title = chosenTrack.title,
                artist = chosenTrack.artist,
                album = chosenTrack.album,
                durationMs = chosenTrack.durationMs,
                confidence = best.score
            ),
            codec = if (media.isFlac) "FLAC" else "MP3",
            sampleRateHz = if (media.isFlac) 44100 else null
        )

        return ResolveResult.Found(stream)
    }

    override suspend fun lyrics(track: TrackQuery): String? {
        val cached = matches[cacheKey(track)]
        val trackId = if (cached != null) {
            cached.id
        } else {
            val queryStr = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
            val candidates = DeezerApi.search(queryStr, host)
            TrackMatcher.best(track, candidates)?.track?.id
        } ?: return null

        return DeezerApi.getLyrics(trackId, host)
    }

    override suspend fun metadata(track: TrackQuery): TrackMetadata? {
        val cached = matches[cacheKey(track)]
        val trackId = if (cached != null) {
            cached.id
        } else {
            val queryStr = "${track.title} ${track.artists.firstOrNull().orEmpty()}".trim()
            val candidates = DeezerApi.search(queryStr, host)
            TrackMatcher.best(track, candidates)?.track?.id
        } ?: return null

        return DeezerApi.getMetadata(trackId, host)
    }

    private fun cacheKey(track: TrackQuery): String =
        "${track.title}|${track.artists.joinToString(",")}"
}
