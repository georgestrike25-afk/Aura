# Bandeja de Signature

Subí acá imágenes (PNG, JPG o WEBP; las de ChatGPT sirven tal cual) y en un par de minutos aparecen en la
galería de la app, sin actualizar el APK:

- `signature/inbox/<Nombre de la colección>/<Título de la obra>.png` → colección con ese nombre.
- `signature/inbox/<Título>.png` → colección «Creaciones».

El workflow «Signature: bandeja» las recorta a 1440×3120 (formato del teléfono), crea la miniatura, las
suma a `signature/catalog.json` y las saca de la bandeja. Subí solo imágenes propias o con permiso.
