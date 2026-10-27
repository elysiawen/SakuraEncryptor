"""
Sakura Encryptor Crypto Core
======================
AES-256-GCM encryption for file names and file content.
Chunked encryption supports random-access decryption in the browser.

File format (.ske):
    Header (55 bytes):
        MAGIC(8) | VERSION(3) | SALT(16) | MASTER_IV(12) | SHMAC(16)
    Options (22 bytes):
        KEY_CHECK(16) | KDF_ITERATIONS(5, big-endian) | FLAGS(1)
    Thumbnail envelope (22 + N bytes, always present):
        THUMB_MAGIC(5) | THUMB_NONCE(12) | THUMB_LEN(5, big-endian) | THUMB_CT(N)
        THUMB_LEN counts the ciphertext including its 16-byte GCM tag.
        THUMB_LEN == 0 means the file carries no thumbnail.
    Body (N blocks):
        each block = AES-256-GCM(chunk) | GCM_TAG(16)
        The last block may be shorter than CHUNK_SIZE.

SHMAC is HMAC-SHA256(key, header[0:39] | options | envelope | body)[:16].
Because it is keyed, a storage server cannot swap or truncate the body and
recompute the tag the way it could with a plain SHA-256 of the ciphertext.

KEY_CHECK is HMAC-SHA256(key, "ske-keycheck")[:16], compared in constant
time as soon as the key is derived, so a wrong password is reported without
reading a single block or the preview.

KDF_ITERATIONS is stored per file (5-byte big-endian) so the work factor can
be raised later without breaking existing containers. Readers reject values
above MAX_KDF_ITERATIONS so a hostile file cannot pin the CPU.

Version history:
    001 — the only version, and the only one this project ever shipped.
          Body blocks are encrypted with the 39-byte header prefix
          (MAGIC | VERSION | SALT | MASTER_IV) plus the options block
          supplied as AES-GCM additional authenticated data, so the
          key-defining fields and the KDF cost cannot be tampered with
          undetected. An optional encrypted thumbnail sits between the
          options and the body, so clients can render a preview from the
          first ~50 KiB without decrypting the whole file; the body
          therefore starts at 55 + 22 + 22 + THUMB_LEN.

    Earlier 002 / 003 numbers were pre-release working names: 002 lacked the
    thumbnail envelope, and the very first commit used an AAD-less header.
    Both were dropped before any release, so no container in the wild uses
    them and the version byte is now fixed at 001.

Name encryption is deterministic (fixed IV derived from key) so that
identical plain names always produce the same cipher-text, enabling
path reconstruction without a database.
"""

from __future__ import annotations

import hashlib
import hmac
import io
import os
import shutil
import subprocess
from base64 import urlsafe_b64decode, urlsafe_b64encode
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
MAGIC = b"SakuraEK"
VERSION = b"001"          # the only supported (and only ever released) version
SUPPORTED_VERSIONS = (VERSION,)

THUMB_MAGIC = b"SKETH"
THUMB_NONCE_SIZE = 12
THUMB_LEN_SIZE = 5
THUMB_HEADER_SIZE = len(THUMB_MAGIC) + THUMB_NONCE_SIZE + THUMB_LEN_SIZE  # 22
# File-browser tiles are ~300 CSS px square, i.e. ~600 physical pixels on a 2x
# display. 512 keeps them crisp without inflating the envelope (JPEG q80 ≈ 40 KB).
THUMB_MAX_SIDE = 512

# Video containers we can sample a frame from (ffmpeg is optional).
VIDEO_SUFFIXES = {
    ".mp4", ".m4v", ".mkv", ".mov", ".avi", ".webm",
    ".ts", ".m2ts", ".flv", ".wmv", ".mpg", ".mpeg", ".3gp",
}
VIDEO_TIMEOUT_S = 30

