"""
Language catalog for the Python client (`ske` and `ske-gui`).

The language is picked once, in this order:

  1. an explicit choice (`--lang` on the CLI, the selector in the GUI)
  2. the `SKE_LANG` environment variable
  3. the system UI language
  4. English

Every user-facing string lives in `CATALOG` under a dotted key, and `t()` fills
the `{placeholders}` in it. Only the two languages the other clients ship are
listed, so a new string always has both halves to compare.
"""

from __future__ import annotations

import locale
import os
import sys

SUPPORTED_LANGUAGES = ("zh", "en")
DEFAULT_LANGUAGE = "en"


CATALOG: dict[str, dict[str, str]] = {
    "zh": {
        # ── Command line ────────────────────────────────────────
        "cli.group_help": "Sakura Encryptor — 零知识加密工具。",
        "cli.encrypt_help": "将目录树加密为 .ske 格式。",
        "cli.decrypt_help": "将 .ske 目录树解密回原始文件。",
        "cli.lang_help": "界面语言（默认跟随系统）。",
        "cli.input_encrypt": "要加密的源文件或目录。",
        "cli.output_encrypt": "加密文件的输出目录。",
        "cli.password": "加密密码。",
        "cli.prompt_password": "密码",
        "cli.include_root": "把源文件夹本身也纳入加密输出。",
        "cli.input_decrypt": "要解密的文件或目录。",
        "cli.output_decrypt": "还原文件的输出目录。",
        "cli.password_decrypt": "解密密码。",
        "cli.encrypting": "  正在加密: {path}",
        "cli.encrypting_to": "  正在加密: {src} → {dst}",
        "cli.decrypting_to": "  正在解密: {src} → {dst}",
        "cli.skipping_not_ske": "  跳过（不是 .ske）: {name}",
        "cli.skipping_bad_name": "  跳过（文件名解密失败）: {name}",
        "cli.empty_dir": "  创建空目录: {path}",
        "cli.restore_empty_dir": "  恢复空目录: {path}",
        "cli.error": "  错误: {message}",
        "cli.must_be_ske": "错误：单文件必须以 .ske 结尾",
        "cli.done": "完成。",
        "cli.help_option": "显示此帮助信息并退出。",
        "cli.required": "必填",

        # ── GUI ─────────────────────────────────────────────────
        "gui.window_title": "Sakura Encryptor Client",
        "gui.subtitle": "安全的一键加密解决方案",
        "gui.password": "🔑 主密码",
        "gui.show_password": "显示",
        "gui.tab_files": " 📂 文件模式 ",
        "gui.tab_text": " 🔤 文本转换 ",
        "gui.source": "源",
        "gui.choose_file": "选文件…",
        "gui.choose_dir": "选目录…",
        "gui.output": "输出目录",
        "gui.browse": "浏览…",
        "gui.include_root": " 📂 包含根目录",
        "gui.include_root_hint": "💡 勾选后将加密文件夹本身及其内部结构",
        "gui.encrypt": "🔒 一键加密",
        "gui.decrypt": "🔓 解密还原",
        "gui.status_ready": "就绪",
        "gui.log": "日志",
        "gui.text_input": "输入文本",
        "gui.text_output": "输出结果",
        "gui.text_encrypt": "🔒 文本加密",
        "gui.text_decrypt": "🔓 文本解密",
        "gui.copy": "📋 复制结果",
        "gui.notice": "提示",
        "gui.error": "错误",
        "gui.need_source": "请选择源目录",
        "gui.need_output": "请选择输出目录",
        "gui.need_password": "请输入主密码",
        "gui.source_missing": "源文件/目录不存在",
        "gui.output_not_dir": "输出必须是一个目录",
        "gui.scanned": "扫描到 {count} 个项目",
        "gui.processing": "处理中… {done}/{total}",
        "gui.encrypting_progress": "加密中… {done}/{total}",
        "gui.decrypting_progress": "解密中… {done}/{total}",
        "gui.finished": "完成 — 成功 {ok}/{total}，失败 {errors}",
        "gui.encrypt_done": "加密完成：{ok} 成功，{errors} 失败",
        "gui.decrypt_done": "解密完成：{ok} 成功，{errors} 失败",
        "gui.item_file": "  ✓ {name}",
        "gui.item_dir_encrypted": "  📁 {name}（文件夹已同步）",
        "gui.item_dir_decrypted": "  📁 已恢复目录结构",
        "gui.item_failed": "  ✗ {name}: {error}",
        "gui.copied": "已复制到剪贴板",
        "gui.text_need_password": "文本转换也需要先输入主密码",
        "gui.text_encrypt_failed": "加密失败: {error}",
        "gui.text_decrypt_failed": "解密失败（可能是密码错误或输入格式不正确）: {error}",
    },
    "en": {
        # ── Command line ────────────────────────────────────────
        "cli.group_help": "Sakura Encryptor — zero-knowledge encryption tool.",
        "cli.encrypt_help": "Encrypt a directory tree into .ske format.",
        "cli.decrypt_help": "Decrypt a .ske directory tree back to original files.",
        "cli.lang_help": "Interface language (system default when omitted).",
        "cli.input_encrypt": "Source file or directory to encrypt.",
        "cli.output_encrypt": "Output directory for encrypted files.",
        "cli.password": "Encryption password.",
        "cli.prompt_password": "Password",
        "cli.include_root": "Include the source folder itself in the encrypted output.",
        "cli.input_decrypt": "Encrypted file or directory to decrypt.",
        "cli.output_decrypt": "Output directory for restored files.",
        "cli.password_decrypt": "Decryption password.",
        "cli.encrypting": "  encrypting: {path}",
        "cli.encrypting_to": "  encrypting: {src} → {dst}",
        "cli.decrypting_to": "  decrypting: {src} → {dst}",
        "cli.skipping_not_ske": "  skipping (not .ske): {name}",
        "cli.skipping_bad_name": "  skipping (name decrypt failed): {name}",
        "cli.empty_dir": "  creating empty dir: {path}",
        "cli.restore_empty_dir": "  restoring empty dir: {path}",
        "cli.error": "  ERROR: {message}",
        "cli.must_be_ske": "Error: single file must end with .ske",
        "cli.done": "Done.",
        "cli.help_option": "Show this message and exit.",
        "cli.required": "required",

        # ── GUI ─────────────────────────────────────────────────
        "gui.window_title": "Sakura Encryptor Client",
        "gui.subtitle": "Secure one-click encryption",
        "gui.password": "🔑 Master password",
        "gui.show_password": "Show",
        "gui.tab_files": " 📂 Files ",
        "gui.tab_text": " 🔤 Text ",
        "gui.source": "Source",
        "gui.choose_file": "Choose file…",
        "gui.choose_dir": "Choose folder…",
        "gui.output": "Output",
        "gui.browse": "Browse…",
        "gui.include_root": " 📂 Include root folder",
        "gui.include_root_hint": "💡 Encrypts the folder itself and its structure too",
        "gui.encrypt": "🔒 Encrypt",
        "gui.decrypt": "🔓 Decrypt",
        "gui.status_ready": "Ready",
        "gui.log": "Log",
        "gui.text_input": "Input text",
        "gui.text_output": "Output",
        "gui.text_encrypt": "🔒 Encrypt text",
        "gui.text_decrypt": "🔓 Decrypt text",
        "gui.copy": "📋 Copy result",
        "gui.notice": "Notice",
        "gui.error": "Error",
        "gui.need_source": "Choose a source folder first",
        "gui.need_output": "Choose an output folder first",
        "gui.need_password": "Enter the master password first",
        "gui.source_missing": "The source file/folder does not exist",
        "gui.output_not_dir": "The output must be a folder",
        "gui.scanned": "Scanned {count} item(s)",
        "gui.processing": "Working… {done}/{total}",
        "gui.encrypting_progress": "Encrypting… {done}/{total}",
        "gui.decrypting_progress": "Decrypting… {done}/{total}",
        "gui.finished": "Finished — {ok}/{total} succeeded, {errors} failed",
        "gui.encrypt_done": "Encryption finished: {ok} succeeded, {errors} failed",
        "gui.decrypt_done": "Decryption finished: {ok} succeeded, {errors} failed",
        "gui.item_file": "  ✓ {name}",
        "gui.item_dir_encrypted": "  📁 {name} (folder synced)",
        "gui.item_dir_decrypted": "  📁 folder structure restored",
        "gui.item_failed": "  ✗ {name}: {error}",
        "gui.copied": "Copied to clipboard",
        "gui.text_need_password": "Text conversion needs the master password too",
        "gui.text_encrypt_failed": "Encryption failed: {error}",
        "gui.text_decrypt_failed": "Decryption failed (wrong password or malformed input): {error}",
    },
}


