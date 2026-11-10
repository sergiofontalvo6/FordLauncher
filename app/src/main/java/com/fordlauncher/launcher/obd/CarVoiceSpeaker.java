package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import java.util.Locale;

/** Motor de voz con atenuacion de audio (Audio Ducking). */
public class CarVoiceSpeaker {
    private static final String TAG = "FordLauncher/Voz";
    public interface Listener {
        void onSpeaking(boolean speaking);
        void onError(String msg);
    }

    private final Context ctx;
    private final AudioManager am;
    private final String prefEngine;
    private final String prefVoice;
    private volatile TextToSpeech tts;
    private boolean ready;
    private boolean speaking;
    private Listener listener;
    private String pending;
    private int pendingChunks;
    private int doneChunks;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {}
    };

    public CarVoiceSpeaker(Context c) {
        ctx = c.getApplicationContext();
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        String eng = null, voi = null;
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("launcher", 0);
            eng = p.getString("tts_engine", null);
            voi = p.getString("tts_voice", null);
        } catch (Throwable ignored) {}
        prefEngine = (eng != null && eng.length() > 0) ? eng : null;
        prefVoice = (voi != null && voi.length() > 0) ? voi : null;
    }

    public void setListener(Listener l) { listener = l; }
    public boolean isSpeaking() { return speaking; }

    public void speak(final String rawText) {
        if (rawText == null || rawText.trim().length() == 0) return;
        final String clean = clean(rawText);
        if (tts == null) {
            pending = clean;
            TextToSpeech.OnInitListener init = new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status == TextToSpeech.SUCCESS) {
                        int r = tts.setLanguage(new Locale("es", "ES"));
                        Log.w(TAG, "TTS setLanguage es-ES -> " + r + " (OK=" + TextToSpeech.LANG_AVAILABLE + " MISSING=" + TextToSpeech.LANG_MISSING_DATA + " NOT_SUPPORTED=" + TextToSpeech.LANG_NOT_SUPPORTED + ")");
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            int d = tts.setLanguage(Locale.getDefault());
                            Log.w(TAG, "TTS fallback default locale -> " + d);
                        }
                        try {
                            if (prefVoice != null && android.os.Build.VERSION.SDK_INT >= 21) {
                                java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
                                if (voices != null) for (android.speech.tts.Voice voice : voices) {
                                    if (prefVoice.equals(voice.getName())) {
                                        int vr = tts.setVoice(voice);
                                        Log.w(TAG, "TTS setVoice " + prefVoice + " -> " + vr + " (SUCCESS=0)");
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable t) { Log.w(TAG, "TTS setVoice error: " + t); }
                        try { tts.setSpeechRate(0.95f); } catch (Throwable ignored) {}
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            public void onStart(String id) { setSpeaking(true); }
                            public void onDone(String id) { setSpeaking(false); abandon(); }
                            @Override
                            public void onError(String id) { setSpeaking(false); abandon(); if (listener != null) listener.onError("Error al reproducir la voz."); }
                            @Override
                            public void onError(String id, int errorCode) { setSpeaking(false); abandon(); if (listener != null) listener.onError("Error al reproducir la voz (" + errorCode + ")."); }
                        });
                        ready = true;
                        if (pending != null) { String p = pending; pending = null; doSpeak(p); }
                    } else {
                        Log.w(TAG, "TTS init failed, status=" + status);
                        if (listener != null) listener.onError("No se pudo iniciar la voz.");
                    }
                }
            };
            try {
                tts = (prefEngine != null) ? new TextToSpeech(ctx, init, prefEngine) : new TextToSpeech(ctx, init);
            } catch (Throwable t) {
                try { tts = new TextToSpeech(ctx, init); } catch (Throwable t2) { if (listener != null) listener.onError("No se pudo iniciar la voz."); }
            }
            return;
        }
        if (!ready) { pending = clean; return; }
        doSpeak(clean);
    }

    /** Habla el texto. Se intenta en un solo bloque para máxima compatibilidad. */
    private void doSpeak(String clean) {
        if (tts == null || !ready) return;
        if (clean == null || clean.length() == 0) return;
        String s = clean.replaceAll("[*#_~`\\[\\]()]", " ").replaceAll("\\s+", " ").trim();
        if (s.length() == 0) return;
        if (s.length() > 320) s = s.substring(0, 320);
        try { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK); } catch (Throwable ignored) {}
        setSpeaking(true);
        pendingChunks = 1;
        doneChunks = 0;
        try {
            int res;
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                android.os.Bundle params = new android.os.Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utt_0");
                params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
                res = tts.speak(s, TextToSpeech.QUEUE_FLUSH, params, "utt_0");
            } else {
                java.util.HashMap<String, String> params = new java.util.HashMap<String, String>();
                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utt_0");
                params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, String.valueOf(AudioManager.STREAM_MUSIC));
                res = tts.speak(s, TextToSpeech.QUEUE_FLUSH, params);
            }
            Log.w(TAG, "TTS speak -> " + res + " chars=" + s.length() + " text=" + s);
            if (res == TextToSpeech.ERROR) {
                setSpeaking(false);
                abandon();
                if (listener != null) listener.onError("No se pudo reproducir la voz en este dispositivo.");
            }
        } catch (Throwable e) {
            Log.w(TAG, "TTS speak exception: " + e);
            setSpeaking(false);
            abandon();
            if (listener != null) listener.onError("No se pudo reproducir la voz en este dispositivo.");
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
