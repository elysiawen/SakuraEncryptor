"""
Tests for the Sakura Encryptor crypto module.
"""

import os
import tempfile
from pathlib import Path

import pytest

from ske_cli.crypto import (
    CHUNK_SIZE,
    HEADER_SIZE,
    MAGIC,
    VERSION,
    decrypt_file,
    decrypt_name,
    decrypt_path,
    derive_key,
    encrypt_file,
    encrypt_name,
    encrypt_path,
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
