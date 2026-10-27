"""
Tests for the Sakura Encryptor crypto module.
"""

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from ske_cli.crypto import (
    AAD_SIZE,
    CHUNK_SIZE,
    HEADER_SIZE,
    HEADER_TAG_SIZE,
    IV_SIZE,
    MAGIC,
    SALT_SIZE,
    THUMB_HEADER_SIZE,
    VERSION,
    VERSION_002,
    _block_nonce,
    decrypt_file,
    decrypt_name,
    decrypt_path,
    decrypt_thumbnail,
    derive_key,
    encrypt_file,
    encrypt_name,
    encrypt_path,
    generate_thumbnail,
)


# ------------------------------------------------------------------ Key
class TestDeriveKey:
    def test_deterministic(self):
        salt = b"0123456789abcdef"
        k1 = derive_key("hello", salt)
        k2 = derive_key("hello", salt)
        assert k1 == k2

    def test_different_password(self):
        salt = b"0123456789abcdef"
        k1 = derive_key("hello", salt)
        k2 = derive_key("world", salt)
        assert k1 != k2

    def test_different_salt(self):
        k1 = derive_key("hello", b"salt_aaaaaaaaaaaa")
        k2 = derive_key("hello", b"salt_bbbbbbbbbbbb")
        assert k1 != k2

    def test_key_length(self):
        k = derive_key("test", b"0123456789abcdef")
        assert len(k) == 32


# ------------------------------------------------------------------ Names
class TestNameEncryption:
    def test_roundtrip(self):
        key = derive_key("pw", b"0123456789abcdef")
        for name in ["hello", "电影", "Action.mp4", "2026", "中文文件夹"]:
            enc = encrypt_name(name, key)
            assert decrypt_name(enc, key) == name

    def test_deterministic(self):
        key = derive_key("pw", b"0123456789abcdef")
        e1 = encrypt_name("movie.mp4", key)
        e2 = encrypt_name("movie.mp4", key)
        assert e1 == e2

    def test_different_names_differ(self):
        key = derive_key("pw", b"0123456789abcdef")
        e1 = encrypt_name("a", key)
        e2 = encrypt_name("b", key)
        assert e1 != e2

    def test_wrong_key_fails(self):
        key1 = derive_key("pw1", b"0123456789abcdef")
        key2 = derive_key("pw2", b"0123456789abcdef")
        enc = encrypt_name("test", key1)
        with pytest.raises(Exception):
            decrypt_name(enc, key2)


# ------------------------------------------------------------------ Paths
class TestPathEncryption:
    def test_roundtrip(self):
        key = derive_key("pw", b"0123456789abcdef")
        original = "电影/2026/Action.mp4"
        enc = encrypt_path(original, key)
        assert decrypt_path(enc, key) == original

    def test_components_encrypted(self):
        key = derive_key("pw", b"0123456789abcdef")
        enc = encrypt_path("a/b/c", key)
        parts = enc.split("/")
        assert len(parts) == 3
        assert all(p != orig for p, orig in zip(parts, ["a", "b", "c"]))


