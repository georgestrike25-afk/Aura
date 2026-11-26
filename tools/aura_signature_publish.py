"""Prepare approved Signature originals and register them in the gallery catalog."""
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


def prepare(path, collection_id):
    with Image.open(path) as source:
        original = ImageOps.exif_transpose(source)
        full = ImageOps.fit(original.convert("RGB"), SIZE, Image.Resampling.LANCZOS)
        image = encode(full, 91)
        thumb = encode(full.resize(THUMB, Image.Resampling.LANCZOS), 84)
    title = path.stem.replace("_", " ").replace("-", " ").strip()
    if not title:
        raise ValueError(f"{path.name}: falta el título")
    identity = hashlib.sha256(f"{collection_id}/{path.stem}".encode()).hexdigest()[:12]
    file_id = hashlib.sha256(image).hexdigest()[:24]
    work = {
        "id": f"{slug(title)[:25] or 'obra'}-{identity}",
        "title": title,
        "image": f"photos/{file_id}.webp",
        "thumb": f"photos/{file_id}-thumb.webp",
        "width": SIZE[0],
        "height": SIZE[1],
    }
    return work, image, thumb


def publish(catalog_path, title, originals):
    collection_id = slug(title)
    if not collection_id:
        raise ValueError("La colección necesita un título con letras o números")
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    if catalog["version"] != 2:
        raise ValueError("Se requiere el catálogo Signature v2")
    prepared = [prepare(path, collection_id) for path in originals]
    if len({work["id"] for work, _, _ in prepared}) != len(prepared):
        raise ValueError("Los originales deben tener nombres distintos")
    collections = catalog["collections"]
    collection = next((col for col in collections if col["id"] == collection_id), None)
    if collection is None:
        collection = {"id": collection_id, "title": title, "items": []}
        collections.append(collection)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("coleccion", help="Título de la colección")
    parser.add_argument("originales", type=Path, nargs="+", help="Imágenes originales autorizadas para la galería")
    args = parser.parse_args()
    catalog = Path(__file__).resolve().parents[1] / "signature" / "catalog-v2.json"
    try:
        publish(catalog, args.coleccion, args.originales)
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
