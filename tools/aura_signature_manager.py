"""Aura Signature para PC: gestiona los fondos de la galería Signature y los publica en GitHub.

Guardá imágenes en «Documentos\\Aura Signature\\<Colección>\\» (o arrastralas a la ventana) y se publican
solas: se preparan en la PC (1440×3120, miniatura, sin repetidas) y se suben en un solo commit a la rama
main del repositorio Aura. La app del teléfono las muestra en Signature en unos minutos.
"""
from __future__ import annotations

import io
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
import traceback
import webbrowser
from pathlib import Path

import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

from PIL import Image, ImageTk

import aura_signature_sync as sync

APP = "Aura Signature"
VERSION = "1.0.0"
TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
IS_WINDOWS = sys.platform == "win32"

try:  # arrastrar y soltar (opcional)
    from tkinterdnd2 import DND_FILES, TkinterDnD
except Exception:  # pragma: no cover
    TkinterDnD = None
    DND_FILES = None


# ------------------------------------------------------------------ configuración

def data_dir() -> Path:
    base = os.environ.get("APPDATA") if IS_WINDOWS else os.environ.get("XDG_CONFIG_HOME", str(Path.home() / ".config"))
    return Path(base or Path.home()) / "AuraSignature"


def default_folder() -> Path:
    docs = Path.home() / "Documents"
    if IS_WINDOWS:
        try:  # la carpeta Documentos real (puede estar en OneDrive)
            import ctypes.wintypes
            buf = ctypes.create_unicode_buffer(ctypes.wintypes.MAX_PATH)
            ctypes.windll.shell32.SHGetFolderPathW(None, 5, None, 0, buf)
            if buf.value:
                docs = Path(buf.value)
        except Exception:
            pass
    return docs / "Aura Signature"


class Config:
    def __init__(self):
        self.file = data_dir() / "config.json"
        try:
            self.values = json.loads(self.file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.values = {}
        self.values.setdefault("repo", "georgestrike25-afk/Aura")
        self.values.setdefault("branch", "main")
        self.values.setdefault("folder", str(default_folder()))

    def save(self):
        self.file.parent.mkdir(parents=True, exist_ok=True)
        self.file.write_text(json.dumps(self.values, ensure_ascii=False, indent=1), encoding="utf-8")

    # El token va al administrador de credenciales de Windows (nunca a un archivo del repositorio).
    def token(self) -> str | None:
        try:
            import keyring
            value = keyring.get_password(APP, "github")
            if value:
                return value
        except Exception:
            pass
        return self.values.get("token_fallback")

    def set_token(self, token: str):
        try:
            import keyring
            keyring.set_password(APP, "github", token)
            self.values.pop("token_fallback", None)
        except Exception:
            self.values["token_fallback"] = token
        self.save()


def set_autostart(enabled: bool):
    if not IS_WINDOWS:
        return
    import winreg
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE)
    with key:
        if enabled:
            exe = sys.executable if getattr(sys, "frozen", False) else f'"{sys.executable}" "{Path(__file__).resolve()}"'
            winreg.SetValueEx(key, APP, 0, winreg.REG_SZ, f'"{exe}" --minimized' if getattr(sys, "frozen", False) else f"{exe} --minimized")
        else:
            try:
                winreg.DeleteValue(key, APP)
            except FileNotFoundError:
                pass


def open_path(path: Path):
    path.mkdir(parents=True, exist_ok=True)
    if IS_WINDOWS:
        os.startfile(path)  # noqa: S606
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])


# ------------------------------------------------------------------ ventana

PALETTE = {"bg": "#111418", "panel": "#1A1F25", "card": "#20262C", "text": "#EDE9E1", "muted": "#9AA3AB", "accent": "#D8BA8B"}
THUMB = (108, 234)


