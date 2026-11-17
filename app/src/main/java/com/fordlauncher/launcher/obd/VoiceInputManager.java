package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;
import java.util.ArrayList;

/**
 * Dictado por voz (SpeechRecognizer Android, es-CO) para preguntar a la IA.
 *
 * <p>En la radio AC8227L (Android 8.1) la tecla «Hablar» del volante llega como
 * {@code VOICE_ASSIST} y el HAL de audio tarda unos cientos de milisegundos en
 * liberar el canal de captura. Llamar {@code startListening()} sobre un
 * {@link SpeechRecognizer} que aún está cerrando devuelve {@code ERROR_CLIENT (5)}.
 * Para evitarlo este gestor:
 * <ul>
 *   <li>cancela la escucha anterior y espera {@link #LIBERAR_CANAL_MS} antes de abrir el micrófono;</li>
 *   <li>si el reconocedor venía de un error, lo destruye y crea uno limpio;</li>
 *   <li>ante el primer {@code ERROR_CLIENT} hace un reintento silencioso
 *       ({@link #RETRY_CLIENT_MS}, reconocedor nuevo) antes de avisar al usuario.</li>
 * </ul>
 */
public class VoiceInputManager {
    public interface VoiceCallback {
        void onQueryReady(String text);
        void onError(String msg);
    }

    private static final String TAG = "FordLauncher/VoiceInput";
    /** Margen para que el HAL/MCU libere el canal de captura tras VOICE_ASSIST. */
    private static final long LIBERAR_CANAL_MS = 320L;
    /** Espera del reintento silencioso tras ERROR_CLIENT (5). */
    private static final long RETRY_CLIENT_MS = 400L;

    private SpeechRecognizer recognizer;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable onBeginning;
    private Runnable onSilence;

    private volatile boolean listening;
    private volatile boolean errorPrevio;
    /** true tras stopListening/cancel: ignora errores tardíos y no reintenta. */
    private volatile boolean detenido;
    private int retryClient;
    private int generacion;
    private Runnable arranquePendiente;
    private Runnable reintentoPendiente;

    public VoiceInputManager(Context c) { ctx = c.getApplicationContext(); }

    /** Se llama cuando el reconocedor detecta el inicio de la voz del usuario. */
    public void setOnBeginningOfSpeech(Runnable r) { onBeginning = r; }

    /** Se llama cuando el usuario se queda en silencio (sin coincidencia o tiempo agotado). */
    public void setOnSilence(Runnable r) { onSilence = r; }

    public static boolean esSilencio(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
    }

    public boolean isListening() { return listening; }

    /** Corta la escucha actual y descarta cualquier arranque pendiente. */
    public void cancel() {
        generacion++;
        detenido = true;
        cancelarArranquePendiente();
        cancelarReintentoPendiente();
        cancelarEscuchaInterna();
        Log.i(TAG, "cancel -> micrófono detenido");
    }

    /** Detiene la escucha actual (stopListening) sin destruir el reconocedor. */
    public void stopListening() {
        // Marca la escucha como terminada ANTES de tocar el reconocedor: su
        // onError(5) posterior no debe programar un reintento que reabra el mic.
        detenido = true;
        generacion++;
        cancelarArranquePendiente();
        cancelarReintentoPendiente();
        listening = false;
        try { if (recognizer != null) recognizer.stopListening(); } catch (Throwable ignored) {}
        Log.i(TAG, "stopListening -> micrófono detenido");
    }

    public boolean isAvailable() {
        try { return SpeechRecognizer.isRecognitionAvailable(ctx); } catch (Throwable t) { return false; }
    }

