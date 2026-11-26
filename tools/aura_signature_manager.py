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

from PIL import Image, ImageOps, ImageTk

import aura_signature_sync as sync

APP = "Aura Signature"
VERSION = "1.1.0"
TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
IS_WINDOWS = sys.platform == "win32"
UI_FONT = "Segoe UI" if IS_WINDOWS else "DejaVu Sans"

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


def desktop_dir() -> Path:
    """El Escritorio real (puede estar en OneDrive)."""
    if IS_WINDOWS:
        try:
            import ctypes.wintypes
            buf = ctypes.create_unicode_buffer(ctypes.wintypes.MAX_PATH)
            ctypes.windll.shell32.SHGetFolderPathW(None, 0x10, None, 0, buf)  # CSIDL_DESKTOPDIRECTORY
            if buf.value:
                return Path(buf.value)
        except Exception:
            pass
    return Path.home() / "Desktop"


def create_desktop_shortcut() -> Path:
    """Crea «Aura Signature.lnk» en el Escritorio apuntando a este .exe (con PowerShell, sin dependencias)."""
    if not IS_WINDOWS or not getattr(sys, "frozen", False):
        raise RuntimeError("El acceso directo se crea desde AuraSignature.exe en Windows")
    exe = Path(sys.executable)
    link = desktop_dir() / "Aura Signature.lnk"

    def q(text) -> str:
        return "'" + str(text).replace("'", "''") + "'"
    script = (
        "$s = (New-Object -ComObject WScript.Shell).CreateShortcut(" + q(link) + "); "
        "$s.TargetPath = " + q(exe) + "; $s.WorkingDirectory = " + q(exe.parent) + "; "
        "$s.IconLocation = " + q(f"{exe},0") + "; $s.Description = 'Gestor de fondos de Aura Signature'; $s.Save()"
    )
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                   check=True, capture_output=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0), timeout=30)
    return link


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
THUMB_SIZES = {"Chicas": (108, 156), "Medianas": (156, 224), "Grandes": (216, 300)}
ALL_WORKS = ":all:"


