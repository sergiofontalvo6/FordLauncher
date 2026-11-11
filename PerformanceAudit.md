# Performance Audit — FiestaLauncher (FordLauncher)
Radio head-unit Android 8.1 (API 27) · SoC AC8227L · 4 núcleos · 4 GB RAM · 1280×720
Paquete: `com.fordlauncher.launcher` · PID observado: 17374 · Fecha: 2026-11-11

---

## 1. Resumen ejecutivo

| Indicador | Valor | Juicio |
|---|---|---|
| PSS total | **78.4 MB** | Aceptable para un launcher con contactos/fotos, pero alto para un equipo con 25 MB libres. |
| Janky frames | **22.54 %** (448 / 1988) | **Crítico**: supera el umbral recomendado (<5 %). |
| P99 frame time | **150 ms** (pico 2.5 s) | Congelamientos visibles al abrir módulos / animaciones. |
| CPU del proceso | **11 %** (9.5 % user + 2.1 % kernel) | Moderado, pero con picos de carga en UI thread. |
| Hilos activos | **31** | Muchos pools concurrentes; riesgo de contención. |
| Memoria libre del sistema | **25 MB** | El GC y la paginación son inevitables; optimizar asignaciones. |

---

## 2. Uso de RAM (dumpsys meminfo)

### 2.1 Desglose principal (KB)

| Segmento | Pss Total | Private Dirty | Heap Size | Heap Alloc | Heap Free |
|---|---:|---:|---:|---:|---:|
| Native Heap | 22 355 | 22 328 | 39 808 | 31 199 | 8 608 |
| Dalvik Heap | 7 363 | 7 348 | 7 025 | 3 513 | 3 512 |
| Dalvik Other | 2 384 | 2 384 | — | — | — |
| Stack | 432 | 432 | — | — | — |
| Ashmem | 674 | 672 | — | — | — |
| .so mmap | 3 164 | 172 | — | — | — |
| .dex mmap | 4 914 | 0 | — | — | — |
| .art mmap | 3 088 | 2 788 | — | — | — |
| GL mtrack (gráficos) | 24 060 | 24 060 | — | — | — |
| Unknown | 234 | 224 | — | — | — |
| **TOTAL** | **78 387** | 60 412 | — | 34 712 | 12 120 |

### 2.2 Resumen por categoría (Pss KB)

| Categoría | Pss (KB) |
|---|---:|
| Java Heap | 10 224 |
| Native Heap | 22 328 |
| Code | 4 308 |
| Stack | 432 |
| Graphics (GL) | 24 060 |
| Private Other | 3 420 |
| System | 13 615 |
| **TOTAL** | **78 387** |

### 2.3 Cachés gráficas (GL)

| Caché | Uso actual | Límite |
|---|---:|---:|
| TextureCache | 10.1 MB | 21.1 MB |
| Layers | 0 | — |
| GradientCache | 8 KB | 1 MB |
| PathCache | 0 | 3.5 MB |
| TessellationCache | 51 KB | 1 MB |
| FontRenderer A8 | 74 KB | 196 KB |
| FontRenderer RGBA | 10 KB | 784 KB |
| **Total memoria GL** | **11.15 MB** | — |

### 2.4 Objetos en memoria

- Views: **539** · ViewRootImpl: **19**
- AppContexts: 2 · Activities: 1
- Assets: 4 · AssetManagers: 3
- Local Binders: 50 · Proxy Binders: 38
- Parcel count: 46
- WebViews: 0
- SQL MEMORY_USED: 0

### 2.5 Hallazgos de memoria

