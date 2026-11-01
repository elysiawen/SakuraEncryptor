"""
Sakura Encryptor GUI — 图形化加密/解密工具
====================================
"""

from __future__ import annotations

import os
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import sys
from pathlib import Path
from tkinterdnd2 import TkinterDnD, DND_FILES

def resource_path(relative_path):
    """ Get absolute path to resource, works for dev and for PyInstaller """
    try:
        base_path = sys._MEIPASS
    except Exception:
        base_path = os.path.abspath(".")
    return os.path.join(base_path, relative_path)

from ske_cli.crypto import (
    NAME_SALT,
    derive_key,
    decrypt_file,
    decrypt_name,
    encrypt_file,
    encrypt_name,
)
from ske_cli.i18n import get_language, set_language, t

SKE_EXT = ".ske"

# ── Color palette (matches web dark theme) ──────────────────────
BG = "#0a0a14"
BG_CARD = "#16163a"
BG_INPUT = "#1e1e4a"
FG = "#e8e8f0"
FG_DIM = "#9090b0"
ACCENT = "#8b5cf6"
ACCENT2 = "#06b6d4"
DANGER = "#ef4444"
SUCCESS = "#22c55e"
BORDER = "#2a2a5a"


class SkeGui(TkinterDnD.Tk):
    def __init__(self):
        super().__init__()
        # Widgets whose label is a catalog key, re-applied on language change.
        self._text_widgets: list[tuple[tk.Widget, str]] = []
        self._tab_keys: dict[tk.Widget, str] = {}
        self._busy = False
        self.title(t("gui.window_title"))
        
        # Set icon
        try:
            icon_path = resource_path("icon.ico")
            if os.path.exists(icon_path):
                self.iconbitmap(icon_path)
        except Exception:
            pass
        self.configure(bg=BG)
        self.geometry("700x620")
        self.resizable(True, True)
        self.minsize(580, 520)

        # ── Style ───────────────────────────────────────────────
        style = ttk.Style(self)
        style.theme_use("clam")

        style.configure(".", background=BG, foreground=FG, fieldbackground=BG_INPUT,
                         bordercolor=BORDER, troughcolor=BG_CARD, font=("Segoe UI", 10))
        style.configure("TFrame", background=BG)
        style.configure("Card.TFrame", background=BG_CARD)
        style.configure("TLabel", background=BG, foreground=FG, font=("Segoe UI", 10))
        style.configure("Title.TLabel", background=BG, foreground=ACCENT, font=("Segoe UI", 20, "bold"))
        style.configure("Sub.TLabel", background=BG, foreground=FG_DIM, font=("Segoe UI", 9))
        style.configure("Card.TLabel", background=BG_CARD, foreground=FG, font=("Segoe UI", 10))
        style.configure("CardDim.TLabel", background=BG_CARD, foreground=FG_DIM, font=("Segoe UI", 9))

        style.configure("TEntry", fieldbackground=BG_INPUT, foreground=FG,
                         insertcolor=FG, borderwidth=1, relief="solid")
        style.map("TEntry", bordercolor=[("focus", ACCENT), ("!focus", BORDER)])

        style.configure("Accent.TButton", background=ACCENT, foreground="#ffffff",
                         font=("Segoe UI", 11, "bold"), padding=(18, 10), borderwidth=0)
        style.map("Accent.TButton",
                   background=[("active", "#7c3aed"), ("disabled", "#3a3a5a")],
                   foreground=[("disabled", "#606080")])

        style.configure("Ghost.TButton", background=BG_CARD, foreground=FG,
                         font=("Segoe UI", 9), padding=(10, 6), borderwidth=1)
        style.map("Ghost.TButton", background=[("active", BG_INPUT)])

        style.configure("green.Horizontal.TProgressbar",
                         troughcolor=BG_CARD, background=SUCCESS, bordercolor=BORDER)

        # ── Notebook Styles (Tabs) ────────────────────────────────
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", background=BG_CARD, foreground=FG_DIM,
                         padding=(12, 4), borderwidth=1)
        style.map("TNotebook.Tab",
                   background=[("selected", ACCENT), ("active", BG_INPUT)],
                   foreground=[("selected", "#ffffff"), ("active", FG)])

        style.configure("Card.TCheckbutton", background=BG_CARD, foreground=FG_DIM,
                         font=("Segoe UI", 9), focuscolor=BG_CARD)
        style.map("Card.TCheckbutton",
                   foreground=[("active", FG), ("selected", ACCENT)],
                   background=[("active", BG_CARD)])

        # ── Language selector ───────────────────────────────────
        # Clam's stock combobox is a light grey box; give it the card palette
        # so it reads as part of the header instead of a foreign control.
        style.configure("Lang.TCombobox",
                         fieldbackground=BG_INPUT, background=BG_CARD, foreground=FG,
                         arrowcolor=FG_DIM, insertcolor=FG,
                         bordercolor=BORDER, lightcolor=BORDER, darkcolor=BG_CARD,
                         padding=(8, 4), borderwidth=1, relief="flat")
        style.map("Lang.TCombobox",
                   fieldbackground=[("readonly", BG_INPUT), ("disabled", BG_CARD)],
                   foreground=[("readonly", FG), ("disabled", FG_DIM)],
                   background=[("active", BG_INPUT), ("readonly", BG_CARD)],
                   arrowcolor=[("active", ACCENT), ("focus", ACCENT)],
                   bordercolor=[("focus", ACCENT), ("active", "#4a4a8a"), ("!focus", BORDER)],
                   lightcolor=[("focus", ACCENT)],
                   darkcolor=[("focus", ACCENT)],
                   selectbackground=[("readonly", BG_INPUT)],
                   selectforeground=[("readonly", FG)])

        # The drop-down itself is a plain Listbox, styled through the option
        # database rather than ttk.
        for pattern, value in (
            ("*TCombobox*Listbox.background", BG_CARD),
            ("*TCombobox*Listbox.foreground", FG),
            ("*TCombobox*Listbox.selectBackground", ACCENT),
            ("*TCombobox*Listbox.selectForeground", "#ffffff"),
            ("*TCombobox*Listbox.borderWidth", 0),
            ("*TCombobox*Listbox.font", ("Segoe UI", 10)),
        ):
            self.option_add(pattern, value)

        # ── Header ──────────────────────────────────────────────
        header = ttk.Frame(self)
        header.pack(fill="x", padx=24, pady=(20, 10))

        brand = ttk.Frame(header)
        brand.pack(side="left", fill="x", expand=True)
        ttk.Label(brand, text="🌸 Sakura Encryptor", style="Title.TLabel").pack(anchor="w")
        self._bind_text(ttk.Label(brand, style="Sub.TLabel"), "gui.subtitle").pack(anchor="w", pady=(2, 0))

        # Language switch: the names stay in their own language so both read
        # correctly whichever one is active.
        self.lang_var = tk.StringVar(value="中文" if get_language() == "zh" else "English")
        lang_box = ttk.Combobox(
            header, textvariable=self.lang_var, values=("中文", "English"),
            state="readonly", width=8, style="Lang.TCombobox",
        )
        lang_box.pack(side="right", anchor="n")
        lang_box.bind("<<ComboboxSelected>>", self._on_language_change)

        # ── Global Password Field (Moved here) ──────────────────
        pw_card = ttk.Frame(self, style="Card.TFrame", padding=(20, 10))
        pw_card.pack(fill="x", padx=24, pady=(0, 10))
        
        row_pw = ttk.Frame(pw_card, style="Card.TFrame")
        row_pw.pack(fill="x")
        self._bind_text(ttk.Label(row_pw, style="CardDim.TLabel", width=10), "gui.password").pack(side="left")
        self.pw_var = tk.StringVar()
        self.pw_entry = ttk.Entry(row_pw, textvariable=self.pw_var, show="●")
        self.pw_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.show_pw = tk.BooleanVar(value=False)
        self.show_pw_box = self._bind_text(ttk.Checkbutton(row_pw, variable=self.show_pw,
                        command=self._toggle_pw), "gui.show_password")
        self.show_pw_box.pack(side="right")

        # ── Tabs ────────────────────────────────────────────────
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=24, pady=(0, 20))

        self.files_tab = ttk.Frame(self.notebook)
        self.text_tab = ttk.Frame(self.notebook)

        for frame, key in ((self.files_tab, "gui.tab_files"), (self.text_tab, "gui.tab_text")):
            self.notebook.add(frame, text=t(key))
            self._tab_keys[frame] = key

        # ── Files Tab ──────────────────────────────────────────
        card = ttk.Frame(self.files_tab, style="Card.TFrame", padding=20)
        card.pack(fill="x", pady=(10, 12))

        # Source dir
        row_src = ttk.Frame(card, style="Card.TFrame")
        row_src.pack(fill="x", pady=(0, 10))
        self._bind_text(ttk.Label(row_src, style="CardDim.TLabel", width=10), "gui.source").pack(side="left")
        self.src_var = tk.StringVar()
        src_entry = ttk.Entry(row_src, textvariable=self.src_var)
        src_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        src_entry.drop_target_register(DND_FILES)
        src_entry.dnd_bind("<<Drop>>", lambda e: self._on_drop(e, self.src_var))
        
        self._bind_text(ttk.Button(row_src, style="Ghost.TButton",
                   command=lambda: self._browse_file(self.src_var)), "gui.choose_file").pack(side="right", padx=(0, 4))
        self._bind_text(ttk.Button(row_src, style="Ghost.TButton",
                   command=lambda: self._browse(self.src_var)), "gui.choose_dir").pack(side="right")

        # Dest dir
        row_dst = ttk.Frame(card, style="Card.TFrame")
        row_dst.pack(fill="x", pady=(0, 10))
        self._bind_text(ttk.Label(row_dst, style="CardDim.TLabel", width=10), "gui.output").pack(side="left")
        self.dst_var = tk.StringVar()
        dst_entry = ttk.Entry(row_dst, textvariable=self.dst_var)
        dst_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        dst_entry.drop_target_register(DND_FILES)
        dst_entry.dnd_bind("<<Drop>>", lambda e: self._on_drop(e, self.dst_var))

        self._bind_text(ttk.Button(row_dst, style="Ghost.TButton",
                   command=lambda: self._browse(self.dst_var)), "gui.browse").pack(side="right")

        # Options row
        row_opt = ttk.Frame(card, style="Card.TFrame")
        row_opt.pack(fill="x", pady=(8, 0))
        self.include_root_var = tk.BooleanVar(value=False)
        cb = self._bind_text(ttk.Checkbutton(row_opt, variable=self.include_root_var,
                             style="Card.TCheckbutton"), "gui.include_root")
        cb.pack(side="left")
        
        # Add a small hint
        self._bind_text(ttk.Label(row_opt, style="CardDim.TLabel", font=("Segoe UI", 8)),
                        "gui.include_root_hint").pack(side="left", padx=(12, 0))

        # Action buttons
        actions = ttk.Frame(self.files_tab)
        actions.pack(fill="x", pady=(4, 8))

        self.btn_encrypt = self._bind_text(ttk.Button(actions, style="Accent.TButton",
                                     command=self._start_encrypt), "gui.encrypt")
        self.btn_encrypt.pack(side="left", padx=(0, 12))

        self.btn_decrypt = self._bind_text(ttk.Button(actions, style="Accent.TButton",
                                     command=self._start_decrypt), "gui.decrypt")
        self.btn_decrypt.pack(side="left")

        # Progress
        prog_frame = ttk.Frame(self.files_tab)
        prog_frame.pack(fill="x", pady=(0, 4))
        self.progress = ttk.Progressbar(prog_frame, style="green.Horizontal.TProgressbar",
                                         mode="determinate", maximum=100)
        self.progress.pack(fill="x")
        self.status_var = tk.StringVar(value=t("gui.status_ready"))
        ttk.Label(prog_frame, textvariable=self.status_var, style="Sub.TLabel").pack(anchor="w", pady=(4, 0))

        # Log
        log_label = self._bind_text(ttk.Label(self.files_tab, style="Sub.TLabel"), "gui.log")
        log_label.pack(anchor="w")

        log_frame = ttk.Frame(self.files_tab)
        log_frame.pack(fill="both", expand=True, pady=(4, 10))

        self.log_text = tk.Text(log_frame, bg=BG_CARD, fg=FG_DIM, insertbackground=FG,
                                font=("Cascadia Code", 9), bd=0, highlightthickness=1,
                                highlightbackground=BORDER, highlightcolor=ACCENT,
                                wrap="word", state="disabled")
        scrollbar = ttk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.log_text.pack(side="left", fill="both", expand=True)

        self.log_text.tag_configure("ok", foreground=SUCCESS)
        self.log_text.tag_configure("err", foreground=DANGER)
        self.log_text.tag_configure("info", foreground=ACCENT2)

        # ── Text Tab ───────────────────────────────────────────
        tcard = ttk.Frame(self.text_tab, style="Card.TFrame", padding=20)
        tcard.pack(fill="both", expand=True, pady=10)

        self._bind_text(ttk.Label(tcard, style="CardDim.TLabel"), "gui.text_input").pack(anchor="w")
        self.text_in = tk.Text(tcard, bg=BG_INPUT, fg=FG, insertbackground=FG,
                               height=6, font=("Segoe UI", 10), bd=0, highlightthickness=1,
                               highlightbackground=BORDER, highlightcolor=ACCENT2)
        self.text_in.pack(fill="x", pady=(4, 12))

        self._bind_text(ttk.Label(tcard, style="CardDim.TLabel"), "gui.text_output").pack(anchor="w")
        self.text_out = tk.Text(tcard, bg=BG_INPUT, fg=SUCCESS, insertbackground=FG,
                                height=6, font=("Cascadia Code", 10), bd=0, highlightthickness=1,
                                highlightbackground=BORDER, highlightcolor=SUCCESS)
        self.text_out.pack(fill="x", pady=(4, 12))

        tactions = ttk.Frame(tcard, style="Card.TFrame")
        tactions.pack(fill="x")
        self._bind_text(ttk.Button(tactions, style="Accent.TButton",
                   command=self._text_encrypt), "gui.text_encrypt").pack(side="left", padx=(0, 8))
        self._bind_text(ttk.Button(tactions, style="Accent.TButton",
                   command=self._text_decrypt), "gui.text_decrypt").pack(side="left")
        self._bind_text(ttk.Button(tactions, style="Ghost.TButton",
                   command=self._copy_result), "gui.copy").pack(side="right")

    # ── Helpers ─────────────────────────────────────────────────
    def _bind_text(self, widget: tk.Widget, key: str) -> tk.Widget:
        """Set a widget's label from the catalog and keep it for translation."""
        widget.configure(text=t(key))
        self._text_widgets.append((widget, key))
        return widget

    def _on_language_change(self, _event=None) -> None:
        set_language("zh" if self.lang_var.get() == "中文" else "en")
        self._retranslate()

    def _retranslate(self) -> None:
        """Re-apply the catalog to everything built from keys."""
        self.title(t("gui.window_title"))
        for widget, key in self._text_widgets:
            widget.configure(text=t(key))
        for tab, key in self._tab_keys.items():
            self.notebook.tab(tab, text=t(key))
        if not self._busy:
            self.status_var.set(t("gui.status_ready"))

    def _browse(self, var: tk.StringVar):
        path = filedialog.askdirectory()
        if path:
            var.set(path)

    def _browse_file(self, var: tk.StringVar):
        path = filedialog.askopenfilename()
        if path:
            var.set(path)

    def _on_drop(self, event, var: tk.StringVar):
        # tkinterdnd2 wraps paths in curly braces if they contain spaces
        path = event.data.strip('{}')
        if path:
            var.set(path)

    def _toggle_pw(self):
        self.pw_entry.configure(show="" if self.show_pw.get() else "●")

    def _log(self, msg: str, tag: str = ""):
        self.log_text.configure(state="normal")
        self.log_text.insert("end", msg + "\n", tag)
        self.log_text.see("end")
        self.log_text.configure(state="disabled")

    def _set_busy(self, busy: bool):
        self._busy = busy
        state = "disabled" if busy else "!disabled"
        self.btn_encrypt.state([state])
        self.btn_decrypt.state([state])

    def _validate(self) -> bool:
        if not self.src_var.get():
            messagebox.showwarning(t("gui.notice"), t("gui.need_source"))
            return False
        if not self.dst_var.get():
            messagebox.showwarning(t("gui.notice"), t("gui.need_output"))
            return False
        if not self.pw_var.get():
            messagebox.showwarning(t("gui.notice"), t("gui.need_password"))
            return False
        if not Path(self.src_var.get()).exists():
            messagebox.showerror(t("gui.error"), t("gui.source_missing"))
            return False
        if not Path(self.dst_var.get()).is_dir():
            messagebox.showerror(t("gui.error"), t("gui.output_not_dir"))
            return False
        return True

    # ── Encrypt ─────────────────────────────────────────────────
    def _start_encrypt(self):
        if not self._validate():
            return
        self._set_busy(True)
        self.progress["value"] = 0
        threading.Thread(target=self._do_encrypt, daemon=True).start()

    def _do_encrypt(self):
        src = Path(self.src_var.get())
        dst = Path(self.dst_var.get())
        password = self.pw_var.get()
        key = derive_key(password, NAME_SALT)

        # Count files and folders
        all_items = []
        if src.is_file():
            all_items.append(src)
        else:
            for root, dirs, files in os.walk(src):
                dirs.sort()
                # Include directory itself if it's empty
                if not dirs and not files:
                    all_items.append(Path(root))
                for f in files:
                    all_items.append(Path(root) / f)

        total = len(all_items)
        self.after(0, lambda: self._log(t("gui.scanned", count=total), "info"))
        self.after(0, lambda: self.status_var.set(t("gui.processing", done=0, total=total)))

        done = 0
        errors = 0

        for item in all_items:
            rel_str = item.name if src.is_file() else str(item.relative_to(src))
            try:
                # Encrypt directory path
                if src.is_file():
                    enc_dir = dst
                else:
                    rel_p = item.relative_to(src)
                    
                    enc_parts = []
                    if self.include_root_var.get():
                        enc_parts.append(encrypt_name(src.name, key))
                    
                    # If item is a file, use parent parts. If it's a dir, use all parts.
                    parts_to_encrypt = rel_p.parts if item.is_dir() else rel_p.parent.parts
                    for p in parts_to_encrypt:
                        enc_parts.append(encrypt_name(p, key))
                        
                    enc_dir = dst / Path(*enc_parts) if enc_parts else dst
                    enc_dir.mkdir(parents=True, exist_ok=True)

                if item.is_file():
                    # Encrypt file name
                    enc_fname = encrypt_name(item.name, key) + SKE_EXT
                    dst_file = enc_dir / enc_fname
                    encrypt_file(item, dst_file, password)
                    msg = t("gui.item_file", name=rel_str)
                else:
                    msg = t("gui.item_dir_encrypted", name=rel_str)

                done += 1
                pct = int(done / total * 100) if total else 100
                self.after(0, lambda d=done, p=pct, m=msg: (
                    self.progress.__setitem__("value", p),
                    self.status_var.set(t("gui.encrypting_progress", done=d, total=total)),
                    self._log(m, "ok"),
                ))
            except Exception as exc:
                errors += 1
                done += 1
                self.after(0, lambda r=rel_str, e=str(exc): self._log(
                    t("gui.item_failed", name=r, error=e), "err"))

        self.after(0, lambda: (
            self.status_var.set(t("gui.finished", ok=done - errors, total=total, errors=errors)),
            self.progress.__setitem__("value", 100),
            self._log(t("gui.encrypt_done", ok=done - errors, errors=errors), "info"),
            self._set_busy(False),
        ))

    # ── Decrypt ─────────────────────────────────────────────────
    def _start_decrypt(self):
        if not self._validate():
            return
        self._set_busy(True)
        self.progress["value"] = 0
        threading.Thread(target=self._do_decrypt, daemon=True).start()

    def _do_decrypt(self):
        src = Path(self.src_var.get())
        dst = Path(self.dst_var.get())
        password = self.pw_var.get()
        key = derive_key(password, NAME_SALT)

        # Count files and folders
        all_items = []
        if src.is_file():
            if src.name.endswith(SKE_EXT):
                all_items.append(src)
        else:
            for root, dirs, files in os.walk(src):
                dirs.sort()
                if not dirs and not files:
                    all_items.append(Path(root))
                for f in files:
                    if f.endswith(SKE_EXT):
                        all_items.append(Path(root) / f)

        total = len(all_items)
        self.after(0, lambda: self._log(t("gui.scanned", count=total), "info"))
        self.after(0, lambda: self.status_var.set(t("gui.processing", done=0, total=total)))

        done = 0
        errors = 0

        for item in all_items:
            rel_str = item.name if src.is_file() else str(item.relative_to(src))
            try:
                # Decrypt directory path
                if src.is_file():
                    dec_dir = dst
                else:
                    rel_p = item.relative_to(src)
                    dec_parts = []
                    for p in rel_p.parts if item.is_dir() else rel_p.parent.parts:
                        try:
                            dec_parts.append(decrypt_name(p, key))
                        except Exception:
                            dec_parts.append(p)
                    dec_dir = dst / Path(*dec_parts) if dec_parts else dst
                    dec_dir.mkdir(parents=True, exist_ok=True)

                if item.is_file():
                    # Decrypt file name
                    enc_name_part = item.name[:-len(SKE_EXT)]
                    dec_fname = decrypt_name(enc_name_part, key)
                    if not dec_fname:
                        raise Exception("Failed to decrypt filename")
                    dst_file = dec_dir / dec_fname
                    decrypt_file(item, dst_file, password)
                    msg = t("gui.item_file", name=dec_fname)
                else:
                    msg = t("gui.item_dir_decrypted")

                done += 1
                pct = int(done / total * 100) if total else 100
                self.after(0, lambda d=done, p=pct, m=msg: (
                    self.progress.__setitem__("value", p),
                    self.status_var.set(t("gui.decrypting_progress", done=d, total=total)),
                    self._log(m, "ok"),
                ))
            except Exception as exc:
                errors += 1
                done += 1
                self.after(0, lambda r=rel_str, e=str(exc): self._log(
                    t("gui.item_failed", name=r, error=e), "err"))

        self.after(0, lambda: (
            self.status_var.set(t("gui.finished", ok=done - errors, total=total, errors=errors)),
            self.progress.__setitem__("value", 100),
            self._log(t("gui.decrypt_done", ok=done - errors, errors=errors), "info"),
            self._set_busy(False),
        ))

    # ── Text Converter Logic ────────────────────────────────────
    def _text_encrypt(self):
        password = self.pw_var.get()
        if not password:
            messagebox.showwarning(t("gui.notice"), t("gui.text_need_password"))
            return
        
        plain = self.text_in.get("1.0", "end-1c").strip()
        if not plain:
            return
            
        try:
            key = derive_key(password, NAME_SALT)
            cipher = encrypt_name(plain, key)
            self.text_out.delete("1.0", "end")
            self.text_out.insert("1.0", cipher)
        except Exception as e:
            messagebox.showerror(t("gui.error"), t("gui.text_encrypt_failed", error=e))

    def _text_decrypt(self):
        password = self.pw_var.get()
        if not password:
            messagebox.showwarning(t("gui.notice"), t("gui.text_need_password"))
            return
            
        cipher = self.text_in.get("1.0", "end-1c").strip()
        if not cipher:
            return
            
        try:
            key = derive_key(password, NAME_SALT)
            plain = decrypt_name(cipher, key)
            self.text_out.delete("1.0", "end")
            self.text_out.insert("1.0", plain)
        except Exception as e:
            messagebox.showerror(t("gui.error"), t("gui.text_decrypt_failed", error=e))

    def _copy_result(self):
        res = self.text_out.get("1.0", "end-1c").strip()
        if res:
            self.clipboard_clear()
            self.clipboard_append(res)
            self.status_var.set(t("gui.copied"))


def main():
    app = SkeGui()
    app.mainloop()


if __name__ == "__main__":
    main()
