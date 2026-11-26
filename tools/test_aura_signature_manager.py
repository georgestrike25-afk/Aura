"""Prueba de interacción de la galería y el visor con Tk, sin conexión."""
import os
os.environ["AURA_NO_TRAY"] = "1"  # la bandeja del sistema no se prueba acá
import tempfile
import tkinter as tk
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

import aura_signature_manager as manager


class GalleryWindowTest(unittest.TestCase):
    def test_selection_double_click_and_fullscreen_zoom(self):
        with tempfile.TemporaryDirectory() as folder:
            root = tk.Tk()
            self.addCleanup(root.destroy)
            self.addCleanup(root.update_idletasks)
            futures = []
            root.report_callback_exception = lambda *error: futures.append(error)
            with patch.object(manager, "data_dir", return_value=Path(folder)), patch.object(manager.ManagerApp, "_connect"):
                app = manager.ManagerApp(root)
            items = [
                {"id": key, "title": name, "thumb": f"{key}.png", "image": f"{key}.png"}
                for key, name in (("ciervo", "Ciervo"), ("zorro", "Zorro"), ("buho", "Búho"))
            ]
            collections = [{"id": "bosque", "title": "Bosque", "items": items},
                           {"id": "mar", "title": "Mar", "items": []}]
            app.gallery = SimpleNamespace(collections=lambda: collections)
            root.update()
            app._render_collections()
            root.update()

            def click(card, stamp):
                card.event_generate("<ButtonPress-1>", x=25, y=30, time=stamp)
                card.event_generate("<ButtonRelease-1>", x=25, y=30, time=stamp + 30)
                root.update()

            card = app.cards["ciervo"]
            click(card, 1000)
            self.assertIs(card, app.cards["ciervo"], "El primer clic debe conservar el objetivo del doble clic")
            self.assertEqual({"ciervo"}, app.selected_works)

            opened = []
            init = manager.Viewer.__init__

            def capture(viewer, *args):
                opened.append(viewer)
                init(viewer, *args)

            def load(viewer, item):
                viewer.images[item["id"]] = Image.new("RGB", (160, 320), "#425266")

            with patch.object(manager.Viewer, "__init__", capture), patch.object(manager.Viewer, "_load", load):
                click(card, 1200)
            self.assertEqual(1, len(opened))
            viewer = opened[0]
            self.assertEqual("ciervo", viewer.current()["id"])
            viewer.fullscreen(True)
            root.update()
            self.assertEqual("", viewer.bar.winfo_manager())
            viewer.zoom_by(1.25, (100, 120))
            self.assertIsNotNone(viewer.zoom)
            viewer.set_zoom(None)
            self.assertIsNone(viewer.zoom)
            viewer.fullscreen(False)
            root.update()
            self.assertEqual("pack", viewer.bar.winfo_manager())
            viewer.win.destroy()

            app._press_work(SimpleNamespace(state=1, x_root=20, y_root=20), "buho", app.cards["buho"].master)
            self.assertEqual({"ciervo", "zorro", "buho"}, app.selected_works)
            _, y, _, h = app.cols.bbox("mar")
            target = SimpleNamespace(x_root=app.cols.winfo_rootx() + 30, y_root=app.cols.winfo_rooty() + y + h // 2)
            app._drag_work(target)
            self.assertEqual("mar", app.drop_target)
            with patch.object(app, "_queue_move") as move:
                app._drop_work(target)
                move.assert_called_once_with("Mar", "mar")

            app.search.set("zorro")
            self.assertEqual(["zorro"], list(app.cards))
            self.assertEqual([], futures)


if __name__ == "__main__":
    unittest.main()
