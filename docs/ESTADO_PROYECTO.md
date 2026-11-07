# Estado del proyecto FordLauncher

Última actualización: 2026-11-04 · Versión publicada: **2.9.3** (versionCode 32)

## Publicado
- Release v2.8.0 en GitHub, firmado con la llave de publicación (secretos de GitHub). La firma coincide con las versiones instaladas a mano, así que se instala encima sin perder la configuración.
- `update.json` en `main`: lo actualiza el bot de `release.yml`. La app lo usa para el aviso de actualización.
- Página web: `site/`, publicada con `pages.yml` en https://lordnordic-debug.github.io/FordLauncher/. El botón de descarga toma solo el último release desde la API de GitHub.
- Donaciones: Ko-fi activo (`ko-fi.com/fordlauncher`) en el README, `.github/FUNDING.yml` y el botón «Donar» de la app.

## Pendientes, en orden
1. **GitHub Sponsors:** el perfil está listo y espera que soporte de GitHub corrija el nombre de facturación (ticket #4952223). Cuando lo aprueben y Andrés publique, activar `github: lordnordic-debug` en `.github/FUNDING.yml` y en la sección «Apoya el proyecto» del README.
2. **Confirmar 2.9.3 en la pantalla de Andrés:** al encender, debe verse el inicio (no «Audio Bluetooth»); ▶ del inicio pasa de radio a Bluetooth; revisar el nuevo paso «¿Qué app abre cada botón?». Pendiente de investigar: detectar el play del celular cuando la radio tiene el sonido (ver notas técnicas); con la opción en prueba activada, mirar *Música → Diagnóstico del reproductor* para ver si llega el evento «el celular empezó a sonar».
3. **Bluetooth en otras marcas:** la 2.9.0 trae una opción en prueba (abrir la app BT al conectar y volver). Para soporte nativo de cada marca, pedir `FordDiag` exportado **con el celular sonando** (incluye `bluetooth.txt` con los servicios activos) y su app BT (apk/odex).
4. **App de música preferida:** opción en *Ajustes → Música* para elegir una app (Musicolet, Spotify…). Si no suena nada y se toca ▶ en el inicio, FordLauncher abre o arranca esa app (primero con tecla multimedia dirigida a su receptor; si no responde, abriéndola). Origen: comentario de Oscar Pizarro (Sonic 2015) en Facebook: Musicolet solo se controla después de abrirla; Moodmax no se controla desde el inicio (revisar si publica MediaSession; pedirle API y *Diagnóstico del reproductor*).
5. **Accesos a pantallas internas:** en *Accesos y apps*, además de apps completas, permitir «Elegir pantalla interna…» (lista de actividades exportadas de una app, por ejemplo la Radio o la Música Bluetooth que viven dentro del launcher de fábrica). Origen: comentario de Giancarlo Maya en Facebook: su pantalla tiene radio y música Bluetooth dentro del launcher de fábrica. Pedirle modelo, API y `FordDiag`.
6. **Capturas reales:** reemplazar los mockups de `fastlane/.../phoneScreenshots` y `site/img` por fotos de la pantalla de Andrés.
7. **Publicar en IzzyOnDroid** y luego en F-Droid (plantilla en `docs/fdroid/`).

## Ideas sin compromiso
- Mapa con vista inclinada (3D) como opción en Ajustes. Antes hay que medir el rendimiento en pantallas lentas.

## Notas técnicas
- Pruebas 2.9.2 en el Picanto (2026-11-05): (a) al instalar/encender con el celular sonando, el primer `poll` tomaba el estado inicial como flanco y abría «Audio Bluetooth», que a veces no volvía; corregido en 2.9.3 (`wasPlaying` empieza en null y 60 s de gracia). (b) Con la radio sonando, dar play en el celular NO dispara nada: probablemente `isPlaying()` (transacción 4) refleja el estado local del servicio, no el A2DP real, mientras la radio tiene el foco. (c) Tocar ▶ en FordLauncher (`togglePlay`, transacción 1) sí pasa el sonido a Bluetooth y pausa la radio: es el método confiable en esta pantalla.
- Actualizaciones: en Android 4.2 la API de GitHub y raw.githubusercontent suelen fallar (TLS); jsDelivr `@main` cachea horas y daba versiones viejas. Desde 2.9.2 la app consulta todas las fuentes y toma la mayor; `release.yml` purga jsDelivr tras actualizar `update.json`.
- Hallazgo con 2.9.0 en la pantalla de Andrés: `setBTMusicMode()` con el celular en pausa (botón ▶ del inicio) sí pasó el sonido de radio a Bluetooth; con el celular ya sonando no. `isAudioTmpLose()` es true mientras la radio tiene el sonido, así que no sirve para descartar.
- Compatibilidad: `showCompat()` en Main lista funciones, requisito (versión de Android o marca) y estado en la pantalla. Al agregar una función nueva, sumarla ahí.
- Clinkway (`com.clw.btmusic`): **probado en 2.9.0 que llamar a `setBTMusicMode()` (transacción 16) desde fuera no pasa el sonido** y además dañaba play/pausa (con el celular en pausa esa llamada no envía play). El sistema cambia la fuente de audio según la app de fuente que pasó al frente por última vez. Solución 2.9.1: FordLauncher mantiene vivo el servicio (`BIND_AUTO_CREATE`) solo para detectar el flanco «pausa → sonando» (`BtBridge.poll`, cada 2 s, también en segundo plano); si la última fuente al frente no fue `com.clw.btmusic` (`getRunningTasks`, permisos GET_TASKS/REORDER_TASKS), abre su pantalla 2 s y vuelve con `moveTaskToFront` a la tarea anterior. `btRouted` se pone en falso cuando otra app de fuente (radio, AUX, video…) pasa al frente o cuando el servicio de fábrica se cierra. Si el servicio se reinicia 3 veces en 90 s, deja de mantenerlo vivo 15 min.
- Idea a futuro: el sistema tiene `android.mode.AppManager` (modo de fuente). Con sus métodos se podría cambiar la fuente sin abrir la pantalla; hay que listarlos por reflexión en el diagnóstico.
- Página: en navegadores internos (Facebook, Instagram, Messenger, TikTok…) muestra una barra y, al tocar Descargar, instrucciones con «Abrir en Chrome» (intent de Android) y copiar enlace. Para probarla en un navegador normal se agrega `?iab=1` a la URL.

## Historial
- 2026-11-05: v2.9.3 — no abrir «Audio Bluetooth» al arrancar; automático en prueba y apagado; revisar qué app abre cada botón.
- 2026-11-05: confirmado en la pantalla de Andrés (Android 4.2): el aviso dentro de la app detecta la 2.9.2 (pasando de 2.9.1).
- 2026-11-04: v2.9.2 — actualizaciones confiables (todas las fuentes + purga de jsDelivr), BT desde la radio, tabla de compatibilidad.
- 2026-11-04: v2.9.1 — corrige play tras pausa y cambia el método: abre «Audio Bluetooth» un instante y vuelve.
- 2026-11-04: v2.9.0 (16c468a) — audio Bluetooth automático (Clinkway) y opción para otras marcas; aviso en la página para navegadores internos.
- 2026-11-04: v2.8.0 publicada; Ko-fi activado; página web en GitHub Pages; ajuste de espacios de la página.