# Fixed salt for the deterministic file-name key.  It MUST stay constant:
# identical plain names have to encrypt to identical tokens so the browser can
# rebuild the encrypted directory tree without a database (see encrypt_name).
NAME_SALT = b"skenamesalt-00-s"

SALT_SIZE = 16
IV_SIZE = 12
TAG_SIZE = 16  # AES-GCM tag
SHMAC_SIZE = 16  # truncated HMAC-SHA256 over everything key- or payload-defining
KEY_CHECK_SIZE = 16  # truncated HMAC of a fixed label, used to test the password
KDF_ITERATIONS_SIZE = 5  # big-endian uint40, stored per file
OPTIONS_FLAGS_SIZE = 1
OPTIONS_SIZE = KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE + OPTIONS_FLAGS_SIZE  # 22
HEADER_SIZE = len(MAGIC) + len(VERSION) + SALT_SIZE + IV_SIZE + SHMAC_SIZE  # 8+3+16+12+16 = 55
AAD_PREFIX_SIZE = HEADER_SIZE - SHMAC_SIZE  # 39: MAGIC | VERSION | SALT | MASTER_IV
AAD_SIZE = AAD_PREFIX_SIZE + OPTIONS_SIZE  # 61: prefix + options, bound into every block
PROBE_SIZE = HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE  # 99: enough to locate the body
CHUNK_SIZE = 1 * 1024 * 1024  # 1 MiB per encrypted block
KDF_ITERATIONS = 100_000
MAX_KDF_ITERATIONS = 2_000_000  # reject hostile files that would stall the client
KEY_CHECK_LABEL = b"ske-keycheck"


# ---------------------------------------------------------------------------
# Key Derivation
# ---------------------------------------------------------------------------
def encode_thumb_length(length: int) -> bytes:
    """THUMB_LEN: 5-byte big-endian ciphertext length, GCM tag included."""
    return length.to_bytes(THUMB_LEN_SIZE, "big")


def encode_kdf_iterations(iterations: int) -> bytes:
    """KDF_ITERATIONS: 5-byte big-endian work factor."""
    return iterations.to_bytes(KDF_ITERATIONS_SIZE, "big")


def derive_key(password: str, salt: bytes, iterations: int = KDF_ITERATIONS) -> bytes:
    """
    Derive a 256-bit key from *password* and *salt* using PBKDF2-HMAC-SHA256.

    File encryption passes the work factor read from the container's options
    block. Name encryption keeps the default: its salt is a constant, so there
    is nowhere to store a per-file value.
    """
    if not 1 <= iterations <= MAX_KDF_ITERATIONS:
        raise ValueError(f"Unsupported KDF iteration count: {iterations}")
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=iterations,
    )
    return kdf.derive(password.encode("utf-8"))


def key_check(key: bytes) -> bytes:
    """The 16-byte password probe stored in the options block."""
    return hmac.new(key, KEY_CHECK_LABEL, hashlib.sha256).digest()[:KEY_CHECK_SIZE]


def encode_options(key: bytes, iterations: int) -> bytes:
    """Options block: key check | KDF iterations | reserved flags (zero)."""
    return key_check(key) + encode_kdf_iterations(iterations) + bytes(OPTIONS_FLAGS_SIZE)


def _decode_options(options: bytes) -> int:
    """Validate an options block and return its KDF iteration count."""
    if len(options) != OPTIONS_SIZE:
        raise ValueError("Corrupted options block")
    if any(options[KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE :]):
        raise ValueError("Unsupported .ske options flags")
    iterations = int.from_bytes(
        options[KEY_CHECK_SIZE : KEY_CHECK_SIZE + KDF_ITERATIONS_SIZE], "big"
    )
    if not 1 <= iterations <= MAX_KDF_ITERATIONS:
        raise ValueError(f"Unsupported KDF iteration count: {iterations}")
    return iterations


def _shmac(key: bytes, *parts: bytes) -> bytes:
    """Truncated HMAC-SHA256 over the given parts, in order."""
    mac = hmac.new(key, digestmod=hashlib.sha256)
    for part in parts:
        mac.update(part)
    return mac.digest()[:SHMAC_SIZE]


