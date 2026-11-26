"""Prepara imágenes para Aura Signature y las registra en signature/catalog.json.

Uso directo:   python3 tools/aura_signature_publish.py "Colección" foto1.png foto2.jpg
Bandeja:       python3 tools/aura_signature_publish.py --inbox
  Procesa signature/inbox/<Colección>/*.{png,jpg,jpeg,webp} (las sueltas en inbox/ van a «Creaciones»),
  las recorta a 1440×3120, crea miniaturas, las suma al catálogo y borra los originales de la bandeja.
  Lo corre solo el workflow «Signature: bandeja» cada vez que se sube algo a signature/inbox/.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import unicodedata

from PIL import Image, ImageOps


SIZE = (1440, 3120)
THUMB = (360, 780)


def slug(text):
    ascii_text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_text.lower()).strip("-")[:40].strip("-")


def encode(image, quality):
    output = io.BytesIO()
    image.save(output, format="WEBP", quality=quality, method=6)
    return output.getvalue()


def dhash(image):
    """Huella visual de 64 bits (igual que ContentNames.dHash en la app)."""
    gray = image.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
    px = list(gray.tobytes())
    value = 0
    for y in range(8):
        for x in range(8):
            value = (value << 1) | (1 if px[y * 9 + x] > px[y * 9 + x + 1] else 0)
    return f"{value:016x}"


def same(a, b):
    return bin(int(a, 16) ^ int(b, 16)).count("1") <= 6


def prepare(path, collection_id):
    with Image.open(path) as source:
        original = ImageOps.exif_transpose(source)
        full = ImageOps.fit(original.convert("RGB"), SIZE, Image.Resampling.LANCZOS)
        image = encode(full, 91)
        thumb = encode(full.resize(THUMB, Image.Resampling.LANCZOS), 84)
    title = " ".join(path.stem.replace("_", " ").replace("-", " ").split())
    title = title[:1].upper() + title[1:]
    if not title:
        raise ValueError(f"{path.name}: falta el título")
    identity = hashlib.sha256(f"{collection_id}/{path.stem}".encode()).hexdigest()[:12]
    file_id = hashlib.sha256(image).hexdigest()[:24]
    work = {
        "id": f"{slug(title)[:25] or 'obra'}-{identity}",
        "title": title,
        "image": f"photos/{file_id}.webp",
        "thumb": f"photos/{file_id}-thumb.webp",
        "dhash": dhash(full),
        "width": SIZE[0],
        "height": SIZE[1],
    }
    return work, image, thumb


def publish(catalog_path, title, originals):
    collection_id = slug(title)
    if not collection_id:
        raise ValueError("La colección necesita un título con letras o números")
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog["version"] = 2
    known = [item.get("dhash") for col in catalog["collections"] for item in col["items"] if item.get("dhash")]
    prepared = []
    for path in originals:
        work, image, thumb = prepare(path, collection_id)
        if any(same(work["dhash"], other) for other in known):
            print(f"Repetida, no se agrega: {path.name}")
            continue
        known.append(work["dhash"])
        prepared.append((work, image, thumb))
    if not prepared:
        return 0
    collections = catalog["collections"]
    collection = next((col for col in collections if col["id"] == collection_id), None)
    if collection is None:
        collection = {"id": collection_id, "title": title, "items": []}
        collections.insert(0, collection)  # lo nuevo, primero
    items = {item["id"]: item for item in collection["items"]}
    (catalog_path.parent / "photos").mkdir(exist_ok=True)
    for work, image, thumb in prepared:
        (catalog_path.parent / work["image"]).write_bytes(image)
        (catalog_path.parent / work["thumb"]).write_bytes(thumb)
        items[work["id"]] = work
    collection["items"] = list(items.values())
    pending = catalog_path.with_suffix(".json.tmp")
    pending.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pending.replace(catalog_path)
    return len(prepared)


IMAGES = {".png", ".jpg", ".jpeg", ".webp"}


def inbox(root):
    box = root / "inbox"
    groups = {}
    for path in sorted(box.rglob("*")) if box.exists() else []:
        if path.is_file() and path.suffix.lower() in IMAGES:
            title = path.parent.name if path.parent != box else "Creaciones"
            groups.setdefault(title, []).append(path)
    count = 0
    for title, paths in groups.items():
        count += publish(root / "catalog.json", title, paths)
        for path in paths:
            path.unlink()
    print(f"Signature: {count} obras nuevas")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--inbox", action="store_true", help="Procesar signature/inbox/")
    parser.add_argument("coleccion", nargs="?", help="Título de la colección")
    parser.add_argument("originales", type=Path, nargs="*", help="Imágenes originales autorizadas para la galería")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "signature"
    try:
        if args.inbox:
            inbox(root)
        elif args.coleccion and args.originales:
            publish(root / "catalog.json", args.coleccion, args.originales)
        else:
            parser.error("Indicá una colección y sus imágenes, o --inbox")
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
