"""Pruebas del gestor de Signature con un GitHub falso en memoria: python3 -m unittest tools/test_aura_signature_sync.py"""
import json
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from PIL import Image, ImageDraw  # noqa: E402

import aura_signature_sync as sync  # noqa: E402


class FakeRepo:
    """Imita GitHubRepo: archivos por commit, ramas que avanzan y conflictos."""

    def __init__(self):
        self.commits = {"c0": {"signature/catalog.json": b'{"version":2,"license":"","collections":[]}'}}
        self.head_sha = "c0"
        self.n = 0
        self.race = 0  # cuántas veces simular que otro publicó en el medio

    def head(self):
        return self.head_sha

    def read(self, path, ref):
        return self.commits[ref].get(path)

    def commit(self, parent, message, files):
        if self.race:
            self.race -= 1
            self._advance({"README": b"otro"})
            raise sync.Conflict(422, "no es avance directo")
        if parent != self.head_sha:
            raise sync.Conflict(422, "no es avance directo")
        return self._advance(files, message)

    def _advance(self, files, message=""):
        self.n += 1
        tree = dict(self.commits[self.head_sha])
        for p, c in files.items():
            if c is None:
                tree.pop(p, None)
            else:
                tree[p] = c
        sha = f"c{self.n}"
        self.commits[sha] = tree
        self.head_sha = sha
        self.last_message = message
        return sha

    def files(self):
        return self.commits[self.head_sha]

    def catalog(self):
        return json.loads(self.files()["signature/catalog.json"])


