package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fordlauncher.launcher.DiagnosticoView;
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
        try { if (ejecutarComandoLocal(pregunta)) return; } catch (Throwable ignored) {}

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

        if (requiereVehiculo && !conectado) {
            final String aviso = "Sergio, el adaptador OBD2 no está conectado. Por favor conéctalo para poder leer los sensores del Fiesta.";
            host.showText(aviso);
            host.speak(aviso, true);
            return;
        }

        final String task = "El conductor pregunta por voz: \"" + pregunta.trim() + "\"";
        final String snap = snapshot();
        host.notifyUser("Consultando…");
        host.setWaiting();
        GeminiDiagnosticClient.analyze(ctx, snap, task, false, new GeminiDiagnosticClient.Callback() {
            public void onDone(final String text, final String error) {
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
            boolean ok = false;
            try { ok = AppLauncherHelper.abrirAppPorNombre(ctx, appName); } catch (Throwable ignored) {}
            String decir;
            if (!ok) decir = "No encontré la aplicación " + (appName.length() > 0 ? appName : "solicitada");
            else decir = speech.length() > 0 ? speech : ("Abriendo " + appName);
            host.speak(decir, false);
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
        return ejecutarMusicaEnSerMusic(q, t.length() > 0 ? t : ("Poniendo " + q + " en SerMusic"));
    }

    public boolean ejecutarMusicaEnSerMusic(String query, String speech) {
        if (query == null || query.trim().length() == 0) return false;
        final String qFinal = query.trim();
        // Cerrar el micrófono antes de reproducir evita que onReadyForSpeech robe el foco de audio.
        host.setConversationActive(false);
        host.stopVoice();
        try { MusicVoiceController.reproducirEnSerMusic(ctx, qFinal); }
        catch (Throwable err) { Log.w("FordLauncher/Music", "No se pudo lanzar SerMusic", err); }
        host.refreshMusicSession();
        host.speak(speech != null && speech.trim().length() > 0 ? speech.trim() : "Ok.", false);
        return true;
    }

    /** Comandos que el launcher resuelve sin nube: dock, diagnóstico, música, llamadas. */
    public boolean ejecutarComandoLocal(String raw) {
        if (raw == null) return false;
        String t = ContactNames.norm(raw);
        if (t.length() == 0) return false;
        String rawLower = raw.trim().toLowerCase(Locale.ROOT);

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
            if (q.length() == 0) return false;
            String qNorm = ContactNames.norm(q);
            if (contienePalabra(qNorm, "radio") || contienePalabra(qNorm, "fm")) {
                boolean handled = host.openDock("radio", pendingJson("OPEN_DOCK", "radio", "Radio."));
                if (host.runsUiLocally() || !handled) host.speak("Radio.", false);
                return true;
            }
            final String qFinal = q;
            try {
                if (AppExecutors.get().network != null) {
                    AppExecutors.get().network.execute(new Runnable() {
                        public void run() {
                            try { MusicVoiceController.reproducirEnSegundoPlano(ctx, qFinal); }
                            catch (Throwable ignored) {}
                        }
                    });
                } else {
                    MusicVoiceController.reproducirEnSegundoPlano(ctx, qFinal);
                }
            } catch (Throwable ignored) {}
            host.refreshMusicSession();
            host.speak("Ok.", false);
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
