package com.fordlauncher.launcher.ai;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.media.AudioManager;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fordlauncher.launcher.DiagnosticoView;
import com.fordlauncher.launcher.Main;
import com.fordlauncher.launcher.Net;
import com.fordlauncher.launcher.obd.GeminiDiagnosticClient;
import com.fordlauncher.launcher.obd.ObdRepository;
import com.fordlauncher.launcher.obd.Pids;
import com.fordlauncher.launcher.obd.VehicleIntent;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * Motor único del copiloto de voz. Contiene TODAS las ramas que antes vivían en
 * {@code Main}: comandos locales, acciones JSON de Gemini, música, llamadas,
 * diagnóstico y el snapshot del vehículo. Tanto {@code Main} como
 * {@code VoiceOverlayService} delegan aquí, de modo que el botón global no puede
 * omitir ninguna acción.
 */
public final class AssistantActionEngine {

    private static final String TAG = "FordLauncher/AI";
    /** Respuesta exacta cuando no hay red y la frase no es un comando local. */
    private static final String MSG_SIN_INTERNET =
            "No tengo conexión a internet para responder eso, pero puedo abrir aplicaciones, revisar el vehículo o reproducir tu música descargada.";

    /** Operaciones dependientes del entorno: Activity (Main) o Service (overlay). */
    public interface Host {
        Context context();
        void showText(String text);
        void speak(String text, boolean continuar);
        void stopVoice();
        void setConversationActive(boolean active);
        void refreshMusicSession();
        void setWaiting();
        void setIdle();
        void setSpeaking();
        void notifyUser(String text);
        /** true cuando la UI del launcher se ejecuta aquí (Main); false si se delegó al servicio. */
        boolean runsUiLocally();
        /** @return true si la acción se abrió correctamente. */
        boolean openDock(String key, String pendingJson);
        boolean openDiagnostic(int tab, boolean analizar, String pendingJson);
        /** Vuelve al dashboard del launcher y cierra cualquier pestaña abierta. */
        void goHome(String pendingJson);
    }

    private final Host host;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AssistantActionEngine(Host host) {
        this.host = host;
        this.ctx = host.context();
    }

    /** Flujo completo: comandos locales -> estado OBD -> Gemini -> ejecutar acción. */
    public void ask(final String pregunta) {
        if (pregunta == null || pregunta.trim().length() == 0) { host.setIdle(); return; }
        LatencyTrace.asegurarTurno("ASK");
        // Los comandos locales SIEMPRE van antes de mirar la red o llamar a Gemini.
        try {
            if (ejecutarComandoLocal(pregunta)) {
                LatencyTrace.iaFin(true);
                return;
            }
        } catch (Throwable ignored) {}

        boolean sinRed = !Net.isNetworkAvailable(ctx);
        boolean requiereVehiculoOffline = sinRed && VehicleIntent.requiereVehiculo(pregunta);
        if (sinRed) {
            if (requiereVehiculoOffline && responderSensoresLocal(pregunta)) {
                LatencyTrace.iaFin(true);
                return;
            }
            if (!requiereVehiculoOffline) {
                LatencyTrace.iaFin(true);
                Log.i(TAG, "Sin red -> respuesta local, Gemini no invocado: " + MSG_SIN_INTERNET);
                host.showText(MSG_SIN_INTERNET);
                host.speak(MSG_SIN_INTERNET, true);
                return;
            }
        }

        boolean conectado = false;
        boolean requiereVehiculo = VehicleIntent.requiereVehiculo(pregunta);
        try {
            ObdRepository r = obd();
            if (r != null) {
                conectado = r.isConnected() || r.hasRecentValues(20000);
                if (!conectado && requiereVehiculo && r.isConnecting()) {
                    conectado = r.esperarConexion(2500) || r.hasRecentValues(20000);
                }
            }
        } catch (Throwable ignored) {}

        // Diagnóstico rápido: los sensores ya leídos se responden en el equipo.
        if (requiereVehiculo && responderSensoresLocal(pregunta)) {
            LatencyTrace.iaFin(true);
            return;
        }
        if (requiereVehiculo && !conectado) {
            LatencyTrace.iaFin(true);
            final String aviso = "Sergio, el adaptador OBD2 no está conectado. Por favor conéctalo para poder leer los sensores del Fiesta.";
            host.showText(aviso);
            host.speak(aviso, true);
            return;
        }
        if (sinRed && requiereVehiculoOffline) {
            LatencyTrace.iaFin(true);
            final String aviso = "Sergio, sin conexión solo puedo leer los sensores disponibles del vehículo.";
            host.showText(aviso);
            host.speak(aviso, true);
            return;
        }

        final String task = "El conductor pregunta por voz: \"" + pregunta.trim() + "\"";
        final String snap = snapshot();
        host.notifyUser("Consultando…");
        host.setWaiting();
        LatencyTrace.iaInicio(GeminiDiagnosticClient.MODEL_VOZ);
        GeminiDiagnosticClient.analyze(ctx, snap, task, false, new GeminiDiagnosticClient.Callback() {
            public void onDone(final String text, final String error) {
                LatencyTrace.iaFin(false);
                main.post(new Runnable() {
                    public void run() { procesarRespuestaGemini(text, error, pregunta); }
                });
            }
        });
    }

