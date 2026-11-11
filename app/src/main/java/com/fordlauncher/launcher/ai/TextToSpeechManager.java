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
import com.fordlauncher.launcher.obd.EdgeVoiceSpeaker;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
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
    private static final String EDGE_PREFIX = "edge:";
    private static final String LAST_CHUNK = "last_chunk_";
    private static final long INIT_TIMEOUT_MS = 8000L;
    private static final float PITCH_LOCAL = 0.85f;
    private static final float RATE_LOCAL = 0.98f;
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

    private TextToSpeechManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, 0);
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
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
            while (!pendingQueue.isEmpty()) {
                hablarTextoLargo(pendingQueue.poll());
            }
        } else {
            Log.e(TAG, "Fallo al inicializar TTS. Código: " + status);
            fallo("No se pudo iniciar la voz (código " + status + ").");
        }
    }

    /** Voz local elegida en Ajustes (null si es nube o automática). */
    private String vozLocalPreferida() {
        String v = prefs.getString(KEY_VOICE, null);
        if (v == null || v.trim().length() == 0 || v.startsWith(EDGE_PREFIX)) return null;
        return v;
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
        boolean asignada = false;
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Set<Voice> voces = tts.getVoices();
                if (voces != null && !voces.isEmpty()) {
                    if (preferida != null) {
                        for (Voice v : voces) {
                            if (v.getName().equalsIgnoreCase(preferida)) {
                                tts.setVoice(v);
                                asignada = true;
                                Log.i(TAG, "Voz local preferida: " + v.getName());
                                break;
                            }
                        }
                    }
                    if (!asignada) {
                        for (Voice v : voces) {
                            Locale loc = v.getLocale();
                            if (loc == null || !"es".equals(loc.getLanguage())) continue;
                            String n = v.getName().toLowerCase(Locale.ROOT);
                            if (n.contains("male") || n.contains("jorge") || n.contains("carlos")
                                    || n.contains("-b-") || n.contains("-d-")) {
                                tts.setVoice(v);
                                asignada = true;
                                Log.i(TAG, "Voz masculina en español: " + v.getName());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudo consultar el catálogo de voces: " + e);
            }
        }
        if (!asignada) {
            int r = tts.setLanguage(new Locale("es", "MX"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }
        }
        tts.setPitch(PITCH_LOCAL);
        tts.setSpeechRate(RATE_LOCAL);
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
        hablarTextoLargoInterno(textoCompleto);
    }

    public synchronized void hablarTextoLargo(String textoCompleto, Runnable onDone) {
        pendingDone = onDone;
        hablarTextoLargoInterno(textoCompleto);
    }

    private void hablarTextoLargoInterno(String textoCompleto) {
        if (textoCompleto == null || textoCompleto.trim().isEmpty()) return;
        String limpio = limpiar(textoCompleto);
        if (limpio.isEmpty()) return;
        String nube = vozNubePreferida();
        if (nube != null && hablarNube(nube, limpio)) return;
        hablarLocal(limpio);
    }

    private boolean hablarNube(String nombreVoz, String texto) {
        try {
            if (edge == null || !nombreVoz.equals(edgeVoice)) {
                if (edge != null) edge.shutdown();
                edgeVoice = nombreVoz;
                edge = new EdgeVoiceSpeaker(context, nombreVoz);
                edge.setListener(new EdgeVoiceSpeaker.Listener() {
                    public void onSpeaking(boolean s) { setSpeaking(s); }
                    public void onError(final String msg) {
                        main.post(new Runnable() {
                            public void run() {
                                Log.w(TAG, "Voz de nube falló, usando motor local: " + msg);
                                String t = ultimoTextoNube;
                                if (t != null) hablarLocal(t);
                            }
                        });
                    }
                    public void onDone() { terminar(null); }
                });
            }
            ultimoTextoNube = texto;
            edge.speak(texto);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar la voz de nube: " + t);
            return false;
        }
    }

    private void hablarLocal(String limpio) {
        if (!isInitialized) {
            pendingQueue.clear();
            pendingQueue.add(limpio);
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
