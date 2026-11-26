package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import com.fordlauncher.launcher.Net;
import com.fordlauncher.launcher.obd.EdgeVoiceSpeaker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Lectura en voz alta de los diagnósticos de IA.
 * - Voz "Nube · ..." elegida en Ajustes (tts_voice = "edge:<nombre>"): se sintetiza en la nube con
 *   EdgeVoiceSpeaker; si falla, se usa el motor local.
 * - Cualquier otra selección: motor TTS del sistema, sin red.
 */
public class TextToSpeechManager implements TextToSpeech.OnInitListener {

    public interface Listener {
        void onSpeakingChanged(boolean speaking);
        void onError(String msg);
    }

    private static final String TAG = "FordLauncher/TTS";
    private static final String PREFS = "launcher";
    private static final String KEY_VOICE = "tts_voice";
    /** Voz local efectiva (misma timbre online y offline cuando no hay voz de nube elegida). */
    private static final String KEY_VOICE_LOCAL = "tts_voice_local";
    /** Migración única de las antiguas voces de nube masculinas a la femenina local. */
    private static final String KEY_VOICE_MIGRATED = "tts_voice_female_migrated";
    private static final String EDGE_PREFIX = "edge:";
    private static final String LAST_CHUNK = "last_chunk_";
    private static final long INIT_TIMEOUT_MS = 12000L;
    private static final float PITCH_LOCAL = 1.02f;
    private static final float RATE_LOCAL = 0.98f;

    /** Voces naturales femeninas instaladas por Google TTS, en orden de preferencia. */
    private static final String[] VOCES_FEMENINAS_LOCALES = {
            "es-us-x-sfb#female_1-local", "es-us-x-sfb#female_2-local", "es-us-x-sfb-local",
            "es-es-x-ana#female_1-local", "es-es-x-ana#female_2-local", "es-es-x-ana-local",
            "es-mx-x-sfb#female_1-local", "es-mx-x-sfb-local",
            "es-co-x-sfb#female_1-local", "es-co-x-sfb-local"
    };
    private static TextToSpeechManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Queue<String> pendingQueue = new LinkedList<String>();
    private TextToSpeech tts;
    private AudioFocusRequest focusRequest;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();
    private Runnable initTimeout;
    private EdgeVoiceSpeaker edge;
    private String edgeVoice;
    private volatile String ultimoId;
    private volatile String ultimoTextoNube;
    private volatile boolean isInitialized;
    private volatile boolean initializing;
    private volatile boolean speaking;
    private volatile Runnable pendingDone;
    /** La siguiente locución pendiente debe ser 100 % local (alerta crítica). */
    private volatile boolean pendingOffline;
    /** Un solo reintento de la voz de nube antes de caer al motor local. */
    private boolean reintentoNubeHecho;

