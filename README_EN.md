# Sakura Encryptor 🌸

**Sakura Encryptor** (formerly SKV-Shield) is a complete **Zero-Knowledge** privacy solution designed for fast encryption and seamless cross-device playback of personal cloud media files.

With this system, you can encrypt private videos, audio, and image files and store them on AList or any WebDAV service, then enjoy smooth real-time decrypted playback in the browser, on Android, or in the desktop app — without worrying about the server exposing your data or passwords.

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
- **🖥️ Desktop Client** — Electron + Vue 3, bringing the cloud library, local library, encryption workbench and transfer queue into one window. The local library reads the disk directly behind its own password — no uploads — and both libraries support a multi-select mode for batch download and delete, alongside an app lock, tray and `.ske` file association.
- **🔐 App Lock** — A launch passphrase, optionally combined with a TOTP token and fingerprint/face. The ticked methods can demand all of them or any one, and each proof is remembered as it passes, so the order is up to you. The TOTP secret is sealed with the Android Keystore and must be confirmed with a live code before it is stored.
- **🗂️ Cache Controls** — The thumbnail cache, the decrypted media-block cache and the derived-key memo can each be switched off; the first two also take a size budget with least-recently-used eviction.
- **🔒 Industrial-Grade Encryption** — AES-256-GCM authenticated encryption with PBKDF2 (100,000 iterations) key derivation.

---

## 📦 Project Structure

- **`ske_cli/`** — Python desktop client for fast local file encryption.
- **`ske_web/`** — Vue 3 + Service Worker powered web player for real-time streaming decryption and playback of cloud files.
- **`ske_desktop/`** — Electron + Vue 3 desktop client: cloud library, local library, encryption workbench and transfer queue in one window, with an app lock and tray.
- **`ske_android/`** — Kotlin + Jetpack Compose Android client with local encryption, AList browsing and streaming decryption playback.
- **`ske_extension/`** — Chrome extension (Sakura Encryptor CORS Helper) that adds `Access-Control-Allow-Origin` to the drives you whitelist, so the web client can read drives that ship no CORS headers.
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

> The web client fetches cloud files directly from the browser (through a Service Worker), so the drive's direct links have to send CORS headers. When a drive refuses — the browser only reports `Failed to fetch` — the UI names the reason and hands that host to the helper extension in `ske_extension/` (Sakura Encryptor CORS Helper); once it is installed and the host added, a refresh is enough. Without the extension, use the desktop or Android client for that drive.

### 3. Desktop Client (Desktop)

Requires Node.js 18+ and npm.

```bash
cd ske_desktop
npm install
npm run dev      # Vite + Electron with hot reload
npm run build    # production build into the repository's out/renderer
npm start        # run the built app
```

The app opens straight into the main UI — no account required — with four pages covering the whole workflow:

- **Cloud library** — create a profile under *Cloud profiles* (one AList account + its own encryption password), then browse, play and upload from the cloud page.
- **Local library** — pick a directory once, unlock it with the **local password**, then read and write `.ske` and plain files directly on disk. It is fully independent of every cloud profile.
- **Encryption workbench** — encrypt files or an entire folder into a target directory, with a live job queue.
- **Transfers** — downloads (decrypt) and uploads (encrypt) run in background queues, two at a time by default; with *close to tray* enabled they keep going after the window is closed.

Both libraries have a **multi-select mode**: press *多选* in the toolbar, then click entries to tick them (hold `Shift` to select a range). The selection bar offers select-all, clear, batch download and batch delete; press *完成* or `Esc` to leave the mode. Right-clicking a ticked entry offers the same batch actions.

*Settings* holds the **app lock** (password, TOTP, or both combined as "all" / "any"; the TOTP secret is sealed by the OS keychain), the thumbnail cache (only encrypted previews are written to disk, with a size budget), seek step, download directory and window/tray behaviour. `.ske` files are associated with the desktop app, so a double-click plays them in place.

### 4. Android Client (Android)

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

### Desktop Implementation
The desktop client keeps every disk and cloud operation in the Electron main process; the renderer only asks for listings, thumbnails and media streams over IPC, so passwords never leave the main process.

- **Local library** — the main process reads and writes the chosen directory with `fs` and returns decrypted names with the listing. Unlike the browser's File System Access API, this needs no re-authorisation after a restart and gives stable paths.
- **Playback** — the media router turns a local `.ske` or a remote container into a plaintext byte stream, served from a randomly picked port on `127.0.0.1`. Every request must carry a token generated for that launch, so other local processes cannot read decrypted media, and Chromium handles `Range` and `<video>` exactly as it would for a website — the web decrypt pipeline is reused unchanged.
- **App lock** — the password is stored with `scrypt` and a per-install salt; the TOTP secret is sealed with `safeStorage` (DPAPI on Windows, Keychain on macOS, libsecret on Linux), because "match any" has to verify a code before any password is available.
- **Caches** — thumbnail previews are re-encrypted with a key derived from the media password before they touch the disk, with a size budget and LRU eviction; decrypted blocks and signed download links stay in memory and are released when the session ends.

---

## 📝 License

This project is for educational and personal use only.

---
*🌸 Sakura Encryptor — Your privacy, invisible yet within reach.*
