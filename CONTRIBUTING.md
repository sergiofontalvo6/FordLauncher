# Cómo contribuir

¡Gracias por querer mejorar FordLauncher! 🚗

## Reportar problemas
Usa la plantilla de [issues](../../issues/new/choose). Lo más útil:
- La versión de FordLauncher y el **Android real** de la pantalla (en *Ajustes → Acerca de*).
- Fotos de la pantalla.
- El diagnóstico (*Ajustes → Sistema → Exportar diagnóstico*).

## Compilar

Requisitos: JDK 17 y el SDK de Android.

```bash
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/
```

## Reglas del código
- **Compatibilidad con Android 4.1 (API 16):** cualquier API más nueva va detrás de `if (Build.VERSION.SDK_INT >= …)`.
- Java 8 **sin lambdas** ni `java.util.Objects`/`StandardCharsets` (no existen en API 16–18).
- Sin dependencias de Google Play Services, para poder estar en F-Droid.
- La interfaz se dibuja por código en una «mesa» de 1024×600 que se escala (ver `Main.place()`).

## Estructura

```
app/src/main/java/com/fordlauncher/launcher/
  Main.java          pantalla de inicio, ajustes, diálogos
  LiveMapView.java   mapa en vivo (teselas OSM, caché, modo oscuro)
  NowPlaying.java    pista en reproducción (Android 4.x + avisos de reproductores y Bluetooth)
  MediaWatcher.java  pista en reproducción en Android 5+ (MediaSession)
  BtBridge.java      consulta directa a la app Bluetooth de fábrica (Autochips/Clinkway)
  NotifArt.java      carátula de Android Auto desde la notificación de Open Headunit
  Weather.java       clima (Open-Meteo)
  Updates.java       avisos de versiones nuevas (GitHub Releases)
  CarPhoto.java      quitar fondo y ajustar la foto del vehículo
  Net.java           HTTPS con TLS 1.2 y certificados actuales en Android 4.x
app/src/hiddenapi/   stub de API oculta de Android 4.x (solo para compilar, no entra al APK)
app/src/legacy/      RcDisplay: única clase que extiende la API oculta (se carga por reflexión)
```

## Publicar una versión (mantenedores)
1. Sube `versionCode` y `versionName` en `app/build.gradle`.
2. Escribe `changelogs/X.Y.Z.md` y `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
3. Haz commit y súbelo a `main` (o sube una etiqueta `vX.Y.Z`).
4. GitHub Actions compila y firma el APK, crea el Release y actualiza `update.json`, el archivo que la app usa para avisar a los usuarios.
