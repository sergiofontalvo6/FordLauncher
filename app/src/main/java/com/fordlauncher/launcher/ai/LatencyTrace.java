package com.fordlauncher.launcher.ai;

import android.os.SystemClock;
import android.util.Log;

/**
 * Telemetría ligera del turno del copiloto: STT -> IA -> Edge TTS -> reproducción.
 * Solo escribe en logcat; no cambia el comportamiento ni las preferencias.
 */
public final class LatencyTrace {
    private static final String TAG = "FordLauncher/Latencia";

    private static final Object LOCK = new Object();
    private static long id;
    private static long t0;
    private static long sttEnd;
    private static long aiStart;
    private static long aiEnd;
    private static long ttsStart;
    private static long firstByte;
    private static long playStart;
    private static String modelo = "-";
    private static String voz = "-";
    private static boolean local;
    private static boolean resumenHecho;
    private static boolean fallback;

    private LatencyTrace() {}

    /** Inicia (o reinicia) un turno. Se llama al terminar la captura STT. */
    public static void nuevoTurno(String origen, String pregunta) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            id++;
            t0 = now;
            sttEnd = now;
            aiStart = 0;
            aiEnd = 0;
            ttsStart = 0;
            firstByte = 0;
            playStart = 0;
            modelo = "-";
            voz = "-";
            local = false;
            resumenHecho = false;
            fallback = false;
        }
        String q = pregunta == null ? "" : pregunta.trim();
        if (q.length() > 60) q = q.substring(0, 60) + "…";
        Log.i(TAG, "INICIO id=" + id + " origen=" + origen + " q=\"" + q + "\"");
    }

    /** Si no hubo marca de STT (pruebas ADB directas), arranca el reloj aquí. */
    public static void asegurarTurno(String origen) {
        synchronized (LOCK) {
            if (t0 == 0) nuevoTurno(origen, "");
        }
    }

    private static void mark(String etapa, long tiempo) {
        long value;
        long ident;
        synchronized (LOCK) { value = tiempo - t0; ident = id; }
        if (value < 0) value = 0;
        Log.i(TAG, "id=" + ident + " " + etapa + " +" + value + "ms");
    }

    public static void iaInicio(String modeloUsado) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            asegurarTurnoLocked("ASK");
            aiStart = now;
            modelo = modeloUsado == null ? "-" : modeloUsado;
        }
        mark("STT->IA", now);
    }

    /** Actualiza el modelo usado sin reiniciar el cronómetro de la petición. */
    public static void iaModelo(String modeloUsado) {
        synchronized (LOCK) { modelo = modeloUsado == null ? "-" : modeloUsado; }
    }

    public static void iaFin(boolean respuestaLocal) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            asegurarTurnoLocked("LOCAL");
            aiEnd = now;
            local = respuestaLocal;
        }
        mark(respuestaLocal ? "COMANDO_LOCAL" : "IA->TEXTO", now);
    }

    public static void ttsInicio(String nombreVoz, int caracteres) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            asegurarTurnoLocked("TTS");
            ttsStart = now;
            firstByte = 0;
            playStart = 0;
            resumenHecho = false;
            fallback = false;
            voz = nombreVoz == null ? "-" : nombreVoz;
        }
        mark("TEXTO->TTS", now);
        Log.i(TAG, "id=" + id + " tts solicitud chars=" + caracteres + " voz=" + voz);
    }

    public static void ttsPrimerByte() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (ttsStart == 0 || firstByte != 0) return; // eco de un turno anterior
            firstByte = now;
        }
        mark("TTS primer audio", now);
    }

    public static void ttsFallback(String motivo) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (ttsStart == 0 || fallback) return;
            if (playStart == 0) playStart = now;
            fallback = true;
            voz = "google(local)";
        }
        mark("FALLBACK_LOCAL", now);
        resumenLocked("fallback:" + motivo);
    }

    public static void ttsReproduciendo(String motor) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (ttsStart == 0) return; // onStart de una locución anterior
            if (playStart == 0) playStart = now;
            if (motor != null && !fallback) voz = motor;
        }
        mark("AUDIO PLAY", now);
        resumenLocked(fallback ? "fallback:lento" : "ok");
    }

    private static void resumenLocked(String resultado) {
        if (t0 == 0 || playStart == 0 || resumenHecho) return;
        resumenHecho = true;
        long sttAi = aiStart > 0 ? aiStart - sttEnd : 0;
        long ia = aiEnd > 0 && aiStart > 0 ? aiEnd - aiStart : 0;
        long iaTts = ttsStart > 0 && aiEnd > 0 ? ttsStart - aiEnd : 0;
        long tts1 = firstByte > 0 && ttsStart > 0 ? firstByte - ttsStart : 0;
        long tts2 = playStart > 0 && firstByte > 0 ? playStart - firstByte
                : (playStart > 0 && ttsStart > 0 ? playStart - ttsStart : 0);
        Log.i(TAG, "RESUMEN id=" + id + " resultado=" + resultado
                + " STT->IA=" + sttAi + "ms IA=" + ia + "ms IA->TTS=" + iaTts + "ms"
                + " TTS primerByte=" + tts1 + "ms primerByte->PLAY=" + tts2 + "ms"
                + " TOTAL=" + (playStart - t0) + "ms"
                + " modelo=" + modelo + " voz=" + voz + " local=" + local);
    }

    private static void asegurarTurnoLocked(String origen) {
        if (t0 == 0) {
            long now = SystemClock.elapsedRealtime();
            id++;
            t0 = now;
            sttEnd = now;
            Log.i(TAG, "INICIO id=" + id + " origen=" + origen + " q=\"\"");
        }
    }
}