    public void startListening(final VoiceCallback callback) {
        if (!isAvailable()) {
            try { Toast.makeText(ctx, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
            if (callback != null) callback.onError("Reconocimiento de voz no disponible");
            return;
        }
        final int gen = ++generacion;
        retryClient = 0;
        detenido = false;
        cancelarArranquePendiente();
        cancelarReintentoPendiente();
        // Nunca arrancar encima de una escucha viva: primero se cancela.
        cancelarEscuchaInterna();
        Log.i(TAG, "startListening -> esperando " + LIBERAR_CANAL_MS + " ms para liberar el micrófono");
        arranquePendiente = new Runnable() {
            public void run() {
                arranquePendiente = null;
                if (gen != generacion) return;
                abrirMicrofono(callback);
            }
        };
        main.postDelayed(arranquePendiente, LIBERAR_CANAL_MS);
    }

    /** Crea/reutiliza el reconocedor y lanza la escucha (ya con el canal libre). */
    private void abrirMicrofono(final VoiceCallback callback) {
        try {
            // Un reconocedor que venía de error o de otra sesión provoca
            // ERROR_CLIENT (5): se reemplaza por uno limpio.
            if (recognizer == null || errorPrevio) recrearReconocedor();
            if (recognizer == null) {
                if (callback != null) callback.onError("No se pudo iniciar el micrófono");
                return;
            }
            recognizer.setRecognitionListener(construirListener(callback));
            listening = true;
            errorPrevio = false;
            Log.i(TAG, "startListening -> startListening(intent) lang=es-CO free=1 max=1 minSilence=1200");
            recognizer.startListening(construirIntent());
        } catch (Throwable t) {
            listening = false;
            errorPrevio = true;
            Log.e(TAG, "startListening falló", t);
            if (callback != null) callback.onError("No se pudo iniciar el micrófono");
        }
    }

    private Intent construirIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Cierres de turno estrictos: el reconocimiento finaliza rápido cuando el
        // usuario deja de hablar (p. ej. al nombrar la canción), sin quedarse en silencio.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L);
        return intent;
    }

    private RecognitionListener construirListener(final VoiceCallback callback) {
        return new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) {
                retryClient = 0;
                Log.i(TAG, "onReadyForSpeech -> micrófono listo");
            }
            public void onBeginningOfSpeech() { if (onBeginning != null) onBeginning.run(); }
            public void onRmsChanged(float rmsdB) { }
            public void onBufferReceived(byte[] buffer) { }
            public void onEndOfSpeech() { }
            public void onError(int error) {
                listening = false;
                errorPrevio = true;
                if (detenido) {
                    Log.i(TAG, "onError code=" + error + " ignorado (escucha detenida)");
                    return;
                }
                Log.i(TAG, "onError code=" + error + " retryClient=" + retryClient);
                // ERROR_CLIENT (5) en el primer intento tras el botón del volante:
                // reintento silencioso recreando el reconocedor, sin avisar en pantalla.
                if (error == SpeechRecognizer.ERROR_CLIENT && retryClient == 0) {
                    retryClient = 1;
                    final int gen = generacion;
                    Log.i(TAG, "ERROR_CLIENT(5) -> reintento silencioso en " + RETRY_CLIENT_MS + " ms");
                    reintentoPendiente = new Runnable() {
                        public void run() {
                            reintentoPendiente = null;
                            if (gen != generacion || detenido) return;
                            Log.i(TAG, "reintento silencioso -> recreando SpeechRecognizer");
                            abrirMicrofono(callback);
                        }
                    };
                    main.postDelayed(reintentoPendiente, RETRY_CLIENT_MS);
                    return;
                }
                if (esSilencio(error) && onSilence != null) onSilence.run();
                if (callback != null) callback.onError("Error de micrófono (" + error + ")");
            }
            public void onResults(Bundle results) {
                listening = false;
                retryClient = 0;
                // El turno termina aquí: cualquier onError(5) posterior de
                // stopListening debe ignorarse (no reabrir el micrófono).
                detenido = true;
                Log.i(TAG, "onResults -> procesando respuesta del usuario");
                // Detener el micrófono inmediatamente: ya entregamos el texto y no debe
                // seguir escuchando (evita captar ruido de cabina / audio como comandos).
                try { if (recognizer != null) recognizer.stopListening(); } catch (Throwable ignored) {}
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (callback != null) callback.onQueryReady(matches.get(0));
                } else if (callback != null) {
                    callback.onError("No se entendió la pregunta");
                }
            }
            public void onPartialResults(Bundle partialResults) { }
            public void onEvent(int eventType, Bundle params) { }
        };
    }

    private void cancelarArranquePendiente() {
        if (arranquePendiente != null) {
            main.removeCallbacks(arranquePendiente);
            arranquePendiente = null;
        }
    }

    private void cancelarReintentoPendiente() {
        if (reintentoPendiente != null) {
            main.removeCallbacks(reintentoPendiente);
            reintentoPendiente = null;
        }
    }

    private void cancelarEscuchaInterna() {
        listening = false;
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
    }

    /** Destruye el reconocedor actual y crea uno nuevo (estado limpio). */
    private void recrearReconocedor() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            Log.i(TAG, "SpeechRecognizer recreado");
        } catch (Throwable t) {
            Log.e(TAG, "createSpeechRecognizer failed", t);
            recognizer = null;
        }
    }

    public void destroy() {
        generacion++;
        detenido = true;
        cancelarArranquePendiente();
        cancelarReintentoPendiente();
        listening = false;
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        recognizer = null;
    }
}
