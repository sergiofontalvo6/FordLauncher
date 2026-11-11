package com.fordlauncher.launcher.ai;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

/** Lectura en voz alta local (sin red) para los diagnósticos de IA. */
public class TextToSpeechManager implements TextToSpeech.OnInitListener {

    public interface Listener {
        void onSpeakingChanged(boolean speaking);
        void onError(String msg);
    }

    private static final String TAG = "FordLauncher/TTS";
    private static final String LAST_CHUNK = "last_chunk_";
    private static final long INIT_TIMEOUT_MS = 8000L;
    private static TextToSpeechManager instance;

    private final Context context;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Queue<String> pendingQueue = new LinkedList<String>();
    private TextToSpeech tts;
    private AudioFocusRequest focusRequest;
    private Listener listener;
    private Runnable initTimeout;
    private volatile String ultimoId;
    private volatile boolean isInitialized;
    private volatile boolean initializing;
    private volatile boolean speaking;

    private TextToSpeechManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        inicializarMotor();
    }

    public static synchronized TextToSpeechManager getInstance(Context context) {
        if (instance == null) instance = new TextToSpeechManager(context);
        return instance;
    }

    public void setListener(Listener l) { listener = l; }

    public boolean isSpeaking() { return speaking; }

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
            configurarIdiomaYVozOffline();
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

    private void configurarIdiomaYVozOffline() {
        int res = tts.setLanguage(new Locale("es", "ES"));
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            res = tts.setLanguage(new Locale("es"));
        }
        Log.i(TAG, "Idioma es -> " + res);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Set<Voice> voces = tts.getVoices();
                Voice elegida = null;
                if (voces != null) {
                    for (Voice v : voces) {
                        if (!"es".equals(v.getLocale().getLanguage())) continue;
                        if (v.isNetworkConnectionRequired()) continue;
                        if (elegida == null || v.getQuality() > elegida.getQuality()) elegida = v;
                    }
                }
                if (elegida != null) {
                    tts.setVoice(elegida);
                    Log.i(TAG, "Voz offline seleccionada: " + elegida.getName());
                } else {
                    Log.w(TAG, "No hay voz española offline instalada en el motor");
                }
            } catch (Exception e) {
                Log.w(TAG, "No se pudieron listar las voces: " + e);
            }
        }
        tts.setPitch(1.0f);
        tts.setSpeechRate(1.05f);
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
        if (textoCompleto == null || textoCompleto.trim().isEmpty()) return;

        if (!isInitialized) {
            pendingQueue.clear();
            pendingQueue.add(textoCompleto);
            setSpeaking(true);
            inicializarMotor();
            return;
        }

        String limpio = textoCompleto.replaceAll("[*#_~`>\\[\\]()]", " ")
                                     .replaceAll("\\s+", " ")
                                     .trim();
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

    public synchronized void detener() {
        pendingQueue.clear();
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        terminar(null);
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
        setSpeaking(false);
        if (error != null) notificarError(error);
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
            public void run() { if (listener != null) listener.onSpeakingChanged(s); }
        });
    }

    private void notificarError(final String msg) {
        main.post(new Runnable() {
            public void run() { if (listener != null) listener.onError(msg); }
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
