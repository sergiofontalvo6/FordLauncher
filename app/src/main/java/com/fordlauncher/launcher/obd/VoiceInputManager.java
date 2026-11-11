package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;
import java.util.ArrayList;

/** Dictado por voz (SpeechRecognizer Android, es-CO) para preguntar a la IA. */
public class VoiceInputManager {
    public interface VoiceCallback {
        void onQueryReady(String text);
        void onError(String msg);
    }

    private SpeechRecognizer recognizer;
    private final Context ctx;
    private Runnable onBeginning;
    private Runnable onSilence;

    public VoiceInputManager(Context c) { ctx = c.getApplicationContext(); }

    /** Se llama cuando el reconocedor detecta el inicio de la voz del usuario. */
    public void setOnBeginningOfSpeech(Runnable r) { onBeginning = r; }

    /** Se llama cuando el usuario se queda en silencio (sin coincidencia o tiempo agotado). */
    public void setOnSilence(Runnable r) { onSilence = r; }

    public static boolean esSilencio(int error) {
        return error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
    }

    /** Corta la escucha actual sin destruir el reconocedor (para el bucle continuo). */
    public void cancel() {
        try { if (recognizer != null) recognizer.cancel(); } catch (Throwable ignored) {}
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
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        } catch (Throwable t) {
            if (callback != null) callback.onError("No se pudo iniciar el micrófono");
            return;
        }
        // Libera cualquier escucha anterior antes de abrir el micrófono otra vez.
        cancel();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        // Cierra el turno rápido cuando el usuario deja de hablar.
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        final VoiceCallback cb = callback;
        RecognitionListener rl = new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) { }
            public void onBeginningOfSpeech() { if (onBeginning != null) onBeginning.run(); }
            public void onRmsChanged(float rmsdB) { }
            public void onBufferReceived(byte[] buffer) { }
            public void onEndOfSpeech() { }
            public void onError(int error) {
                if (esSilencio(error) && onSilence != null) onSilence.run();
                if (cb != null) cb.onError("Error de micrófono (" + error + ")");
            }
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (cb != null) cb.onQueryReady(matches.get(0));
                } else if (cb != null) cb.onError("No se entendió la pregunta");
            }
            public void onPartialResults(Bundle partialResults) { }
            public void onEvent(int eventType, Bundle params) { }
        };
        recognizer.setRecognitionListener(rl);
        try { recognizer.startListening(intent); }
        catch (Throwable t) { if (callback != null) callback.onError("No se pudo iniciar el micrófono"); }
    }

    public void destroy() {
        try { if (recognizer != null) recognizer.destroy(); } catch (Throwable ignored) {}
        recognizer = null;
    }
}
