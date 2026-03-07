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
    derive_key,
    decrypt_file,
    decrypt_name,
    encrypt_file,
    encrypt_name,
)

SKE_EXT = ".ske"
NAME_SALT = b"ske-name-salt-00"

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
        self.title("Sakura Encryptor Client")
        
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

        # ── Header ──────────────────────────────────────────────
        header = ttk.Frame(self)
        header.pack(fill="x", padx=24, pady=(20, 10))
        ttk.Label(header, text="🌸 Sakura Encryptor", style="Title.TLabel").pack(anchor="w")
        ttk.Label(header, text="安全的一键加密解决方案", style="Sub.TLabel").pack(anchor="w", pady=(2, 0))

        # ── Global Password Field (Moved here) ──────────────────
        pw_card = ttk.Frame(self, style="Card.TFrame", padding=(20, 10))
        pw_card.pack(fill="x", padx=24, pady=(0, 10))
        
        row_pw = ttk.Frame(pw_card, style="Card.TFrame")
        row_pw.pack(fill="x")
        ttk.Label(row_pw, text="🔑 主密码", style="CardDim.TLabel", width=10).pack(side="left")
        self.pw_var = tk.StringVar()
        self.pw_entry = ttk.Entry(row_pw, textvariable=self.pw_var, show="●")
        self.pw_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.show_pw = tk.BooleanVar(value=False)
        ttk.Checkbutton(row_pw, text="显示", variable=self.show_pw,
                        command=self._toggle_pw).pack(side="right")

        # ── Tabs ────────────────────────────────────────────────
        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=24, pady=(0, 20))

        self.files_tab = ttk.Frame(self.notebook)
        self.text_tab = ttk.Frame(self.notebook)

        self.notebook.add(self.files_tab, text=" 📂 文件模式 ")
        self.notebook.add(self.text_tab, text=" 🔤 文本转换 ")

        # ── Files Tab ──────────────────────────────────────────
        card = ttk.Frame(self.files_tab, style="Card.TFrame", padding=20)
        card.pack(fill="x", pady=(10, 12))

        # Source dir
        row_src = ttk.Frame(card, style="Card.TFrame")
        row_src.pack(fill="x", pady=(0, 10))
        ttk.Label(row_src, text="源", style="CardDim.TLabel", width=10).pack(side="left")
        self.src_var = tk.StringVar()
        src_entry = ttk.Entry(row_src, textvariable=self.src_var)
        src_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        src_entry.drop_target_register(DND_FILES)
        src_entry.dnd_bind("<<Drop>>", lambda e: self._on_drop(e, self.src_var))
        
        ttk.Button(row_src, text="选文件…", style="Ghost.TButton",
                   command=lambda: self._browse_file(self.src_var)).pack(side="right", padx=(0, 4))
        ttk.Button(row_src, text="选目录…", style="Ghost.TButton",
                   command=lambda: self._browse(self.src_var)).pack(side="right")

        # Dest dir
        row_dst = ttk.Frame(card, style="Card.TFrame")
        row_dst.pack(fill="x", pady=(0, 10))
        ttk.Label(row_dst, text="输出目录", style="CardDim.TLabel", width=10).pack(side="left")
        self.dst_var = tk.StringVar()
        dst_entry = ttk.Entry(row_dst, textvariable=self.dst_var)
        dst_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        dst_entry.drop_target_register(DND_FILES)
        dst_entry.dnd_bind("<<Drop>>", lambda e: self._on_drop(e, self.dst_var))

        ttk.Button(row_dst, text="浏览…", style="Ghost.TButton",
                   command=lambda: self._browse(self.dst_var)).pack(side="right")

        # Action buttons
        actions = ttk.Frame(self.files_tab)
        actions.pack(fill="x", pady=(4, 8))

        self.btn_encrypt = ttk.Button(actions, text="🔒 一键加密", style="Accent.TButton", command=self._start_encrypt)
        self.btn_encrypt.pack(side="left", padx=(0, 12))

        self.btn_decrypt = ttk.Button(actions, text="🔓 解密还原", style="Accent.TButton", command=self._start_decrypt)
        self.btn_decrypt.pack(side="left")

        # Progress
        prog_frame = ttk.Frame(self.files_tab)
        prog_frame.pack(fill="x", pady=(0, 4))
        self.progress = ttk.Progressbar(prog_frame, style="green.Horizontal.TProgressbar",
                                         mode="determinate", maximum=100)
        self.progress.pack(fill="x")
        self.status_var = tk.StringVar(value="就绪")
        ttk.Label(prog_frame, textvariable=self.status_var, style="Sub.TLabel").pack(anchor="w", pady=(4, 0))

        # Log
        log_label = ttk.Label(self.files_tab, text="日志", style="Sub.TLabel")
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

        ttk.Label(tcard, text="输入文本", style="CardDim.TLabel").pack(anchor="w")
        self.text_in = tk.Text(tcard, bg=BG_INPUT, fg=FG, insertbackground=FG,
                               height=6, font=("Segoe UI", 10), bd=0, highlightthickness=1,
                               highlightbackground=BORDER, highlightcolor=ACCENT2)
        self.text_in.pack(fill="x", pady=(4, 12))

        ttk.Label(tcard, text="输出结果", style="CardDim.TLabel").pack(anchor="w")
        self.text_out = tk.Text(tcard, bg=BG_INPUT, fg=SUCCESS, insertbackground=FG,
                                height=6, font=("Cascadia Code", 10), bd=0, highlightthickness=1,
                                highlightbackground=BORDER, highlightcolor=SUCCESS)
        self.text_out.pack(fill="x", pady=(4, 12))

        tactions = ttk.Frame(tcard, style="Card.TFrame")
        tactions.pack(fill="x")
        ttk.Button(tactions, text="🔒 文本加密", style="Accent.TButton", 
                   command=self._text_encrypt).pack(side="left", padx=(0, 8))
        ttk.Button(tactions, text="🔓 文本解密", style="Accent.TButton", 
                   command=self._text_decrypt).pack(side="left")
        ttk.Button(tactions, text="📋 复制结果", style="Ghost.TButton", 
                   command=self._copy_result).pack(side="right")

    # ── Helpers ─────────────────────────────────────────────────
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
        state = "disabled" if busy else "!disabled"
        self.btn_encrypt.state([state])
        self.btn_decrypt.state([state])

    def _validate(self) -> bool:
        if not self.src_var.get():
            messagebox.showwarning("提示", "请选择源目录")
            return False
        if not self.dst_var.get():
            messagebox.showwarning("提示", "请选择输出目录")
            return False
        if not self.pw_var.get():
            messagebox.showwarning("提示", "请输入主密码")
            return False
        if not Path(self.src_var.get()).exists():
            messagebox.showerror("错误", "源文件/目录不存在")
            return False
        if not Path(self.dst_var.get()).is_dir():
            messagebox.showerror("错误", "输出必须是一个目录")
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

        # Count files first
        all_files = []
        if src.is_file():
            all_files.append(src)
        else:
            for root, dirs, files in os.walk(src):
                dirs.sort()
                for f in files:
                    all_files.append(Path(root) / f)

        total = len(all_files)
        self.after(0, lambda: self._log(f"扫描到 {total} 个文件", "info"))
        self.after(0, lambda: self.status_var.set(f"加密中… 0/{total}"))

        done = 0
        errors = 0

        for filepath in all_files:
            rel_str = filepath.name if src.is_file() else str(filepath.relative_to(src))
            try:
                # Encrypt directory path
                if src.is_file():
                    enc_dir = dst
                else:
                    rel_p = filepath.relative_to(src)
                    enc_parts = [encrypt_name(p, key) for p in rel_p.parent.parts]
                    enc_dir = dst / Path(*enc_parts) if enc_parts else dst
                    enc_dir.mkdir(parents=True, exist_ok=True)

                # Encrypt file name
                enc_fname = encrypt_name(filepath.name, key) + SKE_EXT
                dst_file = enc_dir / enc_fname

                encrypt_file(filepath, dst_file, password)
                done += 1
                pct = int(done / total * 100) if total else 100
                self.after(0, lambda d=done, p=pct, r=rel_str: (
                    self.progress.__setitem__("value", p),
                    self.status_var.set(f"加密中… {d}/{total}"),
                    self._log(f"  ✓ {r}", "ok"),
                ))
            except Exception as exc:
                errors += 1
                done += 1
                self.after(0, lambda r=rel_str, e=str(exc): self._log(f"  ✗ {r}: {e}", "err"))

        self.after(0, lambda: (
            self.status_var.set(f"完成 — 成功 {done - errors}/{total}，失败 {errors}"),
            self.progress.__setitem__("value", 100),
            self._log(f"加密完成：{done - errors} 成功，{errors} 失败", "info"),
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

        all_files = []
        if src.is_file():
            if src.name.endswith(SKE_EXT):
                all_files.append(src)
        else:
            for root, dirs, files in os.walk(src):
                dirs.sort()
                for f in files:
                    if f.endswith(SKE_EXT):
                        all_files.append(Path(root) / f)

        total = len(all_files)
        self.after(0, lambda: self._log(f"扫描到 {total} 个 .skmod 文件", "info"))
        self.after(0, lambda: self.status_var.set(f"解密中… 0/{total}"))

        done = 0
        errors = 0

        for filepath in all_files:
            rel_str = filepath.name if src.is_file() else str(filepath.relative_to(src))
            try:
                # Decrypt directory path
                if src.is_file():
                    dec_dir = dst
                else:
                    rel_p = filepath.relative_to(src)
                    dec_parts = []
                    for p in rel_p.parent.parts:
                        try:
                            dec_parts.append(decrypt_name(p, key))
                        except Exception:
                            dec_parts.append(p)
                    dec_dir = dst / Path(*dec_parts) if dec_parts else dst
                    dec_dir.mkdir(parents=True, exist_ok=True)

                # Decrypt file name
                enc_name_part = filepath.name[:-len(SKE_EXT)]
                dec_fname = decrypt_name(enc_name_part, key)
                if not dec_fname:
                    raise Exception("Failed to decrypt filename")
                dst_file = dec_dir / dec_fname

                decrypt_file(filepath, dst_file, password)
                done += 1
                pct = int(done / total * 100) if total else 100
                self.after(0, lambda d=done, p=pct, n=dec_fname: (
                    self.progress.__setitem__("value", p),
                    self.status_var.set(f"解密中… {d}/{total}"),
                    self._log(f"  ✓ → {n}", "ok"),
                ))
            except Exception as exc:
                errors += 1
                done += 1
                self.after(0, lambda r=rel_str, e=str(exc): self._log(f"  ✗ {r}: {e}", "err"))

        self.after(0, lambda: (
            self.status_var.set(f"完成 — 成功 {done - errors}/{total}，失败 {errors}"),
            self.progress.__setitem__("value", 100),
            self._log(f"解密完成：{done - errors} 成功，{errors} 失败", "info"),
            self._set_busy(False),
        ))

    # ── Text Converter Logic ────────────────────────────────────
    def _text_encrypt(self):
        password = self.pw_var.get()
        if not password:
            messagebox.showwarning("提示", "文本转换也需要先输入主密码")
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
            messagebox.showerror("错误", f"加密失败: {e}")

    def _text_decrypt(self):
        password = self.pw_var.get()
        if not password:
            messagebox.showwarning("提示", "文本转换也需要先输入主密码")
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
            messagebox.showerror("错误", f"解密失败 (可能是密码错误或输入格式不正确): {e}")

    def _copy_result(self):
        res = self.text_out.get("1.0", "end-1c").strip()
        if res:
            self.clipboard_clear()
            self.clipboard_append(res)
            self.status_var.set("已复制到剪贴板")


def main():
    app = SkeGui()
    app.mainloop()


if __name__ == "__main__":
    main()
