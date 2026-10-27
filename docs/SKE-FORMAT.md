# SKE 容器格式规范（v002 / v003）

`.ske` 是 Sakura Encryptor 的加密容器格式，被三个实现共享，必须**逐字节兼容**：

| 端 | 权威实现 | 角色 |
| --- | --- | --- |
| CLI（Python） | `ske_cli/crypto.py` | 规范基准，生成跨端测试向量 |
| Web（Service Worker） | `ske_web/public/sw-decrypt.js`（读）、`ske_web/src/composables/useCrypto.js`（写） | 流式只读播放 + 浏览器内加密 |
| Android（Kotlin） | `ske_android/app/src/main/java/com/sakura/encryptor/core/crypto/` | 流式播放 + 本地加解密 |

> 修改任何常量前，请先读完 [§14 修改格式的检查清单](#14-修改格式的检查清单)。

---

## 1. 总览

```
┌──────────────────────────────────────────────────────────┐
│ Header        50 B   MAGIC(7) VERSION(3) SALT(16)        │
│                      MASTER_IV(12) HEADER_TAG(12)        │
├──────────────────────────────────────────────────────────┤
│ Envelope      20 + N B（仅 v003）                        │
│                      "SKTH" THUMB_NONCE(12) LEN(4) CT(N) │
├──────────────────────────────────────────────────────────┤
│ Body          N 块，每块 AES-256-GCM(明文块) || TAG(16)  │
│                      除最后一块外，明文块恒为 1 MiB      │
└──────────────────────────────────────────────────────────┘
```

设计目标：

- **随机访问**：头部自带 `SALT` 与 `MASTER_IV`，任何偏移的块都能独立解密，因此播放器拖动进度条只需拉取所覆盖的块。
- **零知识**：密码不落盘，仅内存中存在派生的 256 位密钥。
- **自校验**：每块带独立 GCM 标签；全文件解密额外校验 `HEADER_TAG`。

---

## 2. 字节布局

### 2.1 Header（所有版本，固定 50 字节）

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 7 | `MAGIC` | ASCII `SakuraE` |
| 7 | 3 | `VERSION` | ASCII `002` / `003` |
| 10 | 16 | `SALT` | 文件级随机盐（PBKDF2 输入） |
| 26 | 12 | `MASTER_IV` | 块 nonce 的基值 |
| 38 | 12 | `HEADER_TAG` | 正文字节流的 SHA-256 前 12 字节 |

`HEADER_SIZE = 7 + 3 + 16 + 12 + 12 = 50`。

### 2.2 Thumbnail envelope（仅 v003，位于头部与正文之间）

| 相对偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 50 | 4 | `THUMB_MAGIC` | ASCII `SKTH` |
| 54 | 12 | `THUMB_NONCE` | 信封独立 nonce，随机 |
| 66 | 4 | `THUMB_LEN` | 大端 uint32，**含 16 字节 GCM 标签**的密文长度 |
| 70 | `THUMB_LEN` | `THUMB_CT` | 加密后的 JPEG 预览 |

`THUMB_ENVELOPE_SIZE = 4 + 12 + 4 = 20`，`PROBE_SIZE = 50 + 20 = 70`。

- v003 **总是**写入信封；`THUMB_LEN == 0` 表示该文件没有预览（写端无法生成预览时就是这种形态）。
- 因此 v003 的正文起点是 `50 + 20 + THUMB_LEN`，而不是固定 50。
- `THUMB_NONCE` 在 `THUMB_LEN == 0` 时仍存在（随机填充），读端应忽略。

### 2.3 Body

```
block i = GCM_CT(明文块 i) || GCM_TAG(16)
```

- 明文块大小恒为 `CHUNK_SIZE = 1 MiB = 1 048 576`，最后一块为剩余字节（可能为 0 字节，即空文件没有任何块）。
- 密文块大小恒为 `ENC_BLOCK_SIZE = CHUNK_SIZE + 16 = 1 048 592`，最后一块可能更短。
- 空文件：只有头部（v002）或头部 + 信封（v003），没有正文块。

---

## 3. 常量表

| 名称 | 值 | 说明 |
| --- | --- | --- |
| `MAGIC` | `SakuraE` | 7 字节 ASCII |
| `VERSION`（写端） | `003` | 三端写出的版本 |
| `THUMB_MAGIC` | `SKTH` | 4 字节 ASCII |
| `SALT_SIZE` | 16 | |
| `IV_SIZE` | 12 | |
| `TAG_SIZE` | 16 | AES-GCM 标签（128 bit） |
| `HEADER_TAG_SIZE` | 12 | |
| `HEADER_SIZE` | 50 | |
| `AAD_SIZE` | 38 | `HEADER_SIZE - HEADER_TAG_SIZE` |
| `THUMB_ENVELOPE_SIZE` | 20 | |
| `PROBE_SIZE` | 70 | 一次读取即可定位正文起点 |
| `CHUNK_SIZE` | 1 MiB | 明文块 |
| `ENC_BLOCK_SIZE` | 1 MiB + 16 | 密文块 |
| `KDF_ITERATIONS` | 100 000 | PBKDF2-HMAC-SHA256 |
| `KEY_BITS` | 256 | |
| `NAME_SALT` | `ske-name-salt-00` | 文件名密钥的固定盐（ASCII，16 B） |
| `NAME_IV_SEED` | `ske-name-iv` | 推导文件名固定 IV 的 HMAC 输入 |
| 文件后缀 | `.ske` | 加密后的文件名 = token + `.ske` |

---

## 4. 密钥派生

```
key = PBKDF2-HMAC-SHA256(password = UTF-8(密码), salt = SALT, iterations = 100_000, dkLen = 32)
```

- 密码按 **UTF-8** 编码（含中文、emoji），三端必须一致。
- `SALT` 每个文件随机生成（16 字节），因此相同密码 + 相同明文 → 不同密文。
- 文件名使用**另一把**密钥（见 §10），盐为常量。

---

## 5. AAD 规则

AAD 参与 GCM 认证、不随密文存储，实际只有三种情形：

| 对象 | 版本 | AAD |
| --- | --- | --- |
| 正文块 | `002` / `003` | `bytes[0:38]`，即 `MAGIC‖VERSION‖SALT‖MASTER_IV` |
| 预览信封 | `003` 且 `THUMB_LEN > 0` | `bytes[0:38] ‖ "SKTH" ‖ BE32(THUMB_LEN)` |

要点：

- 正文 AAD **不含** `HEADER_TAG`，也不含信封内容。这样写端可以最后回填头部（见 §7），而不必重算正文。
- 信封 AAD 把 `THUMB_LEN` 绑定进去，避免"改长度后切片错位"却不被察觉；写端计算时用的是**密文长度**（明文长度 + 16）。
- 早期 `001` 曾不带 AAD（该格式已移除，见 §11），所以现在任何受支持文件都必然携带 AAD，读端不再需要分支判断。

---

## 6. 分块 nonce 与随机访问

### 6.1 nonce 推导

```
nonce(i) = MASTER_IV XOR BE12(i)      // 逐字节异或，i 为大端、左侧补零的 12 字节
```

即 `BE12(i)` 的低 8 字节是块号，高 4 字节为 0。块号从 0 开始、按文件顺序递增，每个文件的 `MASTER_IV` 随机，因此 nonce 全局不重复。

参考向量（`MASTER_IV = 00112233445566778899aabb`）：

| i | nonce |
| --- | --- |
| 0 | `00112233445566778899aabb` |
| 1 | `00112233445566778899aaba` |
| 255 | `00112233445566778899aa44` |
| 256 | `00112233445566778899abbb` |
| 1 000 000 | `00112233445566778896e8fb` |

### 6.2 随机读取任意区间

设容器总长为 `S`，`bodyOffset` 为正文起点（v003 为 `70 + THUMB_LEN`，否则 50）：

```
blocks     = max(0, ceil((S - bodyOffset) / ENC_BLOCK_SIZE))
plainSize  = (S - bodyOffset) - 16 * blocks
```

要读取明文字节 `[start, end]`：

```
first = start / CHUNK_SIZE
last  = end   / CHUNK_SIZE
对 i in [first, last]:
    密文区间 = [bodyOffset + i * ENC_BLOCK_SIZE,
                min(bodyOffset + i * ENC_BLOCK_SIZE + ENC_BLOCK_SIZE, S)]
    明文     = GCM_DECRYPT(key, nonce(i), 密文区间, aad)
    plainOffset = i * CHUNK_SIZE
```

块之间彼此独立，可按任意顺序解密（Android 的 `SkeBlockDecryptor`、Web 的 `batchFetchBlocks` 都依赖这一点）。

---

## 7. 完整性标签 HEADER_TAG

```
HEADER_TAG = SHA-256( block_0_ct ‖ block_1_ct ‖ … ‖ block_n_ct )[:12]
```

- 输入是**正文所有密文块（含各自 16 字节标签）按顺序的拼接**；不包含信封、不包含头部自身。
- 写端因此先写 50 字节零占位 + 信封，流式写正文并同步计算哈希，最后 `seek(0)` 回填 `MAGIC‖VERSION‖SALT‖MASTER_IV‖HEADER_TAG`。
- 空文件：哈希输入为空串，标签即 `SHA-256("")[:12]`。

**校验强度差异（有意为之）：**

| 路径 | 是否校验 `HEADER_TAG` |
| --- | --- |
| CLI 全文件解密 | 是（`hmac.compare_digest`） |
| Android 全文件解密 `SkeFileCipher.decryptFile` | 是（常量时间比较） |
| Android 流式播放 `SkeBlockDecryptor` | 否 —— 校验它要读完整文件，与随机访问矛盾 |
| Web Service Worker | 否 —— 同上 |

因此"能播放"只证明**用到的那些块**未被篡改且密码正确；整文件完整性需要下载后走全量解密。

---

## 8. v003 预览信封

### 8.1 加密

```python
thumb_aad  = header_prefix + b"SKTH" + struct.pack(">I", len(thumb_ct))
thumb_ct   = AESGCM(key).encrypt(thumb_nonce, thumb_plaintext, thumb_aad)   # 自带 16 B 标签
```

`header_prefix` 是 38 字节的 `MAGIC‖VERSION‖SALT‖MASTER_IV`，即与正文相同的 AAD 前缀。

### 8.2 解密（只读 70 + N 字节）

1. range 读取 `[0, 70)`；
2. 校验 `bytes[0:7] == "SakuraE"`、`bytes[7:10] == "003"`，否则本路径直接放弃（v002 无预览）；
3. 校验 `bytes[50:54] == "SKTH"`，否则视为信封损坏；
4. 解析 `THUMB_LEN = BE32(bytes[66:70])`；为 0 则返回"无预览"；
5. range 读取 `[70, 70 + THUMB_LEN)`；
6. 用 §5 的信封 AAD 解密，输出 JPEG 字节。

一次预览请求的总流量是 `70 + THUMB_LEN`（通常 ≈ 40 KB），而不是解码所需的 1 MiB 首块。密码错误时表现为标签校验失败，上层通常降级为"无预览"而不是报错（见 `EnvelopeReader.read`）。

### 8.3 信封损坏的处理

- 信封 magic 不匹配 → `InvalidSkeFileException("Corrupted thumbnail envelope")`（Android）/ `Corrupted .ske thumbnail envelope`（Web）；
- 容器长度小于 `bodyOffset` → 同上；
- 预览解密失败 → `SkeDecryptionException`（全文件解密路径**不受影响**，预览失败不阻塞正文）。

---

## 9. 预览内容生成规则（写端约定）

预览不是安全相关字段，但三端行为应一致，否则同一个源文件在不同端加密后观感不同：

| 规则 | 值 |
| --- | --- |
| 最长边 | 512 px（缩略图约 300 CSS px，2 倍屏仍清晰） |
| 编码 | JPEG，quality 80 |
| 最小体积 | `< 128` 字节视为无效，不写预览 |
| 成本上限 | 预览体积 `* 4 >= 源文件体积` 时放弃（1/4 规则） |
| 静态图 | 源图最长边 `<= 512` 时跳过（否则只是放大） |
| 视频抽帧位置 | 时长 30% 处（取最近的 I 帧） |
| 视频容器白名单 | `.mp4 .m4v .mkv .mov .avi .webm .ts .m2ts .flv .wmv .mpg .mpeg .3gp` |
| 视频解码优先级 | PyAV → 系统 ffmpeg → 跳过（CLI）；浏览器抓帧（Web）；`MediaMetadataRetriever`（Android） |

预览抽取失败**绝不**影响加密结果，只会得到 `THUMB_LEN = 0`。

---

## 10. 文件名加密（确定性）

目录名与文件名逐个路径分量加密，因此无需数据库即可从云端密文目录树重建明文树。

```
name_key = PBKDF2-HMAC-SHA256(password, NAME_SALT, 100_000, 32)
name_iv  = HMAC-SHA256(name_key, "ske-name-iv")[:12]          # 固定 IV
token    = BASE64URL_NO_PADDING( GCM_ENCRYPT(name_key, name_iv, UTF-8(分量), aad=None) )
```

- `NAME_SALT` 与 `NAME_IV_SEED` 是**常量**，改动会让所有历史文件名的密文失效。
- 固定盐 + 固定 IV ⇒ 相同明文分量 → 相同 token（确定性；这本身泄露"同名文件"信息，是有意取舍）。
- token 字母表为 URL-safe Base64 且去掉 `=`，可直接作为 URL 路径与文件名。
- 落盘文件名 = `token + ".ske"`；解密时先去掉 `.ske` 再解 token。
- 加密路径时按 `/` 拆分、丢弃空分量、忽略反斜杠差异（`\` 归一化为 `/`）。

参考向量（密码 `sakura-pass`，`name_key = 70d27931…a7b00a6`）：

| 明文 | token |
| --- | --- |
| `hello.mp4` | `kJLHVtK33bNsfXHJaxyHPRj5pQaH7j2qRQ` |
| `视频 测试.mkv` | `EFAt0x8IkCXtMAAMA4CgT-4Xn9eJvYp911HWBtRCyld9` |
| `樱花` | `Hl8a0jcol3uIrRi9_cA6X2JG0WJpXQ` |
| `a` | `mWBEuB9EVFDyJJ6t8c4XPBQ` |

---

## 11. 版本历史与兼容性

| 版本 | 正文 AAD | 预览信封 | 引入变更 |
| --- | --- | --- | --- |
| `002` | 38 字节头部前缀 | 无 | 头部关键字段不可被无痕篡改 |
| `003` | 38 字节头部前缀 | 有 | 头尾之间插入可选加密预览，正文起点动态 |

**`001` 已不再支持。** 它是仓库首个提交 `05553f2`（2026-03-07）的格式（正文不带 AAD），
从未有正式发布版本携带它，因此三端都已移除其读取分支：现在遇到 `001` 一律报
`Unsupported version`，而不是尝试解密。移除前的历史实现可参考该提交。

| 实现 | 写出 | 读取 | 预览 | `HEADER_TAG` 校验 |
| --- | --- | --- | --- | --- |
| CLI | `003` | `002` / `003` | 生成 + 读取 | 全量解密时校验 |
| Web（浏览器加密） | `003` | `002` / `003` | 生成 + 读取 | 不校验 |
| Android | `003` | `002` / `003` | 生成 + 读取 | 全量解密时校验，流式播放不校验 |

> 版本降级仍被拦住：`test_v1_version_is_rejected` 确保 `001` 被直接拒绝，
> `test_v3_relabelled_as_v2_is_rejected` 确保把 `003` 改标成 `002`（正文起点差 20 字节）
> 无法解密。

---

## 12. 测试向量

位置：`ske_android/app/src/test/resources/vectors/`，统一密码 `sakura-test-密码-123`。

| 文件 | 大小 (B) | 版本 | `THUMB_LEN` | SHA-256 |
| --- | --- | --- | --- | --- |
| `cli_empty.ske` | 50 | `002` | — | `d7100632cf756acc1ab9ed5861885ae3317e69174e291d8aefed0c8bd124c9b8` |
| `cli_empty.txt` | 0 | — | — | `e3b0c442…b7852b855` |
| `cli_sample.ske` | 2 300 098 | `002` | — | `1d1f31a3c0cdb7b5e295a2b325d8e851c5042373a0a1386e29b88b6dc978a7ce` |
| `cli_sample.txt` | 2 300 000 | — | — | `f22b9037120235adc709560a51e050a31f70802677749de40d9c05af99b598f6` |
| `cli_v003.ske` | 300 666 | `003` | 580 | `22ee39cf9206acc37a057bcc6b4dfbcab7f173f3c23a60310051f2144ee53174` |
| `cli_v003.txt` | 300 000 | — | — | `9093dcd1f479149cb8e21303213574dbf089123fc71b695bb8c3745c5e204186` |

体积可直接用公式反推验证：

- `cli_sample.ske = 50 + 2 300 000 + 3 * 16`
- `cli_v003.ske = 50 + 20 + 580 + 300 000 + 16`，其中 `580 = 564（JPEG）+ 16（标签）`
- `cli_v003.ske` 内的预览 JPEG 为 564 字节，SHA-256 为 `5dcc94925985cd211e78f7833c484c7b72c376ee502be9d1ca71fd3944e26760`

覆盖这些向量的测试：

- `ske_android/app/src/test/java/com/sakura/encryptor/core/crypto/SkeCryptoTest.kt`（密钥派生、文件名、nonce、几何、跨端解密、篡改检测）
- `ske_android/app/src/test/java/com/sakura/encryptor/core/media/PreviewEnvelopeTest.kt`（range 读取预览）
- `tests/test_crypto.py`（Python 侧的同一批断言）

### 12.1 三端对齐的验证现状

| 方向 | 手段 | 结果 |
| --- | --- | --- |
| CLI 自身 | `python -m pytest tests/` | 34 项通过 |
| CLI → Android | `SkeCryptoTest`（25 项，含跨端向量与篡改检测）+ `PreviewEnvelopeTest`（5 项） | 全部通过（整个单测套件 74 项、0 失败） |
| Web 写 → CLI 读 | Node 直接 import 真实 `useCrypto.js::encryptFileContent`，生成 0 B / 1 KB / 1 MiB / 2.5 MB 四种容器，再交给 `crypto.decrypt_file` 解密并校验 `HEADER_TAG` | 4/4 字节一致，版本均为 `003`、`bodyOffset = 70` |
| CLI 写 → Web 读 | Node 用 `vm` 加载真实 `sw-decrypt.js`，经 `/ske-local`、`/ske-thumb` 请求读取上面的向量 | `003` / `002` / 空文件均一致；`003` 预览 564 B 且 SHA-256 匹配；`002` 预览返回 404 |
| 文件名 token | Web `encryptName` 对 §10 的四个参考名 | 与向量逐字符一致 |

尚未覆盖的部分：

- Web 的**预览生成**（`createImageBitmap` / `<video>` 抓帧）依赖浏览器，只能在真机浏览器里验证；上表只验证了容器读写。
- `001` 支持已移除，其拒绝行为由 `tests/test_crypto.py::TestFormatVersion::test_v1_version_is_rejected` 覆盖；三端接受版本集合现统一为 `002` / `003`。
- CI（`.github/workflows/release.yml`）只跑 Android 单测；`pytest` 与 Web 都没有 CI 步骤，Web 端目前**没有测试文件**。上面两条 Node 交叉验证是手工脚本，建议固化为 `scripts/` 下的命令并接入 CI，否则 Web 侧的格式回归无人拦截。

---

## 13. 常见错误与排查

| 现象 | 原因 |
| --- | --- |
| 报 `Unsupported .ske version` | 头部 7–9 字节不是 `002/003`（例如早已废弃的 `001`），或文件不是 `.ske` |
| 报 `Corrupted thumbnail envelope` | v003 文件长度 < 70，或 `bytes[50:54] != "SKTH"` |
| 每块都能解密，最后报 `Header integrity check failed` | 正文块被整块替换/重排/删减（单块标签仍有效，只有 `HEADER_TAG` 能发现） |
| 整文件解密通过但播放到中途报错 | 该块被截断；检查发布端是否按 `ENC_BLOCK_SIZE` 完整存储 |
| 只有旧客户端生成的 `002` 文件没有缩略图 | 预期行为，`002` 无信封 |
| 文件名乱码 | 密码不同 → `name_key` 不同，token 解开是无效 UTF-8/GCM 标签失败 |
| Web 请求 0 字节 `.ske` 得到 `416` | Web SW 的 range 逻辑对零长容器返回 `Range Not Satisfiable`；Android 侧返回空流。仅影响空文件，不涉及格式 |

---

## 14. 修改格式的检查清单

格式变更必须**同时**落地到三端，并更新测试向量：

1. `ske_cli/crypto.py`（规范基准）—— 先改这里。
2. `ske_web/src/composables/useCrypto.js`（写）+ `ske_web/public/sw-decrypt.js`（读）。
3. `ske_android/.../core/crypto/SkeFormat.kt` / `SkeHeader.kt` / `SkeFileCipher.kt`。
4. 用 CLI 重新生成 `vectors/*.ske` 与对应明文，同步更新 `SkeCryptoTest.kt` 里的硬编码期望值。
5. `python -m pytest tests/` 与 `cd ske_android && ./gradlew test` 全绿。
6. 确认 `002` / `003` 仍可读（新版本只能作为**新增**分支，不能改写既有偏移语义）。
7. 更新本文档的 §2 布局、§3 常量表、§11 兼容性矩阵与 §12 向量表。

**不能做的事：**

- 不要把 `NAME_SALT` / `NAME_IV_SEED` 改成随机值。
- 不要把 `CHUNK_SIZE` 改成动态值或与文件相关的值（随机访问与已存文件都会失效）。
- 不要复用 `MASTER_IV` 作为信封 nonce。
- 不要把 `THUMB_LEN` 改成明文长度（读端按"含标签"解释）。
- 不要在未提升版本号的情况下改变 AAD 组成。
- 不要新增"不带正文 AAD"的版本：`001` 就是因此被废弃的。

---

## 15. 相关文档

- `README.md` / `README_EN.md` —— 产品能力与快速开始
- `docs/SKE-FORMAT.md` —— 本文（跨端字节级规范）
