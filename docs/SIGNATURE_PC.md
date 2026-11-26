# Aura Signature para PC (Windows)

Gestor de los fondos de la galería **Signature**: lo que guardes en la carpeta aparece solo en la app del
teléfono, para todos los usuarios.

## Primera vez (5 minutos)

1. Bajá `AuraSignature.exe` (de `dist/` en el repositorio o de la versión «aura-signature-pc») y abrilo.
   Windows puede avisar «Windows protegió su PC» porque el programa no está firmado: **Más información → Ejecutar de todas formas**.
2. Creá un **token de GitHub** (se hace una sola vez):
   - En el gestor tocá **Configuración → Crear token…** (abre GitHub).
   - *Token name*: `Aura Signature`. *Expiration*: la más larga que te deje (o «No expiration»).
   - *Repository access*: **Only select repositories** → `Aura`.
   - *Permissions → Repository permissions → Contents*: **Read and write**.
   - **Generate token**, copiá el código y pegalo en el gestor → **Guardar y conectar**.
   El token queda en el administrador de credenciales de Windows. Solo sirve para el repositorio Aura.
3. Marcá **Abrir con Windows** para que la carpeta se sincronice siempre (arranca minimizado).

## Uso diario

- La carpeta es `Documentos\Aura Signature\`. **Cada subcarpeta es una colección** (el nombre de la carpeta es
  el de la colección); el nombre del archivo es el título del fondo («Bailarina en ruinas.jpg»).
- Guardá ahí lo que bajes de ChatGPT u otra IA, o **arrastralo a la ventana** (va a la colección elegida),
  o usá **Agregar imágenes…**. En unos segundos se publica; en unos minutos aparece en los teléfonos.
- Formatos: JPG, PNG o WEBP. Ideal vertical, 1440×3120 o más (mínimo razonable 1080×2340). Se recortan al
  formato del teléfono con el centro de la imagen, así que el protagonista conviene centrado.
- Las **repetidas** no se publican (aunque tengan otro nombre o tamaño).
- En la ventana: **Renombrar**, **Mover a…** otra colección, **Borrar** (Ctrl + clic para elegir varias),
  y en las colecciones **Nueva**, **Editar** (nombre y descripción), **↑ ↓** (orden en la app) y **Borrar**.
  Borrar en el gestor quita el fondo de Signature; **las imágenes de tu carpeta no se tocan**.

## Cómo funciona

El gestor prepara cada imagen en la PC (1440×3120 WEBP, miniatura 360×780 y huella para no repetir) con
`tools/aura_signature_publish.py` y publica imágenes + `signature/catalog.json` en **un solo commit** en `main`
con la API de GitHub. Código: `tools/aura_signature_manager.py` (ventana) y `tools/aura_signature_sync.py`
(lógica, con pruebas en `tools/test_aura_signature_sync.py`). Se compila en `.github/workflows/signature-manager.yml`.
