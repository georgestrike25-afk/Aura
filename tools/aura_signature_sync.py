"""Lógica del gestor de Aura Signature para PC (sin ventana: se prueba sola).

- GitHubRepo: lee y escribe el repositorio con la API REST de GitHub. Cada cambio es UN commit (imágenes,
  miniaturas y catálogo juntos); si alguien publicó en el medio, se vuelve a leer y se reintenta.
- Gallery: operaciones sobre signature/catalog.json (publicar, renombrar, mover, borrar).
- FolderSync: vigila «Documentos\\Aura Signature\\<Colección>\\» y publica lo nuevo.

Las imágenes se preparan en la PC con tools/aura_signature_publish.py (1440×3120, miniatura, huella para
no repetir), el mismo código que usa la bandeja de GitHub.
"""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import aura_signature_publish as publisher

CATALOG = "signature/catalog.json"
IMAGES = {".png", ".jpg", ".jpeg", ".webp"}
DEFAULT_COLLECTION = "Creaciones"


class GitHubError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(f"GitHub {status}: {message}")
        self.status = status


class Conflict(GitHubError):
    """La rama avanzó mientras preparábamos el commit."""


class GitHubRepo:
    """Cliente mínimo de la API REST (sin dependencias)."""

    def __init__(self, token: str, repo: str = "georgestrike25-afk/Aura", branch: str = "main",
                 api: str = "https://api.github.com", opener: Callable | None = None):
        self.token, self.repo, self.branch, self.api = token, repo, branch, api
        self._open = opener or urllib.request.urlopen

    def _call(self, method: str, path: str, body: dict | None = None, raw: bool = False):
        req = urllib.request.Request(
            f"{self.api}/repos/{self.repo}{path}", method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github.raw" if raw else "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "AuraSignatureManager",
                "Content-Type": "application/json",
            },
        )
        try:
            with self._open(req, timeout=60) as r:
                data = r.read()
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code in (409, 422) and method == "PATCH":
                raise Conflict(e.code, detail) from e
            raise GitHubError(e.code, detail) from e
        except urllib.error.URLError as e:
            raise GitHubError(0, f"sin conexión ({e.reason})") from e
        if raw:
            return data
        return json.loads(data) if data else {}

    def head(self) -> str:
        return self._call("GET", f"/git/ref/heads/{self.branch}")["object"]["sha"]

    def read(self, path: str, ref: str) -> bytes | None:
        try:
            return self._call("GET", f"/contents/{path}?ref={ref}", raw=True)
        except GitHubError as e:
            if e.status == 404:
                return None
            raise

    def check(self) -> str:
        """Prueba el token: devuelve el nombre del repositorio o lanza GitHubError."""
        info = self._call("GET", "")
        if not info.get("permissions", {}).get("push"):
            raise GitHubError(403, "el token no tiene permiso de escritura en el repositorio")
        return info.get("full_name", self.repo)

    def commit(self, parent: str, message: str, files: dict[str, bytes | None]) -> str:
        """Un commit con [files] (ruta → contenido; None = borrar) sobre [parent]. Devuelve el sha nuevo."""
        tree = []
        for path, content in files.items():
            if content is None:
                tree.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            else:
                blob = self._call("POST", "/git/blobs", {"content": base64.b64encode(content).decode(), "encoding": "base64"})
                tree.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        base_tree = self._call("GET", f"/git/commits/{parent}")["tree"]["sha"]
        new_tree = self._call("POST", "/git/trees", {"base_tree": base_tree, "tree": tree})["sha"]
        sha = self._call("POST", "/git/commits", {"message": message, "tree": new_tree, "parents": [parent]})["sha"]
        self._call("PATCH", f"/git/refs/heads/{self.branch}", {"sha": sha, "force": False})
        return sha


@dataclass
class Prepared:
    source: Path
    collection: str
    work: dict
    image: bytes
    thumb: bytes


@dataclass
class Result:
    added: list[dict] = field(default_factory=list)
    repeated: list[Path] = field(default_factory=list)
    failed: list[tuple[Path, str]] = field(default_factory=list)
    #: miniaturas recién publicadas (ruta en el catálogo → bytes), para mostrarlas sin volver a bajarlas
    thumbs: dict[str, bytes] = field(default_factory=dict)


