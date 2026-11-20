<div align="center">

  <img width="480" alt="Mochy Resona" src="https://github.com/user-attachments/assets/c5e1d307-ecc4-4c26-a88f-f4f468c8e434" style="max-width:100%; border-radius:10px;" />

  # Mochy Resona ✨

  <p>
    <strong>A refined, vibecoded edition of Mochy Music featuring native lossless audio streaming, regional unblock proxies, and zero-jitter playback.</strong>
  </p>

  <p>
    <a href="https://github.com/Not-RK/Mochy-Music/releases/latest">
      <img height="20" src="https://img.shields.io/badge/Release-v8.4.3--resona-7aa2f7?style=flat&logo=android&logoColor=white" alt="Release" />
    </a>
    <a href="https://github.com/realidkroo/Mochy-Music">
      <img height="20" src="https://img.shields.io/badge/Upstream-v8.4.3-3fb950?style=flat&logo=github&logoColor=white" alt="Upstream Parity" />
    </a>
    <img height="20" src="https://img.shields.io/badge/Audio-Lossless%20FLAC-bb9af7?style=flat" alt="Lossless" />
    <img height="20" src="https://img.shields.io/badge/License-GPL--3.0-e0af68?style=flat" alt="GPL-3.0" />
  </p>

</div>

---

> [!NOTE]
> **Personal Customized Build**: This is a fully vibecoded, personal-use fork of **[Mochy-Music](https://github.com/realidkroo/Mochy-Music)** customized by **Not-RK**. The base application is created and maintained by **[realidkroo](https://github.com/realidkroo)**. All credits and deep respect to the original app maintainer.

---

## ⚡ What's Added in Resona Edition

- 🎵 **Native Lossless Stream Module (`:modules:deezer`)**:
  - Full Blowfish CBC 2048-byte stripe decryption engine.
  - Genuine FLAC (1411 kbps) and 320 kbps MP3 stream routing via local loopback (`127.0.0.1`).
- 🌍 **Regional Geo-Unblock Proxies**:
  - Selectable proxy endpoints (UK 1, UK 2, RU, MD) for seamless playback in unsupported countries (e.g. India).
- 🪄 **Zero-Jitter Polish**:
  - Stripped heavy debug logcat flooding and Timber debug trees to eliminate playback stutters.
- 🎯 **Minimal & Clean Defaults**:
  - Streamlined default sources to **Monochrome** and **Deezer** only.
  - Removed non-functional Last.fm and Libre.fm rows from the Integrations UI.
- ☁️ **Automated Cloud CI/CD**:
  - Streamlined GitHub Actions build pipeline with automatic keystore generation and GitHub Releases publishing.

---

## 📸 Interface Showcase

<details>
<summary><strong>Click to expand preview screenshots (7 mini screens)</strong></summary>

<br/>

<div align="center">
  <img src="https://github.com/user-attachments/assets/c4c122ed-b86c-44e4-a618-417f5287bc98" width="160" />
  <img src="https://github.com/user-attachments/assets/186be8ac-ff3e-4e2a-ab7e-ae80bc9da7e7" width="160" />
  <img src="https://github.com/user-attachments/assets/c394603c-7aa7-4d78-8688-38a6c6e42232" width="160" />
  <img src="https://github.com/user-attachments/assets/06f5c13a-b8f1-4ecf-85aa-b17d020db5c8" width="160" />
</div>

<br/>

<div align="center">
  <img src="https://github.com/user-attachments/assets/71c5ee7e-75c1-479f-8075-039bab4f7fe7" width="160" />
  <img src="https://github.com/user-attachments/assets/e8c2084f-68ec-4959-a881-03a3b93df292" width="160" />
  <img src="https://github.com/user-attachments/assets/50580702-6bc9-4204-8665-b7d6c6390f78" width="160" />
</div>

</details>

---

## 📥 Installation

1. Go to the **[Latest Release](https://github.com/Not-RK/Mochy-Music/releases/latest)** page.
2. Choose your APK:
   - **`Mochy-Resona-v8.4.3-arm64.apk`** *(Recommended)*: For 99% of modern phones and tablets (~33 MB).
   - **`Mochy-Resona-v8.4.3-universal.apk`**: Compatible with all devices including older tablets, 32-bit phones, and emulators.
3. Open and install on Android 8.0+.

---

## 🎧 How to Configure Deezer (FLAC Lossless)

1. Go to **Settings → Modules → Deezer**.
2. **ARL Cookie for FLAC**: Paste your Deezer `arl` cookie into the ARL token field to unlock genuine Hi-Fi Lossless (FLAC 1411 kbps) streaming.
3. **Regional Proxy**: If you are in India or any unsupported country, select a proxy from the dropdown (e.g. `UK 1` or `UK 2`).
4. Set **Deezer** as your preferred source, search any track, and enjoy crystal-clear audio!

---

## 🤝 Credits & Attributions

We express our sincere appreciation to the open-source creators and reference projects that made this possible:

<!-- mochy-credit:start · f208ad37 -->
- **[realidkroo/Mochy-Music](https://github.com/realidkroo/mochy-music)** — Creator and maintainer of Mochy Music.
- **[LuftVerbot/echo-deezer-extension](https://github.com/LuftVerbot/echo-deezer-extension)** — Echo extension reverse-engineering and proxy logic.
- **[murglar.app](https://murglar.app)** — Proxy infrastructure and community decryption research.
- **[monochrome-music](https://monochrome.st)** — Lossless FLAC stream provider.
- **[vivizzz007/vivi-music](https://github.com/vivizzz007/vivi-music)** — Base application framework.
- **[tranxuanthang/lrclib](https://lrclib.net)** — Synchronized LRC lyrics provider.

---

## Forking Mochy

Forks are welcome, that's what open source is for! Just two asks:

- **Keep the credit.** If you rename it, say it's forked from [Mochy Music](https://github.com/realidkroo/mochy-music) by realidkroo, in your README and inside your app, and keep the credits below for everyone Mochy is built on.
- **Keep it free.** Mochy is not monetized and never will be. Please don't put features behind a paywall. A donation page is fine.

AI tools working on this repo or a fork of it follow [AGENTS.md](AGENTS.md), and they're asked to leave this section alone.
<!-- mochy-credit:end -->

---

<div align="center">
  <sub>Distributed under the GNU General Public License v3.0 (GPL-3.0). Free and open source forever.</sub>
</div>