# ---------------------------------------------------------------------------
# Name Encryption (Deterministic)
# ---------------------------------------------------------------------------
def _name_iv(key: bytes) -> bytes:
    """Derive a fixed 12-byte IV from *key* for deterministic name encryption."""
    return hmac.new(key, b"ske-name-iv", hashlib.sha256).digest()[:IV_SIZE]


def encrypt_name(name: str, key: bytes) -> str:
    """Encrypt a single path component and return a URL-safe Base64 token."""
    aesgcm = AESGCM(key)
    iv = _name_iv(key)
    ct = aesgcm.encrypt(iv, name.encode("utf-8"), None)  # ct includes 16-byte tag
    return urlsafe_b64encode(ct).rstrip(b"=").decode("ascii")


def decrypt_name(token: str, key: bytes) -> str:
    """Decrypt a URL-safe Base64 token back to the original path component."""
    # Re-add padding
    padded = token + "=" * (-len(token) % 4)
    ct = urlsafe_b64decode(padded)
    aesgcm = AESGCM(key)
    iv = _name_iv(key)
    return aesgcm.decrypt(iv, ct, None).decode("utf-8")


# ---------------------------------------------------------------------------
# Path Encryption / Decryption
# ---------------------------------------------------------------------------
def encrypt_path(plain_path: str, key: bytes) -> str:
    """Encrypt each component of a '/'-separated path independently."""
    parts = [p for p in plain_path.replace("\\", "/").split("/") if p]
    encrypted = [encrypt_name(p, key) for p in parts]
    return "/".join(encrypted)


def decrypt_path(enc_path: str, key: bytes) -> str:
    """Decrypt each component of an encrypted path."""
    parts = [p for p in enc_path.replace("\\", "/").split("/") if p]
    decrypted = [decrypt_name(p, key) for p in parts]
    return "/".join(decrypted)


# ---------------------------------------------------------------------------
# Block-level nonce derivation
# ---------------------------------------------------------------------------
def _block_nonce(master_iv: bytes, block_index: int) -> bytes:
    """
    Derive a unique 12-byte nonce for block *block_index* by XOR-ing
    the master IV with the block index (big-endian, zero-padded to 12 bytes).
    """
    idx_bytes = block_index.to_bytes(12, "big")
    return bytes(a ^ b for a, b in zip(master_iv, idx_bytes))


# ---------------------------------------------------------------------------
# File Encryption (Chunked)
# ---------------------------------------------------------------------------
def encrypt_file(
    src: str | Path,
    dst: str | Path,
    password: str,
    thumbnail: bytes | None = None,
) -> None:
    """
    Encrypt *src* into *dst* using the .ske chunked format (version 001).

    Each 1 MiB plaintext block is independently encrypted with AES-256-GCM.
    The header stores salt + master IV so the web player can derive the key
    and seek to any block.

    A small JPEG preview is encrypted in the envelope between the header and
    the body. Pass *thumbnail* to supply one; leave it as None to generate a
    preview automatically for image sources.
    """
    src = Path(src)
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    salt = os.urandom(SALT_SIZE)
    master_iv = os.urandom(IV_SIZE)
    iterations = KDF_ITERATIONS
    key = derive_key(password, salt, iterations)
    aesgcm = AESGCM(key)

    # MAGIC | VERSION | SALT | MASTER_IV followed by the options block: both are
    # bound into every block as AAD, so neither can be swapped underneath.
    header_prefix = MAGIC + VERSION + salt + master_iv  # AAD_PREFIX_SIZE bytes
    options = encode_options(key, iterations)
    aad = header_prefix + options  # AAD_SIZE bytes

    if thumbnail is None:
        thumbnail = generate_thumbnail(src)

    thumb_nonce = os.urandom(THUMB_NONCE_SIZE)
    if thumbnail:
        thumb_aad = aad + THUMB_MAGIC + encode_thumb_length(len(thumbnail) + TAG_SIZE)
        thumb_ct = aesgcm.encrypt(thumb_nonce, thumbnail, thumb_aad)
    else:
        thumb_ct = b""

    envelope = THUMB_MAGIC + thumb_nonce + encode_thumb_length(len(thumb_ct)) + thumb_ct

    with open(src, "rb") as fin, open(dst, "wb") as fout:
        # Reserve the header (SHMAC is only known at the end), then options and
        # the envelope. The body starts right after them.
        fout.write(bytes(HEADER_SIZE))
        fout.write(options)
        fout.write(envelope)

        block_index = 0
        body_mac = hmac.new(key, digestmod=hashlib.sha256)
        body_mac.update(header_prefix)
        body_mac.update(options)
        body_mac.update(envelope)

        while True:
            chunk = fin.read(CHUNK_SIZE)
            if not chunk:
                break
            nonce = _block_nonce(master_iv, block_index)
            ct = aesgcm.encrypt(nonce, chunk, aad)  # ciphertext + tag
            fout.write(ct)
            body_mac.update(ct)
            block_index += 1

        fout.seek(0)
        fout.write(header_prefix)
        fout.write(body_mac.digest()[:SHMAC_SIZE])


