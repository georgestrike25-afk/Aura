# Aura — Artistic Evolution

Rama `aura-artistic-evolution`. Resumen técnico de lo implementado y de sus límites. El avance por etapas
está en `PROGRESS.md`.

## Qué hay

| Área | Dónde | Qué hace |
|---|---|---|
| Identidad | `ui/theme/Theme.kt` | Paleta obsidiana `#111418`, grafito `#20262C`, champán `#D8BA8B`, marfil `#EDE9E1`, jade `#719A91`; claro y oscuro; títulos con serif. Navegación: Inicio, Clima, Studio, Signature, Widgets; Ajustes desde el engranaje de Inicio. |
| Dígitos independientes | `core/studio/ArtisticClock.kt`, `studio/render/ClockPainter.kt` (`splitDigits`), `studio/ui/DigitsPanel.kt` | Cada dígito (por lugar: 1.ª/2.ª hora, 1.º/2.º minuto) con posición, escala ancho/alto, giro, inclinación, opacidad, color, fuente, material, intensidad, profundidad, bloqueo, visibilidad y orden de capa. Edición individual, por grupos (Horas, Minutos, Todos) o del reloj completo. Toque para elegir, gestos de uno o dos dedos, guías magnéticas (centro de la pantalla y otros dígitos), deshacer/rehacer, guardado. |
| Aura Elements | `core/studio/ElementTexture.kt`, `studio/render/ElementTextures.kt` | Aqua, Ignis, Ventus, Lux, Terra, Aether, Glacius y Kintsugi: texturas sin costuras generadas en el teléfono + detalles por material (refracción, brasas, musgo, núcleo de luz, grietas, vetas de oro). Intensidad, escala de textura, transparencia, dirección de la luz, brillo. Se conservan Metal, Vidrio, Neón, Piedra y Mínimo. |
| Aura Fusion | `core/studio/Fusion.kt` | Colores dominantes, brillo, contraste, saturación, calidez, brillo detrás del reloj y dirección de la luz; «Armonizar» propone material, color legible, acento, tipografía y luz, con motivos. Solo se aplica con «Aplicar» (un paso de deshacer). |
| Aura Layers | `studio/ui/DigitsPanel.kt` (`LayersSection`), `StudioCompositor.drawClock` | Pila: dígitos delante del sujeto, sujeto, dígitos detrás, foto. Delante/detrás, ocultar, bloquear y reordenar por dígito. Máscara: borde ajustable (expandir/contraer) + suavizado + pincel. |
| Aura Fonts | `studio/render/StudioFonts.kt`, `FontPicker` | 61 fuentes OFL (41 anteriores + 20 nuevas) en Editorial, Monument, Zen, Future, Poetry, Display y Retro; importar TTF/OTF propios (validados, máx. 8 MB, solo en el teléfono). |
| Atmosphere y Living | `core/studio/Atmosphere.kt`, `studio/render/AtmospherePainter.kt` | Lluvia, nieve, viento, polvo luminoso, niebla y cenizas; luz según la hora (amanecer, hora dorada, atardecer, noche). Animación en el editor y en el fondo animado; quieta si se desactiva o con ahorro de batería. |
| Signature | `signature/`, `core/signature/Signature.kt`, `signature/SignatureScreen.kt` | 12 obras originales 1440×3120 en 3 colecciones; catálogo remoto con caché (se suman colecciones sin actualizar el APK). |
| Widgets de clima | `core/widget/WidgetType.kt`, `tools/aura_widgets.py`, `widget/WidgetRenderer.kt` | Atmosphere, Detail, Hourly, Weekly y Sol, además de Weather (Minimal). Transparencia real, 3 tamaños, iconos y colores del estilo, vista previa real. |

## Batería y memoria

- Texturas Elements: 256×256 por material, generadas una vez y cacheadas.
- Fondo animado: un fotograma por minuto (texturas avanzan una vez por minuto). Solo con un efecto de
  Atmosphere animado se redibuja a ~24 fps, únicamente mientras el fondo está visible y sin ahorro de batería,
  con lienzo acelerado por GPU; el fotograma base se reutiliza (no se recompone la foto).
- Partículas sin estado (función del tiempo): sin memoria acumulada.
- Signature no agrega peso al APK: las obras se descargan al verlas.

## Límites conocidos (honestos)

- **Obras de Signature**: son ilustraciones digitales originales generadas por código, no fotografías. La
  galería admite fotografía artística, pero solo se publicará la que tenga licencia compatible; no se simula.
- **Animación de los materiales**: la textura se mueve una vez por minuto en el fondo animado (no es una
  animación continua del fuego o el agua) para no gastar batería.
- **Segmentación del sujeto**: sigue siendo ML Kit en el teléfono más el pincel. No hay un modelo local mejor,
  gratuito y que entre en el APK.
- **Catálogo de Signature**: se lee primero de `main` y, si no existe, de esta rama. Al fusionar a `main`
  queda servido desde ahí.
- **Animaciones del icono de widget y de Atmosphere** no se pueden filmar en el emulador de la CI; se
  verifican por capturas fijas.
- REA (morluto/rea) no fue necesario.