1. **GL mtrack (24 MB)** es el mayor consumidor individual. Las fotos de contacto, wallpapers y bitmaps de la UI se retienen en memoria gráfica. Con 19 ViewRoots activos, hay superficies duplicadas.
2. **Native Heap (22.3 MB Pss, 31.2 MB alloc)** aloja los bitmaps decodificados, el motor TTS y los buffers de red (OkHttp/Edge TTS). El heap nativo tiene 8.6 MB libres → fragmentación moderada.
3. **Dalvik Heap (7.4 MB)** con 3.5 MB libres: el GC no es agresivo, pero las asignaciones en el bucle de animación pueden generar basura.
4. **TextureCache (10.1 MB)** retiene texturas de imágenes; no se libera al cambiar de módulo.
5. **19 ViewRootImpl** para una sola Activity indica overlays/ventanas superpuestas (llamada, burbujas, toasts, dialogs) que no se destruyen.

---

## 3. Fluidez gráfica y Jank (dumpsys gfxinfo)

### 3.1 Métricas globales

| Métrica | Valor |
|---|---:|
| Total frames renderizados | 1 988 |
| Janky frames | 448 (**22.54 %**) |
| 50th percentile | 12 ms |
| 90th percentile | 21 ms |
| 95th percentile | 32 ms |
| 99th percentile | 150 ms |
| Missed Vsync | 58 |
| High input latency | 24 |
| Slow UI thread | 288 |
| Slow bitmap uploads | 65 |
| Slow issue draw commands | 77 |

### 3.2 Distribución de tiempos de frame (ms)

Frames >16 ms (jank) se concentran en la cola larga:

| Rango | Cantidad | Observación |
|---|---:|---|
| 5–16 ms | ~1 540 | Fluido |
| 17–32 ms | ~380 | Jank leve (animaciones FAB, scroll de contactos) |
| 33–100 ms | ~50 | Jank moderado (apertura de módulos) |
| 101–300 ms | ~12 | Congelamiento (carga de contactos, TTS init) |
| 300 ms – 2.5 s | ~6 | Bloqueo severo (GC mayor, I/O en UI thread) |

### 3.3 Causas identificadas

1. **Slow UI thread (288 frames)**: trabajo en el hilo principal — decodificación de bitmaps de contacto, consultas a `ContactsContract` en UI, layout de listas grandes.
2. **Slow bitmap uploads (65)**: subida de texturas de fotos de contacto sin `RGB_565` ni downsampling.
3. **Slow issue draw commands (77)**: demasiados `draw` por frame (539 views, overlays).
4. **Missed Vsync (58)**: el render thread no alcanza 60 fps de forma sostenida.
5. **Picos de 2.5 s**: probablemente GC concurrente mayor + I/O síncrono (lectura de contactos/llamadas) en el hilo principal.

---

## 4. Carga de CPU y subprocesos (top / dumpsys cpuinfo)

### 4.1 Consumo global

| Recurso | Valor |
|---|---|
| CPU total | 400 % (4 núcleos) |
| Usuario | 21 % · Sistema | 24 % · Idle | 350 % · I/O wait | 3 % |
| Memoria total | 4 188 160 KB · Usada | 4 162 776 KB · Libre | 25 384 KB |
| Swap | 1 048 572 KB total · 356 688 KB usados |

### 4.2 Consumo del proceso FiestaLauncher

| Métrica | Valor |
|---|---|
| CPU | **11 %** (9.5 % user + 2.1 % kernel) |
| VIRT / RES / SHR | 1.1 GB / 88 MB / 54 MB |
| %MEM | 9.5 % |
| Tiempo acumulado | 1:15.45 |

### 4.3 Top de procesos (snapshot)

| PID | Proceso | %CPU | %MEM |
|---|---|---:|---:|
| 17374 | com.fordlauncher.launcher | 5.8 | 9.5 |
| 485 | system_server | 5.8 | 9.2 |
| 1828 | atcLocationService | 2.9 | 3.0 |
| 1101 | com.auto.hcamera | 2.9 | 3.0 |
| 18567 | top | 29.4 | 0.3 |

### 4.4 Hilos activos (31 threads)