def generate_thumbnail(
    src: str | Path,
    max_side: int = THUMB_MAX_SIDE,
) -> bytes | None:
    """
    Return a small JPEG preview of *src*, or None when none can be produced.

    Images are decoded with Pillow; videos are sampled with ffmpeg. Both are
    optional — without them encryption still works, the file simply carries no
    preview (THUMB_LEN = 0).

    None is also returned when the source is already no larger than *max_side*
    or when the preview would cost more than a quarter of the source file, so
    small files are never made proportionally bigger by their own preview.
    """
    src = Path(src)
    try:
        src_size = src.stat().st_size
    except OSError:
        return None
    if src_size <= 0:
        return None

    if src.suffix.lower() in VIDEO_SUFFIXES:
        data = _video_thumbnail(src, max_side)
    else:
        data = _image_thumbnail(src, max_side)

    # Keep the envelope cheap: skip degenerate previews, and never spend more
    # than a quarter of the payload on one — a tiny source is cheaper to load
    # whole than to preview.
    if not data or len(data) < 128 or len(data) * 4 >= src_size:
        return None
    return data


def _jpeg_preview(image, max_side: int, *, skip_small: bool = False) -> bytes | None:
    """
    Downscale a Pillow image into a JPEG no larger than *max_side*.

    *skip_small* makes a preview pointless for images that already fit: only
    still pictures benefit from that rule, because a small video frame still
    saves the player from decoding one.
    """
    try:
        if skip_small and max(image.size) <= max_side:
            return None
        image = image.convert("RGB")
        image.thumbnail((max_side, max_side))
        out = io.BytesIO()
        image.save(out, format="JPEG", quality=80)
        return out.getvalue()
    except Exception:
        return None


def _image_thumbnail(src: Path, max_side: int) -> bytes | None:
    try:
        from PIL import Image
    except ImportError:
        return None

    try:
        with Image.open(src) as image:
            if image.format not in ("JPEG", "PNG", "WEBP", "BMP"):
                return None
            # Already small enough: a preview would only be an upscale.
            return _jpeg_preview(image, max_side, skip_small=True)
    except Exception:
        return None


def _video_thumbnail(src: Path, max_side: int) -> bytes | None:
    """
    Grab a frame from a video.

    PyAV is preferred: it bundles its own decoders, so `pip install .[video]`
    is enough and no system ffmpeg is required. A system ffmpeg is still used
    as a fallback for setups that already have one.
    """
    data = _pyav_thumbnail(src, max_side)
    if data is not None:
        return data
    return _ffmpeg_thumbnail(src, max_side)