class Gallery:
    """El catálogo publicado y las operaciones que lo cambian (cada una, un commit)."""

    def __init__(self, repo: GitHubRepo, retries: int = 3):
        self.repo = repo
        self.retries = retries
        self.catalog: dict = {"version": 2, "license": "", "collections": []}

    def refresh(self) -> dict:
        head = self.repo.head()
        self.catalog = self._load(head)
        return self.catalog

    def _load(self, ref: str) -> dict:
        raw = self.repo.read(CATALOG, ref)
        catalog = json.loads(raw) if raw else {"version": 2, "license": "", "collections": []}
        catalog["version"] = 2
        return catalog

    def _apply(self, message: str, change: Callable[[dict], dict[str, bytes | None]]) -> None:
        """Lee el catálogo más nuevo, aplica [change] (que devuelve archivos extra) y publica todo junto."""
        for attempt in range(self.retries):
            head = self.repo.head()
            catalog = self._load(head)
            before = json.dumps(catalog, sort_keys=True)
            files = change(catalog)
            if not files and json.dumps(catalog, sort_keys=True) == before:
                self.catalog = catalog
                return  # nada que publicar
            files[CATALOG] = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode()
            try:
                self.repo.commit(head, message + " [skip ci]", files)
                self.catalog = catalog
                return
            except Conflict:
                if attempt == self.retries - 1:
                    raise
                time.sleep(1 + attempt)

    # ---------- consultas

    def collections(self) -> list[dict]:
        return self.catalog.get("collections", [])

    def find(self, work_id: str) -> tuple[dict, dict] | None:
        for col in self.collections():
            for item in col["items"]:
                if item["id"] == work_id:
                    return col, item
        return None

    # ---------- publicar

    @staticmethod
    def prepare(path: Path, collection: str) -> Prepared:
        col_id = publisher.slug(collection) or publisher.slug(DEFAULT_COLLECTION)
        work, image, thumb = publisher.prepare(path, col_id)
        return Prepared(path, collection, work, image, thumb)

    def publish(self, items: list[tuple[Path, str]], progress: Callable[[str], None] = lambda _: None) -> Result:
        """Prepara y publica [items] (archivo, título de colección) en un solo commit. Saltea repetidas."""
        result = Result()
        prepared: list[Prepared] = []
        for path, collection in items:
            progress(f"Preparando {path.name}…")
            try:
                prepared.append(self.prepare(path, collection))
            except Exception as e:  # imagen dañada o formato raro: se informa y sigue
                result.failed.append((path, str(e)))

        def change(catalog: dict) -> dict[str, bytes | None]:
            result.added.clear()
            result.repeated.clear()
            result.thumbs.clear()
            files: dict[str, bytes | None] = {}
            known = [i.get("dhash") for c in catalog["collections"] for i in c["items"] if i.get("dhash")]
            for p in prepared:
                if any(publisher.same(p.work["dhash"], k) for k in known):
                    result.repeated.append(p.source)
                    continue
                known.append(p.work["dhash"])
                col_id = publisher.slug(p.collection) or publisher.slug(DEFAULT_COLLECTION)
                col = next((c for c in catalog["collections"] if c["id"] == col_id), None)
                if col is None:
                    col = {"id": col_id, "title": p.collection, "items": []}
                    catalog["collections"].insert(0, col)
                col["items"] = [i for i in col["items"] if i["id"] != p.work["id"]] + [p.work]
                files["signature/" + p.work["image"]] = p.image
                files["signature/" + p.work["thumb"]] = p.thumb
                result.added.append(p.work)
                result.thumbs[p.work["thumb"]] = p.thumb
            return files

        if prepared:
            progress(f"Publicando {len(prepared)} imágenes…")
            self._apply(f"Signature: {len(prepared)} obras desde el gestor de PC", change)
        return result

    # ---------- gestionar

    def rename_work(self, work_id: str, title: str) -> None:
        title = " ".join(title.split())
        if not title:
            raise ValueError("El título no puede quedar vacío")

        def change(catalog):
            for col in catalog["collections"]:
                for item in col["items"]:
                    if item["id"] == work_id:
                        item["title"] = title
            return {}
        self._apply(f"Signature: renombrar «{title}»", change)

    def move_work(self, work_id: str, collection: str) -> None:
        self.move_works([work_id], collection)

    def move_works(self, work_ids: list[str], collection: str, *, collection_id: str | None = None) -> None:
        col_id = collection_id or publisher.slug(collection)
        if not col_id:
            raise ValueError("La colección necesita un nombre con letras o números")
        ids = set(work_ids)

        def change(catalog):
            moved = []
            for col in catalog["collections"]:
                if col["id"] != col_id:
                    moved.extend(item for item in col["items"] if item["id"] in ids)
                    col["items"] = [item for item in col["items"] if item["id"] not in ids]
            if not moved:
                return {}
            target = next((col for col in catalog["collections"] if col["id"] == col_id), None)
            if target is None:
                target = {"id": col_id, "title": collection, "items": []}
                catalog["collections"].insert(0, target)
            target["items"].extend(moved)
            catalog["collections"] = [col for col in catalog["collections"] if col["items"]]
            return {}

        self._apply(f"Signature: mover a «{collection}»", change)

    def delete_works(self, work_ids: list[str]) -> None:
        ids = set(work_ids)

        def change(catalog):
            files: dict[str, bytes | None] = {}
            for col in catalog["collections"]:
                for item in col["items"]:
                    if item["id"] in ids:
                        for key in ("image", "thumb", "mask"):
                            if item.get(key):
                                files["signature/" + item[key]] = None
                col["items"] = [i for i in col["items"] if i["id"] not in ids]
            catalog["collections"] = [c for c in catalog["collections"] if c["items"]]
            # Un archivo que otra obra todavía usa no se borra.
            used = {"signature/" + i[k] for c in catalog["collections"] for i in c["items"] for k in ("image", "thumb", "mask") if i.get(k)}
            return {p: v for p, v in files.items() if p not in used}
        self._apply(f"Signature: borrar {len(ids)} obras", change)

    def edit_collection(self, col_id: str, title: str, description: str = "") -> None:
        title = " ".join(title.split())
        if not title:
            raise ValueError("La colección necesita un nombre")

        def change(catalog):
            for col in catalog["collections"]:
                if col["id"] == col_id:
                    col["title"] = title
                    col["description"] = description.strip()
            return {}
        self._apply(f"Signature: colección «{title}»", change)

    def delete_collection(self, col_id: str) -> None:
        col = next((c for c in self.collections() if c["id"] == col_id), None)
        if col:
            self.delete_works([i["id"] for i in col["items"]])

    def move_collection(self, col_id: str, delta: int) -> None:
        def change(catalog):
            cols = catalog["collections"]
            i = next((k for k, c in enumerate(cols) if c["id"] == col_id), None)
            if i is not None:
                j = max(0, min(len(cols) - 1, i + delta))
                cols.insert(j, cols.pop(i))
            return {}
        self._apply("Signature: ordenar colecciones", change)


