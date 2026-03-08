"""
Sakura Encryptor CLI
====================
Command-line interface for encrypting / decrypting directories
using the .ske format.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import click

from ske_cli.crypto import (
    CHUNK_SIZE,
    TAG_SIZE,
    derive_key,
    decrypt_file,
    decrypt_name,
    encrypt_file,
    encrypt_name,
)

SKE_EXT = ".ske"


def _walk_and_encrypt(src_dir: Path, dst_dir: Path, password: str, include_root: bool = False) -> None:
    """Recursively encrypt a directory tree."""
    key = derive_key(password, b"ske-name-salt-00")  # fixed salt for name encryption

    for root, dirs, files in os.walk(src_dir):
        rel_root = Path(root).relative_to(src_dir)

        # Build encrypted output path
        enc_parts: list[str] = []
        
        # Optionally include the source folder name as the root of the encrypted tree
        if include_root:
            enc_parts.append(encrypt_name(src_dir.name, key))

        for part in rel_root.parts:
            enc_parts.append(encrypt_name(part, key))
            
        enc_root = dst_dir / Path(*enc_parts) if enc_parts else dst_dir
        enc_root.mkdir(parents=True, exist_ok=True)

        # Handle empty directories or just ensure the folder exists
        if not files and not dirs:
            click.echo(f"  creating empty dir: {rel_root}")
            # Folder already created by mkdir(parents=True) above

        for fname in files:
            src_file = Path(root) / fname
            enc_fname = encrypt_name(fname, key) + SKE_EXT
            dst_file = enc_root / enc_fname

            click.echo(f"  encrypting: {src_file.relative_to(src_dir if not include_root else src_dir.parent)}")
            encrypt_file(src_file, dst_file, password)

        # Sort dirs in-place so walk order is deterministic
        dirs.sort()


def _walk_and_decrypt(src_dir: Path, dst_dir: Path, password: str) -> None:
    """Recursively decrypt a directory tree."""
    key = derive_key(password, b"ske-name-salt-00")

    for root, dirs, files in os.walk(src_dir):
        rel_root = Path(root).relative_to(src_dir)

        # Decrypt path components
        dec_parts: list[str] = []
        for part in rel_root.parts:
            try:
                dec_parts.append(decrypt_name(part, key))
            except Exception:
                # If a folder name can't be decrypted (e.g. not encrypted), keep it as-is
                dec_parts.append(part)
        dec_root = dst_dir / Path(*dec_parts) if dec_parts else dst_dir
        dec_root.mkdir(parents=True, exist_ok=True)

        # Handle empty directories
        if not files and not dirs:
             click.echo(f"  restoring empty dir: {dec_root.relative_to(dst_dir)}")

        for fname in files:
            if not fname.endswith(SKE_EXT):
                click.echo(f"  skipping (not .ske): {fname}")
                continue

            src_file = Path(root) / fname
            # Remove .ske extension, decrypt name
            enc_name_part = fname[: -len(SKE_EXT)]
            try:
                dec_fname = decrypt_name(enc_name_part, key)
            except Exception:
                click.echo(f"  skipping (name decrypt failed): {fname}", err=True)
                continue

            dst_file = dec_root / dec_fname

            click.echo(f"  decrypting: {src_file.relative_to(src_dir)} → {dec_fname}")
            try:
                decrypt_file(src_file, dst_file, password)
            except ValueError as exc:
                click.echo(f"  ERROR: {exc}", err=True)
                # Remove partially written file
                if dst_file.exists():
                    dst_file.unlink()
                continue

        dirs.sort()


# ---------------------------------------------------------------------------
# CLI Commands
# ---------------------------------------------------------------------------
@click.group()
def main() -> None:
    """Sakura Encryptor — zero-knowledge video encryption tool."""


@main.command()
@click.option("-i", "--input", "src", required=True, type=click.Path(exists=True), help="Source file or directory to encrypt.")
@click.option("-o", "--output", "dst", required=True, type=click.Path(), help="Output directory for encrypted files.")
@click.option("-p", "--password", required=True, prompt=True, hide_input=True, help="Encryption password.")
@click.option("-r", "--include-root", is_flag=True, help="Include the source folder itself in the encrypted output.")
def encrypt(src: str, dst: str, password: str, include_root: bool) -> None:
    """Encrypt a directory tree into .ske format."""
    src_path = Path(src).resolve()
    dst_path = Path(dst).resolve()

    if src_path.is_file():
        # For single files, the output is just the encrypted file in the dst directory
        key = derive_key(password, b"ske-name-salt-00")
        enc_fname = encrypt_name(src_path.name, key) + SKE_EXT
        dst_file = dst_path / enc_fname
        dst_path.mkdir(parents=True, exist_ok=True)
        click.echo(f"  encrypting: {src_path.name} → {dst_file}")
        encrypt_file(src_path, dst_file, password)
    else:
        _walk_and_encrypt(src_path, dst_path, password, include_root=include_root)
    click.echo("Done.")


@main.command()
@click.option("-i", "--input", "src", required=True, type=click.Path(exists=True), help="Encrypted file or directory to decrypt.")
@click.option("-o", "--output", "dst", required=True, type=click.Path(), help="Output directory for restored files.")
@click.option("-p", "--password", required=True, prompt=True, hide_input=True, help="Decryption password.")
def decrypt(src: str, dst: str, password: str) -> None:
    """Decrypt a .ske directory tree back to original files."""
    src_path = Path(src).resolve()
    dst_path = Path(dst).resolve()

    if src_path.is_file():
        if not src_path.name.endswith(SKE_EXT):
            click.echo("Error: single file must end with .ske", err=True)
            sys.exit(1)
        key = derive_key(password, b"ske-name-salt-00")
        enc_name_part = src_path.name[: -len(SKE_EXT)]
        try:
            dec_fname = decrypt_name(enc_name_part, key)
            dst_file = dst_path / dec_fname
            click.echo(f"  decrypting: {src_path.name} → {dst_file}")
            decrypt_file(src_path, dst_file, password)
        except Exception as exc:
            click.echo(f"  ERROR: {exc}", err=True)
            sys.exit(1)
    else:
        _walk_and_decrypt(src_path, dst_path, password)
    click.echo("Done.")


if __name__ == "__main__":
    main()
