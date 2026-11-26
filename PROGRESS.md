# Aura Artistic Evolution 2.0

Rama de trabajo `aura-artistic-evolution-2`, derivada de `72313ef` en `aura-artistic-evolution`.

**El clima se siente. El tiempo se convierte en arte.**

## Corrección de AutoDesign

- La detección del sujeto tiene un límite de seis segundos. Si el modelo tarda, se generan propuestas sin máscara y queda disponible la edición manual.
- Se conservan dimensiones mínimas válidas al abrir fotos muy estrechas. Studio muestra los errores de importación y permite reintentar la apertura.
- Con una referencia guardada, «Generar alternativas» cambia las tres propuestas de cada tanda. Las variantes adaptan el encuadre y el contraste a la foto con la tipografía y el material de referencia, en modo Puro.

- Revisión `7260fe4` publicada. Pasaron 83 pruebas del núcleo, las pruebas instrumentadas en Android 14 y `lint`. El APK público coincide con el artefacto firmado y tiene SHA-256 `e1efcf6c39130c32d967b5e64b974dc7f663178e26e5476e5bda174493ea2c92`. [Resultados de CI](https://github.com/georgestrike25-afk/Aura/actions/runs/45790450262) · [APK actualizado](https://github.com/georgestrike25-afk/Aura/releases/download/aura-artistic-evolution-2-test/Aura.apk?rev=7260fe4)

## Ajuste de AutoDesign y vistas de widgets

- AutoDesign aplica el modo Puro a todas las propuestas. Las propuestas nuevas usan cifras altas, acabados de metal o vidrio y separación con el sujeto cuando hay máscara. Si Studio tiene un diseño de referencia, usa el estilo del proyecto abierto, el activo o el último personalizado, en ese orden.
- Las vistas de widgets leen el estado de clima observado por la pantalla. Sin pronóstico muestran un estado explícito y Aura Sol conserva un mensaje visible en todos los tamaños.
- La captura del zorro y la mujer en el agua se usó como referencia visual. La colección Signature requiere los originales verticales sin reloj.

- Revisión `ff7997d` publicada. Pasaron las 81 pruebas del núcleo, la instrumentación en Android 14 y `lint`. La descarga pública coincide con el APK firmado de Actions y tiene SHA-256 `d6a40642549f80c4872d4382c121d216954dd3c562348bfa2973fc088f1fbcf1`. [CI](https://github.com/georgestrike25-afk/Aura/actions/runs/45748587260) · [APK actualizado](https://github.com/georgestrike25-afk/Aura/releases/download/aura-artistic-evolution-2-test/Aura.apk?rev=ff7997d)

## Validación y distribución inicial

[Descargar APK firmado](https://github.com/georgestrike25-afk/Aura/releases/download/aura-artistic-evolution-2-test/Aura.apk) · [Versión preliminar en GitHub](https://github.com/georgestrike25-afk/Aura/releases/tag/aura-artistic-evolution-2-test)

- Gradle local con JDK 17 y SDK 35: 80 pruebas de núcleo aprobadas, APK debug y APK de instrumentación compilados. El módulo Android no tiene pruebas JVM propias.
- `:app:lintDebug` finalizó sin errores. El informe incluye advertencias de los layouts de widgets generados y de atributos introducidos después del SDK mínimo.
- El catálogo remoto responde con la versión 2 y cero colecciones. La primera publicación requiere las fotos del propietario.
- [GitHub Actions, ejecución 45741807846](https://github.com/georgestrike25-afk/Aura/actions/runs/45741807846) compiló el APK de distribución con la configuración de firma del repositorio y aprobó `:app:lintDebug`. En el emulador API 34 pasaron 30 de 31 pruebas. La única falla fue la expectativa anterior de diez widgets en el selector, que mostró los seis meteorológicos.
- La revisión `2cc3a0c` actualiza esa prueba para validar el nuevo selector y verificar también que los cuatro receptores heredados conservan sus metadatos. Pasaron las 31 pruebas instrumentadas en Android 14, además de las 80 pruebas del núcleo y el análisis estático. [Segunda ejecución de GitHub Actions](https://github.com/georgestrike25-afk/Aura/actions/runs/45743377212).
- APK de la revisión `2cc3a0c` verificado con `apksigner` y `aapt`: `app.aura.preview`, `1.0.0-evolution2`, «Aura 2 · Prueba». SHA-256 del APK `8d24f018b446064109be7fc277efb7689f2039fc485a465c44418651b3b8cf1d`. Certificado de Aura `1f6f0c68557969d4f59cc8281f86c09437ce3f94511c10471867908c92867c2c`.
- La descarga pública de GitHub Releases coincide byte por byte con el APK firmado de CI. La publicación se recuperó reutilizando la compilación que pasó las pruebas. El workflow también distribuye el binario en la rama de prueba para mantener una descarga directa cuando no pueda adjuntarlo a Releases. [Ejecución de publicación](https://github.com/georgestrike25-afk/Aura/actions/runs/45745385489).

## Estado de las etapas

| Etapa | Avance | Verificación |
|---|---|---|
| Auditoría | Se revisaron documentación, navegación, clima, widgets, Studio, Signature, fondo animado y CI. La base ya incluye capas, cuatro dígitos editables, importación TTF/OTF, materiales, Open-Meteo y el reloj del sistema. | Rama base verificada. |
| AutoDesign | Importar una foto abre tres propuestas generadas localmente a partir del encuadre, paleta, contraste, detalle y máscara del sujeto cuando está disponible. Cada diseño ofrece aplicar, editar y generar alternativas. | Pruebas JVM en verde. La comprobación geométrica de los 1.440 minutos por formato pasó en `StudioFlowTest`. |
| Información del reloj | Puro, Esencial, Ambiental y Completo, con posición, tamaño, fuente, color, opacidad y visibilidad por campo. Los proyectos anteriores conservan el modo Original. | Compila en Android. Queda la revisión visual de cada modo con fotos reales. |
| Signature | Catálogo 2.0 remoto dedicado a fotografía, favoritos persistentes, colecciones, miniaturas, descarga bajo demanda, caché local, máscara y reloj recomendado opcionales. | El catálogo está vacío a la espera de las fotografías del propietario. |
| Atmosphere | Puede seguir las condiciones meteorológicas guardadas, incluidos viento y nubosidad, con intensidad y apagado. El fondo limita el movimiento a periodos visibles. | Resolución de efectos probada en JVM y lluvia renderizada en emulador. Pendiente el Galaxy S24+. |
| Clima y Widgets | Clima abre la app, muestra el estado de carga o antigüedad de los datos y conserva el pronóstico. El catálogo de widgets ofrece los meteorológicos. Los receptores e identificadores heredados siguen declarados para conservar widgets existentes. | Navegación y catálogo de seis widgets verificados en emulador. Pendiente One UI Home y una actualización real desde la versión anterior. |
| Compilación y distribución | CI prepara un APK firmado con el identificador `app.aura.preview`, instalable junto a la estable, y verifica las pruebas y la huella SHA-256 de firma, comprobada con el APK de la rama base antes de publicar. | Distribución pública y descarga verificadas. |

## Límites y siguientes pasos

- La segmentación de ML Kit se ejecuta localmente. La entrega inicial del modelo depende de Play Services. Si no hay modelo o sujeto, se presentan propuestas sin profundidad y se conserva el pincel manual.
- El contraste y la ocupación estimados por AutoDesign son heurísticos. La prueba instrumentada recorre los 1.440 minutos en 12 y 24 horas con una escena sintética, comprueba límites del lienzo y solapamiento entre dígitos. Queda por contrastar el resultado con fotografías reales y máscaras imperfectas.
- El proveedor de clima actual no expone alertas oficiales en el modelo de Aura. Se mantienen las condiciones y avisos de precipitación derivados del pronóstico.
- Para publicar una colección, editar `signature/catalog-v2.json` en esta rama. Cada obra necesita un ID único estable, título, archivo de 1440 × 3120 y miniatura. La máscara opcional debe ser PNG de 1440 × 3120 con silueta opaca y fondo transparente. `recommendedDesign` admite el esquema de `ClockDesign`. Usar rutas nuevas cuando se actualicen imágenes y registrar la licencia suministrada. Verificar la fotografía con el estándar de composición del propietario.
- La validación en hardware incluye instalación paralela, reloj del sistema, fondo animado, rendimiento y migración de widgets en Galaxy S24+.

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