class FolderSync:
    """Vigila la carpeta local: cada subcarpeta es una colección; lo suelto va a «Creaciones».

    Un archivo se publica cuando dejó de cambiar (para no subir una descarga a medias). Lo ya publicado o
    descartado queda anotado en el archivo de estado y no se vuelve a subir.
    """

    def __init__(self, root: Path, state_file: Path):
        self.root = root
        self.state_file = state_file
        self.state: dict = self._read_state()
        self._seen: dict[str, tuple[int, float]] = {}

    def _read_state(self) -> dict:
        try:
            return json.loads(self.state_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"done": {}}

    def save(self) -> None:
        self.state_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state, ensure_ascii=False, indent=1), encoding="utf-8")
        os.replace(tmp, self.state_file)

    def ensure_folders(self, titles: list[str]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        for t in titles:
            name = "".join(ch for ch in t if ch not in '<>:"/\\|?*').strip().rstrip(".")
            if name:
                (self.root / name).mkdir(exist_ok=True)

    @staticmethod
    def _key(path: Path, st: os.stat_result) -> str:
        return f"{st.st_size}:{int(st.st_mtime)}"

    def pending(self) -> list[tuple[Path, str]]:
        """Archivos nuevos y estables (mismo tamaño que en la vuelta anterior)."""
        if not self.root.exists():
            return []
        ready: list[tuple[Path, str]] = []
        seen_now: dict[str, tuple[int, float]] = {}
        for path in sorted(self.root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in IMAGES:
                continue
            rel = path.relative_to(self.root).as_posix()
            if len(path.relative_to(self.root).parts) > 2:
                continue  # solo un nivel de subcarpetas
            try:
                st = path.stat()
            except OSError:
                continue
            key = self._key(path, st)
            if self.state["done"].get(rel) == key:
                continue
            seen_now[rel] = (st.st_size, st.st_mtime)
            if self._seen.get(rel) == (st.st_size, st.st_mtime):
                collection = path.parent.name if path.parent != self.root else DEFAULT_COLLECTION
                ready.append((path, collection))
        self._seen = seen_now
        return ready

    def mark(self, paths: list[Path]) -> None:
        for path in paths:
            try:
                st = path.stat()
            except OSError:
                continue
            self.state["done"][path.relative_to(self.root).as_posix()] = self._key(path, st)
        self.save()
