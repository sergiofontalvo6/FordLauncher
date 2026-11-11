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

    public VoiceInputManager(Context c) { ctx = c.getApplicationContext(); }

    /** Se llama cuando el reconocedor detecta el inicio de la voz del usuario. */
    public void setOnBeginningOfSpeech(Runnable r) { onBeginning = r; }

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
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        final VoiceCallback cb = callback;
        RecognitionListener rl = new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) { }
            public void onBeginningOfSpeech() { if (onBeginning != null) onBeginning.run(); }
            public void onRmsChanged(float rmsdB) { }
            public void onBufferReceived(byte[] buffer) { }
            public void onEndOfSpeech() { }
            public void onError(int error) { if (cb != null) cb.onError("Error de micrófono (" + error + ")"); }
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
