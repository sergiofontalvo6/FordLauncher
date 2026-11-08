package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

/** Motor de voz con atenuacion de audio (Audio Ducking). */
public class CarVoiceSpeaker {
    public interface Listener {
        void onSpeaking(boolean speaking);
        void onError(String msg);
    }

    private final Context ctx;
    private final AudioManager am;
    private TextToSpeech tts;
    private boolean ready;
    private boolean speaking;
    private Listener listener;
    private String pending;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {}
    };

    public CarVoiceSpeaker(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(Listener l) { listener = l; }
    public boolean isSpeaking() { return speaking; }

    public void speak(final String rawText) {
        if (rawText == null || rawText.trim().length() == 0) return;
        final String clean = clean(rawText);
        if (tts == null) {
            pending = clean;
            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(new Locale("es", "ES"));
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                        try { tts.setSpeechRate(0.95f); } catch (Throwable ignored) {}
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            public void onStart(String id) { setSpeaking(true); }
                            public void onDone(String id) { setSpeaking(false); abandon(); }
                            public void onError(String id) { setSpeaking(false); abandon(); }
                        });
                        ready = true;
                        if (pending != null) { String p = pending; pending = null; doSpeak(p); }
                    } else if (listener != null) listener.onError("No se pudo iniciar la voz.");
                }
            });
            return;
        }
        if (!ready) { pending = clean; return; }
        doSpeak(clean);
    }

    private void doSpeak(String clean) {
        try { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Throwable ignored) {}
        setSpeaking(true);
        try {
            java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
            m.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ford-ai");
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, m);
        } catch (Throwable e) {
            setSpeaking(false);
            abandon();
        }
    }

    private void setSpeaking(boolean s) {
        speaking = s;
        if (listener != null) listener.onSpeaking(s);
    }

    private void abandon() {
        try { am.abandonAudioFocus(focusListener); } catch (Throwable ignored) {}
    }

    public void stop() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        setSpeaking(false);
        abandon();
    }

    public void shutdown() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null; ready = false; speaking = false;
        abandon();
    }

    static String clean(String s) {
        return s.replaceAll("[*_#\\[\\]]", "").replaceAll("\\s+", " ").trim();
    }
}
