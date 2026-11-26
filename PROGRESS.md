# Aura Artistic Evolution 2.0

Rama de trabajo `aura-artistic-evolution-2`, derivada de `72313ef` en `aura-artistic-evolution`.

## Estado de las etapas

| Etapa | Avance | Verificación |
|---|---|---|
| Auditoría | Se revisaron documentación, navegación, clima, widgets, Studio, Signature, fondo animado y CI. La base ya incluye capas, cuatro dígitos editables, importación TTF/OTF, materiales, Open-Meteo y el reloj del sistema. | Rama base verificada. |
| AutoDesign | Importar una foto abre tres propuestas generadas localmente a partir del encuadre, paleta, contraste, detalle y máscara del sujeto cuando está disponible. Cada diseño ofrece aplicar, editar y generar alternativas. | Pruebas JVM añadidas. Flujo Android pendiente de verificación. |
| Información del reloj | Puro, Esencial, Ambiental y Completo, con posición, tamaño, fuente, color, opacidad y visibilidad por campo. Los proyectos anteriores conservan el modo Original. | Pendiente de verificación Android. |
| Signature | Catálogo 2.0 remoto dedicado a fotografía, favoritos persistentes, colecciones, miniaturas, descarga bajo demanda, caché local, máscara y reloj recomendado opcionales. | El catálogo está vacío a la espera de las fotografías del propietario. |
| Atmosphere | Puede seguir las condiciones meteorológicas guardadas, incluidos viento y nubosidad, con intensidad y apagado. El fondo limita el movimiento a periodos visibles. | Pendiente de verificación Android y Galaxy S24+. |
| Clima y Widgets | Clima abre la app, muestra el estado de carga o antigüedad de los datos y conserva el pronóstico. El catálogo de widgets ofrece los meteorológicos. Los receptores e identificadores heredados siguen declarados para conservar widgets existentes. | Pendiente de pruebas del selector de One UI Home y de migración real. |
| Compilación y distribución | CI prepara un APK firmado con el identificador `app.aura.preview`, instalable junto a la estable, y verifica las pruebas y el prefijo de la huella de firma antes de publicar. | Pendiente de ejecución y comprobación del artefacto. |

## Límites y siguientes pasos

- La segmentación de ML Kit se ejecuta localmente. La entrega inicial del modelo depende de Play Services. Si no hay modelo o sujeto, se presentan propuestas sin profundidad y se conserva el pincel manual.
- El contraste y la ocupación estimados por AutoDesign son heurísticos. Queda por medir la legibilidad y la oclusión minuto a minuto sobre fotografías reales, también con máscaras imperfectas.
- El proveedor de clima actual no expone alertas oficiales en el modelo de Aura. Se mantienen las condiciones y avisos de precipitación derivados del pronóstico.
- Para publicar una colección, editar `signature/catalog-v2.json` en esta rama. Cada obra necesita un ID único estable, título, archivo de 1440 × 3120 y miniatura. La máscara opcional debe ser PNG de 1440 × 3120 con silueta opaca y fondo transparente. `recommendedDesign` admite el esquema de `ClockDesign`. Usar rutas nuevas cuando se actualicen imágenes y registrar la licencia suministrada. Verificar la fotografía con el estándar de composición del propietario.
- Ejecutar `:core:test :app:testDebugUnitTest :app:lintDebug` y `:app:connectedDebugAndroidTest`. Verificar también la instalación paralela, el reloj del sistema y el fondo en un Galaxy S24+.
- La etiqueta para la prueba es `aura-artistic-evolution-2-test`. El enlace de descarga se debe comunicar tras verificar que el APK está publicado.

## Historial de Artistic Evolution 1

# Aura — Artistic Evolution: progreso

Rama: `aura-artistic-evolution` (no se fusiona a `main` automáticamente).
APK de prueba: versión `aura-artistic-evolution-test` →
https://github.com/georgestrike25-afk/Aura/releases/download/aura-artistic-evolution-test/Aura.apk
(la estable `aura-latest` solo se publica desde `main`).