# ------------------------------------------------------------------ Files
class TestFileEncryption:
    def _roundtrip(self, data: bytes, password: str = "testpw"):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(data)
            encrypt_file(src, enc, password)

            # Check header
            with open(enc, "rb") as f:
                header = f.read(HEADER_SIZE)
            assert header[:7] == MAGIC
            assert header[7:10] == VERSION

            decrypt_file(enc, dec, password)
            assert dec.read_bytes() == data

    def test_small_file(self):
        self._roundtrip(b"Hello, Sakura Encryptor!")

    def test_empty_file(self):
        self._roundtrip(b"")

    def test_exact_chunk_size(self):
        self._roundtrip(os.urandom(CHUNK_SIZE))

    def test_multi_chunk(self):
        # 2.5 MiB — tests multi-block + partial last block
        self._roundtrip(os.urandom(CHUNK_SIZE * 2 + CHUNK_SIZE // 2))

    def test_large_file(self):
        # 5 MiB
        self._roundtrip(os.urandom(CHUNK_SIZE * 5))

    def test_wrong_password(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"secret data here")
            encrypt_file(src, enc, "correct_pw")

            with pytest.raises(ValueError, match="Decryption failed|Wrong password"):
                decrypt_file(enc, dec, "wrong_pw")

    def test_corrupted_magic(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"test data")
            encrypt_file(src, enc, "pw")

            # Corrupt magic
            data = bytearray(enc.read_bytes())
            data[0:7] = b"BADMAGN"
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Invalid magic"):
                decrypt_file(enc, dec, "pw")


# ------------------------------------------------------- Format compatibility
def _v2_bytes(data: bytes, password: str, salt: bytes, master_iv: bytes) -> bytes:
    """Reproduce the v002 writer: AAD-protected body, no thumbnail envelope."""
    key = derive_key(password, salt)
    aesgcm = AESGCM(key)
    header_prefix = MAGIC + VERSION_002 + salt + master_iv
    body = bytearray()
    body_hash = hashlib.sha256()
    for block_index, off in enumerate(range(0, len(data), CHUNK_SIZE)):
        chunk = data[off : off + CHUNK_SIZE]
        ct = aesgcm.encrypt(_block_nonce(master_iv, block_index), chunk, header_prefix)
        body += ct
        body_hash.update(ct)
    header = header_prefix + body_hash.digest()[:HEADER_TAG_SIZE]
    assert len(header_prefix) == AAD_SIZE
    return header + bytes(body)


class TestFormatVersion:
    def test_encrypt_writes_current_version(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            src.write_bytes(b"payload")
            encrypt_file(src, enc, "pw")
            assert enc.read_bytes()[7:10] == VERSION == b"003"

    def test_v1_version_is_rejected(self):
        """The unreleased 001 format is refused outright."""
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"secret")
            encrypt_file(src, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[7:10] = b"001"
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Unsupported version"):
                decrypt_file(enc, dec, "pw")

    def test_v3_relabelled_as_v2_is_rejected(self):
        """A v003 body starts 20 bytes late, so relabelling it as v002 must fail."""
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"secret" * 1000)
            encrypt_file(src, enc, "pw")

            data = bytearray(enc.read_bytes())
            assert data[7:10] == VERSION
            data[7:10] = VERSION_002
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Decryption failed|Wrong password"):
                decrypt_file(enc, dec, "pw")

    def test_v2_file_without_envelope_still_decrypts(self):
        data = b"aad protected payload " * 2048
        salt = bytes(range(SALT_SIZE))
        master_iv = bytes(range(IV_SIZE))
        blob = _v2_bytes(data, "pw", salt, master_iv)

        with tempfile.TemporaryDirectory() as tmpdir:
            enc = Path(tmpdir) / "v2.ske"
            dec = Path(tmpdir) / "v2.out"
            enc.write_bytes(blob)

            assert enc.read_bytes()[7:10] == VERSION_002
            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == data
            assert decrypt_thumbnail(enc, "pw") is None


# ------------------------------------------------------------- Thumbnails
def _make_image(path: Path, size=(1200, 900), fmt: str = "JPEG"):
    """Write a detailed image large enough to justify a downscaled preview."""
    Image = pytest.importorskip("PIL.Image")
    pixels = os.urandom(size[0] * size[1] * 3)
    Image.frombytes("RGB", size, pixels).save(path, format=fmt, quality=92)


def _thumb_envelope(blob: bytes) -> tuple[bytes, bytes]:
    """Return (thumb_nonce, thumb_ciphertext) from a v003 container."""
    start = HEADER_SIZE + 4  # skip THUMB_MAGIC
    nonce = blob[start : start + 12]
    length = int.from_bytes(blob[start + 12 : start + 16], "big")
    return nonce, blob[start + 16 : start + 16 + length]


class TestThumbnail:
    def test_generate_thumbnail_returns_jpeg(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            _make_image(img)
            data = generate_thumbnail(img)
            assert data is not None
            assert data.startswith(b"\xff\xd8")  # JPEG SOI

    def test_generate_thumbnail_rejects_non_image(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            f = Path(tmpdir) / "notes.txt"
            f.write_bytes(b"not an image")
            assert generate_thumbnail(f) is None

    def test_generate_thumbnail_skips_images_that_are_already_small(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "small.jpg"
            _make_image(img, size=(400, 300))
            assert generate_thumbnail(img) is None

    def test_roundtrip_with_thumbnail(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            dec = Path(tmpdir) / "pic.out.png"
            _make_image(img)

            encrypt_file(img, enc, "pw")

            blob = enc.read_bytes()
            assert blob[7:10] == VERSION
            nonce, thumb_ct = _thumb_envelope(blob)
            assert len(nonce) == 12
            assert thumb_ct

            preview = decrypt_thumbnail(enc, "pw")
            assert preview is not None
            assert preview.startswith(b"\xff\xd8")
            assert len(preview) < img.stat().st_size  # envelope never outweighs the payload

            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == img.read_bytes()

    def test_no_thumbnail_when_source_is_not_image(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "data.bin"
            enc = Path(tmpdir) / "data.ske"
            dec = Path(tmpdir) / "data.out"
            src.write_bytes(b"\x00\x01binary payload" * 100)

            encrypt_file(src, enc, "pw")

            blob = enc.read_bytes()
            assert len(blob) >= HEADER_SIZE + THUMB_HEADER_SIZE
            assert int.from_bytes(blob[HEADER_SIZE + 16 : HEADER_SIZE + 20], "big") == 0
            assert decrypt_thumbnail(enc, "pw") is None

            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == src.read_bytes()

    def test_encrypt_works_without_pillow(self, monkeypatch):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            dec = Path(tmpdir) / "pic.out"
            _make_image(img)
            original = img.read_bytes()

            # Pillow becomes unavailable: encryption must still succeed.
            monkeypatch.setitem(sys.modules, "PIL", None)
            monkeypatch.setitem(sys.modules, "PIL.Image", None)

            assert generate_thumbnail(img) is None
            encrypt_file(img, enc, "pw")
            assert int.from_bytes(
                enc.read_bytes()[HEADER_SIZE + 16 : HEADER_SIZE + 20], "big"
            ) == 0
            assert decrypt_thumbnail(enc, "pw") is None

            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == original

    def test_explicit_thumbnail_bytes(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "data.bin"
            enc = Path(tmpdir) / "data.ske"
            src.write_bytes(b"payload " * 500)

            encrypt_file(src, enc, "pw", thumbnail=b"\xff\xd8fake-jpeg-body")
            assert decrypt_thumbnail(enc, "pw") == b"\xff\xd8fake-jpeg-body"

    def test_wrong_password_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "right")

            with pytest.raises(ValueError, match="Thumbnail decryption failed"):
                decrypt_thumbnail(enc, "wrong")

    def test_corrupted_envelope_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[HEADER_SIZE : HEADER_SIZE + 4] = b"XXXX"
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Corrupted thumbnail envelope"):
                decrypt_thumbnail(enc, "pw")

    def test_truncated_envelope_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "pw")

            blob = enc.read_bytes()
            _, thumb_ct = _thumb_envelope(blob)
            enc.write_bytes(blob[: HEADER_SIZE + THUMB_HEADER_SIZE + len(thumb_ct) - 1])

            with pytest.raises(ValueError, match="Truncated thumbnail envelope"):
                decrypt_thumbnail(enc, "pw")

    def test_tampered_thumbnail_ciphertext_fails(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[HEADER_SIZE + THUMB_HEADER_SIZE] ^= 0xFF
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Thumbnail decryption failed"):
                decrypt_thumbnail(enc, "pw")


# ---------------------------------------------------------- Video previews
def _make_video(path: Path, seconds: int = 30) -> None:
    """Render a synthetic clip with ffmpeg; skips the test when it is missing."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        pytest.skip("ffmpeg is not installed")
    subprocess.run(
        [
            ffmpeg, "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", f"testsrc=size=640x480:rate=10:duration={seconds}",
            "-pix_fmt", "yuv420p", "-y", str(path),
        ],
        check=True,
        timeout=120,
    )


class TestVideoThumbnail:
    def test_video_preview_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "clip.mp4"
            enc = Path(tmpdir) / "clip.ske"
            dec = Path(tmpdir) / "clip.out.mp4"
            _make_video(src)

            preview = generate_thumbnail(src)
            assert preview is not None
            assert preview.startswith(b"\xff\xd8")

            encrypt_file(src, enc, "pw")
            assert decrypt_thumbnail(enc, "pw") == preview

            # The envelope must not touch the payload: decryption is exact.
            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == src.read_bytes()

    def test_video_without_ffmpeg_has_no_preview(self, monkeypatch):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "clip.mp4"
            src.write_bytes(b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 4096)
            monkeypatch.setattr("ske_cli.crypto.shutil.which", lambda *a, **k: None)

            assert generate_thumbnail(src) is None

            enc = Path(tmpdir) / "clip.ske"
            encrypt_file(src, enc, "pw")
            assert int.from_bytes(enc.read_bytes()[66:70], "big") == 0

            dec = Path(tmpdir) / "clip.out"
            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == src.read_bytes()
