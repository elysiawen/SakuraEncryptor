"""
Tests for the Sakura Encryptor crypto module.
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import pytest

from ske_cli.crypto import (
    CHUNK_SIZE,
    HEADER_SIZE,
    KDF_ITERATIONS,
    MAGIC,
    MAX_KDF_ITERATIONS,
    OPTIONS_SIZE,
    PROBE_SIZE,
    TAG_SIZE,
    THUMB_HEADER_SIZE,
    THUMB_MAGIC,
    THUMB_NONCE_SIZE,
    VERSION,
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
            assert header[: len(MAGIC)] == MAGIC
            assert header[len(MAGIC) : len(MAGIC) + len(VERSION)] == VERSION

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
class TestFormatVersion:
    def test_encrypt_writes_version_001(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            src.write_bytes(b"payload")
            encrypt_file(src, enc, "pw")
            version_at = len(MAGIC)
            assert enc.read_bytes()[version_at : version_at + len(VERSION)] == VERSION == b"001"

    @pytest.mark.parametrize("version", [b"000", b"002", b"003", b"999"])
    def test_other_version_bytes_are_rejected(self, version):
        """Pre-release labels (002 / 003) and anything else are refused."""
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"secret")
            encrypt_file(src, enc, "pw")

            data = bytearray(enc.read_bytes())
            version_at = len(MAGIC)
            data[version_at : version_at + len(VERSION)] = version
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Unsupported version"):
                decrypt_file(enc, dec, "pw")

    def test_container_without_envelope_is_rejected(self):
        """The old 002 layout (header straight into the body) must not decode."""
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "input.bin"
            enc = Path(tmpdir) / "output.ske"
            dec = Path(tmpdir) / "restored.bin"

            src.write_bytes(b"secret" * 1000)
            encrypt_file(src, enc, "pw")

            blob = enc.read_bytes()
            envelope_at = HEADER_SIZE + OPTIONS_SIZE
            assert blob[envelope_at : envelope_at + len(THUMB_MAGIC)] == THUMB_MAGIC
            # Strip the envelope: the reader still expects it right there.
            enc.write_bytes(blob[:envelope_at] + blob[PROBE_SIZE:])

            with pytest.raises(ValueError, match="Corrupted thumbnail envelope"):
                decrypt_file(enc, dec, "pw")


class TestHeaderOptions:
    ITERS_AT = HEADER_SIZE + 16  # KEY_CHECK(16) then KDF_ITERATIONS(5)

    def test_options_record_the_work_factor(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "in.bin"
            enc = Path(tmpdir) / "out.ske"
            src.write_bytes(b"abc")
            encrypt_file(src, enc, "pw")

            blob = enc.read_bytes()
            assert int.from_bytes(blob[self.ITERS_AT : self.ITERS_AT + 5], "big") == KDF_ITERATIONS

    def test_changed_work_factor_rejects_the_password(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "in.bin"
            enc = Path(tmpdir) / "out.ske"
            dec = Path(tmpdir) / "out.bin"
            src.write_bytes(b"abc")
            encrypt_file(src, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[self.ITERS_AT : self.ITERS_AT + 5] = (KDF_ITERATIONS + 1).to_bytes(5, "big")
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Wrong password"):
                decrypt_file(enc, dec, "pw")

    def test_absurd_work_factor_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "in.bin"
            enc = Path(tmpdir) / "out.ske"
            dec = Path(tmpdir) / "out.bin"
            src.write_bytes(b"abc")
            encrypt_file(src, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[self.ITERS_AT : self.ITERS_AT + 5] = (MAX_KDF_ITERATIONS + 1).to_bytes(5, "big")
            enc.write_bytes(bytes(data))

            with pytest.raises(ValueError, match="Unsupported KDF iteration count"):
                decrypt_file(enc, dec, "pw")

    def test_truncated_body_is_caught_by_shmac(self):
        """Every remaining block still decrypts; only the keyed MAC sees the loss."""
        with tempfile.TemporaryDirectory() as tmpdir:
            src = Path(tmpdir) / "in.bin"
            enc = Path(tmpdir) / "out.ske"
            dec = Path(tmpdir) / "out.bin"
            src.write_bytes(b"x" * (CHUNK_SIZE + 1000))
            encrypt_file(src, enc, "pw")

            blob = enc.read_bytes()
            enc.write_bytes(blob[: PROBE_SIZE + CHUNK_SIZE + TAG_SIZE])

            with pytest.raises(ValueError, match="Integrity check failed"):
                decrypt_file(enc, dec, "pw")


# ------------------------------------------------------------- Thumbnails
def _make_image(path: Path, size=(1200, 900), fmt: str = "JPEG"):
    """Write a detailed image large enough to justify a downscaled preview."""
    Image = pytest.importorskip("PIL.Image")
    pixels = os.urandom(size[0] * size[1] * 3)
    Image.frombytes("RGB", size, pixels).save(path, format=fmt, quality=92)


def _thumb_envelope(blob: bytes) -> tuple[bytes, bytes]:
    """Return (thumb_nonce, thumb_ciphertext) from a container."""
    envelope_at = HEADER_SIZE + OPTIONS_SIZE
    start = envelope_at + len(THUMB_MAGIC)
    nonce = blob[start : start + THUMB_NONCE_SIZE]
    length = int.from_bytes(blob[start + THUMB_NONCE_SIZE : envelope_at + THUMB_HEADER_SIZE], "big")
    ct_start = envelope_at + THUMB_HEADER_SIZE
    return nonce, blob[ct_start : ct_start + length]


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
            version_at = len(MAGIC)
            assert blob[version_at : version_at + len(VERSION)] == VERSION
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
            assert len(blob) >= HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE
            length_at = HEADER_SIZE + OPTIONS_SIZE + len(THUMB_MAGIC) + THUMB_NONCE_SIZE
            assert int.from_bytes(blob[length_at : HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE], "big") == 0
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
            length_at = HEADER_SIZE + OPTIONS_SIZE + len(THUMB_MAGIC) + THUMB_NONCE_SIZE
            assert int.from_bytes(
                enc.read_bytes()[length_at : HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE], "big"
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

            # The key check rejects the password before the preview is touched.
            with pytest.raises(ValueError, match="Wrong password"):
                decrypt_thumbnail(enc, "wrong")

    def test_corrupted_envelope_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "pw")

            data = bytearray(enc.read_bytes())
            envelope_at = HEADER_SIZE + OPTIONS_SIZE
            data[envelope_at : envelope_at + len(THUMB_MAGIC)] = b"X" * len(THUMB_MAGIC)
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
            enc.write_bytes(blob[: HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE + len(thumb_ct) - 1])

            with pytest.raises(ValueError, match="Truncated thumbnail envelope"):
                decrypt_thumbnail(enc, "pw")

    def test_tampered_thumbnail_ciphertext_fails(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            img = Path(tmpdir) / "pic.jpg"
            enc = Path(tmpdir) / "pic.ske"
            _make_image(img)
            encrypt_file(img, enc, "pw")

            data = bytearray(enc.read_bytes())
            data[HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE] ^= 0xFF
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
            length_at = HEADER_SIZE + OPTIONS_SIZE + len(THUMB_MAGIC) + THUMB_NONCE_SIZE
            assert int.from_bytes(
                enc.read_bytes()[length_at : HEADER_SIZE + OPTIONS_SIZE + THUMB_HEADER_SIZE], "big"
            ) == 0

            dec = Path(tmpdir) / "clip.out"
            decrypt_file(enc, dec, "pw")
            assert dec.read_bytes() == src.read_bytes()
