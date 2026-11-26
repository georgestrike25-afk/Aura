# Aura — pase para Codex (y cualquier agente)

Leé esto antes de tocar nada. El detalle del proyecto está en `CLAUDE.md`, `PROGRESS.md` y `docs/`.

## Qué es
App Android de clima, widgets transparentes y fondos con reloj 3D («Aura Studio»). Kotlin + Compose,
minSdk 26, target 35, UI en español rioplatense. Sin servicios pagos, sin claves de API, sin telemetría;
las fotos se procesan en el teléfono. Pensada para un Samsung Galaxy S24+.

## Dónde está cada cosa
- `core/` (JVM puro, JUnit): clima, estilos de widget, `studio/` (ClockDesign, ArtisticClock, Fusion,
  AutoDesign, Atmosphere, ElementTexture), `signature/Signature.kt` (catálogo de la galería).
- `app/src/main/java/app/aura/`: `widget/` (RemoteViews), `ui/` (pantallas), `studio/` (render, máscara,
  fondo animado), `signature/` (galería), `work/`, `data/`.
- `tools/aura_widgets.py` GENERA los layouts XML de widgets: no editarlos a mano.
- `tools/aura_signature_publish.py`: prepara imágenes para Signature (también `--inbox`).

## Estado al 26/11/2026 (rama `aura-artistic-evolution-2`)
- Una sola app: paquete `app.aura` (se instala encima de la Aura del usuario). No volver a crear un paquete aparte.
- Navegación: abre en Clima; pestañas Clima, Studio, Signature, Widgets, Ajustes. El catálogo de widgets
  muestra solo los de clima (los clásicos siguen registrados para no romper los ya puestos). El usuario
  pidió mantenerlo así.
- **AutoDesign** (`core/studio/AutoDesign.kt`): tres propuestas «Monumento», «Apilado» y «Gigante», solo el
  reloj (nunca fecha ni clima por defecto: lo pidió el usuario), grandes y condensadas, detrás del sujeto si
  hay máscara. Referencia de estilo: fondos con profundidad tipo «Depth Wallpapers».
- **Signature**: un solo `signature/catalog.json` (versión 2). La app lee `main` y esta rama y combina las
  colecciones (`Signature.BASE_URLS`, `Signature.merge`). «Subir mis imágenes» guarda en «Mis obras» (solo
  en el teléfono). Imágenes nuevas para todos: subir a `signature/inbox/<Colección>/` y el workflow
  `.github/workflows/signature.yml` las publica. Las obras remotas deben ser 1440×3120.

## Compilar y entregar
- Se compila en GitHub Actions (`.github/workflows/android.yml`): pruebas de `core`, APK firmado, lint y
  pruebas en emulador con capturas en `docs/capturas/`. En el contenedor local Gradle no baja las
  dependencias de Android: validar en la CI.
- Firma: secretos `AURA_KEYSTORE_BASE64` y `AURA_KEYSTORE_PASSWORD` (nunca mostrar, extraer ni subir la
  clave). La CI verifica la huella SHA-256 completa
  `1f6f0c68557969d4f59cc8281f86c09437ce3f94511c10471867908c92867c2c`; si no coincide, no se entrega.
- Publicación: `aura-latest` solo sale de `main`; otras ramas publican en `<rama>-test`.
- No modificar ni fusionar `main` sin permiso explícito del usuario.

## Cómo trabaja el usuario
- Después de cada cambio en la app quiere el APK y capturas reales.
- Respuestas breves, en español rioplatense.
- Commits chicos e independientes; registrar avances en `PROGRESS.md`.

## Trampas conocidas
- En clases con una propiedad llamada `app` (StudioViewModel, SignatureRepository) los nombres completos
  `app.aura...` quedan tapados: usar `import`.
- La segmentación de ML Kit no anda en el emulador (se usa el pincel manual).
- Las pruebas que dibujan muchos cielos tumban el emulador: dividirlas.

## Pendiente
- Probar en un S24+ real: AutoDesign con fotos reales, fondo animado sin parpadeo, subida a «Mis obras».
- Fusionar esta rama a `main` cuando el usuario lo apruebe (así el catálogo de Signature se sirve desde `main`).
