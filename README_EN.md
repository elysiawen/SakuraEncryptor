# Sakura Encryptor 🌸

**Sakura Encryptor** (formerly SKV-Shield) is a complete **Zero-Knowledge** privacy solution designed for fast encryption and seamless cross-device playback of personal cloud media files.

With this system, you can encrypt private videos, audio, and image files and store them on AList or any WebDAV service, then enjoy smooth real-time decrypted playback in the browser or on Android — without worrying about the server exposing your data or passwords.

> 🇨🇳 [中文说明](README.md)

---

## ✨ Key Features

- **🛡️ Zero-Knowledge Frontend Architecture** — Passwords never leave your device. Decryption happens 100% in the browser; the server acts only as a static proxy.
- **⚡ Real-Time Streaming Decryption** — Built on Service Worker and Web Crypto API. Supports arbitrary seeking in videos with instant playback.
- **🎵 Immersive Music Player** — Dedicated vinyl disc UI with dynamic ambient lighting and automatic ID3 album art extraction.
- **🖼️ Image Viewer** — Real-time decryption and display of common image formats with rotation, zoom and previous/next navigation; a gallery can switch to a continuous "comic" strip whose toolbar hides itself while you scroll.
- **📋 Playlist** — Music, video, and image players all support folder-based playlists with the same media type. Quickly switch tracks and auto-play the next item.
- **📊 Real-Time Performance Monitor** — Integrated FPS, frame drops, decryption latency, network speed, and cache hit rate display on the playback page.
- **💾 Smart Offline Caching** — Service Worker 2.0 block-level caching (LRU) significantly reduces network traffic and solves the bandwidth amplification problem.
- **📱 Android Client** — Native Kotlin + Jetpack Compose app with local encrypt/decrypt, AList cloud browsing, and streaming decryption playback; its decrypted caches are optional and wiped when the vault locks.
- **🔐 App Lock** — A launch passphrase, optionally combined with a TOTP token and fingerprint/face. The ticked methods can demand all of them or any one, and each proof is remembered as it passes, so the order is up to you. The TOTP secret is sealed with the Android Keystore and must be confirmed with a live code before it is stored.
- **🗂️ Cache Controls** — The thumbnail cache, the decrypted media-block cache and the derived-key memo can each be switched off; the first two also take a size budget with least-recently-used eviction.
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

The Android client covers four areas: cloud browsing/playback of encrypted files, local `.ske` playback, local encrypt/decrypt, and download-then-decrypt. Cloud folders are listed 500 entries at a time, so directories beyond the server's default 200-entry page are no longer truncated, and entries are sorted naturally after decryption (folders first).

*Settings → Security* turns on the **app lock**: once a launch passphrase is set, password, TOTP token and fingerprint/face can be ticked in any combination, and the authenticator is bound on the same page (the secret is only ever stored Keystore-sealed). *Settings → Caches* controls the thumbnail cache, the decrypted media-block cache and the derived-key memo, with size budgets for the first two.

---

## 🛡️ How It Works

### Encryption (SKE version 001)
1. **Chunking** — Each file is split into 1 MB blocks.
2. **Key Derivation** — A 256-bit Master Key is derived from the user password + random salt via PBKDF2-HMAC-SHA256 (100k iterations).
3. **Block Encryption** — Each block is independently encrypted with AES-256-GCM using a unique nonce; the AAD is the concatenation of the key-defining prefix (Magic / version / salt / master IV) and the 22-byte options block that follows it (`KEY_CHECK`, KDF iterations, flags), so tampering with either half fails authentication.
4. **Real-Time Decryption** — The Service Worker intercepts `/ske-decrypt/` requests, fetches encrypted blocks on demand, decrypts them in memory, and feeds the result to the player via `Content-Range` responses.
5. **Encrypted Preview** — Images *and* videos may carry a 512px AES-GCM-encrypted JPEG preview between the header and the body (`SKETH` envelope; `THUMB_LEN = 0` means "no preview"), which keeps the body byte-identical to the original file. The web client decrypts just this envelope through `/ske-thumb` (≈40 KB), so grids never pull a 1 MiB ciphertext block nor decode a video frame for a tile. Images are downscaled with Pillow; videos are sampled with PyAV (`pip install .[video]`, which bundles its own decoders), falling back to a system ffmpeg and skipping entirely when neither is available (encryption is unaffected). The browser samples a frame while encrypting in the web app. Previews are also skipped when the source is already small, or when they would cost more than a quarter of the source.

### File-Name Encryption
File names are encrypted too: a *deterministic* AES-GCM construction (fixed salt-derived key plus a key-derived fixed IV) means identical plain names always produce identical tokens, so the encrypted directory tree can be rebuilt without any database.

### Network Optimization
The SW 2.0 architecture introduces **session-level caching**: file headers and derived keys are computed/fetched only once per playback session. For video seeking, the SW deduplicates in-flight requests via merging to avoid bandwidth waste.

### Android Implementation
The Android client reuses the exact same `.ske` version 001 format (byte-compatible, verified in `ske_android/app/src/test`). Playback goes through a custom Media3 `DataSource` that fetches only the ciphertext blocks covering the requested plaintext range — HTTP Range for remote files, SAF random access for local ones — decrypts them in memory and feeds the player. Decrypted blocks are reused from an in-memory LRU; two further disk caches are optional — thumbnails (on by default, 128 MB) and decrypted media blocks (off by default, 1 GB) — written under the app's private `cache/` directory, evicted least-recently-used over budget, and wiped when the vault locks, when switched off or by the system.

---

## 📝 License

This project is for educational and personal use only.

---
*🌸 Sakura Encryptor — Your privacy, invisible yet within reach.*
