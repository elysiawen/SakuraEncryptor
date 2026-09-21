# Sakura Encryptor 🌸

**Sakura Encryptor** (formerly SKV-Shield) is a complete **Zero-Knowledge** privacy solution designed for fast encryption and seamless cross-device playback of personal cloud media files.

With this system, you can encrypt private videos, audio, and image files and store them on AList or any WebDAV service, then enjoy smooth real-time decrypted playback in the browser or on Android — without worrying about the server exposing your data or passwords.

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
- **📱 Android Client** — Native Kotlin + Jetpack Compose app with local encrypt/decrypt, AList cloud browsing, and streaming decryption playback (plaintext never touches disk).
- **🔒 Industrial-Grade Encryption** — AES-256-GCM authenticated encryption with PBKDF2 (100,000 iterations) key derivation.

---

## 📦 Project Structure

- **`ske_cli/`** — Python desktop client for fast local file encryption.
- **`ske_web/`** — Vue 3 + Service Worker powered web player for real-time streaming decryption and playback of cloud files.
- **`ske_android/`** — Kotlin + Jetpack Compose Android client with local encryption, AList browsing and streaming decryption playback.
- **`tests/`** — Comprehensive encryption consistency test suite.

---

## 🚀 Quick Start

### 1. Local Encryption (CLI)

Requires Python 3.10+.

```bash
pip install .          # installs the ske / ske-gui commands

# Encrypt a single file or a whole directory
ske encrypt -i "my_video.mp4" -o ./encrypted -p "your-password"
ske encrypt -i ./videos -o ./encrypted -p "your-password" -r   # -r also encrypts the source folder name

# Decrypt back
ske decrypt -i ./encrypted -o ./restored -p "your-password"
```

You can also run `python -m ske_cli ...`; the drag-and-drop GUI is `ske-gui`.

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

### 3. Android Client (Android)

Requires the Android SDK (compileSdk 35) and JDK 17+.

```bash
cd ske_android
./gradlew assembleDebug
# Artifact: app/build/outputs/apk/debug/app-debug.apk
```

The app opens **straight into the main UI — no account required**:

- **Local-only**: unlock once with the **local password** on the Local tab to play or encrypt/decrypt `.ske` files, then pick a folder to encrypt new files into. It is fully independent from every cloud profile.
- **Cloud**: create profiles under *Settings → Cloud profiles* — **one profile = one AList account + its own encryption password**. Keep several and switch at any time; switching unlocks and reconnects automatically.

Every password is stored only as a Keystore-sealed blob (opt in per profile) and lives in memory while in use.

The Android client covers four areas: cloud browsing/playback of encrypted files, local `.ske` playback, local encrypt/decrypt, and download-then-decrypt.

---

## 🛡️ How It Works

### Encryption (SKE v2.0)
1. **Chunking** — Each file is split into 1 MB blocks.
2. **Key Derivation** — A 256-bit Master Key is derived from the user password + random salt via PBKDF2-HMAC-SHA256 (100k iterations).
3. **Block Encryption** — Each block is independently encrypted with AES-256-GCM using a unique nonce; the file-header prefix (Magic / version / salt / master IV) is bound in as AAD so key-defining header fields cannot be tampered with (legacy v1.0 files still decrypt).
4. **Real-Time Decryption** — The Service Worker intercepts `/ske-decrypt/` requests, fetches encrypted blocks on demand, decrypts them in memory, and feeds the result to the player via `Content-Range` responses.

### File-Name Encryption
File names are encrypted too: a *deterministic* AES-GCM construction (fixed salt-derived key plus a key-derived fixed IV) means identical plain names always produce identical tokens, so the encrypted directory tree can be rebuilt without any database.

### Network Optimization
The SW 2.0 architecture introduces **session-level caching**: file headers and derived keys are computed/fetched only once per playback session. For video seeking, the SW deduplicates in-flight requests via merging to avoid bandwidth waste.

### Android Implementation
The Android client reuses the exact same `.ske` v2.0 format (byte-compatible, verified in `ske_android/app/src/test`). Playback goes through a custom Media3 `DataSource` that fetches only the ciphertext blocks covering the requested plaintext range — HTTP Range for remote files, SAF random access for local ones — decrypts them in memory and feeds the player. Decrypted blocks are reused via an LRU cache and plaintext is never written to disk.

---

## 📝 License

This project is for educational and personal use only.

---
*🌸 Sakura Encryptor — Your privacy, invisible yet within reach.*