Cómo retomar: leer este archivo, `git log --oneline -20` en la rama y seguir con la primera etapa
no marcada como hecha. Cada etapa es un commit (o varios) independiente.

Validación local: el módulo `core` (Kotlin puro) se prueba con un proyecto aparte que enlaza
`core/src` (Maven de Google está bloqueado en el contenedor; `app` solo compila en GitHub Actions).
Copia del arnés: `tools/corecheck/` (ver README ahí).

## Etapas

| # | Etapa | Estado |
|---|-------|--------|
| 0 | Auditoría, PROGRESS.md, canal de publicación de prueba | hecho |
| 1 | Identidad visual (paleta obsidiana/grafito/champán/marfil/jade, navegación con Signature) | hecho |
| 2 | Studio: cuatro dígitos independientes (mover, escalar, rotar, deformar, color, fuente, opacidad), grupos, guías magnéticas, bloqueo | hecho  |
| 3 | Aura Elements: 8 materiales paramétricos + controles | hecho  |
| 4 | Aura Fusion: análisis de la imagen y «Armonizar» | hecho  |
| 5 | Aura Layers: capas, dígitos delante/detrás del sujeto, orden/ocultar/bloquear, máscara | hecho  |
| 6 | Aura Fonts: familias, nuevas fuentes libres, importar TTF/OTF | hecho  |
| 7 | Atmosphere y Living: partículas, luz según la hora, desactivable | hecho  |
| 8 | Aura Signature: galería con catálogo remoto y obras originales | hecho  |
| 9 | Widgets meteorológicos nuevos + migración | hecho  |
| 10 | QA final: pruebas, lint, APK firmado, enlace | hecho |

## Auditoría inicial (etapa 0)

- Código: ~11 000 líneas Kotlin. `core` (JVM, probado) + `app` (Compose, RemoteViews, Studio).
- `ArtisticClock.kt` (commit 5eeea88) es solo modelo de datos (estilo por dígito, materiales,
  profundidad); no estaba conectado a nada. Se integra en la etapa 2 dentro de `ClockDesign`.
- Riesgos detectados:
  - El muestrario de cielos con 44 lienzos tumbaba el emulador (ya dividido en 3 pruebas).
  - El workflow publicaba `aura-latest` desde cualquier rama: corregido (etapa 0).
  - La CI depende de secretos de firma (`AURA_KEYSTORE_*`); sin ellos falla a propósito.
  - Proyectos de Studio se guardan como JSON con `ignoreUnknownKeys`: los campos nuevos deben tener
    valores por defecto para no romper proyectos viejos.
- REA (morluto/rea) no fue necesario.

## Registro

- Etapa 0: canal de prueba en el workflow; este archivo.
- Etapa 1: paleta nueva con alias de los nombres viejos; títulos con serif; Ajustes pasa a un engranaje en Inicio (con volver).
- Etapa 2: `ArtisticClock` (core) integrado en `ClockDesign.artistic`; `ClockPainter` separa los dígitos
  (`splitDigits`) y dibuja cada uno con su matriz, fuente, color, opacidad y material; pestaña «Dígitos»
  (`studio/ui/DigitsPanel.kt`) con selección por toque, grupos, gestos, guías magnéticas (`MagneticGuides`),
  bloqueo, visibilidad, orden de capas y ajuste fino. Profundidad por dígito: `Pass.BEHIND/FRONT` +
  `StudioCompositor.drawClock` (usado también por el fondo animado).
- Etapa 3: `ClockMaterial` suma AQUA, IGNIS, VENTUS, LUX, TERRA, AETHER, GLACIUS, KINTSUGI; texturas
  sin costuras generadas en core (`ElementTexture`, probado) y cacheadas (`ElementTextures`); intensidad,
  escala de textura, transparencia; detalles por elemento (refracción, musgo, núcleo de luz); en el fondo
  animado la textura avanza una vez por minuto (`phase`), sin redibujar entre minutos.