    /** Igual que Main.procesarRespuestaGemini: la acción se parsea del raw, no de limpiarParaVoz(). */
    public void procesarRespuestaGemini(String text, String error, String userSpeech) {
        if (error != null || text == null) {
            host.setIdle();
            host.notifyUser(error != null ? error : "Sin respuesta de la IA");
            return;
        }
        Log.d(TAG, "raw=" + text);
        if (ejecutarAccionIa(text, userSpeech)) return;
        final String textoLimpio = GeminiDiagnosticClient.limpiarParaVoz(text);
        if (textoLimpio == null) {
            host.setIdle();
            host.notifyUser("Sin respuesta de la IA");
            return;
        }
        host.speak(textoLimpio, true);
    }

    /** Todas las acciones JSON de Gemini, sin recortar ninguna rama. */
    public boolean ejecutarAccionIa(String text, String userSpeech) {
        if (text == null) return false;
        Log.d(TAG, "raw=" + text);
        String t = text.trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) return ejecutarMusicaPorTexto(t, userSpeech);
        final String json = t.substring(s, e + 1);
        if (!json.contains("\"action\"")) return ejecutarMusicaPorTexto(t, userSpeech);

        String action = "", speech = "", appName = "", query = "";
        try {
            JSONObject o = new JSONObject(json);
            action = o.optString("action", "").trim();
            speech = o.optString("speech", "").trim();
            appName = o.optString("app_name", "").trim();
            query = o.optString("query", "").trim();
        } catch (Throwable parseError) {
            Log.w(TAG, "JSON no válido=" + json, parseError);
            return ejecutarMusicaPorTexto(t, userSpeech);
        }