class ManagerApp:
    def __init__(self, root: tk.Tk, start_minimized: bool = False):
        self.root = root
        self.cfg = Config()
        self.events: queue.Queue = queue.Queue()
        self.jobs: queue.Queue = queue.Queue()
        self.gallery: sync.Gallery | None = None
        self.folder: sync.FolderSync | None = None
        self.selected_col: str | None = None
        self.selected_works: set[str] = set()
        self.images: dict[str, ImageTk.PhotoImage] = {}
        self.cache = data_dir() / "cache"
        self.busy = False

        root.title(f"{APP}")
        root.geometry("1120x740")
        root.minsize(860, 560)
        self._style()
        self._build()
        self._connect()
        threading.Thread(target=self._worker, daemon=True).start()
        root.after(150, self._drain)
        root.after(4000, self._tick)
        if start_minimized:
            root.iconify()

    # ---------- aspecto

    def _style(self):
        try:
            import sv_ttk
            sv_ttk.set_theme("dark")
        except Exception:
            pass
        s = ttk.Style()
        s.configure("Title.TLabel", font=("Segoe UI Semibold", 18))
        s.configure("Muted.TLabel", foreground=PALETTE["muted"])
        s.configure("Accent.TButton")

    def _build(self):
        top = ttk.Frame(self.root, padding=(16, 12))
        top.pack(fill="x")
        ttk.Label(top, text="Aura Signature", style="Title.TLabel").pack(side="left")
        self.status = ttk.Label(top, text="", style="Muted.TLabel")
        self.status.pack(side="left", padx=16)
        ttk.Button(top, text="Configuración", command=self.settings).pack(side="right", padx=4)
        ttk.Button(top, text="Actualizar", command=lambda: self.run(self._refresh, "Actualizando la galería…")).pack(side="right", padx=4)
        ttk.Button(top, text="Abrir carpeta", command=self.open_folder).pack(side="right", padx=4)
        ttk.Button(top, text="Agregar imágenes…", style="Accent.TButton", command=self.add_images).pack(side="right", padx=4)

        body = ttk.PanedWindow(self.root, orient="horizontal")
        body.pack(fill="both", expand=True, padx=16)

        left = ttk.Frame(body, padding=(0, 0, 12, 0))
        ttk.Label(left, text="Colecciones", style="Muted.TLabel").pack(anchor="w")
        self.cols = ttk.Treeview(left, show="tree", selectmode="browse", height=18)
        self.cols.pack(fill="both", expand=True, pady=6)
        self.cols.bind("<<TreeviewSelect>>", lambda _: self._select_collection())
        row = ttk.Frame(left)
        row.pack(fill="x")
        for text, cmd in (("Nueva", self.new_collection), ("Editar", self.edit_collection), ("↑", lambda: self.move_collection(-1)),
                          ("↓", lambda: self.move_collection(1)), ("Borrar", self.delete_collection)):
            ttk.Button(row, text=text, command=cmd, width=6 if len(text) > 1 else 3).pack(side="left", padx=2)
        body.add(left, weight=1)

        right = ttk.Frame(body)
        bar = ttk.Frame(right)
        bar.pack(fill="x")
        self.col_title = ttk.Label(bar, text="", font=("Segoe UI Semibold", 14))
        self.col_title.pack(side="left")
        self.sel_label = ttk.Label(bar, text="", style="Muted.TLabel")
        self.sel_label.pack(side="left", padx=12)
        ttk.Button(bar, text="Borrar", command=self.delete_works).pack(side="right", padx=2)
        ttk.Button(bar, text="Mover a…", command=self.move_works).pack(side="right", padx=2)
        ttk.Button(bar, text="Renombrar", command=self.rename_work).pack(side="right", padx=2)

        holder = ttk.Frame(right)
        holder.pack(fill="both", expand=True, pady=8)
        self.canvas = tk.Canvas(holder, highlightthickness=0, bg=PALETTE["bg"])
        scroll = ttk.Scrollbar(holder, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.grid = tk.Frame(self.canvas, bg=PALETTE["bg"])
        self.canvas.create_window((0, 0), window=self.grid, anchor="nw")
        self.grid.bind("<Configure>", lambda _: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda _: self._render_works())
        self.canvas.bind_all("<MouseWheel>", lambda e: self.canvas.yview_scroll(int(-e.delta / 120), "units"))
        body.add(right, weight=4)

        bottom = ttk.Frame(self.root, padding=(16, 6, 16, 12))
        bottom.pack(fill="x")
        self.progress = ttk.Progressbar(bottom, mode="indeterminate", length=160)
        self.progress.pack(side="right")
        self.log_box = tk.Text(bottom, height=5, bg=PALETTE["panel"], fg=PALETTE["text"], relief="flat", wrap="word",
                               font=("Segoe UI", 9), state="disabled")
        self.log_box.pack(side="left", fill="x", expand=True, padx=(0, 12))

        if TkinterDnD is not None and hasattr(self.root, "drop_target_register"):
            self.root.drop_target_register(DND_FILES)
            self.root.dnd_bind("<<Drop>>", self._on_drop)

    # ---------- conexión

    def _connect(self):
        token = self.cfg.token()
        folder = Path(self.cfg.values["folder"])
        self.folder = sync.FolderSync(folder, data_dir() / "sync-state.json")
        if not token:
            self.status.configure(text="Sin conectar · tocá Configuración y pegá tu token de GitHub")
            self.gallery = None
            self.root.after(400, self.settings)
            return
        repo = sync.GitHubRepo(token, self.cfg.values["repo"], self.cfg.values["branch"])
        self.gallery = sync.Gallery(repo)
        self.status.configure(text=f"{self.cfg.values['repo']} · {self.cfg.values['branch']}")
        self.run(self._refresh, "Leyendo la galería…")

    # ---------- trabajo en segundo plano

    def run(self, fn, label: str):
        self.jobs.put((fn, label))

    def _worker(self):
        while True:
            fn, label = self.jobs.get()
            self.events.put(("busy", label))
            try:
                fn()
            except sync.GitHubError as e:
                hint = " Revisá el token en Configuración." if e.status in (401, 403, 404) else ""
                self.events.put(("log", f"⚠ {e}.{hint}"))
            except Exception as e:  # noqa: BLE001
                self.events.put(("log", f"⚠ {e}"))
                traceback.print_exc()
            finally:
                self.events.put(("idle", None))

    def _drain(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "log":
                    self.log(value)
                elif kind == "busy":
                    self.busy = True
                    self.progress.start(12)
                    if value:
                        self.log(value)
                elif kind == "idle":
                    self.busy = False
                    self.progress.stop()
                elif kind == "catalog":
                    self._render_collections()
                elif kind == "thumb":
                    self._render_works()
        except queue.Empty:
            pass
        self.root.after(150, self._drain)

    def _tick(self):
        """Cada 4 s mira la carpeta; cada 2 min vuelve a leer la galería publicada."""
        if self.gallery and not self.busy and self.jobs.empty():
            ready = self.folder.pending()
            if ready:
                self.run(lambda r=ready: self._publish(r), f"Encontré {len(ready)} imágenes nuevas en la carpeta.")
            elif time.time() - getattr(self, "_last_refresh", 0) > 120:
                self.run(self._refresh, "")
        self.root.after(4000, self._tick)

    def _refresh(self):
        self.gallery.refresh()
        self._last_refresh = time.time()
        self.folder.ensure_folders([c["title"] for c in self.gallery.collections()])
        self.events.put(("catalog", None))
        self._fetch_thumbs()

    def _fetch_thumbs(self):
        for col in self.gallery.collections():
            for item in col["items"]:
                path = self.cache / item["thumb"]
                if path.exists():
                    continue
                data = self.gallery.repo.read("signature/" + item["thumb"], self.cfg.values["branch"])
                if data:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                    self.events.put(("thumb", None))

    def _publish(self, items):
        r = self.gallery.publish(items, progress=lambda m: self.events.put(("log", m)))
        for thumb, data in r.thumbs.items():
            path = self.cache / thumb
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        done = [p for p, _ in items]
        self.folder.mark(done)
        parts = []
        if r.added:
            parts.append(f"✓ Publicadas {len(r.added)}: " + ", ".join(w["title"] for w in r.added[:6]) + ("…" if len(r.added) > 6 else ""))
        if r.repeated:
            parts.append(f"{len(r.repeated)} ya estaban publicadas (no se repiten)")
        for path, err in r.failed:
            parts.append(f"⚠ {path.name}: {err}")
        self.events.put(("log", " · ".join(parts) or "Nada nuevo."))
        if r.added:
            self.events.put(("log", "En unos minutos aparecen en Signature (el teléfono revisa cada 5 minutos)."))
        self.events.put(("catalog", None))

    # ---------- listas

    def log(self, text: str):
        if not text:
            return
        self.log_box.configure(state="normal")
        self.log_box.insert("end", time.strftime("%H:%M  ") + text + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def _collections(self) -> list[dict]:
        return self.gallery.collections() if self.gallery else []

    def _render_collections(self):
        self.cols.delete(*self.cols.get_children())
        for col in self._collections():
            self.cols.insert("", "end", iid=col["id"], text=f"{col['title']}  ({len(col['items'])})")
        ids = [c["id"] for c in self._collections()]
        if self.selected_col not in ids:
            self.selected_col = ids[0] if ids else None
        if self.selected_col:
            self.cols.selection_set(self.selected_col)
        self._render_works()

    def _select_collection(self):
        sel = self.cols.selection()
        if sel and sel[0] != self.selected_col:
            self.selected_col = sel[0]
            self.selected_works.clear()
            self._render_works()

    def _current(self) -> dict | None:
        return next((c for c in self._collections() if c["id"] == self.selected_col), None)

    def _thumb_image(self, item: dict) -> ImageTk.PhotoImage | None:
        key = item["thumb"]
        if key in self.images:
            return self.images[key]
        path = self.cache / key
        if not path.exists():
            return None
        try:
            im = Image.open(path).convert("RGB")
            im.thumbnail(THUMB)
            self.images[key] = ImageTk.PhotoImage(im)
            return self.images[key]
        except Exception:
            return None

    def _render_works(self):
        for w in self.grid.winfo_children():
            w.destroy()
        col = self._current()
        self.col_title.configure(text=col["title"] if col else "Todavía no hay colecciones")
        self.sel_label.configure(text=f"{len(self.selected_works)} seleccionadas" if self.selected_works else "")
        if not col:
            tk.Label(self.grid, text="Arrastrá imágenes acá o guardalas en la carpeta «Aura Signature».",
                     bg=PALETTE["bg"], fg=PALETTE["muted"], font=("Segoe UI", 11)).grid(padx=20, pady=40)
            return
        per_row = max(1, (self.canvas.winfo_width() - 10) // (THUMB[0] + 22))
        for i, item in enumerate(col["items"]):
            selected = item["id"] in self.selected_works
            cell = tk.Frame(self.grid, bg=PALETTE["accent"] if selected else PALETTE["card"], padx=3, pady=3)
            cell.grid(row=i // per_row, column=i % per_row, padx=6, pady=6, sticky="n")
            img = self._thumb_image(item)
            pic = tk.Label(cell, image=img, bg=PALETTE["card"], width=THUMB[0], height=THUMB[1]) if img else \
                tk.Label(cell, text="…", bg=PALETTE["card"], fg=PALETTE["muted"], width=13, height=14)
            pic.pack()
            name = tk.Label(cell, text=item["title"][:18], bg=cell["bg"], fg="#111418" if selected else PALETTE["text"], font=("Segoe UI", 9))
            name.pack(fill="x")
            for widget in (cell, pic, name):
                widget.bind("<Button-1>", lambda e, wid=item["id"]: self._toggle(wid, e))
                widget.bind("<Double-Button-1>", lambda e, it=item: self.preview(it))

    def _toggle(self, work_id: str, event):
        ctrl = bool(event.state & 0x0004)
        if ctrl:
            self.selected_works ^= {work_id}
        else:
            self.selected_works = set() if self.selected_works == {work_id} else {work_id}
        self._render_works()

    def preview(self, item: dict):
        win = tk.Toplevel(self.root)
        win.title(item["title"])
        win.configure(bg="#000")
        label = tk.Label(win, text="Cargando…", bg="#000", fg="#fff")
        label.pack(padx=8, pady=8)

        def load():
            data = self.gallery.repo.read("signature/" + item["image"], self.cfg.values["branch"])
            if not data:
                return
            im = Image.open(io.BytesIO(data)).convert("RGB")
            h = int(self.root.winfo_screenheight() * 0.8)
            im = im.resize((int(im.width * h / im.height), h), Image.LANCZOS)
            self.root.after(0, lambda: self._show(label, im))
        threading.Thread(target=load, daemon=True).start()

    def _show(self, label, im):
        photo = ImageTk.PhotoImage(im)
        label.configure(image=photo, text="")
        label.image = photo

    # ---------- acciones

    def _folder_for(self, title: str) -> Path:
        name = "".join(ch for ch in title if ch not in '<>:"/\\|?*').strip().rstrip(".") or sync.DEFAULT_COLLECTION
        return self.folder.root / name

    def _copy_in(self, paths: list[Path]):
        col = self._current()
        target = self._folder_for(col["title"] if col else sync.DEFAULT_COLLECTION)
        target.mkdir(parents=True, exist_ok=True)
        n = 0
        for p in paths:
            if p.suffix.lower() in sync.IMAGES and p.is_file():
                dest = target / p.name
                if dest.resolve() != p.resolve():
                    shutil.copy2(p, dest)
                n += 1
        self.log(f"Copié {n} imágenes a «{target.name}»: se publican en unos segundos." if n else "No había imágenes (PNG, JPG o WEBP).")

    def add_images(self):
        files = filedialog.askopenfilenames(title="Elegí imágenes", filetypes=[("Imágenes", "*.png *.jpg *.jpeg *.webp")])
        if files:
            self._copy_in([Path(f) for f in files])

    def _on_drop(self, event):
        self._copy_in([Path(p) for p in self.root.tk.splitlist(event.data)])

    def open_folder(self):
        open_path(self.folder.root)

    def new_collection(self):
        title = simpledialog.askstring(APP, "Nombre de la colección nueva:", parent=self.root)
        if title and title.strip():
            folder = self._folder_for(title.strip())
            folder.mkdir(parents=True, exist_ok=True)
            self.log(f"Carpeta «{folder.name}» lista: guardá ahí las imágenes y se publican solas.")
            open_path(folder)

    def edit_collection(self):
        col = self._current()
        if not col:
            return
        title = simpledialog.askstring(APP, "Nombre de la colección:", initialvalue=col["title"], parent=self.root)
        if not title:
            return
        desc = simpledialog.askstring(APP, "Descripción (opcional):", initialvalue=col.get("description", ""), parent=self.root) or ""
        old = self._folder_for(col["title"])
        new = self._folder_for(title)
        if old.exists() and not new.exists() and old != new:
            old.rename(new)
            self.folder.state["done"] = {k.replace(old.name + "/", new.name + "/", 1) if k.startswith(old.name + "/") else k: v
                                         for k, v in self.folder.state["done"].items()}
            self.folder.save()
        self.run(lambda: (self.gallery.edit_collection(col["id"], title, desc), self.events.put(("catalog", None))), f"Guardando «{title}»…")

    def move_collection(self, delta: int):
        col = self._current()
        if col:
            self.run(lambda: (self.gallery.move_collection(col["id"], delta), self.events.put(("catalog", None))), "")

    def delete_collection(self):
        col = self._current()
        if col and messagebox.askyesno(APP, f"¿Borrar la colección «{col['title']}» y sus {len(col['items'])} fondos de Signature?\n"
                                            "Las imágenes de tu carpeta no se tocan."):
            self.run(lambda: (self.gallery.delete_collection(col["id"]), self.events.put(("catalog", None))), f"Borrando «{col['title']}»…")

    def rename_work(self):
        if len(self.selected_works) != 1:
            messagebox.showinfo(APP, "Elegí un fondo para renombrar.")
            return
        work_id = next(iter(self.selected_works))
        found = self.gallery.find(work_id)
        title = simpledialog.askstring(APP, "Nombre del fondo:", initialvalue=found[1]["title"] if found else "", parent=self.root)
        if title:
            self.run(lambda: (self.gallery.rename_work(work_id, title), self.events.put(("catalog", None))), f"Renombrando a «{title}»…")

    def move_works(self):
        if not self.selected_works:
            messagebox.showinfo(APP, "Elegí uno o más fondos (Ctrl + clic para varios).")
            return
        names = ", ".join(c["title"] for c in self._collections())
        title = simpledialog.askstring(APP, f"¿A qué colección? (existentes: {names})", parent=self.root)
        if title:
            ids = list(self.selected_works)

            def job():
                for wid in ids:
                    self.gallery.move_work(wid, title.strip())
                self.events.put(("catalog", None))
            self.selected_works.clear()
            self.run(job, f"Moviendo {len(ids)} fondos a «{title}»…")

    def delete_works(self):
        if not self.selected_works:
            messagebox.showinfo(APP, "Elegí uno o más fondos (Ctrl + clic para varios).")
            return
        ids = list(self.selected_works)
        if messagebox.askyesno(APP, f"¿Borrar {len(ids)} fondos de Signature? Las imágenes de tu carpeta no se tocan."):
            self.selected_works.clear()
            self.run(lambda: (self.gallery.delete_works(ids), self.events.put(("catalog", None))), f"Borrando {len(ids)} fondos…")

    def settings(self):
        win = tk.Toplevel(self.root)
        win.title("Configuración")
        win.transient(self.root)
        frame = ttk.Frame(win, padding=18)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Token de GitHub", font=("Segoe UI Semibold", 11)).grid(row=0, column=0, sticky="w")
        ttk.Label(frame, style="Muted.TLabel", wraplength=460, justify="left", text=(
            "Creá uno en GitHub › Settings › Developer settings › Fine-grained tokens: «Only select repositories» → Aura, "
            "y en Permissions › Contents: «Read and write». Se guarda en el administrador de credenciales de Windows.")
        ).grid(row=1, column=0, columnspan=2, sticky="w", pady=(2, 6))
        token = tk.StringVar(value=self.cfg.token() or "")
        ttk.Entry(frame, textvariable=token, show="•", width=56).grid(row=2, column=0, sticky="we")
        ttk.Button(frame, text="Crear token…", command=lambda: webbrowser.open(TOKEN_URL)).grid(row=2, column=1, padx=6)

        ttk.Label(frame, text="Repositorio y rama", font=("Segoe UI Semibold", 11)).grid(row=3, column=0, sticky="w", pady=(14, 2))
        repo = tk.StringVar(value=self.cfg.values["repo"])
        branch = tk.StringVar(value=self.cfg.values["branch"])
        rb = ttk.Frame(frame)
        rb.grid(row=4, column=0, columnspan=2, sticky="w")
        ttk.Entry(rb, textvariable=repo, width=36).pack(side="left")
        ttk.Entry(rb, textvariable=branch, width=12).pack(side="left", padx=6)

        ttk.Label(frame, text="Carpeta sincronizada", font=("Segoe UI Semibold", 11)).grid(row=5, column=0, sticky="w", pady=(14, 2))
        folder = tk.StringVar(value=self.cfg.values["folder"])
        ttk.Entry(frame, textvariable=folder, width=56).grid(row=6, column=0, sticky="we")
        ttk.Button(frame, text="Elegir…", command=lambda: folder.set(filedialog.askdirectory() or folder.get())).grid(row=6, column=1, padx=6)

        auto = tk.BooleanVar(value=self.cfg.values.get("autostart", False))
        if IS_WINDOWS:
            ttk.Checkbutton(frame, text="Abrir con Windows (minimizada) para que la carpeta se sincronice siempre",
                            variable=auto).grid(row=7, column=0, columnspan=2, sticky="w", pady=(14, 0))
        result = ttk.Label(frame, text="", style="Muted.TLabel", wraplength=460)
        result.grid(row=8, column=0, columnspan=2, sticky="w", pady=(10, 0))

        def save():
            t = token.get().strip()
            if not t:
                result.configure(text="Pegá el token.")
                return
            try:
                name = sync.GitHubRepo(t, repo.get().strip(), branch.get().strip()).check()
            except sync.GitHubError as e:
                result.configure(text=f"No funcionó: {e}")
                return
            self.cfg.set_token(t)
            self.cfg.values.update(repo=repo.get().strip(), branch=branch.get().strip(), folder=folder.get().strip(), autostart=auto.get())
            self.cfg.save()
            try:
                set_autostart(auto.get())
            except Exception as e:  # noqa: BLE001
                self.log(f"⚠ No se pudo configurar el inicio con Windows: {e}")
            self.log(f"Conectado a {name}.")
            win.destroy()
            self._connect()

        buttons = ttk.Frame(frame)
        buttons.grid(row=9, column=0, columnspan=2, sticky="e", pady=(16, 0))
        ttk.Button(buttons, text="Cancelar", command=win.destroy).pack(side="right", padx=4)
        ttk.Button(buttons, text="Guardar y conectar", style="Accent.TButton", command=save).pack(side="right", padx=4)


def main():
    if IS_WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # nítida en pantallas con escala (antes de crear la ventana)
        except Exception:
            pass
    root = TkinterDnD.Tk() if TkinterDnD is not None else tk.Tk()
    ManagerApp(root, start_minimized="--minimized" in sys.argv)
    root.mainloop()


if __name__ == "__main__":
    main()