- Etapa 4: `core/studio/Fusion.kt` (probado): colores dominantes (k-medias determinista), brillo, contraste,
  saturación, calidez, brillo en la zona del reloj y dirección de la luz; `harmonize` propone material,
  color legible (contraste ≥ 3), acento, tipografía y ángulo de luz con motivos. UI: sección «Aura Fusion»
  en Efectos (paleta tocable + «Aplicar», un paso de deshacer). El camaleón sigue igual y combinable.
- Etapa 5: pestaña «Capas» (antes Profundidad) con la pila real: dígitos de adelante, sujeto, dígitos de atrás
  y foto; por capa: editar, delante/detrás, ocultar, bloquear, subir/bajar. Máscara: nuevo «Borde del
  sujeto» (`MaskSettings.grow`, expandir/contraer con desenfoque + curva de alfa); pincel manual intacto.
  La segmentación automática sigue siendo ML Kit (no hay un modelo local mejor y gratuito que entre en el APK).
- Etapa 6: 20 fuentes OFL nuevas (61 en total, ~700 KB, recortadas a los caracteres del reloj; licencias en
  docs/licencias/fuentes); `StudioFonts` con familias Editorial, Monument, Zen, Future, Poetry, Display,
  Retro y «Mis fuentes»; importar TTF/OTF con el selector del sistema (se copia a filesDir/fonts, se valida
  con `Typeface.createFromFile`, máx. 8 MB, id `user:…`). Selector `FontPicker` con vista previa real.
- CI: el registro ahora muestra al final solo los errores de compilación (antes los tapaba la traza).
- Etapa 7: `core/studio/Atmosphere.kt` (probado): efectos lluvia, nieve, viento, polvo luminoso, niebla y
  cenizas como partículas sin estado (función del tiempo) + `TimeLight` (tinte por amanecer, hora dorada,
  atardecer y noche, con salida/puesta del sol del clima). `AtmospherePainter` dibuja; el compositor tiñe la
  escena (foto, sujeto y números de atrás) y no los números de adelante. Editor: pestaña «Ambiente» con
  partículas animadas sobre la vista previa (30 fps máx.). Fondo animado: ~24 fps solo visible, sin ahorro de
  batería y con «Animado» encendido (lienzo por GPU); si no, las partículas quedan quietas en el fotograma.
- Etapa 8: 12 obras originales 1440×3120 generadas por código (`tools/aura_signature.py`, Pillow+numpy,
  CC BY 4.0) en 3 colecciones (Sueños quietos, Horizontes, Materia) en `signature/` + `catalog.json`.
  No van en el APK: la app descarga el catálogo de main o, si no existe, de esta rama
  (`Signature.BASE_URLS`), guarda todo en caché y valida ids/rutas. Pestaña «Signature» (Inicio, Clima,
  Studio, Signature, Widgets); tocar una obra la descarga y crea un proyecto de Studio. Para sumar una
  colección: agregar imágenes y entradas en `signature/catalog.json` (sin actualizar el APK).
- Etapa 9: 5 tipos nuevos al final de `WidgetType` (ATMOSPHERE, DETAIL, HOURLY, WEEKLY, ASTRO) con
  composiciones por tamaño en `tools/aura_widgets.py` (480 diseños), receptores, `widget_*_info.xml`,
  indicadores (sensación, humedad, viento, lluvia, UV; ocultos si no hay dato), horas/días con lluvia %,
  hasta 7 días, sol con barra de recorrido. Minimal = Aura Weather. Migración: índices intactos, los
  clásicos siguen y van en «Clásicos con reloj». Vista previa de los nuevos con el renderizador real.
- Etapa 10 (24/11/2026): CI en verde en ed19b9c — pruebas unitarias (core + app), lint, APK debug y release
  firmado (huella 1f6f0c68557969d4f) y pruebas instrumentadas en emulador API 34 (todas pasan; capturas en
  docs/capturas). Corregido en el camino: nombre de paquete tapado por `app`, composable en función local,
  llave sobrante, prueba que esperaba 5 widgets. Versión de prueba publicada en `aura-artistic-evolution-test`;
  `aura-latest` (estable) sin tocar. Límites: ver docs/ARTISTIC_EVOLUTION.md.
