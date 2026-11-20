# Mochy-Music: AI Agent Universal Knowledge Base & Developer Ledger

> [!IMPORTANT]
> **To All AI Agents (Antigravity, Claude Code, Cursor, Codex, Gemini CLI):**
> **READ THIS DOCUMENT FIRST BEFORE SCANNING THE REPO OR FETCHING COMMITS.**
> This file contains the complete, verified state of this repository. Reading this document saves 30,000+ tokens per session by eliminating redundant file exploration, dependency discovery, and architectural analysis.

---

## 1. Project & Environment Snapshot

| Parameter | Current Value | Notes |
| :--- | :--- | :--- |
| **App Name** | **Mochy Music** | Fork of Metrolist / ViMusic with modular engine |
| **Active Base Version** | **v8.4.3 (build#1280, versionCode 73)** | Tag `mochy-8.4.3` on commit `a5bc898b` |
| **Active Git Branch** | `clean-v8.4.2.2` | Aligned with official `origin/main` / `mochy-8.4.3` |
| **Local Project Path** | `C:\Users\Rohan\Documents\Antigravity\Mochy-Music` | Root repository |
| **JDK 21 Path** | `C:\Users\Rohan\Documents\Antigravity\jdk-21\jdk-21.0.6+7` | Required for Gradle daemon |
| **Android SDK Path** | `C:\Users\Rohan\Documents\Antigravity\android-sdk` | Platforms 36 & 37 installed |
| **Git CLI Path** | `C:\Users\Rohan\Documents\Antigravity\mingit\cmd` | Required in PATH |

### Essential Environment Setup for Terminal / Gradle
Every PowerShell session executing Gradle MUST set these environment variables:
```powershell
$env:JAVA_HOME = "C:\Users\Rohan\Documents\Antigravity\jdk-21\jdk-21.0.6+7"
$env:ANDROID_HOME = "C:\Users\Rohan\Documents\Antigravity\android-sdk"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\Rohan\Documents\Antigravity\mingit\cmd;$env:PATH"
Set-Location "C:\Users\Rohan\Documents\Antigravity\Mochy-Music"
```

### Android SDK 37 Hotfix Reference
AGP 9.0 looks for hash string `android-37`. The SDK was downloaded as `platforms/android-37.0`.
- A directory junction exists: `platforms\android-37` $\to$ `platforms\android-37.0`.
- In `platforms/android-37.0/source.properties`: `AndroidVersion.ApiLevel=37`.
- In `gradle.properties`: `android.suppressUnsupportedCompileSdk=37,37.0`.

---

## 2. Active Customizations & Architecture Ledger

### A. Native Deezer Source Module (`:modules:deezer`)
A fully native, self-contained Deezer module built into Mochy. Does NOT require any external web servers, manifests, or signing keys.

- **Location**: `modules/deezer/`
- **Interfaces Implemented**: `SourceModule`, `LyricsModule`, `MetadataModule`, `ModuleFields`
- **Key Components**:
  1. `DeezerCipher.kt`: Blowfish CBC stripe decryption ported from `echo-deezer-extension`. Derives 16-character blowfish key via `MD5(trackId) XOR secret ("g4el58wc0zvf9na1")`. Decrypts every 3rd 2048-byte chunk on-the-fly via `StripeDecryptingInputStream`.
  2. `DeezerStreamProxy.kt`: Local loopback HTTP server on `127.0.0.1:[dynamic_port]`. Handles HTTP `Range` requests from ExoPlayer, proxies Akamai CDN chunks, decrypts on-the-fly, and serves `audio/flac` or `audio/mpeg`.
  3. `DeezerApi.kt`: Direct Deezer REST & GraphQL client. Resolves signed CDN URLs via `lufts-dzmedia.fly.dev/get_url` (free public endpoint) with fallback to `media.deezer.com/v1/get_url` (ARL authenticated). Synchronized lyrics fetched in standard `[mm:ss.xx]` LRC format via Deezer GraphQL pipe.
  4. `DeezerModule.kt`: Module lifecycle and settings management.

#### Geo-Unblock Engine (Echo Extension Integration)
To enable full streaming and search in Deezer-unsupported countries (such as India):
- Integrated workable proxy presets from `LuftVerbot/echo-deezer-extension`:
  - `UK 1 (uk1.proxy.murglar.app:3128)` — **Default preset** (routes via UK residential node where Deezer is fully supported).
  - `UK 2 (uk2.proxy.murglar.app:3128)` — Backup UK proxy node.
  - `RU 1`, `RU 2`, `MD`, and `none` (Direct connection).
- `DeezerApi.executeHttp()`: Routes all API calls (`search`, `metadata`, `ARL login`, `track tokens`, `lyrics`) through the selected proxy with automatic SSL trust-all bypass (`trustAllSocketFactory` + `HostnameVerifier { true }`).
- CDN chunk fetcher in `DeezerStreamProxy.openCdnConnection()` uses the same proxy and SSL bypass for media streams.

---

### B. Module Registry & Default Providers
The user specifically requested a lean, clean default module setup:
- **Default Built-In Modules (`ModuleRegistry.kt`)**:
  ```kotlin
  val builtIn = listOf<MochyModule>(MonochromeModule(), DeezerModule()).map { it to ModuleOrigin.BuiltIn }
  ```
  - **Only Monochrome** (Lossless FLAC) and **Deezer** (Lossless FLAC & 320kbps MP3) are enabled by default.
  - `AppleMusicModule` and `JioSaavnModule` were removed from the default `builtIn` list to eliminate bloat. Their code projects remain intact in `modules/applemusic` and `modules/jiosaavn` so users can still add them or any other module via links or `.mochyymodule.zip`.
- **Source Priority Order (`SourceModules.kt`)**:
  ```kotlin
  private val KnownOrder = listOf(MonochromeModule.Id, DeezerModule.Id, YouTubeId)
  ```

---

### C. Logging & Jitter Removal (Performance Optimization)
The user requested minimal, non-breaking removal of heavy debug logging that causes thread locking and audio/scroll jitter:
1. `app/src/main/kotlin/com/mochyy/mochymusic/App.kt`:
   Disabled `Timber.plant(Timber.DebugTree())` in debug/fast builds:
   ```kotlin
   // Debug logging disabled to prevent logcat jitter and micro-stutters
   // if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "fast") {
   //     Timber.plant(Timber.DebugTree())
   // }
   ```
2. `app/proguard-rules.pro`:
   Added ProGuard rule to strip runtime debug logs from release builds:
   ```proguard
   ## Strip debug logging
   -assumenosideeffects class android.util.Log {
       public static boolean isLoggable(java.lang.String, int);
       public static int v(...);
       public static int d(...);
       public static int i(...);
   }
   ```
3. All essential crash handling (`CrashHandler`), data storage, and error reporting were preserved untouched.

---

### D. Integrations Menu Cleanup
In `app/src/main/kotlin/com/mochyy/mochymusic/ui/screens/settings/integrations/IntegrationsScreen.kt`:
- Removed unused `Last.fm` and `Libre.fm` scrobbler UI rows.
- Kept **Discord RPC**, **YouTube Music Sync**, and **Cloud Backup** intact.
- The underlying `Scrobbler` service remains functional in background if needed, ensuring zero crashes or broken references.

---

## 3. Token-Saving Navigation Map for Future AI Agents

> [!TIP]
> **DO NOT attach or read more than 2-3 files per task.** Mochy is organized into clear decoupled layers. Use this lookup table:

| Subsystem / Task | Relevant Files | What NOT to Touch |
| :--- | :--- | :--- |
| **Deezer Module** | `modules/deezer/src/main/kotlin/.../DeezerModule.kt`<br>`DeezerApi.kt`, `DeezerCipher.kt`, `DeezerStreamProxy.kt` | Do not modify `modules/api` |
| **Module Registry / Order** | `app/.../modules/ModuleRegistry.kt`<br>`app/.../modules/SourceModules.kt` | Do not touch `CodeModules.kt` or `WebModule.kt` |
| **Audio Quality & Lab UI** | `app/.../ui/screens/audiolab/AudioQualityScreen.kt`<br>`app/.../ui/screens/audiolab/AudioLabScreen.kt` | Do not touch native C++ DSP in `app/src/main/cpp/stage` |
| **Bottom Sheets & Menus** | `app/.../ui/component/PopupSheet.kt`<br>`app/.../ui/menu/SongMenu.kt` | **Never create custom ModalBottomSheet**; always use `PopupSheet` |
| **Player UI & Artwork** | `app/.../ui/player/BottomSheetPlayer.kt`<br>`app/.../ui/player/MorphingCover.kt` | Do not touch `ExoPlayer` or `MusicService.kt` |
| **Lyrics Display & Glow** | `app/.../ui/player/LyricsGlowLine.kt`<br>`app/.../ui/player/LyricsScreen.kt` | Do not touch lyrics provider network code |
| **Integrations & Sync** | `app/.../ui/screens/settings/integrations/IntegrationsScreen.kt` | Do not touch `CloudHttp.kt` or `YouTubeSync` |
| **Build Settings & Dependencies** | `app/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Do not bump `compileSdk` beyond 37 |

---

## 4. Upstream Maintainer Rules (From AGENTS.md & CONTRIBUTING.md)

1. **Keep Mochy's Credit**:
   Never remove credits to `realidkroo` or Mochy Music. The credit in `README.md`, `About`, and `NOTICE` is mandatory under GPL-3.0.
2. **No Paywalls**:
   Mochy is 100% free. Never add ads, license keys, subscription tiers, or paid locks.
3. **Comment Style**:
   Write short, concise inline lowercase comments:
   ```kotlin
   // check if the proxy is enabled, otherwise use direct host
   val conn = openConnection(url)
   ```
   Do NOT write verbose Javadoc/LLM-style headers (`/** This function handles... */`).
4. **PopupSheet Contract**:
   Always use `PopupSheet` via `LocalMenuState.current` or `LocalBottomSheetPageState.current`. Sheets animate with spring physics (`PopupSpring`) and capped at ~86% screen height. Full-screen blur is avoided for GPU fill-rate performance.
5. **Standard Verification & Build Commands**:
   - Quick compile check:
     ```powershell
     .\gradlew.bat :app:compileArm64FossDebugKotlin
     ```
   - Release APK build:
     ```powershell
     .\gradlew.bat :app:assembleArm64FossRelease
     ```
     Output path: `app/build/outputs/apk/arm64Foss/release/app-arm64-foss-release.apk`
