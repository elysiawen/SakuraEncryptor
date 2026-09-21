# Sakura Encryptor 🌸

**Sakura Encryptor** (原 SKV-Shield) 是一套完整的 **零知识（Zero-Knowledge）** 隐私保护方案，专门用于个人云端媒体文件的极速加密与跨设备无感播放。

通过这套系统，你可以将私密的视频、音频和图片文件加密后存储在 AList 或任何 WebDAV 服务上，并在浏览器或安卓设备上实现流畅的实时解密播放，而无需担心服务器泄露你的数据或密码。

> 🇬🇧 [English README](README_EN.md)

---

## ✨ 核心特性

- **🛡️ 纯前端零知识架构**：密码永远不离本地，解密过程 100% 在浏览器内完成，服务端仅作为静态代理。
- **⚡ 极速实时流解密**：基于 Service Worker 和 Web Crypto API，支持视频任意跳进度（Seek），即点即播。
- **🎵 沉浸式音乐体验**：专属黑胶唱片播放界面，支持动态环境光效及 ID3 专辑封面自动提取。
- **🖼️ 图片浏览**：支持常见图片格式的实时解密查看，带旋转等操作。
- **📋 播放列表**：音乐、视频、图片播放页均支持文件夹内同类型文件的播放列表，可快速切换、自动播放下一首。
- **📊 实时性能监控**：在播放页集成 FPS、丢帧率、解密延迟、网速及缓存命中率的实时监控。
- **💾 智能离线缓存**：Service Worker 2.0 级块缓存（LRU），显著降低网络流量消耗，解决流量放大问题。
- **📱 安卓客户端**：Kotlin + Jetpack Compose 原生应用，支持本地文件加解密、AList 云端目录浏览，以及边下边解密的流式播放（明文永不落盘）。
- **🔒 工业级加密**：采用 AES-256-GCM 认证加密与 PBKDF2 (100,000 迭代) 密钥导出。

---

## 📦 项目结构

- **`ske_cli/`**：Python 编写的桌面客户端。负责本地文件的极速加密。
- **`ske_web/`**：Vue 3 + Service Worker 驱动的在线播放站。负责云端文件的实时流解密播放。
- **`ske_android/`**：Kotlin + Jetpack Compose 安卓客户端。支持本地加解密、AList 云端浏览与流式解密播放。
- **`tests/`**：完善的加密一致性测试脚本。

---

## 🚀 快速开始

### 1. 本地加密 (CLI)

确保已安装 Python 3.10+。

```bash
pip install .          # 安装后提供 ske / ske-gui 命令

# 加密单个文件或整个目录
ske encrypt -i "my_video.mp4" -o ./encrypted -p "your-password"
ske encrypt -i ./videos -o ./encrypted -p "your-password" -r   # -r 将源文件夹名一并纳入加密树

# 解密还原
ske decrypt -i ./encrypted -o ./restored -p "your-password"
```

也可以用 `python -m ske_cli ...` 调用；图形界面（支持拖拽）运行 `ske-gui`。

### 2. 在线播放 (Web)

确保已安装 Node.js。

```bash
cd ske_web
npm install
npm run dev
```

1.  打开浏览器访问 `http://localhost:5173`。
2.  输入你的 AList / WebDAV 地址及密码。
3.  点击加密文件，输入加密时设置的密码。
4.  开始享受私密播放！

### 3. 安卓客户端 (Android)

需要 Android SDK（compileSdk 35）与 JDK 17+。

```bash
cd ske_android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

应用启动后**直接进入主界面，不需要任何账号**：

- **只用本地**：在「本地」页输入一次**本地密码**，即可播放 / 加解密本地 `.ske` 文件，并选择文件夹加密新文件。本地密码与云端配置完全独立。
- **需要云端**：在「设置 → 云端配置」中新建配置 —— **每个配置 = 一套 AList 账号 + 一套独立的加密密码**。可以保存多个配置并随时切换，切换后自动解锁并重连。

所有密码都只以 Android Keystore 加密的形式落盘（可在配置中勾选“记住”），解密时仅存在于内存。

安卓端提供四大功能：云端加密目录浏览与播放、本地 `.ske` 文件播放、本地文件加解密、下载并解密到本地。

---

## 🛡️ 技术原理

### 加密逻辑 (SKE v2.0)
1.  **文件分块**：每个文件被切分为 1MB 的块。
2.  **密钥导出**：利用用户密码 + 随机 Salt，通过 PBKDF2-HMAC-SHA256 (100k 迭代) 导出 256 位 Master Key。
3.  **分块加密**：每个块独立使用 AES-256-GCM 加密，并带有独立的 Nonce；同时将文件头前缀（Magic / 版本 / Salt / Master IV）作为 AAD 参与认证，防止关键头部字段被篡改（v1.0 旧文件仍可正常解密）。
4.  **实时解密**：Service Worker 拦截 `/ske-decrypt/` 请求，按需拉取加密分块并在内存中完成解密，随后通过 `Content-Range` 响应喂给播放器。

### 文件名加密
文件名同样被加密：使用固定盐派生的密钥与从密钥派生的固定 IV 进行**确定性** AES-GCM 加密，因此相同明文文件名始终得到相同密文 token，无需数据库即可在云端重建整棵目录树。

### 网络优化
最新的 SW 2.0 架构引入了 **Session 级缓存**，对于同一次播放，文件头和导出密钥只需计算/拉取一次。针对视频拖动，SW 会通过 `inflight` 合并重复请求，避免了带宽浪费。

### 安卓端实现
安卓客户端复用完全相同的 `.ske` v2.0 格式（字节级兼容，见 `ske_android/app/src/test`）。播放时通过 Media3 自定义 `DataSource` 按需拉取密文块——远端走 HTTP Range、本地走 SAF 随机读——在内存中解密后喂给播放器，因此拖动进度条时只会下载所覆盖的块；已解密的块由 LRU 缓存复用，明文始终不写入磁盘。

---

## 📝 开源许可

本项目仅供学习与交流使用。

---
*🌸 Sakura Encryptor - 让你的隐私从此不可见，却又触手可及。*