class ManagerApp:
    def __init__(self, root: tk.Tk, start_minimized: bool = False):
        self.root = root
        self.cfg = Config()
        self.events: queue.Queue = queue.Queue()
        self.jobs: queue.Queue = queue.Queue()
        self.gallery: sync.Gallery | None = None
        self.folder: sync.FolderSync | None = None
        self.selected_col = ALL_WORKS
        self.selection_anchor = None
        self.cards = {}
        self.drag_origin = None
        self.drop_target = None
        self.resize_job = None
        self.failed_uploads = []
        self.thumb_size = THUMB_SIZES["Grandes"]
        self.selected_works: set[str] = set()
        self.images: dict[tuple[str, tuple[int, int]], ImageTk.PhotoImage] = {}
        self.cache = data_dir() / "cache"
        self.busy = False

        root.title(f"{APP}")
        root.geometry("1320x860")
        root.minsize(860, 560)
        self._style()
        self._build()
        self._connect()
        threading.Thread(target=self._worker, daemon=True).start()
        root.after(150, self._drain)
        root.after(1500, self._first_run_shortcut)
        root.after(4000, self._tick)
        if start_minimized:
            root.iconify()

    # ---------- aspecto

    def _style(self):
        self.root.configure(bg=PALETTE["bg"])
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", font=(UI_FONT, 10), background=PALETTE["bg"], foreground=PALETTE["text"],
                        bordercolor="#323940", lightcolor="#323940", darkcolor="#323940",
                        troughcolor=PALETTE["panel"], arrowcolor=PALETTE["text"])
        style.configure("TButton", padding=(10, 6), background=PALETTE["card"], bordercolor=PALETTE["panel"])
        style.map("TButton", background=[("active", "#333a41")])
        style.configure("Accent.TButton", background=PALETTE["accent"], foreground=PALETTE["bg"])
        style.map("Accent.TButton", background=[("active", "#ecd2aa")])
        style.configure("Title.TLabel", font=(UI_FONT, 24, "bold"))
        style.configure("Muted.TLabel", foreground=PALETTE["muted"])
        style.configure("Sidebar.TFrame", background=PALETTE["panel"])
        style.configure("Sidebar.TLabel", background=PALETTE["panel"], font=(UI_FONT, 16, "bold"))
        style.configure("Gallery.Treeview", background=PALETTE["panel"], fieldbackground=PALETTE["panel"],
                        borderwidth=0, rowheight=50)
        style.map("Gallery.Treeview", background=[("selected", "#534735")], foreground=[("selected", "#fff0d5")])
        style.configure("TEntry", fieldbackground=PALETTE["bg"], insertcolor=PALETTE["text"])
        style.configure("TCombobox", fieldbackground=PALETTE["card"], arrowsize=14)
        style.map("TCombobox", fieldbackground=[("readonly", PALETTE["card"])])
        self.root.option_add("*TCombobox*Listbox.background", PALETTE["card"])
        self.root.option_add("*TCombobox*Listbox.foreground", PALETTE["text"])

    def _build(self):
        body = ttk.Frame(self.root)
        body.pack(fill="both", expand=True)
        left = ttk.Frame(body, padding=(14, 18), style="Sidebar.TFrame")
        left.pack(side="left", fill="y")
        ttk.Label(left, text="Colecciones", style="Sidebar.TLabel").pack(anchor="w")
        self.search = tk.StringVar()
        search = ttk.Entry(left, textvariable=self.search, width=28)
        search.pack(fill="x", pady=(12, 6))
        search.bind("<Escape>", lambda _: self.search.set(""))
        ttk.Label(left, text="Buscar por nombre", style="Muted.TLabel").pack(anchor="w", pady=(0, 8))
        self.cols = ttk.Treeview(left, show="tree", selectmode="browse", style="Gallery.Treeview", height=10)
        self.cols.column("#0", width=235, minwidth=235, stretch=False)
        self.cols.tag_configure("drop", background="#79613c", foreground="#ffffff")
        side_scroll = ttk.Scrollbar(left, orient="vertical", command=self.cols.yview)
        self.cols.configure(yscrollcommand=side_scroll.set)
        side_scroll.pack(side="right", fill="y")
        row = ttk.Frame(left, style="Sidebar.TFrame")
        row.pack(side="bottom", fill="x", pady=(10, 0))
        ttk.Button(row, text="Nueva colección", command=self.new_collection).pack(fill="x", pady=(0, 6))
        meta = ttk.Frame(row, style="Sidebar.TFrame")
        meta.pack(fill="x")
        for text, command in (("Editar", self.edit_collection), ("↑", lambda: self.move_collection(-1)),
                              ("↓", lambda: self.move_collection(1)), ("Quitar", self.delete_collection)):
            ttk.Button(meta, text=text, command=command, width=6 if len(text) > 1 else 2).pack(side="left")
        self.cols.pack(fill="both", expand=True)
        self.cols.bind("<<TreeviewSelect>>", lambda _: self._select_collection())
        self.search.trace_add("write", lambda *_: self._render_collections())

        right = ttk.Frame(body, padding=(18, 16, 18, 0))
        right.pack(side="left", fill="both", expand=True)
        bar = ttk.Frame(right)
        bar.pack(fill="x", pady=(0, 14))
        self.col_title = ttk.Label(bar, text="Todas las obras", style="Title.TLabel")
        self.col_title.pack(side="left")
        menu_button = ttk.Menubutton(bar, text="⋯", width=3)
        menu = tk.Menu(menu_button, tearoff=False)
        menu.add_command(label="Abrir carpeta", command=self.open_folder)
        menu.add_command(label="Actualizar", command=self.refresh_catalog)
        menu.add_command(label="Configuración", command=self.settings)
        menu_button.configure(menu=menu)
        menu_button.pack(side="right")
        size = ttk.Combobox(bar, values=list(THUMB_SIZES), state="readonly", width=10)
        size.set("Grandes")
        size.bind("<<ComboboxSelected>>", lambda _: self.set_thumb_size(size.get()))
        size.pack(side="right", padx=8)
        ttk.Button(bar, text="+  Agregar fotos", style="Accent.TButton", command=self.add_images).pack(side="right")

        self.actions = ttk.Frame(right, padding=(0, 8))
        self.actions.pack(side="bottom", fill="x")
        self.sel_label = ttk.Label(self.actions, style="Muted.TLabel")
        self.sel_label.pack(side="left", padx=(0, 12))
        self.work_buttons = []
        for label, cmd in (("Ver en grande", self.view_selected), ("Renombrar", self.rename_work),
                           ("Mover a…", self.move_works), ("Quitar de Signature", self.delete_works)):
            button = ttk.Button(self.actions, text=label, command=cmd, state="disabled")
            button.pack(side="left", padx=(0, 6))
            self.work_buttons.append(button)

        holder = ttk.Frame(right)
        holder.pack(fill="both", expand=True)
        self.canvas = tk.Canvas(holder, highlightthickness=0, bg=PALETTE["bg"])
        scroll = ttk.Scrollbar(holder, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.grid = tk.Frame(self.canvas, bg=PALETTE["bg"])
        self.grid_window = self.canvas.create_window((0, 0), window=self.grid, anchor="nw")
        self.grid.bind("<Configure>", lambda _: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", self._resize_gallery)
        self.root.bind("<MouseWheel>", self._scroll_gallery)
        self.root.bind("<Button-4>", self._scroll_gallery)
        self.root.bind("<Button-5>", self._scroll_gallery)
        self.root.bind("<Delete>", self._delete_selection)
        self.root.bind("<Control-f>", lambda _: search.focus_set())

        bottom = ttk.Frame(self.root, padding=(16, 8))
        bottom.pack(fill="x")
        self.status = ttk.Label(bottom, text="", style="Muted.TLabel")
        self.status.pack(side="left")
        self.activity = ttk.Label(bottom, text="", style="Muted.TLabel", width=44)
        self.activity.pack(side="left", padx=12)
        self.progress = ttk.Progressbar(bottom, mode="indeterminate", length=90)
        self.progress.pack(side="right")
        ttk.Button(bottom, text="Actividad", command=self._toggle_log).pack(side="right", padx=8)
        self.retry_uploads = ttk.Button(bottom, text="Reintentar", command=self._retry_failed, state="disabled")
        self.retry_uploads.pack(side="right")
        self.log_box = tk.Text(bottom, height=5, bg=PALETTE["panel"], fg=PALETTE["text"], relief="flat", wrap="word",
                               font=(UI_FONT, 9), state="disabled")
        if TkinterDnD is not None and hasattr(self.root, "drop_target_register"):
            self.root.drop_target_register(DND_FILES)
            self.root.dnd_bind("<<Drop>>", self._on_drop)

    def _toggle_log(self):
        if self.log_box.winfo_manager():
            self.log_box.pack_forget()
        else:
            self.log_box.pack(side="bottom", fill="x", pady=(8, 0), before=self.status)

    def refresh_catalog(self):
        if self.gallery:
            self.run(self._refresh, "Actualizando la galería…")
        else:
            self.settings()

    def _resize_gallery(self, event):
        self.canvas.itemconfigure(self.grid_window, width=event.width)
        if self.resize_job:
            self.root.after_cancel(self.resize_job)
        self.resize_job = self.root.after(85, self._render_works)

    def _scroll_gallery(self, event):
        if event.widget not in (self.canvas, self.grid) and not str(event.widget).startswith(str(self.grid) + "."):
            return
        delta = -1 if event.num == 4 else 1 if event.num == 5 else -1 if event.delta > 0 else 1
        self.canvas.yview_scroll(delta, "units")

    def _delete_selection(self, event):
        if event.widget == self.cols or str(event.widget).startswith(str(self.grid)):
            if self.selected_works:
                self.delete_works()
                return "break"

    def set_thumb_size(self, label):
        self.thumb_size = THUMB_SIZES[label]
        self.images.clear()
        self._render_collections()

    def _first_run_shortcut(self):
        """La primera vez deja el acceso directo en el escritorio."""
        if self.cfg.values.get("shortcut_done") or not (IS_WINDOWS and getattr(sys, "frozen", False)):
            return
        try:
            link = create_desktop_shortcut()
            self.log(f"Dejé un acceso directo en el escritorio: {link.name}")
        except Exception as e:  # noqa: BLE001
            self.log(f"⚠ No se pudo crear el acceso directo: {e}")
        self.cfg.values["shortcut_done"] = True
        self.cfg.save()

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
        refresh = False
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
                elif kind in ("catalog", "thumb"):
                    refresh = True
                elif kind == "failed":
                    self.failed_uploads = value
                    self.retry_uploads.configure(state="normal" if value else "disabled")
        except queue.Empty:
            pass
        if refresh:
            self._render_collections()
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
        self.folder.mark([p for p, _ in items])
        failed_paths = {path for path, _ in r.failed}
        self.events.put(("failed", [(path, collection) for path, collection in items if path in failed_paths]))
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

    def _retry_failed(self):
        if self.failed_uploads and not self.busy:
            items = self.failed_uploads[:]
            self.retry_uploads.configure(state="disabled")
            self.run(lambda: self._publish(items), f"Reintentando {len(items)} imágenes…")

    # ---------- listas

    def log(self, text: str):
        if not text:
            return
        self.activity.configure(text=text.splitlines()[0][:65] if text else "")
        self.log_box.configure(state="normal")
        self.log_box.insert("end", time.strftime("%H:%M  ") + text + "\n")
        self.log_box.see("end")
        self.log_box.configure(state="disabled")

    def _collections(self) -> list[dict]:
        return self.gallery.collections() if self.gallery else []

    def _render_collections(self):
        self.cols.delete(*self.cols.get_children())
        collections = self._collections()
        all_count = sum(len(col["items"]) for col in collections)
        self.cols.insert("", "end", iid=ALL_WORKS, text=f"Todas las obras  ·  {all_count}")
        query = self.search.get().strip().casefold()
        for col in collections:
            items = col["items"]
            if query and query not in col["title"].casefold() and not any(query in w["title"].casefold() for w in items):
                continue
            cover = self._thumb_image(items[0], (38, 38)) if items else None
            options = {"image": cover} if cover else {}
            self.cols.insert("", "end", iid=col["id"], text=f"{col['title']}  ·  {len(items)}", **options)
        if not self.cols.exists(self.selected_col):
            self.selected_col = ALL_WORKS
            self.selected_works.clear()
        self.cols.selection_set(self.selected_col)
        self._render_works()

    def _select_collection(self):
        selected = self.cols.selection()
        if selected and selected[0] != self.selected_col:
            self.selected_col = selected[0]
            self.selected_works.clear()
            self.selection_anchor = None
            self.canvas.yview_moveto(0)
            self._render_works()

    def _current(self) -> dict | None:
        return next((c for c in self._collections() if c["id"] == self.selected_col), None)

    def _visible_works(self):
        col = self._current()
        groups = [col] if col else self._collections()
        query = self.search.get().strip().casefold()
        return [item for group in groups for item in group["items"]
                if query in group["title"].casefold() or query in item["title"].casefold()]

    def _thumb_image(self, item: dict, size=None) -> ImageTk.PhotoImage | None:
        size = size or self.thumb_size
        key = (item["thumb"], size)
        if key in self.images:
            return self.images[key]
        path = self.cache / item["thumb"]
        try:
            with Image.open(path) as source:
                image = source.convert("RGB")
            tile = ImageOps.fit(image, size, Image.Resampling.LANCZOS)
            self.images[key] = ImageTk.PhotoImage(tile, master=self.root)
            return self.images[key]
        except (OSError, ValueError):
            return None

    def _render_works(self):
        if self.resize_job:
            self.root.after_cancel(self.resize_job)
            self.resize_job = None
        items = self._visible_works()
        self.selected_works.intersection_update(item["id"] for item in items)
        for widget in self.grid.winfo_children():
            widget.destroy()
        self.cards.clear()
        col = self._current()
        self.col_title.configure(text=col["title"] if col else "Todas las obras")
        if not items:
            text = "No hay resultados para esa búsqueda." if self.search.get().strip() else "Arrastrá fotos acá o usá Agregar fotos."
            tk.Label(self.grid, text=text, bg=PALETTE["bg"], fg=PALETTE["muted"],
                     font=(UI_FONT, 11)).grid(padx=22, pady=44)
        width, height = self.thumb_size
        per_row = max(1, (self.canvas.winfo_width() - 4) // (width + 18))
        for i, item in enumerate(items):
            wid = item["id"]
            cell = tk.Frame(self.grid, bg=PALETTE["bg"], takefocus=True, padx=0, pady=0)
            cell.grid(row=i // per_row, column=i % per_row, padx=6, pady=(0, 14), sticky="n")
            art = tk.Canvas(cell, width=width, height=height, bg=PALETTE["card"], bd=0, highlightthickness=2,
                            highlightbackground=PALETTE["bg"], highlightcolor=PALETTE["accent"])
            art.pack()
            image = self._thumb_image(item)
            if image is not None:
                art.create_image(width // 2, height // 2, image=image)
            else:
                art.create_text(width // 2, height // 2, text="Cargando…", fill=PALETTE["muted"])
            name = tk.Label(cell, text=item["title"], width=max(8, width // 8), anchor="w",
                            bg=PALETTE["bg"], fg=PALETTE["text"], font=(UI_FONT, 10))
            name.pack(fill="x", pady=(3, 0))
            self.cards[wid] = art
            for widget in (cell, art, name):
                widget.bind("<Button-1>", lambda e, key=wid, card=cell: self._press_work(e, key, card))
                widget.bind("<Double-Button-1>", lambda e, current=item: self._open_preview(current))
                widget.bind("<B1-Motion>", self._drag_work)
                widget.bind("<ButtonRelease-1>", self._drop_work)
            cell.bind("<Return>", lambda _, current=item: self._open_preview(current))
        self._paint_selection()

    def _paint_selection(self):
        count = len(self.selected_works)
        self.sel_label.configure(text=f"{count} seleccionadas" if count else "")
        for button, enabled in zip(self.work_buttons, (count > 0, count == 1, count > 0, count > 0)):
            button.configure(state="normal" if enabled else "disabled")
        for wid, art in self.cards.items():
            selected = wid in self.selected_works
            art.configure(highlightbackground=PALETTE["accent"] if selected else PALETTE["bg"])
            art.delete("selection")
            if selected:
                x = self.thumb_size[0] - 18
                art.create_oval(x - 11, 7, x + 11, 29, fill=PALETTE["accent"], outline="", tags="selection")
                art.create_text(x, 18, text="✓", fill=PALETTE["bg"], tags="selection")

    def _press_work(self, event, wid, cell):
        cell.focus_set()
        ctrl = bool(event.state & 0x0004)
        shift = bool(event.state & 0x0001)
        ids = list(self.cards)
        if shift and self.selection_anchor in ids:
            first, last = sorted((ids.index(self.selection_anchor), ids.index(wid)))
            selected = set(ids[first:last + 1])
            self.selected_works = self.selected_works | selected if ctrl else selected
        elif ctrl:
            self.selected_works ^= {wid}
            self.selection_anchor = wid
        elif wid not in self.selected_works or len(self.selected_works) == 1:
            self.selected_works = {wid}
            self.selection_anchor = wid
        self.drag_origin = (event.x_root, event.y_root, wid, ctrl or shift)
        self._paint_selection()

    def _collection_at(self, x, y):
        offset_x, offset_y = x - self.cols.winfo_rootx(), y - self.cols.winfo_rooty()
        if not (0 <= offset_x < self.cols.winfo_width() and 0 <= offset_y < self.cols.winfo_height()):
            return None
        row = self.cols.identify_row(offset_y)
        return next((col for col in self._collections() if col["id"] == row), None)

    def _highlight_drop(self, col):
        if self.drop_target and self.cols.exists(self.drop_target):
            self.cols.item(self.drop_target, tags=())
        self.drop_target = col["id"] if col else None
        if self.drop_target:
            self.cols.item(self.drop_target, tags=("drop",))

    def _drag_work(self, event):
        if self.drag_origin and self.selected_works:
            self._highlight_drop(self._collection_at(event.x_root, event.y_root))

    def _drop_work(self, event):
        origin, self.drag_origin = self.drag_origin, None
        self._highlight_drop(None)
        if origin is None:
            return
        x, y, wid, modifier = origin
        if abs(event.x_root - x) + abs(event.y_root - y) < 8:
            if not modifier and wid in self.selected_works:
                self.selected_works = {wid}
                self.selection_anchor = wid
                self._paint_selection()
            return
        col = self._collection_at(event.x_root, event.y_root)
        if col and col["id"] != self.selected_col and self.selected_works:
            self._queue_move(col["title"], col["id"])

    def _open_preview(self, item):
        self.drag_origin = None
        self.preview(item)
        return "break"

    def preview(self, item: dict):
        items = self._visible_works() or [item]
        start = next((i for i, it in enumerate(items) if it["id"] == item["id"]), 0)
        Viewer(self, items, start)

    def full_image(self, item: dict) -> Image.Image | None:
        """Obra completa (en caché en la PC después de la primera vez)."""
        path = self.cache / "full" / item["image"]
        if not path.exists():
            data = self.gallery.repo.read("signature/" + item["image"], self.cfg.values["branch"])
            if not data:
                return None
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        try:
            return Image.open(path).convert("RGB")
        except Exception:
            return None

    # ---------- acciones

    def _folder_for(self, title: str) -> Path:
        name = "".join(ch for ch in title if ch not in '<>:"/\\|?*').strip().rstrip(".") or sync.DEFAULT_COLLECTION
        return self.folder.root / name

    def _copy_in(self, paths: list[Path], col: dict | None = None):
        if self.folder is None:
            self.settings()
            return
        col = col or self._current()
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
        col = self._collection_at(event.x_root, event.y_root)
        self._copy_in([Path(p) for p in self.root.tk.splitlist(event.data)], col)
        return "copy"

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

    def view_selected(self):
        items = self._visible_works()
        if items:
            first = next((item for item in items if item["id"] in self.selected_works), items[0])
            self.preview(first)

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
        if title and title.strip():
            self._queue_move(title.strip())

    def _queue_move(self, title, collection_id=None):
        ids = list(self.selected_works)
        if collection_id is None:
            match = next((col for col in self._collections() if col["title"].casefold() == title.casefold()), None)
            collection_id = match["id"] if match else None

        def job():
            self.gallery.move_works(ids, title, collection_id=collection_id)
            self.events.put(("catalog", None))

        self.selected_works.clear()
        self._paint_selection()
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
        if IS_WINDOWS:
            def shortcut():
                try:
                    link = create_desktop_shortcut()
                    result.configure(text=f"Listo: «{link.name}» en el escritorio.")
                except Exception as e:  # noqa: BLE001
                    result.configure(text=f"No se pudo crear: {e}")
            ttk.Button(frame, text="Crear acceso directo en el escritorio", command=shortcut).grid(row=10, column=0, sticky="w", pady=(12, 0))
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


class Viewer:
    """Visor grande, con los atajos de Mirador: ← → pasar, rueda para el zoom, arrastrar para moverse,
    0 ajustar, 1 al 100 %, F11 o doble clic pantalla completa. Además, para decidir rápido:
    Supr borrar de Signature, F2 renombrar, M mover a otra colección."""

    BG = "#111114"
    BAR = "#212125"
    TEXT = "#F7F0E5"
    SOFT = "#C2BAB0"
    AMBER = "#FFAD32"

    def __init__(self, app: "ManagerApp", items: list[dict], index: int):
        self.app, self.items, self.index = app, items, index
        self.zoom = None  # None = ajustar a la ventana
        self.offset = [0.0, 0.0]
        self.is_fullscreen = False
        self.images: dict[str, Image.Image] = {}
        self.photo = None
        self.win = w = tk.Toplevel(app.root)
        w.title("Visor · Aura Signature")
        w.configure(bg=self.BG)
        sh = app.root.winfo_screenheight()
        w.geometry(f"{max(820, int(sh * 0.62))}x{int(sh * 0.9)}")
        w.minsize(720, 520)
        self.canvas = tk.Canvas(w, bg=self.BG, highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        bar = self.bar = tk.Frame(w, bg=self.BAR, padx=14, pady=10)
        bar.pack(fill="x")
        self.title = tk.Label(bar, text="", bg=self.BAR, fg=self.TEXT, font=(UI_FONT, 13, "bold"), anchor="w")
        self.title.pack(fill="x")
        self.info = tk.Label(bar, text="", bg=self.BAR, fg=self.SOFT, font=(UI_FONT, 9), anchor="w", justify="left", wraplength=780)
        self.info.pack(fill="x")
        row = tk.Frame(bar, bg=self.BAR)
        row.pack(fill="x", pady=(8, 0))
        for text, cmd in (("←", lambda: self.go(-1)), ("→", lambda: self.go(1)), ("Renombrar (F2)", self.rename),
                          ("Mover (M)", self.move), ("−", lambda: self.zoom_by(0.8)),
                          ("Ajustar", lambda: self.set_zoom(None)), ("+", lambda: self.zoom_by(1.25)),
                          ("Pantalla completa (F11)", self.fullscreen)):
            ttk.Button(row, text=text, command=cmd).pack(side="left", padx=(0, 6))
        ttk.Button(row, text="Borrar (Supr)", command=self.delete).pack(side="right")

        w.bind("<Right>", lambda e: self.go(1))
        w.bind("<space>", lambda e: self.go(1))
        w.bind("<Next>", lambda e: self.go(1))
        w.bind("<Left>", lambda e: self.go(-1))
        w.bind("<BackSpace>", lambda e: self.go(-1))
        w.bind("<Prior>", lambda e: self.go(-1))
        w.bind("<Home>", lambda e: self.go(-len(self.items)))
        w.bind("<End>", lambda e: self.go(len(self.items)))
        w.bind("<Delete>", lambda e: self.delete())
        w.bind("<F2>", lambda e: self.rename())
        w.bind("<m>", lambda e: self.move())
        w.bind("<F11>", lambda e: self.fullscreen())
        w.bind("<Escape>", lambda e: self.fullscreen(False) if self.is_fullscreen else w.destroy())
        w.bind("<Key-0>", lambda e: self.set_zoom(None))
        w.bind("<Key-1>", lambda e: self.set_zoom(1.0))
        w.bind("<plus>", lambda e: self.zoom_by(1.25))
        w.bind("<minus>", lambda e: self.zoom_by(0.8))
        self.canvas.bind("<Configure>", lambda e: self.draw())
        self.canvas.bind("<Double-Button-1>", lambda e: self.fullscreen())
        self.canvas.bind("<MouseWheel>", lambda e: self.zoom_by(1.2 if e.delta > 0 else 1 / 1.2, (e.x, e.y)))
        self.canvas.bind("<Button-4>", lambda e: self.zoom_by(1.2, (e.x, e.y)))
        self.canvas.bind("<Button-5>", lambda e: self.zoom_by(1 / 1.2, (e.x, e.y)))
        self.canvas.bind("<ButtonPress-1>", self._drag_start)
        self.canvas.bind("<B1-Motion>", self._drag)
        w.focus_force()
        self.show()

    # ---------- carga

    def current(self) -> dict | None:
        return self.items[self.index] if 0 <= self.index < len(self.items) else None

    def _load(self, item: dict):
        def job():
            im = self.app.full_image(item)
            if im is not None:
                self.images[item["id"]] = im
                try:
                    self.win.after(0, self.draw)
                except tk.TclError:
                    pass
        threading.Thread(target=job, daemon=True).start()

    def show(self):
        item = self.current()
        if item is None:
            self.win.destroy()
            return
        self.zoom = None
        self.offset = [0.0, 0.0]
        col = self.app._current()
        self.title.configure(text=item["title"])
        self.info.configure(text=f"{self.index + 1} de {len(self.items)}" + (f" · {col['title']}" if col else "") +
                            "   ·   ← → pasar · rueda zoom · Supr borrar · F2 renombrar · M mover")
        for k in (self.index, self.index + 1, self.index - 1):  # la actual y las vecinas, por adelantado
            if 0 <= k < len(self.items) and self.items[k]["id"] not in self.images:
                self._load(self.items[k])
        # Fuera de la vista no se guardan más de 5 imágenes grandes.
        keep = {self.items[k]["id"] for k in range(max(0, self.index - 2), min(len(self.items), self.index + 3))}
        for key in list(self.images):
            if key not in keep:
                self.images.pop(key)
        self.draw()

    def draw(self):
        c = self.canvas
        c.delete("all")
        item = self.current()
        cw, ch = max(1, c.winfo_width()), max(1, c.winfo_height())
        im = self.images.get(item["id"]) if item else None
        if im is None:
            thumb = self.app.cache / item["thumb"] if item else None
            if thumb is not None and thumb.exists():
                im = Image.open(thumb).convert("RGB")  # mientras baja la grande
            else:
                c.create_text(cw / 2, ch / 2, text="Cargando…", fill=self.SOFT, font=(UI_FONT, 12))
                return
        fit = min(cw / im.width, ch / im.height)
        scale = fit if self.zoom is None else self.zoom
        w, h = max(1, int(im.width * scale)), max(1, int(im.height * scale))
        left = (cw - w) / 2 + self.offset[0]
        top = (ch - h) / 2 + self.offset[1]
        x0, y0 = max(0, int(left)), max(0, int(top))
        x1, y1 = min(cw, int(left + w)), min(ch, int(top + h))
        if x1 > x0 and y1 > y0:
            box = (max(0, (x0 - left) * im.width / w), max(0, (y0 - top) * im.height / h),
                   min(im.width, (x1 - left) * im.width / w), min(im.height, (y1 - top) * im.height / h))
            shown = im.resize((x1 - x0, y1 - y0), Image.LANCZOS if scale < 1 else Image.BILINEAR, box=box)
            self.photo = ImageTk.PhotoImage(shown)
            c.create_image(x0, y0, image=self.photo, anchor="nw")
        if self.zoom is not None:
            c.create_text(12, 12, anchor="nw", text=f"{int(scale * 100)} %", fill=self.AMBER, font=(UI_FONT, 10))

    # ---------- navegación y zoom

    def go(self, delta: int):
        if not self.items:
            return
        self.index = max(0, min(len(self.items) - 1, self.index + delta))
        self.show()

    def set_zoom(self, z):
        self.zoom = z
        self.offset = [0.0, 0.0]
        self.draw()

    def zoom_by(self, k: float, point=None):
        item = self.current()
        im = self.images.get(item["id"]) if item else None
        if im is None:
            return
        fit = min(self.canvas.winfo_width() / im.width, self.canvas.winfo_height() / im.height)
        old_zoom = self.zoom or fit
        self.zoom = max(fit, min(4.0, old_zoom * k))
        if point is not None and old_zoom > 0:
            ratio = self.zoom / old_zoom
            for axis, dimension in enumerate((self.canvas.winfo_width(), self.canvas.winfo_height())):
                position = point[axis] - dimension / 2
                self.offset[axis] = position + (self.offset[axis] - position) * ratio
        if abs(self.zoom - fit) < 1e-3:
            self.zoom, self.offset = None, [0.0, 0.0]
        self.draw()

    def _drag_start(self, e):
        self._last = (e.x, e.y)

    def _drag(self, e):
        if self.zoom is None:
            return
        item = self.current()
        im = self.images.get(item["id"]) if item else None
        if im is None:
            return
        for axis, new, length, viewport in (
            (0, e.x, im.width, self.canvas.winfo_width()),
            (1, e.y, im.height, self.canvas.winfo_height()),
        ):
            limit = max(0.0, (length * self.zoom - viewport) / 2)
            self.offset[axis] = max(-limit, min(limit, self.offset[axis] + new - self._last[axis]))
        self._last = (e.x, e.y)
        self.draw()

    def fullscreen(self, on: bool | None = None):
        self.is_fullscreen = not self.is_fullscreen if on is None else on
        self.win.attributes("-fullscreen", self.is_fullscreen)
        if self.is_fullscreen:
            self.bar.pack_forget()
        else:
            self.bar.pack(fill="x")

    # ---------- decidir

    def delete(self):
        item = self.current()
        if item is None:
            return
        if not messagebox.askyesno(APP, f"¿Borrar «{item['title']}» de Signature?\nLa imagen de tu carpeta no se toca.", parent=self.win):
            return
        app = self.app
        app.run(lambda: (app.gallery.delete_works([item["id"]]), app.events.put(("catalog", None))), f"Borrando «{item['title']}»…")
        self.items.pop(self.index)
        self.images.pop(item["id"], None)
        if not self.items:
            self.win.destroy()
            return
        self.index = min(self.index, len(self.items) - 1)
        self.show()

    def rename(self):
        item = self.current()
        if item is None:
            return
        title = simpledialog.askstring(APP, "Nombre del fondo:", initialvalue=item["title"], parent=self.win)
        if title and title.strip():
            item["title"] = " ".join(title.split())
            app = self.app
            app.run(lambda: (app.gallery.rename_work(item["id"], item["title"]), app.events.put(("catalog", None))), f"Renombrando a «{item['title']}»…")
            self.show()

    def move(self):
        item = self.current()
        if item is None:
            return
        names = ", ".join(c["title"] for c in self.app._collections())
        title = simpledialog.askstring(APP, f"¿A qué colección? (existentes: {names})", parent=self.win)
        if title and title.strip():
            app = self.app
            app.run(lambda: (app.gallery.move_work(item["id"], title.strip()), app.events.put(("catalog", None))), f"Moviendo a «{title.strip()}»…")
            self.items.pop(self.index)
            if not self.items:
                self.win.destroy()
                return
            self.index = min(self.index, len(self.items) - 1)
            self.show()


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