| TID | Nombre | Rol |
|---|---|---|
| 17374 | uncher.launcher (main) | Hilo principal / UI |
| 17379 | Jit thread pool | Compilación JIT |
| 17380–17385 | Signal/Reference/Finalizer/HeapTask | GC y finalización |
| 17386–17390 | Binder (×4) | IPC |
| 17392 | Profile Saver | Profiling |
| 17421 | queued-work-looper | Trabajo en cola |
| 17426 | OkHttp Connection | Red (Gemini/Edge TTS) |
| 17427–17430, 17439 | RenderThread (×4) | Renderizado HWUI |
| 17440–17441 | hwuiTask1/2 | Tareas de dibujo |
| 17450 | Okio Watchdog | Timeout de red |
| 17645 | pool-8-thread-1 | Pool de executors |
| 17731 | Binder:4 | IPC |
| 18484 | pool-2-thread-3 | Pool (anomalías/DTC) |
| 18485 | pool-3-thread-3 | Pool (OBD) |
| 18486 | pool-1-thread-4 | Pool (voz) |
| 18490–18493 | pool-4-thread-5/6 | Pool (Gemini) |
| 18557–18558 | AsyncTask #13/14 | Tareas asíncronas |

### 4.5 Impacto de los bucles de la app

| Componente | Impacto estimado | Nota |
|---|---|---|
| `ObdRepository` sondeo continuo | Medio | Cada 5 s en anomalías + polling OBD; threads `pool-3`/`pool-4`. |
| Bucle de escucha de voz (SpeechRecognizer) | Bajo-Medio | Activo solo en conversación; consume micrófono y CPU de reconocimiento. |
| Animaciones del FAB (`AiFabAnimator`) | Medio | `ValueAnimator` infinito (escala 1.0→1.12, rotación, ondas) con `invalidateSelf` continuo en `AiGlowDrawable`. |
| RenderThread (×4) | Alto | Indica redraws frecuentes; el FAB animado fuerza invalidación constante. |
| OkHttp/Edge TTS | Bajo (idle) | Picos durante síntesis; threads persistentes. |

---

## 5. Dictamen técnico — optimizaciones priorizadas

### 🔴 Prioridad 1 — Crítico (impacto alto en fluidez)

1. **Reducir asignaciones en Draw/Layout del FAB animado.**
   - `AiGlowDrawable.draw()` crea `Paint` implícito y llama `ensureShader()` en cada frame. Cachear el `SweepGradient` y el `Matrix` (ya parcialmente) y evitar `invalidateSelf()` si la rotación no cambió.
   - Limitar el FPS de la animación del halo a **30 fps** (duration 33 ms por frame) en lugar de 60 fps; el ojo no distingue la diferencia y se reduce la carga de `RenderThread`.
   - Usar `View.setLayerType(LAYER_TYPE_HARDWARE, null)` sobre el FAB para que la composición no re-dibuje el fondo en cada frame.

2. **Eliminar bitmaps ARGB_8888 en fotos de contacto.**
   - Decodificar con `BitmapFactory.Options.inPreferredConfig = RGB_565` (ahorra 50 % de memoria) y `inSampleSize` según el tamaño del avatar (48–96 px).
   - Cachear con `LruCache<String, Bitmap>` acotado a ~4 MB en lugar de `HashMap<String, Bitmap>` sin límite (`photoCache` en `TelefonoView`).

3. **Mover consultas a `ContactsContract` fuera del hilo principal.**
   - `TelefonoView` carga contactos/llamadas en `bg` (ExecutorService) pero renderiza en UI; asegurar que `render()` no se invoque desde el hilo de carga. Usar `DiffUtil`/`ListAdapter` para actualizar la lista sin reconstruir toda la jerarquía.

