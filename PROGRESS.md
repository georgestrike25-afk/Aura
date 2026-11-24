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
| 1 | Identidad visual (paleta obsidiana/grafito/champán/marfil/jade, navegación con Signature) | hecho (pestaña Signature se agrega en etapa 8) |
| 2 | Studio: cuatro dígitos independientes (mover, escalar, rotar, deformar, color, fuente, opacidad), grupos, guías magnéticas, bloqueo | pendiente |
| 3 | Aura Elements: 8 materiales paramétricos + controles | pendiente |
| 4 | Aura Fusion: análisis de la imagen y «Armonizar» | pendiente |
| 5 | Aura Layers: capas, dígitos delante/detrás del sujeto, orden/ocultar/bloquear, máscara | pendiente |
| 6 | Aura Fonts: familias, nuevas fuentes libres, importar TTF/OTF | pendiente |
| 7 | Atmosphere y Living: partículas, luz según la hora, desactivable | pendiente |
| 8 | Aura Signature: galería con catálogo remoto y obras originales | pendiente |
| 9 | Widgets meteorológicos nuevos + migración | pendiente |
| 10 | QA final: pruebas, lint, APK firmado, enlace | pendiente |

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
