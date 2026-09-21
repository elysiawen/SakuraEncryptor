"""
Sakura Encryptor Crypto Core
======================
AES-256-GCM encryption for file names and file content.
Chunked encryption supports random-access decryption in the browser.

File format (.ske):
    Header (50 bytes):
        MAGIC(7) | VERSION(3) | SALT(16) | MASTER_IV(12) | HEADER_TAG(12)
    Body (N blocks):
        each block = AES-256-GCM(chunk) | GCM_TAG(16)
        The last block may be shorter than CHUNK_SIZE.

Version history:
    001 — initial format; body blocks were encrypted without AAD.
    002 — body blocks are encrypted with the 38-byte header prefix
          (MAGIC | VERSION | SALT | MASTER_IV) supplied as AES-GCM
          additional authenticated data, so the key-defining header
          fields can no longer be tampered with undetected.
          This module writes 002; 001 files remain readable.

Name encryption is deterministic (fixed IV derived from key) so that
identical plain names always produce the same cipher-text, enabling
path reconstruction without a database.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import struct
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
VERSION = b"002"          # format written by this module
LEGACY_VERSION = b"001"   # initial format (no AAD) — still readable
SUPPORTED_VERSIONS = (VERSION, LEGACY_VERSION)

# Fixed salt for the deterministic file-name key.  It MUST stay constant:
# identical plain names have to encrypt to identical tokens so the browser can
# rebuild the encrypted directory tree without a database (see encrypt_name).
NAME_SALT = b"ske-name-salt-00"

SALT_SIZE = 16
IV_SIZE = 12
TAG_SIZE = 16  # AES-GCM tag
HEADER_TAG_SIZE = 12  # stored in header (first 12 bytes of the SHA-256 of the body)
HEADER_SIZE = len(MAGIC) + len(VERSION) + SALT_SIZE + IV_SIZE + HEADER_TAG_SIZE  # 7+3+16+12+12 = 50
AAD_SIZE = HEADER_SIZE - HEADER_TAG_SIZE  # 38: header prefix authenticated as AAD in v002
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
def encrypt_file(src: str | Path, dst: str | Path, password: str) -> None:
    """
    Encrypt *src* into *dst* using the .ske chunked format.

    Each 1 MiB plaintext block is independently encrypted with AES-256-GCM.
    The header stores salt + master IV so the web player can derive the key
    and seek to any block.
    """
    salt = os.urandom(SALT_SIZE)
    master_iv = os.urandom(IV_SIZE)
    key = derive_key(password, salt)
    aesgcm = AESGCM(key)

    # The header prefix defines the key; bind it into every block as AAD so
    # salt / IV / version cannot be swapped without breaking decryption.
    header_prefix = MAGIC + VERSION + salt + master_iv  # AAD_SIZE bytes

    src = Path(src)
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    with open(src, "rb") as fin, open(dst, "wb") as fout:
        # Reserve space for header (will rewrite after we know header tag)
        fout.write(b"\x00" * HEADER_SIZE)

        # Encrypt body in chunks
        block_index = 0
        body_hash = hashlib.sha256()

        while True:
            chunk = fin.read(CHUNK_SIZE)
            if not chunk:
                break
            nonce = _block_nonce(master_iv, block_index)
            ct = aesgcm.encrypt(nonce, chunk, header_prefix)  # ct = ciphertext + 16-byte tag
            fout.write(ct)
            body_hash.update(ct)
            block_index += 1

        # Compute a header integrity tag from body hash (first 12 bytes)
        header_tag = body_hash.digest()[:HEADER_TAG_SIZE]

        # Write final header
        fout.seek(0)
        fout.write(header_prefix)
        fout.write(header_tag)


def decrypt_file(src: str | Path, dst: str | Path, password: str) -> None:
    """
    Decrypt a .ske file back to its original content.
    Raises ``ValueError`` on wrong password or corrupted data.
    """
    src = Path(src)
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)

    with open(src, "rb") as fin:
        header = fin.read(HEADER_SIZE)
        if len(header) < HEADER_SIZE:
            raise ValueError("File too small to be a valid .ske")

        magic = header[0:7]
        version = header[7:10]
        salt = header[10:26]
        master_iv = header[26:38]
        header_tag = header[38:50]

        if magic != MAGIC:
            raise ValueError(f"Invalid magic number: {magic!r}")
        if version not in SUPPORTED_VERSIONS:
            raise ValueError(f"Unsupported version: {version!r}")

        # v002 authenticates the header prefix as AAD; v001 used no AAD.
        aad = header[:AAD_SIZE] if version == VERSION else None

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
            if not hmac.compare_digest(computed_tag, header_tag):
                raise ValueError(
                    "Header integrity check failed. File may be corrupted."
                )
