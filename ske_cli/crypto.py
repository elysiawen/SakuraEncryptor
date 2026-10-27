"""
Sakura Encryptor Crypto Core
======================
AES-256-GCM encryption for file names and file content.
Chunked encryption supports random-access decryption in the browser.

File format (.ske):
    Header (50 bytes):
        MAGIC(7) | VERSION(3) | SALT(16) | MASTER_IV(12) | HEADER_TAG(12)
    Thumbnail envelope (20 + N bytes, v003 only):
        THUMB_MAGIC(4) | THUMB_NONCE(12) | THUMB_LEN(4, big-endian) | THUMB_CT(N)
        THUMB_LEN counts the ciphertext including its 16-byte GCM tag.
        THUMB_LEN == 0 means the file carries no thumbnail.
    Body (N blocks):
        each block = AES-256-GCM(chunk) | GCM_TAG(16)
        The last block may be shorter than CHUNK_SIZE.

Version history:
    002 — body blocks are encrypted with the 38-byte header prefix
          (MAGIC | VERSION | SALT | MASTER_IV) supplied as AES-GCM
          additional authenticated data, so the key-defining header
          fields can no longer be tampered with undetected.
    003 — adds an optional encrypted thumbnail between the header and the
          body, so clients can render a preview from the first ~50 KiB
          without decrypting the whole file. Body offset is
          50 + 20 + THUMB_LEN. 002 remains readable.
          This module writes 003.

The unreleased 001 format (body blocks without AAD) is not supported:
no build was ever published with it, so every container in the wild is
002 or 003.

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
import struct
import subprocess
from base64 import urlsafe_b64decode, urlsafe_b64encode
from pathlib import Path
from typing import BinaryIO

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
MAGIC = b"SakuraE"
VERSION = b"003"          # format written by this module
VERSION_002 = b"002"      # AAD-protected body, no thumbnail envelope
SUPPORTED_VERSIONS = (VERSION, VERSION_002)

THUMB_MAGIC = b"SKTH"
THUMB_NONCE_SIZE = 12
THUMB_LEN_SIZE = 4
THUMB_HEADER_SIZE = len(THUMB_MAGIC) + THUMB_NONCE_SIZE + THUMB_LEN_SIZE  # 20
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
NAME_SALT = b"ske-name-salt-00"

SALT_SIZE = 16
IV_SIZE = 12
TAG_SIZE = 16  # AES-GCM tag
HEADER_TAG_SIZE = 12  # stored in header (first 12 bytes of the SHA-256 of the body)
HEADER_SIZE = len(MAGIC) + len(VERSION) + SALT_SIZE + IV_SIZE + HEADER_TAG_SIZE  # 7+3+16+12+12 = 50
AAD_SIZE = HEADER_SIZE - HEADER_TAG_SIZE  # 38: header prefix authenticated as AAD in v002/v003
CHUNK_SIZE = 1 * 1024 * 1024  # 1 MiB per encrypted block
KDF_ITERATIONS = 100_000


# ---------------------------------------------------------------------------
# Key Derivation
# ---------------------------------------------------------------------------
def derive_key(password: str, salt: bytes) -> bytes:
    """Derive a 256-bit key from *password* and *salt* using PBKDF2-HMAC-SHA256."""
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=KDF_ITERATIONS,
    )
    return kdf.derive(password.encode("utf-8"))


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
    Encrypt *src* into *dst* using the .ske v003 chunked format.

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
    key = derive_key(password, salt)
    aesgcm = AESGCM(key)

    # The header prefix defines the key; bind it into every block as AAD so
    # salt / IV / version cannot be swapped without breaking decryption.
    header_prefix = MAGIC + VERSION + salt + master_iv  # AAD_SIZE bytes

    if thumbnail is None:
        thumbnail = generate_thumbnail(src)

    thumb_nonce = os.urandom(THUMB_NONCE_SIZE)
    if thumbnail:
        thumb_aad = header_prefix + THUMB_MAGIC + struct.pack(
            ">I", len(thumbnail) + TAG_SIZE
        )
        thumb_ct = aesgcm.encrypt(thumb_nonce, thumbnail, thumb_aad)
    else:
        thumb_ct = b""

    with open(src, "rb") as fin, open(dst, "wb") as fout:
        # Reserve the header (rewritten once the body hash is known), then the
        # thumbnail envelope. The body starts right after it.
        fout.write(b"\x00" * HEADER_SIZE)
        fout.write(THUMB_MAGIC)
        fout.write(thumb_nonce)
        fout.write(struct.pack(">I", len(thumb_ct)))
        fout.write(thumb_ct)

        block_index = 0
        body_hash = hashlib.sha256()

        while True:
            chunk = fin.read(CHUNK_SIZE)
            if not chunk:
                break
            nonce = _block_nonce(master_iv, block_index)
            ct = aesgcm.encrypt(nonce, chunk, header_prefix)  # ciphertext + tag
            fout.write(ct)
            body_hash.update(ct)
            block_index += 1

        header_tag = body_hash.digest()[:HEADER_TAG_SIZE]

        fout.seek(0)
        fout.write(header_prefix)
        fout.write(header_tag)


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
    """Return the preview stored in a v003 container, or None when absent."""
    with open(src, "rb") as fin:
        header, version, salt, _, thumb_nonce, thumb_ct, _ = _read_header_and_envelope(fin)

    if version != VERSION or not thumb_ct:
        return None

    aad = header[:AAD_SIZE] + THUMB_MAGIC + struct.pack(">I", len(thumb_ct))
    try:
        return AESGCM(derive_key(password, salt)).decrypt(thumb_nonce, thumb_ct, aad)
    except Exception as exc:
        raise ValueError("Thumbnail decryption failed. Wrong password or corrupted file.") from exc


def _read_header_and_envelope(fin):
    """
    Read the 50-byte header and, for v003, the thumbnail envelope.

    Returns the raw header, version, salt, master IV, thumbnail nonce and
    ciphertext, plus the byte offset where the body starts. The file position
    is left at the first body block.
    """
    header = fin.read(HEADER_SIZE)
    if len(header) < HEADER_SIZE:
        raise ValueError("File too small to be a valid .ske")

    magic = header[0:7]
    version = header[7:10]
    salt = header[10:26]
    master_iv = header[26:38]

    if magic != MAGIC:
        raise ValueError(f"Invalid magic number: {magic!r}")
    if version not in SUPPORTED_VERSIONS:
        raise ValueError(f"Unsupported version: {version!r}")

    thumb_nonce = b""
    thumb_ct = b""
    if version == VERSION:
        envelope = fin.read(THUMB_HEADER_SIZE)
        if len(envelope) < THUMB_HEADER_SIZE or envelope[0:4] != THUMB_MAGIC:
            raise ValueError("Corrupted thumbnail envelope")
        thumb_nonce = envelope[4:16]
        thumb_len = struct.unpack(">I", envelope[16:20])[0]
        if thumb_len:
            thumb_ct = fin.read(thumb_len)
            if len(thumb_ct) != thumb_len:
                raise ValueError("Truncated thumbnail envelope")

    return header, version, salt, master_iv, thumb_nonce, thumb_ct, fin.tell()


def decrypt_file(src: str | Path, dst: str | Path, password: str) -> None:
    """
    Decrypt a .ske file back to its original content.
    Raises ``ValueError`` on wrong password or corrupted data.
    """
    src = Path(src)
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    with open(src, "rb") as fin:
        header, _, salt, master_iv, _, _, _ = _read_header_and_envelope(fin)

        # Every supported version authenticates the header prefix as AAD.
        aad = header[:AAD_SIZE]

        key = derive_key(password, salt)
        aesgcm = AESGCM(key)

        # Each encrypted block = CHUNK_SIZE + TAG_SIZE bytes (except possibly last)
        enc_block_size = CHUNK_SIZE + TAG_SIZE

        with open(dst, "wb") as fout:
            block_index = 0
            body_hash = hashlib.sha256()

            while True:
                ct = fin.read(enc_block_size)
                if not ct:
                    break
                body_hash.update(ct)
                nonce = _block_nonce(master_iv, block_index)
                try:
                    plaintext = aesgcm.decrypt(nonce, ct, aad)
                except Exception as exc:
                    raise ValueError(
                        f"Decryption failed at block {block_index}. "
                        "Wrong password or corrupted file."
                    ) from exc
                fout.write(plaintext)
                block_index += 1

            # Verify header tag
            computed_tag = body_hash.digest()[:HEADER_TAG_SIZE]
            if not hmac.compare_digest(computed_tag, header[38:50]):
                raise ValueError(
                    "Header integrity check failed. File may be corrupted."
                )