def normalize_language(value: str | None) -> str | None:
    """`zh-CN`, `zh_CN`, `Chinese (Simplified)_China` → `zh`; unknown → None."""
    if not value:
        return None

    lowered = value.replace("_", "-").strip().lower()
    if lowered.startswith("zh") or "chinese" in lowered:
        return "zh"
    if lowered.startswith("en"):
        return "en"
    return None


def _system_language() -> str:
    for name in ("LC_ALL", "LC_MESSAGES", "LANG"):
        found = normalize_language(os.environ.get(name))
        if found:
            return found

    if sys.platform == "win32":
        # The Windows UI language is what the user set in Settings; the C
        # locale below can report a code page instead, which says nothing.
        try:
            import ctypes

            langid = ctypes.windll.kernel32.GetUserDefaultUILanguage()
            # Primary language id 0x04 is Chinese (any region).
            return "zh" if (langid & 0x3FF) == 0x04 else "en"
        except Exception:
            pass

    try:
        code = locale.getlocale(locale.LC_MESSAGES)[0]
    except Exception:
        code = None
    return normalize_language(code) or DEFAULT_LANGUAGE


def default_language() -> str:
    """`SKE_LANG` when set, otherwise the system language."""
    return normalize_language(os.environ.get("SKE_LANG")) or _system_language()


_LANGUAGE = default_language()


def get_language() -> str:
    return _LANGUAGE


def set_language(language: str | None) -> str:
    """Switch the active language; unknown values leave it untouched."""
    global _LANGUAGE
    found = normalize_language(language)
    if found:
        _LANGUAGE = found
    return _LANGUAGE


def t(key: str, **values: object) -> str:
    """Translate `key`, filling `{placeholders}` from `values`."""
    template = CATALOG.get(_LANGUAGE, {}).get(key) or CATALOG[DEFAULT_LANGUAGE].get(key, key)
    if not values:
        return template
    try:
        return template.format(**values)
    except (KeyError, IndexError):
        return template