    private TextToSpeechManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, 0);
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        migrarVozMasculinaNube();
        inicializarMotor();
    }

    public static synchronized TextToSpeechManager getInstance(Context context) {
        if (instance == null) instance = new TextToSpeechManager(context);
        return instance;
    }

    public void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isSpeaking() { return speaking; }

    /** Relee la voz de Ajustes; llamar tras elegir otra voz. */
    public synchronized void reiniciarVoz() {
        detener();
        if (edge != null) {
            try { edge.shutdown(); } catch (Throwable ignored) {}
        }
        edge = null;
        edgeVoice = null;
        initializing = false;
        liberarMotor();
        inicializarMotor();
    }

    /** Voz configurada en Ajustes ("edge:...", nombre local o "auto"). */
    public String vozConfigurada() {
        try { return prefs.getString(KEY_VOICE, "auto"); } catch (Throwable t) { return "auto"; }
    }

    /**
     * Pre-inicializa el motor local y la voz neural antes de hablar.
     *
     * <p>En el arranque en frío del AC8227L la CPU está saturada y el Wi-Fi aún
     * no está listo: si el saludo se emite sin pre-calentar, EdgeVoiceSpeaker
     * falla y cae al motor local robótico (voz distinta y entrecortada). Aquí se
     * crea el motor TTS, se resuelve la pila TLS y se deja listo el
     * {@link EdgeVoiceSpeaker} con la MISMA voz configurada.
     */
    public void precalentar() {
        Log.i(TAG, "precalentar: voz configurada=" + vozConfigurada());
        try {
            if (!isInitialized && !initializing) inicializarMotor();
        } catch (Throwable ignored) {}
        final String nube = vozNubePreferida();
        // Sin red no se prepara la nube: se usa directamente la voz local femenina.
        if (nube == null || !Net.isNetworkAvailable(context)) return;
        new Thread(new Runnable() {
            public void run() {
                // Resuelve certificados/roots y la fábrica TLS (IPv4) fuera del
                // hilo principal para que la primera síntesis no arranque en frío.
                try { com.fordlauncher.launcher.Net.init(context); } catch (Throwable ignored) {}
                main.post(new Runnable() {
                    public void run() { prepararEdge(nube); }
                });
            }
        }, "tts-prewarm").start();
    }

    /** Crea/reutiliza el sintetizador de nube con la voz indicada. */
    private void prepararEdge(String nombreVoz) {
        try {
            if (edge != null && nombreVoz.equals(edgeVoice)) return;
            if (edge != null) {
                try { edge.shutdown(); } catch (Throwable ignored) {}
            }
            edgeVoice = nombreVoz;
            edge = new EdgeVoiceSpeaker(context, nombreVoz);
            edge.setListener(listenerNube());
            Log.i(TAG, "EdgeVoiceSpeaker listo (voz=" + nombreVoz + ")");
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo preparar EdgeVoiceSpeaker: " + t);
            edge = null;
            edgeVoice = null;
        }
    }

    /** Listener común de la voz de nube (incluye el reintento antes del fallback). */
    private EdgeVoiceSpeaker.Listener listenerNube() {
        return new EdgeVoiceSpeaker.Listener() {
            public void onSpeaking(boolean s) { setSpeaking(s); }
            public void onError(final String msg) {
                main.post(new Runnable() {
                    public void run() {
                        final String t = ultimoTextoNube;
                        Log.w(TAG, "Voz de nube falló (" + msg + ") reintento=" + reintentoNubeHecho);
                        if (t == null) return;
                        if (!reintentoNubeHecho) {
                            reintentoNubeHecho = true;
                            // En el arranque el Wi-Fi puede tardar: se espera y se
                            // reintenta con la MISMA voz antes de caer a la local.
                            main.postDelayed(new Runnable() {
                                public void run() {
                                    if (edge == null) {
                                        Log.w(TAG, "EdgeVoiceSpeaker no disponible, usando motor local");
                                        hablarLocal(t);
                                        return;
                                    }
                                    Log.i(TAG, "Reintentando voz de nube con la misma voz configurada");
                                    edge.speak(t);
                                }
                            }, 1800L);
                            return;
                        }
                        Log.w(TAG, "Voz de nube no disponible tras reintento, usando motor local");
                        hablarLocal(t);
                    }
                });
            }
            public void onDone() { terminar(null); }
        };
    }

    private synchronized void inicializarMotor() {
        if (initializing) return;
        liberarMotor();
        initializing = true;
        try {
            tts = new TextToSpeech(context, this);
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo crear TextToSpeech", t);
            fallo("No se pudo iniciar el motor de voz.");
            return;
        }
        initTimeout = new Runnable() {
            public void run() {
                if (!isInitialized) {
                    Log.e(TAG, "Tiempo de inicio agotado");
                    fallo("El motor de voz tardó demasiado en iniciar.");
                }
            }
        };
        main.postDelayed(initTimeout, INIT_TIMEOUT_MS);
    }

    @Override
    public void onInit(int status) {
        cancelarTimeout();
        initializing = false;
        if (status == TextToSpeech.SUCCESS && tts != null) {
            configurarVozSegunPreferencias();
            configurarListener();
            isInitialized = true;
            Log.i(TAG, "Motor listo: " + tts.getDefaultEngine());
            boolean offline = pendingOffline;
            pendingOffline = false;
            while (!pendingQueue.isEmpty()) {
                hablarTextoLargoInterno(pendingQueue.poll(), offline);
            }
        } else {
            Log.e(TAG, "Fallo al inicializar TTS. Código: " + status);
            fallo("No se pudo iniciar la voz (código " + status + ").");
        }
    }

    /** Migra una vez las voces de nube masculinas a la femenina local persistida. */
    private void migrarVozMasculinaNube() {
        try {
            if (prefs.getBoolean(KEY_VOICE_MIGRATED, false)) return;
            String v = prefs.getString(KEY_VOICE, null);
            if (v != null && v.startsWith(EDGE_PREFIX)) {
                String n = v.substring(EDGE_PREFIX.length());
                if (n.contains("Gonzalo") || n.contains("Jorge") || n.contains("Tomas") || n.contains("Alvaro")) {
                    String local = vozLocalEfectiva();
                    if (local != null && local.length() > 0) prefs.edit().putString(KEY_VOICE, local).apply();
                    else prefs.edit().remove(KEY_VOICE).apply();
                    Log.i(TAG, "Voz de nube masculina migrada a femenina local: "
                            + (local == null ? "automática" : local));
                }
            }
            prefs.edit().putBoolean(KEY_VOICE_MIGRATED, true).apply();
        } catch (Throwable ignored) {}
    }

    /** Voz local elegida en Ajustes (null si es nube o automática). */
    private String vozLocalPreferida() {
        String v = prefs.getString(KEY_VOICE, null);
        if (v == null || v.trim().length() == 0 || v.startsWith(EDGE_PREFIX)) return null;
        return v;
    }

    /** Voz local efectiva ya persistida; garantiza el mismo timbre online y offline. */
    public String vozLocalEfectiva() {
        try { return prefs.getString(KEY_VOICE_LOCAL, null); } catch (Throwable t) { return null; }
    }

    private void guardarVozLocal(String nombre) {
        if (nombre == null || nombre.trim().length() == 0) return;
        try { prefs.edit().putString(KEY_VOICE_LOCAL, nombre).apply(); } catch (Throwable ignored) {}
    }

    /** Nombre corto de la voz de nube (p. ej. es-MX-JorgeNeural) o null. */
    private String vozNubePreferida() {
        String v = prefs.getString(KEY_VOICE, null);
        if (v != null && v.startsWith(EDGE_PREFIX) && v.length() > EDGE_PREFIX.length()) {
            return v.substring(EDGE_PREFIX.length());
        }
        return null;
    }

    private void configurarVozSegunPreferencias() {
        String preferida = vozLocalPreferida();
        String persistida = vozLocalEfectiva();
        Voice elegida = null;
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Set<Voice> voces = tts.getVoices();
                if (voces != null && !voces.isEmpty()) {
                    Map<String, Voice> indice = new HashMap<String, Voice>();
                    for (Voice v : voces) {
                        try {
                            Log.i(TAG, "Voz disponible: " + v.getName()
                                    + " locale=" + v.getLocale()
                                    + " requiereRed=" + v.isNetworkConnectionRequired());
                        } catch (Throwable ignored) {}
                        if (esVozLocal(v)) indice.put(v.getName().toLowerCase(Locale.ROOT), v);
                    }
                    // 1) la voz que el usuario eligió, 2) la ya persistida, 3)
                    //    female_1/female_2 natural de Google, 4) cualquier femenina.
                    if (preferida != null) elegida = indice.get(preferida.toLowerCase(Locale.ROOT));
                    if (elegida == null && persistida != null) elegida = indice.get(persistida.toLowerCase(Locale.ROOT));
                    if (elegida == null) {
                        for (String nombre : VOCES_FEMENINAS_LOCALES) {
                            elegida = indice.get(nombre.toLowerCase(Locale.ROOT));
                            if (elegida != null) break;
                        }
                    }
                    if (elegida == null) {
                        for (Voice v : indice.values()) {
                            Locale loc = v.getLocale();
                            if (loc == null || !"es".equals(loc.getLanguage())) continue;
                            if (v.getName().toLowerCase(Locale.ROOT).contains("female")) {
                                elegida = v;
                                break;
                            }
                        }
                    }
                    if (elegida == null) {
                        for (Voice v : indice.values()) {
                            Locale loc = v.getLocale();
                            if (loc != null && "es".equals(loc.getLanguage())) {
                                elegida = v;
                                break;
                            }
                        }
                    }
                    if (elegida != null) {
                        tts.setVoice(elegida);
                        guardarVozLocal(elegida.getName());
                        Log.i(TAG, "Voz femenina local persistida (online/offline): " + elegida.getName());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudo consultar el catálogo de voces: " + e);
            }
        }
        if (elegida == null) {
            int r = tts.setLanguage(new Locale("es", "MX"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }
            Log.w(TAG, "No hay voz femenina local; se usa el idioma por defecto");
        }
        tts.setPitch(PITCH_LOCAL);
        tts.setSpeechRate(RATE_LOCAL);
    }

    /** Una voz es utilizable sin internet solo si no exige conexión de red. */
    private static boolean esVozLocal(Voice v) {
        if (v == null) return false;
        try { return !v.isNetworkConnectionRequired(); } catch (Throwable t) { return false; }
    }

    private void configurarListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { }

            public void onDone(String id) {
                if (id != null && id.equals(ultimoId)) terminar(null);
            }

            public void onError(String id) {
                terminar("Error al reproducir la voz.");
            }

            @Override
            public void onError(String id, int errorCode) {
                terminar("Error al reproducir la voz (" + errorCode + ").");
            }
        });
    }

    public synchronized void hablarTextoLargo(String textoCompleto) {
        pendingDone = null;
        hablarTextoLargoInterno(textoCompleto, false);
    }

    public synchronized void hablarTextoLargo(String textoCompleto, Runnable onDone) {
        pendingDone = onDone;
        hablarTextoLargoInterno(textoCompleto, false);
    }

    /**
     * Alerta crítica del vehículo: SIEMPRE se sintetiza con el motor local, sin
     * nube ni Wi-Fi, y con prioridad sobre cualquier locución en curso. Es la
     * ruta que usan temperatura, batería, DTC y puertas/CANBUS.
     */
    public synchronized void hablarAlertaCritica(String textoCompleto) {
        pendingDone = null;
        // Corta lo que esté diciendo (Gemini, música, avisos anteriores) para
        // que la advertencia se escuche de inmediato.
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        if (edge != null) { try { edge.stop(); } catch (Throwable ignored) {} }
        hablarTextoLargoInterno(textoCompleto, true);
    }

    private void hablarTextoLargoInterno(String textoCompleto, boolean offline) {
        if (textoCompleto == null || textoCompleto.trim().isEmpty()) return;
        String limpio = limpiar(textoCompleto);
        if (limpio.isEmpty()) return;
        // offline=true: nunca se intenta Edge/nube, aunque la voz configurada
        // sea "Nube · ...". La alerta debe sonar sin internet.
        if (!offline) {
            boolean sinRed = !Net.isNetworkAvailable(context);
            String nube = vozNubePreferida();
            if (nube != null && !sinRed && hablarNube(nube, limpio)) return;
            if (sinRed) offline = true;
        }
        hablarLocal(limpio, offline);
    }

    private boolean hablarNube(String nombreVoz, String texto) {
        try {
            prepararEdge(nombreVoz);
            if (edge == null) return false;
            ultimoTextoNube = texto;
            reintentoNubeHecho = false;
            edge.speak(texto);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar la voz de nube: " + t);
            return false;
        }
    }

    private void hablarLocal(String limpio) { hablarLocal(limpio, false); }

    private void hablarLocal(String limpio, boolean offline) {
        if (!isInitialized) {
            pendingQueue.clear();
            pendingQueue.add(limpio);
            pendingOffline = offline;
            setSpeaking(true);
            inicializarMotor();
            return;
        }
        List<String> fragmentos = new ArrayList<String>();
        for (String parte : limpio.split("(?<=[.!?])\\s+")) {
            String t = parte.trim();
            if (!t.isEmpty()) fragmentos.add(t);
        }
        if (fragmentos.isEmpty()) return;

        solicitarAudioDucking();
        setSpeaking(true);
        long stamp = System.currentTimeMillis();
        for (int i = 0; i < fragmentos.size(); i++) {
            boolean ultimo = (i == fragmentos.size() - 1);
            String uId = ultimo ? (LAST_CHUNK + stamp) : ("chunk_" + stamp + "_" + i);
            if (ultimo) ultimoId = uId;

            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uId);
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            // Android 8.1: impide que el motor intente sintetizar en la nube
            // cuando no hay Wi-Fi (fallaría en silencio).
            params.putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false");

            int modo = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int res = tts.speak(fragmentos.get(i), modo, params, uId);
            if (res == TextToSpeech.ERROR) {
                Log.e(TAG, "tts.speak devolvió ERROR en fragmento " + i);
                terminar("No se pudo reproducir la voz.");
                return;
            }
        }
    }

    public void abandonAudioFocus() {
        try { liberarAudioDucking(); } catch (Throwable ignored) {}
    }

    public synchronized void detener() {
        pendingQueue.clear();
        if (edge != null) {
            try { edge.stop(); } catch (Throwable ignored) {}
        }
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        terminar(null);
    }

    private static String limpiar(String texto) {
        return texto.replaceAll("[*#_~`>\\[\\]()]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
    }

    private void fallo(String msg) {
        cancelarTimeout();
        liberarMotor();
        initializing = false;
        pendingQueue.clear();
        terminar(msg);
    }

    private void terminar(String error) {
        liberarAudioDucking();
        Runnable done = pendingDone;
        pendingDone = null;
        setSpeaking(false);
        if (error != null) notificarError(error);
        if (error == null && done != null) main.post(done);
    }

    private void liberarMotor() {
        isInitialized = false;
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) {}
        tts = null;
    }

    private void cancelarTimeout() {
        if (initTimeout != null) main.removeCallbacks(initTimeout);
        initTimeout = null;
    }

    private void setSpeaking(final boolean s) {
        if (speaking == s) return;
        speaking = s;
        main.post(new Runnable() {
            public void run() { for (Listener l : listeners) l.onSpeakingChanged(s); }
        });
    }

    private void notificarError(final String msg) {
        main.post(new Runnable() {
            public void run() { for (Listener l : listeners) l.onError(msg); }
        });
    }

    private void solicitarAudioDucking() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes atributos = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(atributos)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void liberarAudioDucking() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }
}
