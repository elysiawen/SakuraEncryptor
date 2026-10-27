# SKE 容器格式规范（版本 001）

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
│ Header        55 B   MAGIC(8) VERSION(3) SALT(16)        │
│                      MASTER_IV(12) SHMAC(16)             │
├──────────────────────────────────────────────────────────┤
│ Options       22 B   KEY_CHECK(16) ITERATIONS(5) FLAGS(1)│
├──────────────────────────────────────────────────────────┤
│ Envelope      22 + N B（总是存在）                        │
│                     "SKETH" THUMB_NONCE(12) LEN(5) CT(N) │
├──────────────────────────────────────────────────────────┤
│ Body          N 块，每块 AES-256-GCM(明文块) || TAG(16)  │
│                      除最后一块外，明文块恒为 1 MiB      │
└──────────────────────────────────────────────────────────┘
```

设计目标：

- **随机访问**：头部自带 `SALT` 与 `MASTER_IV`，任何偏移的块都能独立解密，因此播放器拖动进度条只需拉取所覆盖的块。
- **零知识**：密码不落盘，仅内存中存在派生的 256 位密钥。
- **自校验**：每块带独立 GCM 标签；全文件解密额外校验 keyed 的 `SHMAC`；`KEY_CHECK` 让"密码错了"在读第一块之前就能判定。

---

## 2. 字节布局

### 2.1 Header（固定 55 字节）

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 0 | 8 | `MAGIC` | ASCII `SakuraEK` |
| 8 | 3 | `VERSION` | ASCII `001` |
| 11 | 16 | `SALT` | 文件级随机盐（PBKDF2 输入） |
| 27 | 12 | `MASTER_IV` | 块 nonce 的基值 |
| 39 | 16 | `SHMAC` | `HMAC-SHA256(key, prefix‖options‖envelope‖body)[:16]` |

`HEADER_SIZE = 8 + 3 + 16 + 12 + 16 = 55`，其中 `[0,39)` 是"定义密钥的前缀"（`AAD_PREFIX_SIZE = 39`）。

### 2.2 Options（固定 22 字节，紧跟头部）

| 偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 55 | 16 | `KEY_CHECK` | `HMAC-SHA256(key, "ske-keycheck")[:16]`，常量时间比较 |
| 71 | 5 | `KDF_ITERATIONS` | 大端 uint40，写端写入本次使用的 PBKDF2 迭代数 |
| 76 | 1 | `FLAGS` | 保留位，必须为 0 |

`OPTIONS_SIZE = 16 + 5 + 1 = 22`。

- `KEY_CHECK` 让读端在派生密钥后立刻判断密码是否正确，无需读任何块或预览。
- `KDF_ITERATIONS` 必须落在 `1 ..= 2 000 000`，否则读端拒绝，防止恶意文件拖死 CPU。
- 任何非 0 的保留位同样被拒绝（`Unsupported .ske options`）。

### 2.3 Thumbnail envelope（总是存在，位于 options 与正文之间）

| 绝对偏移 | 长度 | 字段 | 说明 |
| --- | --- | --- | --- |
| 77 | 5 | `THUMB_MAGIC` | ASCII `SKETH` |
| 82 | 12 | `THUMB_NONCE` | 信封独立 nonce，随机 |
| 94 | 5 | `THUMB_LEN` | 大端 uint40，**含 16 字节 GCM 标签**的密文长度 |
| 99 | `THUMB_LEN` | `THUMB_CT` | 加密后的 JPEG 预览 |

`THUMB_MAGIC_SIZE = 5`、`THUMB_LEN_SIZE = 5`，`THUMB_ENVELOPE_SIZE = 5 + 12 + 5 = 22`，`PROBE_SIZE = 55 + 22 + 22 = 99`。

- 信封**总是**写入，即使是空文件；`THUMB_LEN == 0` 表示该文件没有预览（写端无法生成预览时就是这种形态）。
- 因此正文起点恒为 `99 + THUMB_LEN`，不存在"正文紧跟 options"的布局。
- `THUMB_NONCE` 在 `THUMB_LEN == 0` 时仍存在（随机填充），读端应忽略。

### 2.4 Body

```
block i = GCM_CT(明文块 i) || GCM_TAG(16)
```

- 明文块大小恒为 `CHUNK_SIZE = 1 MiB = 1 048 576`，最后一块为剩余字节（可能为 0 字节，即空文件没有任何块）。
- 密文块大小恒为 `ENC_BLOCK_SIZE = CHUNK_SIZE + 16 = 1 048 592`，最后一块可能更短。
- 空文件：只有头部 + options + 信封（共 99 字节），没有正文块。

---

## 3. 常量表

| 名称 | 值 | 说明 |
| --- | --- | --- |
| `MAGIC` | `SakuraEK` | 8 字节 ASCII |
| `VERSION`（写端） | `001` | 三端写出且唯一接受的版本 |
| `THUMB_MAGIC` | `SKETH` | 5 字节 ASCII |
| `SALT_SIZE` | 16 | |
| `IV_SIZE` | 12 | |
| `TAG_SIZE` | 16 | AES-GCM 标签（128 bit） |
| `SHMAC_SIZE` | 16 | 截断的 HMAC-SHA256 |
| `KEY_CHECK_SIZE` | 16 | |
| `KDF_ITERATIONS_SIZE` | 5 | 大端 uint40 |
| `OPTIONS_FLAGS_SIZE` | 1 | 必须为 0 |
| `OPTIONS_SIZE` | 22 | `16 + 5 + 1` |
| `HEADER_SIZE` | 55 | `8 + 3 + 16 + 12 + 16` |
| `AAD_PREFIX_SIZE` | 39 | `HEADER_SIZE - SHMAC_SIZE` |
| `AAD_SIZE` | 61 | `39 + 22`，正文块的 AAD 长度 |
| `THUMB_MAGIC_SIZE` | 5 | `SKETH` 的字节长度 |
| `THUMB_LEN_SIZE` | 5 | 大端 uint40 |
| `THUMB_ENVELOPE_SIZE` | 22 | `5 + 12 + 5` |
| `PROBE_SIZE` | 99 | 一次读取即可定位正文起点 |
| `CHUNK_SIZE` | 1 MiB | 明文块 |
| `ENC_BLOCK_SIZE` | 1 MiB + 16 | 密文块 |
| `KDF_ITERATIONS` | 100 000 | 写端当前使用的 PBKDF2 迭代数 |
| `MAX_KDF_ITERATIONS` | 2 000 000 | 读端接受上限 |
| `KEY_BITS` | 256 | |
| `NAME_SALT` | `skenamesalt-00-s` | 文件名密钥的固定盐（ASCII，16 B） |
| `NAME_IV_SEED` | `ske-name-iv` | 推导文件名固定 IV 的 HMAC 输入 |
| 文件后缀 | `.ske` | 加密后的文件名 = token + `.ske` |

---

## 4. 密钥派生

```
key = PBKDF2-HMAC-SHA256(password = UTF-8(密码), salt = SALT, iterations = KDF_ITERATIONS(文件内记录), dkLen = 32)
```

- 密码按 **UTF-8** 编码（含中文、emoji），三端必须一致。
- `SALT` 每个文件随机生成（16 字节），因此相同密码 + 相同明文 → 不同密文。
- 文件密钥的迭代数**来自文件本身**（options 块），这样以后提高成本不会让旧容器失效。
- 文件名使用**另一把**密钥（见 §10），盐为常量，没有地方存 per-file 参数，因此固定用 `100 000`。
- 应用锁凭据同样固定用 `100 000`，与文件格式解耦。

---

## 5. AAD 规则

AAD 参与 GCM 认证、不随密文存储，只有两种情形：

| 对象 | AAD |
| --- | --- |
| 正文块 | `bytes[0:39] ‖ bytes[55:77]`，即 `MAGIC‖VERSION‖SALT‖MASTER_IV` 加上 options 块 |
| 预览信封（`THUMB_LEN > 0`） | 正文 AAD `‖ "SKETH" ‖ BE40(THUMB_LEN)` |

要点：

- 正文 AAD **不含** `SHMAC`，也不含信封内容。这样写端可以最后回填 `SHMAC`（见 §7），而不必重算正文。
- options 被绑定进 AAD，因此 `KDF_ITERATIONS` 与 `KEY_CHECK` 都不能被掉包；否则派生出的密钥会不同，正文直接认证失败。
- 信封 AAD 把 `THUMB_LEN` 绑定进去，避免"改长度后切片错位"却不被察觉；写端计算时用的是**密文长度**（明文长度 + 16）。
- AAD 是无条件的：没有"无 AAD"的容器形态，读端不需要分支判断。
- 注意 AAD 不是文件上的连续区间：`SHMAC(16 B)` 夹在前缀与 options 之间，读端必须**拼接**两段，不能直接 `slice(0, 61)`。

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

设容器总长为 `S`，`bodyOffset` 为正文起点（`99 + THUMB_LEN`，最小 99）：

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

## 7. 完整性标签 SHMAC

```
SHMAC = HMAC-SHA256(key, bytes[0:39] ‖ options ‖ envelope ‖ 全部密文块)[:16]
```

- 输入覆盖"定义密钥的前缀 + options + 整个信封（含预览密文）+ 正文所有密文块"，顺序与磁盘一致。
- 使用 HMAC（而不是裸 SHA-256），因此存储服务端即使能改写密文，也无法重算标签。
- 写端因此先写 55 字节零占位 + options + 信封，流式写正文并同步喂给 MAC，最后 `seek(0)` 回填前缀与 `SHMAC`。
- 空文件的 MAC 输入非空（前缀 + options + 空信封），写端照常计算。

**校验强度差异（有意为之）：**

| 路径 | 是否校验 `SHMAC` |
| --- | --- |
| CLI 全文件解密 | 是（`hmac.compare_digest`） |
| Android 全文件解密 `SkeFileCipher.decryptFile` | 是（常量时间比较） |
| Android 流式播放 `SkeBlockDecryptor` | 否 —— 校验它要读完整文件，与随机访问矛盾 |
| Web Service Worker | 否 —— 同上 |

因此"能播放"只证明**用到的那些块**未被篡改且密码正确；整文件完整性需要下载后走全量解密。

---

## 8. 预览信封

### 8.1 加密

```python
thumb_aad = header_prefix + options + b"SKETH" + encode_thumb_length(len(thumb_ct))
thumb_ct  = AESGCM(key).encrypt(thumb_nonce, thumb_plaintext, thumb_aad)   # 自带 16 B 标签
```

`header_prefix` 是 39 字节的 `MAGIC‖VERSION‖SALT‖MASTER_IV`，`options` 是 22 字节 options 块。

### 8.2 解密（只读 99 + N 字节）

1. range 读取 `[0, 99)`；
2. 校验 `bytes[0:8] == "SakuraEK"`、`bytes[8:11] == "001"`，否则本路径直接放弃；
3. 校验 `bytes[77:82] == "SKETH"`，否则视为信封损坏；
4. 解析 `THUMB_LEN = BE40(bytes[94:99])`；为 0 则返回"无预览"；
5. range 读取 `[99, 99 + THUMB_LEN)`；
6. 用 §5 的信封 AAD 解密，输出 JPEG 字节。

一次预览请求的总流量是 `99 + THUMB_LEN`（通常 ≈ 40 KB），而不是解码所需的 1 MiB 首块。密码错误时表现为 `KEY_CHECK` 或标签校验失败，上层通常降级为"无预览"而不是报错（见 `EnvelopeReader.read`）。

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

- `NAME_SALT` 与 `NAME_IV_SEED` 是**常量**，改动会让所有历史文件名的密文失效（磁盘上没有数据库可以重算）。当前 `NAME_SALT = skenamesalt-00-s`（由 `ske-name-salt-00` 变更而来，旧目录需重新加密或改名）。
- 固定盐 + 固定 IV ⇒ 相同明文分量 → 相同 token（确定性；这本身泄露"同名文件"信息，是有意取舍）。换一个盐字符串不会提升任何安全性。
- token 字母表为 URL-safe Base64 且去掉 `=`，可直接作为 URL 路径与文件名。
- 落盘文件名 = `token + ".ske"`；解密时先去掉 `.ske` 再解 token。
- 加密路径时按 `/` 拆分、丢弃空分量、忽略反斜杠差异（`\` 归一化为 `/`）。

参考向量（密码 `sakura-pass`，`name_key = cc20be52…ba831c6`）：

| 明文 | token |
| --- | --- |
| `hello.mp4` | `-WDapQ3GsjxJfy6fEt-tJuO1fQSTvy6RgA` |
| `视频 测试.mkv` | `eaIwIMB5_6rIsHX-E9ylPnPDHCs2SH8ac7ZOiZ7zCk_b` |
| `樱花` | `d60HIehZAmgeaRpqWbNNYSvR13deXw` |
| `a` | `8F07rQb78nknIdjJwUquKi0` |

---

## 11. 版本与兼容性

本格式只有一个版本：`001`。

| 版本 | 正文 AAD | 预览信封 | 说明 |
| --- | --- | --- | --- |
| `001` | 39 字节前缀 + 22 字节 options（61 B） | 总是存在 | 唯一版本，也是唯一发布过的版本 |

早期编号都是发布前的工作代号，现已全部移除：

| 旧编号 | 与 `001` 的差别 | 状态 |
| --- | --- | --- |
| 首个提交（无 AAD 形态） | 正文块不带 AAD | 已移除；遇到即报 `Unsupported version` |
| `002` | 没有预览信封，正文紧跟头部 | 已移除；同上 |
| `003` | 与 `001` 完全相同，只是版本字节不同 | 已重命名为 `001` |

| 实现 | 写出 | 读取 | 预览 | `SHMAC` 校验 |
| --- | --- | --- | --- | --- |
| CLI | `001` | `001` | 生成 + 读取 | 全量解密时校验 |
| Web（浏览器加密） | `001` | `001` | 生成 + 读取 | 不校验 |
| Android | `001` | `001` | 生成 + 读取 | 全量解密时校验，流式播放不校验 |

> 其他版本字节一律拒绝（`TestFormatVersion::test_other_version_bytes_are_rejected` 覆盖
> `000` / `002` / `003` / `999`），把信封剪掉的 002 布局也无法解析
> （`test_container_without_envelope_is_rejected`）。

---

## 12. 测试向量

位置：`ske_android/app/src/test/resources/vectors/`，统一密码 `sakura-test-密码-123`，全部由 CLI 生成。

| 文件 | 大小 (B) | `THUMB_LEN` | SHA-256 |
| --- | --- | --- | --- |
| `cli_empty.ske` | 99 | 0 | `0053df307f97c953f6297c2b1dda38b5460b52fce94924cb9c8d4119ecdddd41` |
| `cli_empty.txt` | 0 | — | `e3b0c442…b7852b855` |
| `cli_sample.ske` | 2 300 147 | 0 | `6a28f7281c32a5cdd11198abaee865113aa6d62ddd1e4eccc70633eb759e31e0` |
| `cli_sample.txt` | 2 300 000 | — | `f22b9037120235adc709560a51e050a31f70802677749de40d9c05af99b598f6` |
| `cli_preview.ske` | 300 695 | 580 | `94c8446e398926d8e4596dd07c5ced93e42c95b10f9e21404d89a586b26883b4` |
| `cli_preview.txt` | 300 000 | — | `f2515fbb0c0e34bfb43b0fa75610932abc9552a249eac5f5b304dd3cb6104ddb` |

三个容器的版本字节都是 `001`，体积可直接用公式反推验证：

- `cli_empty.ske = 99`（头部 + options + 空信封，没有正文块）
- `cli_sample.ske = 99 + 2 300 000 + 3 * 16`
- `cli_preview.ske = 99 + 580 + 300 000 + 16`，其中 `580 = 564（JPEG）+ 16（标签）`
- `cli_preview.ske` 内的预览 JPEG 为 564 字节，SHA-256 为 `5dcc94925985cd211e78f7833c484c7b72c376ee502be9d1ca71fd3944e26760`

覆盖这些向量的测试：

- `ske_android/app/src/test/java/com/sakura/encryptor/core/crypto/SkeCryptoTest.kt`（密钥派生、文件名、nonce、几何、跨端解密、篡改检测）
- `ske_android/app/src/test/java/com/sakura/encryptor/core/media/PreviewEnvelopeTest.kt`（range 读取预览）
- `tests/test_crypto.py`（Python 侧的同一批断言）

### 12.1 三端对齐的验证现状

| 方向 | 手段 | 结果 |
| --- | --- | --- |
| CLI 自身 | `python -m pytest tests/` | 40 项通过 |
| CLI → Android | `SkeCryptoTest`（25 项，含跨端向量与篡改检测）+ `PreviewEnvelopeTest`（5 项） | 全部通过（整个单测套件 74 项、0 失败） |
| Android 写 → CLI 读 | Kotlin `SkeFileCipher.encryptFile` 生成容器（含缩略图），交给 `crypto.decrypt_file` / `decrypt_thumbnail` | 明文与预览均一致 |
| Web 写 → CLI 读 | Node 直接 import 真实 `useCrypto.js::encryptFileContent`，生成 0 B / 1.5 MB 容器，再交给 `crypto.decrypt_file` 解密 | 字节一致，版本均为 `001` |
| CLI 写 → Web 读 | Node 通过 stub（`self` / `fetch`）加载真实 `sw-decrypt.js`，调用 `createSession` / `handleFullRequest` / `handleRangeRequest` | 全量与 range 明文一致；错误密码被 `KEY_CHECK` 提前拒绝 |

尚未覆盖的部分：

- Web 的**预览生成**（`createImageBitmap` / `<video>` 抓帧）依赖浏览器，只能在真机浏览器里验证；上表只验证了容器读写。
- 三端只接受 `001`，其他版本字节（含旧编号 `002` / `003`）一律拒绝，由
  `tests/test_crypto.py::TestFormatVersion::test_other_version_bytes_are_rejected` 覆盖。
- CI（`.github/workflows/release.yml`）只跑 Android 单测；`pytest` 与 Web 都没有 CI 步骤，Web 端目前**没有测试文件**。上面两条 Node 交叉验证是手工脚本，建议固化为 `scripts/` 下的命令并接入 CI，否则 Web 侧的格式回归无人拦截。

---

## 13. 常见错误与排查

| 现象 | 原因 |
| --- | --- |
| 报 `Unsupported .ske version` | 头部 8–10 字节不是 `001`（包括旧编号 `002` / `003`），或文件不是 `.ske` |
| 报 `Invalid magic number` | 前 8 字节不是 `SakuraEK`（例如旧容器用的是 `SakuraE`） |
| 报 `Wrong password.` | `KEY_CHECK` 常量时间比较失败，密码错误（无需解密正文） |
| 报 `Unsupported .ske options` | options 保留位非 0，或 `KDF_ITERATIONS` 超出 `1..2 000 000` |
| 报 `Corrupted thumbnail envelope` | 容器长度 < 99，或 `bytes[77:82] != "SKETH"` |
| 每块都能解密，最后报 `SHMAC mismatch` | 正文块被整块替换/重排/删减（单块标签仍有效，只有 keyed `SHMAC` 能发现） |
| 整文件解密通过但播放到中途报错 | 该块被截断；检查发布端是否按 `ENC_BLOCK_SIZE` 完整存储 |
| 列表里没有缩略图 | 该容器的 `THUMB_LEN = 0`（写端未能生成预览），属预期行为 |
| 文件名乱码 | 密码不同 → `name_key` 不同，token 解开是无效 UTF-8/GCM 标签失败 |
| Web 请求 0 字节 `.ske` 得到 `416` | Web SW 的 range 逻辑对零长容器返回 `Range Not Satisfiable`；Android 侧返回空流。仅影响空文件，不涉及格式 |

---

## 14. 修改格式的检查清单

格式变更必须**同时**落地到三端，并更新测试向量：

1. `ske_cli/crypto.py`（规范基准）—— 先改这里。
2. `ske_web/src/composables/useCrypto.js`（写）+ `ske_web/public/sw-decrypt.js`（读，含 `SALT_AT` / `IV_AT` / `ENVELOPE_START` 等偏移常量）。
3. `ske_android/.../core/crypto/SkeFormat.kt` / `SkeHeader.kt` / `SkeFileCipher.kt`。
4. 用 CLI 重新生成 `vectors/*.ske` 与对应明文，同步更新 `SkeCryptoTest.kt` 里的硬编码期望值。
5. `python -m pytest tests/` 与 `cd ske_android && ./gradlew test` 全绿。
6. 确认 `001` 仍可读（要变更就新增版本号，不能改写既有偏移语义）。
7. 更新本文档的 §2 布局、§3 常量表、§11 兼容性矩阵与 §12 向量表。

**不能做的事：**

- 不要把 `NAME_SALT` / `NAME_IV_SEED` 改成随机值：它们是文件名确定性加密的一部分，随机值会让目录树彻底无法重建。确有需要变更常量时，必须一次性接受历史文件名全部失效，并同步改三端与本文档 §10 的参考 token。
- 不要把 `CHUNK_SIZE` 改成动态值或与文件相关的值（随机访问与已存文件都会失效）。
- 不要复用 `MASTER_IV` 作为信封 nonce。
- 不要把 `THUMB_LEN` 改成明文长度（读端按"含标签"解释）。
- 不要在未提升版本号的情况下改变 AAD 组成或 options 布局。
- 不要新增"不带正文 AAD"的版本：`001` 就是因此被废弃的。

---

## 15. 相关文档

- `README.md` / `README_EN.md` —— 产品能力与快速开始
- `docs/SKE-FORMAT.md` —— 本文（跨端字节级规范）
