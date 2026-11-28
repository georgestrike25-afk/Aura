"""Instalación y actualización automática del gestor de Aura Signature (Windows).

- Instalación: el .exe se copia a «%LOCALAPPDATA%\\Programs\\Aura Signature\\AuraSignature.exe» y desde ahí
  corre siempre (acceso directo, inicio con Windows). La copia descargada se borra sola.
- Actualización: compara la compilación propia (archivo «AuraSignature.version» embebido) con la publicada en la
  versión «aura-signature-pc» de GitHub; si hay una nueva, la baja y la reemplaza al reiniciar.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

RELEASE = "https://github.com/georgestrike25-afk/Aura/releases/download/aura-signature-pc/"
EXE_NAME = "AuraSignature.exe"
IS_WINDOWS = sys.platform == "win32"
FROZEN = bool(getattr(sys, "frozen", False))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
DETACHED = getattr(subprocess, "DETACHED_PROCESS", 0)


def install_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    return Path(base) / "Programs" / "Aura Signature"


def installed_exe() -> Path:
    return install_dir() / EXE_NAME


def current_exe() -> Path:
    return Path(sys.executable).resolve()


def build_id() -> str:
    """Identificador de esta compilación (commit). «dev» si se ejecuta desde el código."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    try:
        return (base / "AuraSignature.version").read_text(encoding="utf-8").strip() or "dev"
    except OSError:
        return "dev"


def is_installed() -> bool:
    try:
        return current_exe() == installed_exe().resolve()
    except OSError:
        return False


def clean_env(env: dict[str, str] | None = None) -> dict[str, str]:
    """Entorno para abrir otra copia del .exe. Sin esto, la copia nueva hereda las rutas de la carpeta temporal
    de esta (_MEIxxxx), que se borra al cerrarse, y falla con «Failed to load Python DLL … python312.dll»."""
    env = dict(os.environ if env is None else env)
    for key in list(env):
        if key.startswith("_PYI_") or key in ("_MEIPASS2", "TCL_LIBRARY", "TK_LIBRARY", "TCLLIBPATH"):
            del env[key]
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"  # PyInstaller ≥ 6.9: la copia nueva arranca de cero
    return env


def _launch(args: list[str]) -> None:
    subprocess.Popen(args, close_fds=True, env=clean_env(), creationflags=DETACHED | NO_WINDOW if IS_WINDOWS else 0)


def install_self() -> bool:
    """Si este .exe no está en su carpeta fija, se copia ahí, abre la copia instalada y devuelve True
    (quien llama tiene que cerrarse). La copia instalada borra después el archivo descargado."""
    if not (IS_WINDOWS and FROZEN) or is_installed():
        return False
    target = installed_exe()
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".new")
    data = current_exe().read_bytes()
    tmp.write_bytes(data)
    for _ in range(20):  # si una copia instalada vieja sigue abierta, esperar a que se cierre
        try:
            os.replace(tmp, target)
            break
        except PermissionError:
            time.sleep(0.5)
    else:
        return False
    _launch([str(target), "--instalado-desde", str(current_exe())])
    return True


def remove_old_copy(argv: list[str]) -> Path | None:
    """Llamado por la copia instalada: borra el .exe desde el que se instaló (cuando ya se cerró)."""
    if "--instalado-desde" not in argv:
        return None
    i = argv.index("--instalado-desde")
    if i + 1 >= len(argv):
        return None
    old = Path(argv[i + 1])
    if old.resolve() == current_exe():
        return None
    for _ in range(20):
        try:
            old.unlink()
            return old
        except FileNotFoundError:
            return old
        except PermissionError:
            time.sleep(0.5)
    return None


def latest_build(timeout: float = 15) -> str | None:
    """Compilación publicada en GitHub, o None si no hay conexión."""
    req = urllib.request.Request(RELEASE + "AuraSignature.version?t=" + str(int(time.time())), headers={"User-Agent": "AuraSignatureManager"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read(200).decode("utf-8", errors="replace").strip() or None
    except Exception:
        return None


def update_available() -> str | None:
    """La compilación nueva, si la publicada es distinta de la propia (y esta es un .exe instalado)."""
    mine = build_id()
    if mine == "dev":
        return None
    latest = latest_build()
    return latest if latest and latest != mine else None


def download_update(progress=lambda done, total: None) -> Path:
    """Baja el .exe nuevo junto al instalado (AuraSignature.update.exe)."""
    target = install_dir() / "AuraSignature.update.exe"
    req = urllib.request.Request(RELEASE + EXE_NAME + "?t=" + str(int(time.time())), headers={"User-Agent": "AuraSignatureManager"})
    tmp = target.with_suffix(".part")
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as out:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(256 * 1024)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            progress(done, total)
    if tmp.stat().st_size < 1_000_000:
        tmp.unlink(missing_ok=True)
        raise IOError("La descarga quedó incompleta")
    os.replace(tmp, target)
    return target


def apply_update_and_restart(new_exe: Path) -> None:
    """Deja un script que espera a que se cierre el gestor, reemplaza el .exe y lo vuelve a abrir.
    Quien llama tiene que cerrarse enseguida."""
    target = installed_exe()
    script = Path(tempfile.gettempdir()) / "aura_signature_update.cmd"
    script.write_text(
        "@echo off\r\n"
        "set n=0\r\n"
        ":wait\r\n"
        "timeout /t 1 /nobreak >nul\r\n"
        f'move /y "{new_exe}" "{target}" >nul 2>&1 && goto done\r\n'
        "set /a n+=1\r\n"
        "if %n% lss 60 goto wait\r\n"
        ":done\r\n"
        f'start "" "{target}"\r\n'
        'del "%~f0"\r\n',
        encoding="mbcs" if IS_WINDOWS else "utf-8",
    )
    _launch(["cmd", "/c", str(script)])