        if ("MAKE_CALL".equalsIgnoreCase(action)) {
            String target = (appName.length() > 0) ? appName : query;
            try { target = new JSONObject(json).optString("target", target).trim(); } catch (Throwable ignored) {}
            if (target.length() == 0) target = query.length() > 0 ? query : appName;
            String decir = speech.length() > 0 ? speech : ("Llamando a " + target);
            host.setConversationActive(false);
            host.stopVoice();
            host.showText(decir);
            final String finalTarget = target;
            final String finalSpeech = decir;
            main.postDelayed(new Runnable() {
                public void run() {
                    try { VoiceCallHelper.procesarLlamadaPorVoz(ctx, finalTarget, finalSpeech); }
                    catch (Throwable ignored) {}
                }
            }, 50);
            return true;
        }
        if ("OPEN_APP".equalsIgnoreCase(action)) {
            String target = appName.length() > 0 ? appName : query;
            if (target.length() == 0) target = speech;
            String nTarget = ContactNames.norm(target);
            // "apps" no es una app externa: es el cajón del dock de FordLauncher.
            if (esPedidoDeAppsDrawer(nTarget)) {
                abrirCajonApps(json, speech.length() > 0 ? speech : "Abriendo aplicaciones");
                return true;
            }
            // "radio" es el slot del dock, que ya lanza la app de radio de la radio.
            if (esAliasRadio(nTarget)) {
                abrirRadioDock(json, speech.length() > 0 ? speech : "Abriendo Radio");
                return true;
            }
            // 100% en segundo plano: se lanza el intent desde el contexto del servicio,
            // sin traer Main al frente ni enviar PENDING_ACTION_JSON.
            boolean ok = false;
            try { ok = AppLauncherHelper.abrirAppPorNombre(ctx, target); } catch (Throwable ignored) {}
            if (!ok && target.length() == 0) {
                try { ok = AppLauncherHelper.abrirAppPorNombre(ctx, t.replaceAll("(?i)^abr(?:e|ir)\\s+", "")); }
                catch (Throwable ignored) {}
            }
            String decir = ok ? (speech.length() > 0 ? speech : ("Abriendo " + target))
                    : ("No encontré la aplicación " + (target.length() > 0 ? target : "solicitada"));
            host.speak(decir, false);
            return true;
        }
        if ("GO_HOME".equalsIgnoreCase(action)) {
            ejecutarGoHome(json, speech.length() > 0 ? speech : "Volviendo al inicio");
            return true;
        }
        if ("PLAY_MUSIC".equalsIgnoreCase(action)) {
            String q = query;
            if ((q == null || q.trim().length() == 0)) q = appName;
            if ((q == null || q.trim().length() == 0)) q = speech;
            return ejecutarMusicaEnSerMusic((q == null) ? "" : q.trim(), speech);
        }
        if ("OPEN_DOCK".equalsIgnoreCase(action)) {
            String item = appName.length() > 0 ? appName : query;
            String key = dockKeyDe(ContactNames.norm(item));
            boolean ok = host.openDock(key, json);
            String decir = ok ? (speech.length() > 0 ? speech : ("Abriendo " + etiquetaDock(key)))
                    : ("No encontré \"" + item + "\" en el menú");
            if (host.runsUiLocally() || !ok) host.speak(decir, false);
            return true;
        }
        if ("OPEN_DIAGNOSTIC".equalsIgnoreCase(action)) {
            String item = query.length() > 0 ? query : appName;
            int tab = DiagnosticoView.pestanaDe(ContactNames.norm(item));
            boolean ok = host.openDiagnostic(tab, false, json);
            if (host.runsUiLocally() || !ok) {
                host.speak(speech.length() > 0 ? speech
                        : (tab >= 0 ? ("Abriendo Diagnóstico en " + nombrePestana(tab) + ".") : "Abriendo Diagnóstico."), false);
            }
            return true;
        }
        if ("ANALYZE_AI".equalsIgnoreCase(action)) {
            String item = query.length() > 0 ? query : appName;
            int tab = DiagnosticoView.pestanaDe(ContactNames.norm(item));
            boolean ok = host.openDiagnostic(tab, true, json);
            if (host.runsUiLocally() || !ok) host.speak(speech.length() > 0 ? speech : "Analizando con IA.", false);
            return true;
        }
        return false;
    }

    /** Gemini puede responder texto plano: no dejamos escapar una petición de música. */
    public boolean ejecutarMusicaPorTexto(String text, String userSpeech) {
        String t = text == null ? "" : text.trim();
        String u = userSpeech == null ? "" : userSpeech.trim();
        if (!VoiceActionParser.isMusicIntent(text, userSpeech)) return false;
        String fuente = t.toLowerCase(Locale.ROOT).contains("sermusic") ? t : u;
        String q = VoiceActionParser.extractMusicQuery(fuente);
        if (q.length() == 0) return false;
        Log.i(TAG, "fallback PLAY_MUSIC query=\"" + q + "\"");
        return ejecutarMusicaEnSerMusic(q, t.length() > 0 ? t : ("Sonando " + q + " en SerMusic"));
    }

    public boolean ejecutarMusicaEnSerMusic(String query, String speech) {
        if (query == null || query.trim().length() == 0) return false;
        final String qFinal = query.trim();
        final boolean sinRed = !Net.isNetworkAvailable(ctx);
        final String decir = sinRed ? mensajeMusicaOffline(qFinal)
                : (speech != null && speech.trim().length() > 0 ? speech.trim() : "Ok.");
        Log.i(TAG, "PLAY_MUSIC offline=" + sinRed + " decir=\"" + decir + "\"");
        // Cerrar el micrófono antes de reproducir evita que onReadyForSpeech robe el foco de audio.
        host.setConversationActive(false);
        host.stopVoice();
        try { MusicVoiceController.reproducirEnSerMusic(ctx, qFinal, sinRed); }
        catch (Throwable err) { Log.w("FordLauncher/Music", "No se pudo lanzar SerMusic", err); }
        host.refreshMusicSession();
        host.speak(decir, false);
        return true;
    }

    /** Mensaje exacto de música sin red: con canción concreta o genérico. */
    private static String mensajeMusicaOffline(String query) {
        String n = ContactNames.norm(query);
        boolean generico = n.length() == 0 || n.equals("musica") || n.equals("mi musica")
                || n.equals("tu musica") || n.equals("canciones") || n.equals("mis canciones")
                || n.equals("sermusic") || n.equals("ser music") || n.equals("reproductor")
                || n.equals("reproductor de musica") || n.equals("descargada") || n.equals("descargadas");
        return generico ? "Sin conexión a internet, reproduciendo tu música descargada"
                : "Sin conexión a internet, buscando " + query.trim() + " en tu música descargada";
    }

    /**
     * Respuesta de sensores 100 % local (sin Gemini) cuando el conductor pregunta
     * por el vehículo y no hay internet. Usa los últimos valores del OBD2.
     */
    private boolean responderSensoresLocal(String pregunta) {
        String n = ContactNames.norm(pregunta);
        if (n == null || n.length() == 0) return false;
        ObdRepository r = obd();
        if (r == null || (!r.isConnected() && !r.hasRecentValues(20000))) return false;
        Map<String, Float> v = r.getValues();
        if (v == null || v.isEmpty()) return false;

        Float coolant = v.get(Pids.COOLANT);
        Float voltage = v.get(Pids.VOLTAGE);
        Float rpm = v.get(Pids.RPM);
        Float speed = v.get(Pids.SPEED);
        Float consumo = v.get(Pids.FUEL_RATE);
        if (consumo == null) consumo = v.get(Pids.CONSUMPTION);
        String respuesta = null;

        boolean porCodigos = n.contains("codigo") || n.contains("falla") || n.contains("dtc");
        if (porCodigos) {
            com.fordlauncher.launcher.obd.Health.EngineReport rep = r.getLastReport();
            java.util.List<String> codes = rep == null ? null : rep.stored;
            if (codes == null || codes.isEmpty()) codes = rep == null ? null : rep.pending;
            respuesta = (codes == null || codes.isEmpty())
                    ? "Sergio, no hay códigos de falla guardados en el vehículo."
                    : "Sergio, los códigos de falla son " + codes.toString() + ".";
        } else if ((n.contains("temperatura") || n.contains("refrigerante") || n.contains("caliente")) && coolant != null) {
            respuesta = "Sergio, el refrigerante está a " + dec(coolant) + " grados.";
        } else if ((n.contains("bateria") || n.contains("voltaje") || n.contains("voltios")) && voltage != null) {
            respuesta = "Sergio, la batería marca " + dec(voltage) + " voltios.";
        } else if ((n.contains("revolucion") || n.contains("rpm") || n.contains("motor")) && rpm != null) {
            respuesta = "Sergio, el motor está a " + entero(rpm) + " revoluciones por minuto.";
        } else if (n.contains("velocidad") && speed != null) {
            respuesta = "Sergio, la velocidad es de " + entero(speed) + " kilómetros por hora.";
        } else if ((n.contains("consumo") || n.contains("combustible")) && consumo != null) {
            respuesta = "Sergio, el consumo es de " + dec(consumo) + " litros por hora.";
        } else if (n.contains("sensor") || n.contains("vehiculo") || n.contains("auto")
                || n.contains("carro") || n.contains("fiesta") || n.contains("estado")) {
            if (coolant == null && voltage == null && rpm == null && speed == null) return false;
            StringBuilder sb = new StringBuilder("Sergio, sin conexión leo los sensores directamente");
            if (coolant != null) sb.append(": refrigerante ").append(dec(coolant)).append(" grados,");
            if (voltage != null) sb.append(" batería ").append(dec(voltage)).append(" voltios,");
            if (rpm != null) sb.append(" motor ").append(entero(rpm)).append(" revoluciones,");
            if (speed != null) sb.append(" velocidad ").append(entero(speed)).append(" kilómetros por hora,");
            if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
            sb.append('.');
            respuesta = sb.toString();
        }
        if (respuesta == null) return false;
        Log.i(TAG, "Lectura OBD local sin red: " + respuesta);
        host.showText(respuesta);
        host.speak(respuesta, true);
        return true;
    }

    private static String dec(float x) { return String.format(Locale.getDefault(), "%.1f", x); }
    private static String entero(float x) { return String.format(Locale.getDefault(), "%.0f", x); }

    /** Comandos que el launcher resuelve sin nube: dock, diagnóstico, música, llamadas. */
    public boolean ejecutarComandoLocal(String raw) {
        if (raw == null) return false;
        String t = ContactNames.norm(raw);
        if (t.length() == 0) return false;
        String rawLower = raw.trim().toLowerCase(Locale.ROOT);

        // 0a) Acciones del vehículo que no deben esperar a la nube.
        if (ejecutarVolumenLocal(t)) return true;
        if (ejecutarBrilloLocal(t)) return true;
        if (ejecutarNavegacionLocal(t)) return true;
        if (ejecutarMusicaGenericaLocal(t)) return true;

        // 0) Volver al home real antes que cualquier otra interpretación.
        if (esPedidoDeHome(t)) {
            ejecutarGoHome(null, "Volviendo al inicio");
            return true;
        }

        // 0b) "apps / aplicaciones" es el cajón del dock, no una app externa.
        if (esPedidoDeAppsDrawer(t)) {
            abrirCajonApps(null, "Abriendo aplicaciones");
            return true;
        }
        if (esAliasRadio(t)) {
            abrirRadioDock(null, "Abriendo Radio");
            return true;
        }

        // 0c) "abre <app>" conocida: launch directo desde el contexto del host.
        String openTarget = quitarVerboAbrir(rawLower);
        if (openTarget != null && openTarget.length() > 0 && dockKeyDe(t) == null
                && AppLauncherHelper.buscarPaquete(ctx, openTarget) != null) {
            boolean ok = AppLauncherHelper.abrirAppPorNombre(ctx, openTarget);
            host.speak(ok ? ("Abriendo " + openTarget) : ("No encontré la aplicación " + openTarget), false);
            return true;
        }

        boolean pideAnalisis = t.contains("analiza") || t.contains("analisis");
        boolean pideDiag = contienePalabra(t, "diagnostico") || contienePalabra(t, "taller")
                || contienePalabra(t, "obd");
        int tab = DiagnosticoView.pestanaDe(t);

        if (pideAnalisis) {
            String decir = tab >= 0
                    ? ("Abriendo Diagnóstico en " + nombrePestana(tab) + " y analizando con IA.")
                    : "Abriendo Diagnóstico y analizando con IA.";
            boolean handled = host.openDiagnostic(tab, true, pendingJson("ANALYZE_AI", t, decir));
            if (host.runsUiLocally() || !handled) host.speak(decir, false);
            return true;
        }
        if (pideDiag) {
            String decir = tab >= 0 ? ("Abriendo Diagnóstico en " + nombrePestana(tab) + ".") : "Abriendo Diagnóstico.";
            boolean handled = host.openDiagnostic(tab, false, pendingJson("OPEN_DIAGNOSTIC", t, decir));
            if (host.runsUiLocally() || !handled) host.speak(decir, false);
            return true;
        }
        if (tab >= 0 && (esImperativo(t) || t.equals(ContactNames.norm(nombrePestana(tab)))
                || t.startsWith("pestana ") || t.startsWith("la pestana "))) {
            String decir = "Abriendo Diagnóstico en " + nombrePestana(tab) + ".";
            boolean handled = host.openDiagnostic(tab, false, pendingJson("OPEN_DIAGNOSTIC", nombrePestana(tab), decir));
            if (host.runsUiLocally() || !handled) host.speak(decir, false);
            return true;
        }

        String[] musicVerbs = {"reproduce ", "reproducir ", "ponme ", "pon ", "toca ", "tocar ",
                "escuchar ", "quiero escuchar ", "pon la musica ", "pon musica ", "pon la cancion ",
                "pon la canción ", "buscar cancion ", "buscar canción ", "busca la cancion ",
                "busca la canción ", "busca cancion ", "busca canción "};
        for (String v : musicVerbs) {
            if (!rawLower.startsWith(v)) continue;
            String q = raw.trim().substring(v.length()).trim();
            q = q.replaceFirst("(?i)^(la|el|los|las|un|una|mi|mis|cancion|canción|tema)\\s+", "").trim();
            q = q.replaceFirst("(?i)^(la|el|los|las|un|una|mi|mis)\\s+", "").trim();
            q = q.replaceFirst("(?i)^(cancion|canción|tema)\\s+", "").trim();
            if (q.length() == 0) return false;
            String qNorm = ContactNames.norm(q);
            if (contienePalabra(qNorm, "radio") || contienePalabra(qNorm, "fm")) {
                boolean handled = host.openDock("radio", pendingJson("OPEN_DOCK", "radio", "Radio."));
                if (host.runsUiLocally() || !handled) host.speak("Radio.", false);
                return true;
            }
            final String qFinal = q;
            final boolean sinRed = !Net.isNetworkAvailable(ctx);
            Log.i(TAG, "PLAY_MUSIC local offline=" + sinRed + " decir=\""
                    + (sinRed ? mensajeMusicaOffline(qFinal) : "Ok.") + "\"");
            try {
                if (AppExecutors.get().network != null) {
                    AppExecutors.get().network.execute(new Runnable() {
                        public void run() {
                            try { MusicVoiceController.reproducirEnSerMusic(ctx, qFinal, sinRed); }
                            catch (Throwable ignored) {}
                        }
                    });
                } else {
                    MusicVoiceController.reproducirEnSerMusic(ctx, qFinal, sinRed);
                }
            } catch (Throwable ignored) {}
            host.refreshMusicSession();
            host.speak(sinRed ? mensajeMusicaOffline(qFinal) : "Ok.", false);
            return true;
        }

        String[] callVerbs = {"llama a ", "llamar a ", "marca a ", "marcar a ",
                "llama al ", "marca al ", "llamale a ", "comunica con "};
        for (String v : callVerbs) {
            if (!rawLower.startsWith(v)) continue;
            String target = raw.trim().substring(v.length()).trim();
            if (target.length() == 0) return false;
            final String finalTarget = target;
            final String decir = "Llamando a " + target;
            host.setConversationActive(false);
            host.showText(decir);
            main.postDelayed(new Runnable() {
                public void run() {
                    try { VoiceCallHelper.procesarLlamadaPorVoz(ctx, finalTarget, decir); }
                    catch (Throwable ignored) {}
                }
            }, 50);
            return true;
        }

        String key = dockKeyDe(t);
        if (key != null) {
            boolean exacto = t.equals(key) || t.equals("el " + key) || t.equals("la " + key)
                    || t.equals("los " + key) || t.equals("las " + key)
                    || t.endsWith(" " + key) && esImperativo(t);
            if (esImperativo(t) || exacto) {
                final String decir = "Abriendo " + etiquetaDock(key) + ".";
                boolean handled = host.openDock(key, pendingJson("OPEN_DOCK", key, decir));
                if (host.runsUiLocally() || !handled) host.speak(decir, false);
                return true;
            }
        }
        return false;
    }

    /** Volumen: se resuelve en el equipo, sin ida y vuelta a Gemini. */
    private boolean ejecutarVolumenLocal(String n) {
        boolean pedir = n.contains("volumen") || n.contains("sube el sonido") || n.contains("baja el sonido")
                || n.contains("mas alto") || n.contains("mas bajo") || n.contains("silencia")
                || n.contains("silenciar") || n.contains("mute") || n.contains("calla");
        if (!pedir) return false;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        int stream = AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(stream);
        int actual = am.getStreamVolume(stream);
        String decir;
        try {
            if (n.contains("silencia") || n.contains("silenciar") || n.contains("mute") || n.contains("calla")) {
                am.setStreamVolume(stream, 0, 0);
                decir = "Silenciado.";
            } else if (n.contains("sube") || n.contains("aumenta") || n.contains("mas alto")) {
                am.setStreamVolume(stream, Math.min(max, actual + Math.max(1, max / 8)), 0);
                decir = "Subiendo el volumen.";
            } else if (n.contains("baja") || n.contains("reduce") || n.contains("mas bajo")) {
                am.setStreamVolume(stream, Math.max(0, actual - Math.max(1, max / 8)), 0);
                decir = "Bajando el volumen.";
            } else {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(n);
                if (!m.find()) return false;
                int pct = Integer.parseInt(m.group(1));
                if (pct > 100) pct = 100;
                am.setStreamVolume(stream, Math.round(max * pct / 100f), 0);
                decir = "Volumen al " + pct + " por ciento.";
            }
        } catch (Throwable t) {
            decir = "No pude cambiar el volumen.";
        }
        host.showText(decir);
        host.speak(decir, false);
        return true;
    }

    /** Brillo: Settings.System directo; si no hay permiso se dice sin consultar la nube. */
    private boolean ejecutarBrilloLocal(String n) {
        if (!n.contains("brillo") && !n.contains("pantalla")) return false;
        int valor = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(n);
        if (m.find()) valor = Math.min(100, Integer.parseInt(m.group(1)));
        if (valor < 0) {
            valor = n.contains("sube") || n.contains("aumenta") || n.contains("mas")
                    ? 85 : (n.contains("baja") || n.contains("reduce") || n.contains("menos") ? 30 : -1);
        }
        if (valor < 0) return false;
        String decir;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(ctx)) {
                decir = "No tengo permiso para cambiar el brillo.";
            } else {
                Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                        Math.max(1, Math.round(valor * 255f / 100f)));
                decir = "Brillo al " + valor + " por ciento.";
            }
        } catch (Throwable t) {
            decir = "No pude cambiar el brillo.";
        }
        host.showText(decir);
        host.speak(decir, false);
        return true;
    }

    /** Navegación: lanza la app de maps/Waze directamente desde el contexto del host. */
    private boolean ejecutarNavegacionLocal(String n) {
        boolean navegar = n.startsWith("navega") || n.contains("navegacion") || n.contains("guiame")
                || n.contains("lle name") || n.contains("llevame") || n.contains("waze")
                || n.contains("google maps") || n.contains("maps");
        if (!navegar) return false;
        String app = n.contains("waze") ? "Waze" : (n.contains("maps") ? "Google Maps" : "Waze");
        boolean ok = false;
        try { ok = AppLauncherHelper.abrirAppPorNombre(ctx, app); } catch (Throwable ignored) {}
        String decir = ok ? "Abriendo navegación." : "No encontré la app de navegación.";
        host.showText(decir);
        host.speak(decir, false);
        return true;
    }

    /** "Pon música"/"mis canciones" sin artista: abre SerMusic sin pasar por Gemini. */
    private boolean ejecutarMusicaGenericaLocal(String n) {
        String[] frases = {"musica", "mi musica", "mis musicas", "canciones", "mis canciones",
                "reproduce musica", "pon musica", "pone musica", "escuchar musica", "sermusic"};
        for (String f : frases) if (f.equals(n)) return ejecutarMusicaEnSerMusic("musica", "Reproduciendo tu música.");
        return false;
    }

    /** Mismo snapshot que Main.fabSnapshot(): el prompt de Gemini ve el estado real del vehículo. */
    public String snapshot() {
        try {
            StringBuilder sb = new StringBuilder("{");
            ObdRepository r = obd();
            boolean conectado = r != null && r.isConnected();
            boolean reciente = r != null && r.hasRecentValues(20000);
            sb.append("\"obd_conectado\":").append(conectado || reciente);
            sb.append(",\"obd_estado\":\"").append(r != null ? r.getLinkState().name() : "DISCONNECTED").append("\"");
            sb.append(",\"obd_reciente\":").append(reciente);
            sb.append(",\"obd_ultima_trama_ms\":").append(r != null ? (System.currentTimeMillis() - r.getLastFrameMs()) : -1);
            Map<String, Float> v = r != null ? r.getValues() : null;
            if (v != null) {
                String[] keys = {Pids.RPM, Pids.COOLANT, Pids.SPEED, Pids.FUEL_RATE, Pids.VOLTAGE, Pids.FUEL};
                for (String k : keys) {
                    Float x = v.get(k);
                    if (x == null) continue;
                    sb.append(",\"").append(k).append("\":").append(x.floatValue());
                }
            }
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) { return "{\"obd_conectado\":false}"; }
    }

    private ObdRepository obd() {
        try { return ObdRepository.getInstance(ctx); } catch (Throwable t) { return null; }
    }

    /** JSON que el servicio envía a Main cuando la acción necesita UI del launcher. */
    public static String pendingJson(String action, String query, String speech) {
        try {
            JSONObject o = new JSONObject();
            o.put("action", action);
            o.put("query", query == null ? "" : query);
            o.put("speech", speech == null ? "" : speech);
            return o.toString();
        } catch (Throwable t) { return "{\"action\":\"" + action + "\"}"; }
    }

    /** Intento de sistema hacia el launcher principal, con la acción pendiente para cerrar pestañas. */
    public static void launchHome(Context ctx, String pendingJson) {
        if (ctx == null) return;
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setComponent(new ComponentName(ctx, Main.class));
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (pendingJson != null && pendingJson.length() > 0) home.putExtra("PENDING_ACTION_JSON", pendingJson);
        try {
            ctx.startActivity(home);
            Log.i(TAG, "GO_HOME -> " + Main.class.getName());
        } catch (Throwable t) { Log.w(TAG, "GO_HOME no se pudo lanzar", t); }
    }

    private void ejecutarGoHome(String json, String decir) {
        String pending = (json != null && json.length() > 0) ? json : pendingJson("GO_HOME", "", decir);
        host.goHome(pending);
        host.speak(decir, false);
    }

    private void abrirCajonApps(String json, String decir) {
        String pending = (json != null && json.length() > 0) ? json : pendingJson("OPEN_DOCK", "apps", decir);
        boolean ok = host.openDock("apps", pending);
        if (host.runsUiLocally() || !ok) host.speak(decir, false);
    }

    private void abrirRadioDock(String json, String decir) {
        String pending = (json != null && json.length() > 0) ? json : pendingJson("OPEN_DOCK", "radio", decir);
        boolean ok = host.openDock("radio", pending);
        if (host.runsUiLocally() || !ok) host.speak(decir, false);
    }

    private static boolean esPedidoDeHome(String t) {
        if (t == null) return false;
        String n = ContactNames.norm(t).trim();
        if (n.length() == 0) return false;
        String[] phrases = {"home", "inicio", "principal", "launcher", "escritorio", "casa",
                "pantalla principal", "pantalla de inicio", "menu principal", "menu de inicio",
                "volver al inicio", "volver inicio", "volver al home", "volver home", "volver al launcher",
                "vuelve al inicio", "vuelve al home", "regresar al inicio", "regresar al home",
                "regresa al inicio", "regresa al home", "regresar inicio", "regresar home",
                "ir al inicio", "ir al home", "ir inicio", "ir home", "ir al launcher",
                "volver a la pantalla principal", "regresar a la pantalla principal",
                "ver el inicio", "mostrar el inicio", "llévame al inicio", "llevame al inicio"};
        for (String p : phrases) if (n.equals(p)) return true;
        return false;
    }

    private static boolean esPedidoDeAppsDrawer(String t) {
        if (t == null) return false;
        String n = ContactNames.norm(t).trim();
        if (n.length() == 0) return false;
        String[] phrases = {"apps", "aplicaciones", "aplicacion", "drawer",
                "menu de aplicaciones", "menu apps", "cajon de aplicaciones", "lista de aplicaciones",
                "todas las apps", "todas las aplicaciones", "todos los apps", "todos los aplicaciones",
                "abrir apps", "abrir aplicaciones", "abre apps", "abre aplicaciones",
                "ver apps", "ver aplicaciones", "mostrar apps", "mostrar aplicaciones"};
        for (String p : phrases) if (n.equals(p)) return true;
        return false;
    }

    private static boolean esAliasRadio(String t) {
        if (t == null) return false;
        String n = ContactNames.norm(t).trim();
        return n.equals("radio") || n.equals("la radio") || n.equals("radio fm")
                || n.equals("abrir radio") || n.equals("abre radio") || n.equals("fm")
                || n.equals("emisora");
    }

    /** Extrae el nombre de app de "abre X", "inicia X", etc. (texto ya en minúsculas). */
    private static String quitarVerboAbrir(String rawLower) {
        if (rawLower == null) return null;
        String s = rawLower.trim();
        String[] verbs = {"abre ", "abrir ", "abreme ", "ábreme ", "inicia ", "iniciar ",
                "lanza ", "lanzar ", "ejecuta ", "ejecutar ", "arranca ", "arrancar "};
        for (String v : verbs) if (s.startsWith(v)) return s.substring(v.length()).trim();
        return null;
    }

    private static boolean esImperativo(String t) {
        String[] v = {"abre", "abrir", "abreme", "ve a", "ir a", "vamos a", "lleva", "llevame",
                "entra", "entrar", "muestra", "muestrame", "cambia", "cambiar", "pon", "ponme",
                "quiero ver", "quiero ir", "inicia", "iniciar", "lanza", "lanzar", "ejecuta"};
        for (String x : v) if (t.startsWith(x + " ") || t.equals(x)) return true;
        return false;
    }

    private static boolean contienePalabra(String t, String w) {
        if (t == null) return false;
        String[] tok = t.split(" ");
        for (String x : tok) if (x.equals(w)) return true;
        return false;
    }

    private static String dockKeyDe(String t) {
        if (t == null || t.length() == 0) return null;
        if (contienePalabra(t, "radio") || contienePalabra(t, "fm") || t.contains("emisora")) return "radio";
        if (contienePalabra(t, "bluetooth") || contienePalabra(t, "bt")) return "bluetooth";
        if (contienePalabra(t, "musica") || contienePalabra(t, "music") || contienePalabra(t, "sermusic")) return "musica";
        if (contienePalabra(t, "telefono") || contienePalabra(t, "llamadas") || contienePalabra(t, "marcador")
                || contienePalabra(t, "contactos")) return "telefono";
        if (contienePalabra(t, "apps") || contienePalabra(t, "aplicaciones") || contienePalabra(t, "aplicacion")
                || contienePalabra(t, "programas")) return "apps";
        if (contienePalabra(t, "navegador") || contienePalabra(t, "internet") || contienePalabra(t, "web")) return "navegador";
        if (contienePalabra(t, "archivo") || contienePalabra(t, "archivos") || contienePalabra(t, "explorador")
                || contienePalabra(t, "documentos") || t.contains("gestor de archivos")) return "archivos";
        if (contienePalabra(t, "diagnostico") || contienePalabra(t, "taller") || contienePalabra(t, "obd")) return "diagnostico";
        if (contienePalabra(t, "ajustes") || contienePalabra(t, "configuracion") || contienePalabra(t, "settings")
                || contienePalabra(t, "preferencias")) return "ajustes";
        return null;
    }

    private static String etiquetaDock(String key) {
        if ("radio".equals(key)) return "Radio";
        if ("bluetooth".equals(key)) return "Bluetooth";
        if ("musica".equals(key)) return "Música";
        if ("telefono".equals(key)) return "Teléfono";
        if ("apps".equals(key)) return "Apps";
        if ("navegador".equals(key)) return "Navegador";
        if ("archivos".equals(key)) return "Archivos";
        if ("diagnostico".equals(key)) return "Diagnóstico";
        if ("ajustes".equals(key)) return "Ajustes";
        return key;
    }

    private static String nombrePestana(int tab) {
        switch (tab) {
            case 0: return "Salud";
            case 1: return "Códigos de falla";
            case 2: return "Sensores";
            case 3: return "Gráficas";
            case 4: return "Más";
        }
        return "Salud";
    }
}
