# Sakura Encryptor 🌸

**Sakura Encryptor** (formerly SKV-Shield) is a complete **Zero-Knowledge** privacy solution designed for fast encryption and seamless cross-device playback of personal cloud media files.

With this system, you can encrypt private videos, audio, and image files and store them on AList or any WebDAV service, then enjoy smooth real-time decrypted playback in the browser — without worrying about the server exposing your data or passwords.

> 🇨🇳 [中文说明](README.md)

---

## ✨ Key Features

- **🛡️ Zero-Knowledge Frontend Architecture** — Passwords never leave your device. Decryption happens 100% in the browser; the server acts only as a static proxy.
- **⚡ Real-Time Streaming Decryption** — Built on Service Worker and Web Crypto API. Supports arbitrary seeking in videos with instant playback.
- **🎵 Immersive Music Player** — Dedicated vinyl disc UI with dynamic ambient lighting and automatic ID3 album art extraction.
- **🖼️ Image Viewer** — Real-time decryption and display of common image formats, with rotation support.
- **📋 Playlist** — Music, video, and image players all support folder-based playlists with the same media type. Quickly switch tracks and auto-play the next item.
- **📊 Real-Time Performance Monitor** — Integrated FPS, frame drops, decryption latency, network speed, and cache hit rate display on the playback page.
- **💾 Smart Offline Caching** — Service Worker 2.0 block-level caching (LRU) significantly reduces network traffic and solves the bandwidth amplification problem.
- **🔒 Industrial-Grade Encryption** — AES-256-GCM authenticated encryption with PBKDF2 (100,000 iterations) key derivation.

---

## 📦 Project Structure

- **`ske_cli/`** — Python desktop client for fast local file encryption.
- **`ske_web/`** — Vue 3 + Service Worker powered web player for real-time streaming decryption and playback of cloud files.
- **`tests/`** — Comprehensive encryption consistency test suite.

---

## 🚀 Quick Start

### 1. Local Encryption (CLI)

Requires Python 3.8+.

```bash
cd ske_cli
# Encrypt a video
python main.py encrypt "my_video.mp4" --output "my_video.mp4.ske"
# Set password: prompted on first run
```

### 2. Web Playback (Web)

Requires Node.js.

```bash
cd ske_web
npm install
npm run dev
```

1. Open `http://localhost:5173` in your browser.
2. Enter your AList / WebDAV server address and password.
3. Click an encrypted file and enter the password used during encryption.
4. Enjoy private playback!

---

## 🛡️ How It Works

### Encryption (SKE v1.0)
1. **Chunking** — Each file is split into 1 MB blocks.
2. **Key Derivation** — A 256-bit Master Key is derived from the user password + random salt via PBKDF2-HMAC-SHA256 (100k iterations).
3. **Block Encryption** — Each block is independently encrypted with AES-256-GCM using a unique nonce.
4. **Real-Time Decryption** — The Service Worker intercepts `/ske-decrypt/` requests, fetches encrypted blocks on demand, decrypts them in memory, and feeds the result to the player via `Content-Range` responses.

### Network Optimization
The SW 2.0 architecture introduces **session-level caching**: file headers and derived keys are computed/fetched only once per playback session. For video seeking, the SW deduplicates in-flight requests via merging to avoid bandwidth waste.

---

## 📝 License

This project is for educational and personal use only.

---
*🌸 Sakura Encryptor — Your privacy, invisible yet within reach.*