def _pyav_thumbnail(src: Path, max_side: int) -> bytes | None:
    """Sample the frame near 30% with PyAV; None when it is unavailable/fails."""
    try:
        import av
    except ImportError:
        return None

    try:
        with av.open(str(src)) as container:
            if not container.streams.video:
                return None
            stream = container.streams.video[0]

            duration = 0.0
            if container.duration:
                duration = container.duration / av.time_base
            elif stream.duration and stream.time_base:
                duration = float(stream.duration * stream.time_base)

            target = duration * 0.3 if duration > 0 else 0.0
            if target > 0:
                # Lands on the nearest keyframe at/just before the target —
                # close enough visually, and it avoids decoding a whole GOP.
                container.seek(int(target * av.time_base), backward=True, any_frame=False)

            frame = next(container.decode(stream), None)
            if frame is None:
                return None
            image = frame.to_image()
    except Exception:
        return None

    return _jpeg_preview(image, max_side)


def _ffmpeg_thumbnail(src: Path, max_side: int) -> bytes | None:
    """Sample the frame near 30% with a system ffmpeg; None when absent/fails."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        return None

    seek = _video_seek_seconds(src)
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
    ]
    tail = [
        "-i", str(src),
        "-frames:v", "1",
        "-vf", f"scale={max_side}:{max_side}:force_original_aspect_ratio=decrease",
        "-f", "image2pipe",
        "-vcodec", "mjpeg",
        "-q:v", "3",
        "-",
    ]

    # First try the sampled frame, then fall back to the very first one.
    attempts = [["-ss", f"{seek:.3f}"], []] if seek > 0 else [[]]

    for seek_args in attempts:
        try:
            proc = subprocess.run(
                command + seek_args + tail,
                capture_output=True,
                timeout=VIDEO_TIMEOUT_S,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            return None
        if proc.returncode == 0 and proc.stdout:
            return proc.stdout
    return None


def _video_seek_seconds(src: Path) -> float:
    """
    Seek 30% into the clip — past the intro, still representative.

    Matches the browser's poster capture, so a video looks the same whether the
    preview came from the envelope or was grabbed at playback time.
    """
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        return 1.0

    try:
        proc = subprocess.run(
            [
                ffprobe, "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                str(src),
            ],
            capture_output=True,
            timeout=15,
            check=False,
        )
        duration = float(proc.stdout.decode("utf-8", "ignore").strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        return 1.0

    if duration <= 0:
        return 0.0
    return duration * 0.3


def decrypt_thumbnail(src: str | Path, password: str) -> bytes | None:
    """Return the preview stored in the envelope, or None when it is empty."""
    with open(src, "rb") as fin:
        prefix = _read_prefix(fin)

    if not prefix.thumb_ct:
        return None

    key = derive_key(password, prefix.salt, prefix.iterations)
    _verify_key_check(prefix, key)

    aad = (
        prefix.header[:AAD_PREFIX_SIZE]
        + prefix.options
        + THUMB_MAGIC
        + encode_thumb_length(len(prefix.thumb_ct))
    )
    try:
        return AESGCM(key).decrypt(prefix.thumb_nonce, prefix.thumb_ct, aad)
    except Exception as exc:
        raise ValueError(
            "Thumbnail decryption failed. Wrong password or corrupted file."
        ) from exc


@dataclass(frozen=True)
class _Prefix:
    """Everything before the body: header, options and thumbnail envelope."""

    header: bytes
    options: bytes
    envelope: bytes
    salt: bytes
    master_iv: bytes
    iterations: int
    thumb_nonce: bytes
    thumb_ct: bytes
    body_offset: int


def _verify_key_check(prefix: _Prefix, key: bytes) -> None:
    """Reject a wrong password immediately instead of after the first block."""
    if not hmac.compare_digest(prefix.options[:KEY_CHECK_SIZE], key_check(key)):
        raise ValueError("Wrong password.")


def _read_prefix(fin) -> _Prefix:
    """
    Read the header, the options block and the thumbnail envelope.

    The file position is left at the first body block.
    """
    header = fin.read(HEADER_SIZE)
    if len(header) < HEADER_SIZE:
        raise ValueError("File too small to be a valid .ske")

    magic = header[0 : len(MAGIC)]
    version = header[len(MAGIC) : len(MAGIC) + len(VERSION)]
    if magic != MAGIC:
        raise ValueError(f"Invalid magic number: {magic!r}")
    if version not in SUPPORTED_VERSIONS:
        raise ValueError(f"Unsupported version: {version!r}")

    salt = header[len(MAGIC) + len(VERSION) : len(MAGIC) + len(VERSION) + SALT_SIZE]
    master_iv = header[AAD_PREFIX_SIZE - IV_SIZE : AAD_PREFIX_SIZE]

    options = fin.read(OPTIONS_SIZE)
    if len(options) < OPTIONS_SIZE:
        raise ValueError("File too small to be a valid .ske")
    iterations = _decode_options(options)

    envelope = fin.read(THUMB_HEADER_SIZE)
    if len(envelope) < THUMB_HEADER_SIZE or envelope[: len(THUMB_MAGIC)] != THUMB_MAGIC:
        raise ValueError("Corrupted thumbnail envelope")
    thumb_nonce = envelope[len(THUMB_MAGIC) : len(THUMB_MAGIC) + THUMB_NONCE_SIZE]
    thumb_len = int.from_bytes(
        envelope[len(THUMB_MAGIC) + THUMB_NONCE_SIZE :], "big"
    )
    thumb_ct = b""
    if thumb_len:
        thumb_ct = fin.read(thumb_len)
        if len(thumb_ct) != thumb_len:
            raise ValueError("Truncated thumbnail envelope")

    return _Prefix(
        header=header,
        options=options,
        envelope=envelope + thumb_ct,
        salt=salt,
        master_iv=master_iv,
        iterations=iterations,
        thumb_nonce=thumb_nonce,
        thumb_ct=thumb_ct,
        body_offset=fin.tell(),
    )


def decrypt_file(src: str | Path, dst: str | Path, password: str) -> None:
    """
    Decrypt a .ske file back to its original content.
    Raises ``ValueError`` on wrong password or corrupted data.
    """
    src = Path(src)
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    with open(src, "rb") as fin:
        prefix = _read_prefix(fin)

        key = derive_key(password, prefix.salt, prefix.iterations)
        _verify_key_check(prefix, key)

        # Every block authenticates the key-defining fields plus the options.
        aad = prefix.header[:AAD_PREFIX_SIZE] + prefix.options
        aesgcm = AESGCM(key)

        # Each encrypted block = CHUNK_SIZE + TAG_SIZE bytes (except possibly last)
        enc_block_size = CHUNK_SIZE + TAG_SIZE

        mac = hmac.new(key, digestmod=hashlib.sha256)
        mac.update(prefix.header[:AAD_PREFIX_SIZE])
        mac.update(prefix.options)
        mac.update(prefix.envelope)

        with open(dst, "wb") as fout:
            block_index = 0

            while True:
                ct = fin.read(enc_block_size)
                if not ct:
                    break
                mac.update(ct)
                nonce = _block_nonce(prefix.master_iv, block_index)
                try:
                    plaintext = aesgcm.decrypt(nonce, ct, aad)
                except Exception as exc:
                    raise ValueError(
                        f"Decryption failed at block {block_index}. "
                        "Wrong password or corrupted file."
                    ) from exc
                fout.write(plaintext)
                block_index += 1

            if not hmac.compare_digest(mac.digest()[:SHMAC_SIZE], prefix.header[AAD_PREFIX_SIZE:]):
                raise ValueError(
                    "Integrity check failed (SHMAC mismatch). File may be corrupted."
                )