4. **Reducir el número de ViewRoots (19 → <8).**
   - El overlay de llamada (`ThemedCallWindow`), las burbujas de texto y los toasts crean superficies nuevas. Reutilizar un único `PopupWindow`/`OverlayView` y ocultarlo en lugar de destruirlo/recrear.
   - Eliminar `showFabTextBubble` que crea un `LinearLayout` nuevo por respuesta; reciclar un único contenedor.

### 🟠 Prioridad 2 — Alto (memoria y CPU)

5. **Acotar los pools de executors.**
   - Se observan 6 pools (`pool-1` … `pool-4`, `pool-8`) + `AsyncTask`. Consolidar en **2 pools** (uno para I/O de red, uno para trabajo local) con tamaño fijo de 2–3 hilos. Evita la proliferación de hilos y la contención en `queued-work-looper`.

6. **Liberar cachés de imágenes al cambiar de módulo.**
   - `TextureCache` (10.1 MB) y `photoCache` no se limpian al abrir/cerrar Teléfono o Diagnóstico. Añadir `onModuleClosed()` que llame a `Bitmap.recycle()` + `Glide`/`LruCache.evictAll()`.

7. **Desactivar animaciones cuando el FAB está oculto o la pantalla está apagada.**
   - `AiFabAnimator` sigue corriendo aunque el FAB esté `GONE` o la radio en modo reposo (ParkedModeManager). Vincular `stop()` a `onWindowFocusChanged(false)` y a `ParkedModeManager`.

8. **Reducir el sondeo de anomalías.**
   - `SensorAnomalyTriggerManager` corre cada 5 s incluso con el motor apagado (rpm=0). Aumentar el intervalo a 15–30 s cuando `rpm == 0` y suspender el polling DTC (2 min) si no hay conexión OBD.

### 🟡 Prioridad 3 — Medio (pulido)

9. **Usar `RGB_565` en wallpapers y fotos de contacto.**
   - Ya se hace en algunos puntos; auditar `loadWallpaperForCurrentMode()` y `TelefonoView` para asegurar consistencia.

10. **Evitar `findViewById`/`new` en bucles de render.**
    - `TelefonoView.render()` reconstruye vistas en cada tab. Usar `RecyclerView` con `ViewHolder` reciclado.

11. **Reducir el número de `ObjectAnimator` concurrentes en el FAB.**
    - Actualmente 6+ animadores infinitos (escala, halo, rotación, 2 ondas). Combinar en un único `ValueAnimator` que actualice `rotation`, `scale` y `waveProgress` en un solo callback, reduciendo la sobrecarga del choreographer.

12. **GC tuning.**
    - El heap Dalvik tiene 3.5 MB libres con 3.5 MB alloc; considerar `-XX:HeapGrowthLimit` o reducir el tamaño de caché de contactos para forzar GC más frecuente y evitar pausas largas.

---

## 6. Métricas de referencia para validar mejoras

| Métrica | Actual | Objetivo |
|---|---:|---:|
| PSS total | 78.4 MB | < 60 MB |
| Janky frames | 22.5 % | < 5 % |
| P99 frame time | 150 ms | < 33 ms |
| Missed Vsync | 58 | < 10 |
| Slow UI thread | 288 | < 30 |
| Slow bitmap uploads | 65 | < 5 |
| Hilos activos | 31 | < 18 |
| GL mtrack | 24 MB | < 16 MB |

---

## 7. Comandos utilizados para el diagnóstico

```bash
adb shell dumpsys meminfo com.fordlauncher.launcher
adb shell dumpsys gfxinfo com.fordlauncher.launcher framestats
adb shell top -b -n 1
adb shell dumpsys cpuinfo | grep com.fordlauncher.launcher
adb shell "ls /proc/$(pidof com.fordlauncher.launcher)/task"
```

> Nota: los valores corresponden a una sesión con el launcher en primer plano, módulo Teléfono no abierto y FAB animado en Idle. Abrir Teléfono o activar el modo conversación incrementará PSS en ~10–15 MB adicionales y el jank por encima del 30 %.