def image(path, sky, ground, size=(1024, 1536)):
    im = Image.new("RGB", size, sky)
    ImageDraw.Draw(im).polygon([(0, size[1]), (size[0] // 2, size[1] // 3), (size[0], size[1])], fill=ground)
    im.save(path)
    return path


class GalleryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.repo = FakeRepo()
        self.g = sync.Gallery(self.repo)

    def test_publish_in_one_commit_without_repeats(self):
        a = image(self.tmp / "Pico azul.png", (40, 80, 160), (240, 240, 250))
        b = image(self.tmp / "Fuego.jpg", (40, 20, 60), (230, 120, 40))
        copy = self.tmp / "copia.jpg"
        Image.open(a).convert("RGB").save(copy, quality=80)
        r = self.g.publish([(a, "Montañas"), (b, "Montañas"), (copy, "Otra")])
        self.assertEqual(2, len(r.added))
        self.assertEqual([copy], r.repeated)
        self.assertEqual(1, self.repo.n, "todo en un solo commit")
        self.assertIn("[skip ci]", self.repo.last_message)
        self.assertTrue(all(i.get("added") for c in self.repo.catalog()["collections"] for i in c["items"]), "fecha de publicación")
        cat = self.repo.catalog()
        self.assertEqual(["Montañas"], [c["title"] for c in cat["collections"]])
        self.assertEqual(["Pico azul", "Fuego"], [i["title"] for i in cat["collections"][0]["items"]])
        for item in cat["collections"][0]["items"]:
            img = self.repo.files()["signature/" + item["image"]]
            self.assertEqual((1440, 3120), Image.open(__import__("io").BytesIO(img)).size)
        # Otra vez lo mismo: nada nuevo.
        again = self.g.publish([(a, "Montañas")])
        self.assertEqual([], again.added)
        self.assertEqual(1, self.repo.n)

    def test_retries_when_someone_else_published(self):
        a = image(self.tmp / "a.png", (10, 10, 10), (200, 200, 200))
        self.repo.race = 1
        r = self.g.publish([(a, "X")])
        self.assertEqual(1, len(r.added))
        self.assertIn("README", self.repo.files(), "conserva lo que publicó el otro")

    def test_rename_move_delete(self):
        a = image(self.tmp / "a.png", (10, 10, 10), (200, 200, 200))
        b = image(self.tmp / "b.png", (200, 30, 30), (20, 20, 200))
        self.g.publish([(a, "Uno"), (b, "Uno")])
        ida, idb = [i["id"] for i in self.repo.catalog()["collections"][0]["items"]]
        self.g.rename_work(ida, "  Amanecer   frío ")
        self.g.move_work(idb, "Dos")
        cat = self.repo.catalog()
        self.assertEqual(["Dos", "Uno"], [c["title"] for c in cat["collections"]])
        self.assertEqual("Amanecer frío", cat["collections"][1]["items"][0]["title"])
        img = "signature/" + cat["collections"][1]["items"][0]["image"]
        self.g.delete_works([ida])
        cat = self.repo.catalog()
        self.assertEqual(["Dos"], [c["title"] for c in cat["collections"]])
        self.assertNotIn(img, self.repo.files())
        col = cat["collections"][0]["id"]
        self.g.edit_collection(col, "Ciudades", "De noche")
        self.assertEqual("De noche", self.repo.catalog()["collections"][0]["description"])
        self.g.delete_collection(col)
        self.assertEqual([], self.repo.catalog()["collections"])


class SameNameTest(unittest.TestCase):
    def test_editions_go_to_the_same_collection(self):
        self.assertEqual(sync.title_key("Árboles de la vida"), sync.title_key("Árboles de la vida - Segunda edición"))
        self.assertEqual(sync.title_key("Árboles de la vida"), sync.title_key("Árboles de la vida Tercera edición"))
        self.assertEqual(sync.title_key("retratos del mar"), sync.title_key("Retratos del Mar 2"))
        tmp = Path(tempfile.mkdtemp())
        repo = FakeRepo()
        g = sync.Gallery(repo)
        a = image(tmp / "a.png", (10, 10, 10), (200, 200, 200))
        b = image(tmp / "b.png", (200, 30, 30), (20, 20, 200))
        g.publish([(a, "Árboles de la vida")])
        g.publish([(b, "Árboles de la vida - Segunda edición")])
        cols = repo.catalog()["collections"]
        self.assertEqual(["Árboles de la vida"], [c["title"] for c in cols])
        self.assertEqual(2, len(cols[0]["items"]))

    def test_existing_duplicates_are_united_on_next_change(self):
        catalog = {"version": 2, "collections": [
            {"id": "x-3", "title": "X - Tercera edición", "items": [{"id": "c"}]},
            {"id": "x", "title": "X", "items": [{"id": "a"}]},
            {"id": "y", "title": "Y", "items": [{"id": "d"}]},
        ]}
        self.assertTrue(sync.unite_collections(catalog))
        self.assertEqual([("X", ["c", "a"]), ("Y", ["d"])], [(c["title"], [i["id"] for i in c["items"]]) for c in catalog["collections"]])


class FolderSyncTest(unittest.TestCase):
    def test_new_files_wait_until_stable_and_are_not_repeated(self):
        root = Path(tempfile.mkdtemp())
        state = root.parent / (root.name + "-state.json")
        fs = sync.FolderSync(root, state)
        fs.ensure_folders(["Retratos", "Mar/Agua"])
        self.assertTrue((root / "Retratos").is_dir())
        self.assertTrue((root / "MarAgua").is_dir())
        p = image(root / "Retratos" / "Ana.png", (10, 10, 10), (200, 200, 200))
        loose = image(root / "Suelta.jpg", (90, 10, 10), (200, 200, 200))
        (root / "Retratos" / "notas.txt").write_text("x")
        self.assertEqual([], fs.pending(), "primera vuelta: espera a que el archivo no cambie")
        ready = fs.pending()
        self.assertEqual({(p, "Retratos"), (loose, "Creaciones")}, set(ready))
        fs.mark([p, loose])
        self.assertEqual([], fs.pending())
        self.assertEqual([], sync.FolderSync(root, state).pending(), "el estado se guarda entre sesiones")
        time.sleep(1.1)
        image(p, (0, 0, 0), (255, 255, 255))  # la reemplazó por otra
        fs.pending()
        self.assertEqual([(p, "Retratos")], fs.pending())


if __name__ == "__main__":
    unittest.main()
