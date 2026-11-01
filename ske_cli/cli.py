"""
Sakura Encryptor CLI
====================
Command-line interface for encrypting / decrypting directories
using the .ske format.

All help and log text comes from `ske_cli.i18n`, so the language follows the
system (or `--lang` / `SKE_LANG`) instead of being baked into the source.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import click

from ske_cli.crypto import (
    CHUNK_SIZE,
    NAME_SALT,
    TAG_SIZE,
    derive_key,
    decrypt_file,
    decrypt_name,
    encrypt_file,
    encrypt_name,
)
from ske_cli.i18n import normalize_language, set_language, t

SKE_EXT = ".ske"


class _LocalizedOption(click.Option):
    """Click writes `[required]` itself; it has to follow the active language."""

    def get_help_extra(self, ctx: click.Context) -> dict:
        extra = super().get_help_extra(ctx)
        if extra.get("required"):
            extra["required"] = t("cli.required")
        return extra


class _LocalizedCommand(click.Command):
    def get_help_option(self, ctx: click.Context) -> click.Option | None:
        option = super().get_help_option(ctx)
        if option is not None:
            option.help = t("cli.help_option")
        return option


class _LocalizedGroup(click.Group):
    def get_help_option(self, ctx: click.Context) -> click.Option | None:
        option = super().get_help_option(ctx)
        if option is not None:
            option.help = t("cli.help_option")
        return option


def _walk_and_encrypt(src_dir: Path, dst_dir: Path, password: str, include_root: bool = False) -> None:
    """Recursively encrypt a directory tree."""
    key = derive_key(password, NAME_SALT)

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
            click.echo(t("cli.empty_dir", path=rel_root))
            # Folder already created by mkdir(parents=True) above

        for fname in files:
            src_file = Path(root) / fname
            enc_fname = encrypt_name(fname, key) + SKE_EXT
            dst_file = enc_root / enc_fname

            click.echo(t(
                "cli.encrypting",
                path=src_file.relative_to(src_dir if not include_root else src_dir.parent),
            ))
            encrypt_file(src_file, dst_file, password)

        # Sort dirs in-place so walk order is deterministic
        dirs.sort()


def _walk_and_decrypt(src_dir: Path, dst_dir: Path, password: str) -> None:
    """Recursively decrypt a directory tree."""
    key = derive_key(password, NAME_SALT)

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
             click.echo(t("cli.restore_empty_dir", path=dec_root.relative_to(dst_dir)))

        for fname in files:
            if not fname.endswith(SKE_EXT):
                click.echo(t("cli.skipping_not_ske", name=fname))
                continue

            src_file = Path(root) / fname
            # Remove .ske extension, decrypt name
            enc_name_part = fname[: -len(SKE_EXT)]
            try:
                dec_fname = decrypt_name(enc_name_part, key)
            except Exception:
                click.echo(t("cli.skipping_bad_name", name=fname), err=True)
                continue

            dst_file = dec_root / dec_fname

            click.echo(t("cli.decrypting_to", src=src_file.relative_to(src_dir), dst=dec_fname))
            try:
                decrypt_file(src_file, dst_file, password)
            except ValueError as exc:
                click.echo(t("cli.error", message=exc), err=True)
                # Remove partially written file
                if dst_file.exists():
                    dst_file.unlink()
                continue

        dirs.sort()


# ---------------------------------------------------------------------------
# CLI Commands
# ---------------------------------------------------------------------------
def _build_cli() -> click.Group:
    """Assemble the command tree in the language active right now."""

    @click.group(cls=_LocalizedGroup, help=t("cli.group_help"))
    @click.option(
        "--lang",
        "lang",
        type=click.Choice(["zh", "en"], case_sensitive=False),
        default=None,
        is_eager=True,
        expose_value=False,
        callback=lambda _ctx, _param, value: set_language(value) if value else None,
        help=t("cli.lang_help"),
    )
    def main() -> None:
        pass

    @main.command(cls=_LocalizedCommand, help=t("cli.encrypt_help"))
    @click.option(
        "--lang",
        "lang",
        type=click.Choice(["zh", "en"], case_sensitive=False),
        default=None,
        is_eager=True,
        expose_value=False,
        hidden=True,
        callback=lambda _ctx, _param, value: set_language(value) if value else None,
    )
    @click.option("-i", "--input", "src", required=True, type=click.Path(exists=True), cls=_LocalizedOption, help=t("cli.input_encrypt"))
    @click.option("-o", "--output", "dst", required=True, type=click.Path(), cls=_LocalizedOption, help=t("cli.output_encrypt"))
    @click.option("-p", "--password", required=True, prompt=t("cli.prompt_password"), hide_input=True, cls=_LocalizedOption, help=t("cli.password"))
    @click.option("-r", "--include-root", is_flag=True, cls=_LocalizedOption, help=t("cli.include_root"))
    def encrypt(src: str, dst: str, password: str, include_root: bool) -> None:
        src_path = Path(src).resolve()
        dst_path = Path(dst).resolve()

        if src_path.is_file():
            # For single files, the output is just the encrypted file in the dst directory
            key = derive_key(password, NAME_SALT)
            enc_fname = encrypt_name(src_path.name, key) + SKE_EXT
            dst_file = dst_path / enc_fname
            dst_path.mkdir(parents=True, exist_ok=True)
            click.echo(t("cli.encrypting_to", src=src_path.name, dst=dst_file))
            encrypt_file(src_path, dst_file, password)
        else:
            _walk_and_encrypt(src_path, dst_path, password, include_root=include_root)
        click.echo(t("cli.done"))

    @main.command(cls=_LocalizedCommand, help=t("cli.decrypt_help"))
    @click.option(
        "--lang",
        "lang",
        type=click.Choice(["zh", "en"], case_sensitive=False),
        default=None,
        is_eager=True,
        expose_value=False,
        hidden=True,
        callback=lambda _ctx, _param, value: set_language(value) if value else None,
    )
    @click.option("-i", "--input", "src", required=True, type=click.Path(exists=True), cls=_LocalizedOption, help=t("cli.input_decrypt"))
    @click.option("-o", "--output", "dst", required=True, type=click.Path(), cls=_LocalizedOption, help=t("cli.output_decrypt"))
    @click.option("-p", "--password", required=True, prompt=t("cli.prompt_password"), hide_input=True, cls=_LocalizedOption, help=t("cli.password_decrypt"))
    def decrypt(src: str, dst: str, password: str) -> None:
        src_path = Path(src).resolve()
        dst_path = Path(dst).resolve()

        if src_path.is_file():
            if not src_path.name.endswith(SKE_EXT):
                click.echo(t("cli.must_be_ske"), err=True)
                sys.exit(1)
            key = derive_key(password, NAME_SALT)
            enc_name_part = src_path.name[: -len(SKE_EXT)]
            try:
                dec_fname = decrypt_name(enc_name_part, key)
                dst_file = dst_path / dec_fname
                click.echo(t("cli.decrypting_to", src=src_path.name, dst=dst_file))
                decrypt_file(src_path, dst_file, password)
            except Exception as exc:
                click.echo(t("cli.error", message=exc), err=True)
                sys.exit(1)
        else:
            _walk_and_decrypt(src_path, dst_path, password)
        click.echo(t("cli.done"))

    return main


def _language_from_args(argv: list[str]) -> str | None:
    """Read `--lang zh` / `--lang=zh` before Click builds its help text."""
    for index, argument in enumerate(argv):
        value = None
        if argument == "--lang" and index + 1 < len(argv):
            value = argv[index + 1]
        elif argument.startswith("--lang="):
            value = argument.split("=", 1)[1]
        if value:
            return normalize_language(value)
    return None


def main() -> None:
    """Entry point: fix the language, then hand over to Click."""
    explicit = _language_from_args(sys.argv[1:])
    if explicit:
        set_language(explicit)
    _build_cli().main(prog_name="ske")


if __name__ == "__main__":
    main()
