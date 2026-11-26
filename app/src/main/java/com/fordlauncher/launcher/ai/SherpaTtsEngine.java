package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import kotlin.jvm.functions.Function1;

/**
 * Respaldo neuronal 100 % offline: sherpa-onnx Supertonic 3 int8 (10 speakers).
 *
 * <p>El modelo NO se empaqueta en el APK (145 MB): se lee desde
 * {@code getExternalFilesDir(null)/sherpa-onnx/supertonic3}. Carga y síntesis
 * ocurren en un único hilo de baja prioridad, fuera del hilo principal y del
 * timeout de 12 s del TTS del sistema. Esta clase solo produce/reproduce PCM;
 * el foco de audio (ducking de SerMusic) y el estado de locución los gestiona
 * {@link TextToSpeechManager}.
 */
public final class SherpaTtsEngine {

    public interface Callback {
        void onDone();
        void onError(String msg);
    }

    public enum Estado { NO_DISPONIBLE, CARGANDO, LISTO, ERROR }

    public static final String PREF_SID = "pref_offline_supertonic_sid";
    private static final String TAG = "FordLauncher/Sherpa";
    private static final String PREFS = "launcher";
    private static final String RUTA_MODELO = "sherpa-onnx/supertonic3";
    private static final String RUTA_ASSETS = "tts/supertonic3";
    private static final int SPEAKER_MAX = 9;
    private static final long REINTENTO_ERROR_MS = 15000L;

    /** Los 7 archivos exactos del modelo Supertonic 3 int8. */
    private static final String[] ARCHIVOS = {
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx", "vocoder.int8.onnx",
            "tts.json", "unicode_indexer.bin", "voice.bin"
    };

    private static final SherpaTtsEngine INSTANCE = new SherpaTtsEngine();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object trackLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "sherpa-tts");
            t.setPriority(Thread.MIN_PRIORITY + 1);
            try { t.setDaemon(true); } catch (Throwable ignored) {}
            return t;
        }
    });

    private volatile OfflineTts tts;
    private volatile Estado estado = Estado.NO_DISPONIBLE;
    private volatile boolean cargando;
    private volatile long ultimoError;
    /** Cambia al detener o al iniciar una síntesis nueva; invalida la anterior. */
    private volatile long generacion;
    private volatile int sampleRate;
    private volatile int speakerCount = SPEAKER_MAX + 1;
    private volatile boolean ocupada;
    private AudioTrack trackActiva;

    private SherpaTtsEngine() {}

    public static SherpaTtsEngine get() { return INSTANCE; }

    public boolean estaListo() {
        return Build.VERSION.SDK_INT >= 21 && estado == Estado.LISTO && tts != null;
    }

    public Estado estado() { return estado; }

    public int speakerCount() { return speakerCount; }

    public int sampleRate() { return sampleRate; }

    /** True mientras el nativo está generando; evita encolar más trabajo detrás. */
    public boolean estaOcupada() { return ocupada; }

    /** Speaker guardado en Ajustes IA, siempre dentro de 0..9. */
    public static int speakerPreferido(Context c) {
        int sid = 0;
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, 0);
            sid = p.getInt(PREF_SID, 0);
        } catch (Throwable ignored) {}
        if (sid < 0) sid = 0;
        if (sid > SPEAKER_MAX) sid = SPEAKER_MAX;
        return sid;
    }

    public static File directorioModelo(Context c) {
        File base = c.getExternalFilesDir(null);
        return base == null ? null : new File(base, RUTA_MODELO);
    }

    /** Inicialización asíncrona; se puede llamar muchas veces desde servicios. */
    public void precalentar(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 21) return;
        if (estado == Estado.LISTO && tts != null) return;
        if (cargando) return;
        if (estado == Estado.ERROR && System.currentTimeMillis() - ultimoError < REINTENTO_ERROR_MS) return;
        cargando = true;
        estado = Estado.CARGANDO;
        final Context app = c.getApplicationContext();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() { cargar(app); }
            });
        } catch (Throwable t) {
            cargando = false;
            estado = Estado.ERROR;
            ultimoError = System.currentTimeMillis();
            Log.e(TAG, "No se pudo programar la carga de Supertonic 3", t);
        }
    }

    private void cargar(Context app) {
        try {
            File dir = directorioModelo(app);
            if (dir == null) throw new IOException("Sin almacenamiento externo para el modelo");
            if (!modeloCompleto(dir)) {
                // Instalación opcional: si algún día se distribuye el modelo en
                // assets, se copia una sola vez al almacenamiento privado.
                copiarDesdeAssetsSiExiste(app, dir);
            }
            List<String> faltan = archivosFaltantes(dir);
            if (!faltan.isEmpty()) {
                throw new IOException("Faltan archivos del modelo: " + faltan + " en " + dir);
            }

            OfflineTtsSupertonicModelConfig st = new OfflineTtsSupertonicModelConfig(
                    new File(dir, "duration_predictor.int8.onnx").getAbsolutePath(),
                    new File(dir, "text_encoder.int8.onnx").getAbsolutePath(),
                    new File(dir, "vector_estimator.int8.onnx").getAbsolutePath(),
                    new File(dir, "vocoder.int8.onnx").getAbsolutePath(),
                    new File(dir, "tts.json").getAbsolutePath(),
                    new File(dir, "unicode_indexer.bin").getAbsolutePath(),
                    new File(dir, "voice.bin").getAbsolutePath()
            );
            OfflineTtsModelConfig modelo = new OfflineTtsModelConfig();
            modelo.setSupertonic(st);
            // Configuración del ejemplo oficial de Supertonic 3. Más hilos no
            // mejoran el tiempo en el AC8227L y elevan el pico de heap nativo.
            modelo.setNumThreads(2);
            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(modelo);

            OfflineTts nueva = new OfflineTts(null, config);
            tts = nueva;
            sampleRate = nueva.sampleRate();
            speakerCount = nueva.numSpeakers();
            estado = Estado.LISTO;
            Log.i(TAG, "Supertonic 3 listo: speakers=" + speakerCount
                    + " sampleRate=" + sampleRate + " idioma=" + nueva.lang()
                    + " modelo=" + dir);
        } catch (Throwable t) {
            estado = Estado.ERROR;
            ultimoError = System.currentTimeMillis();
            Log.w(TAG, "Supertonic 3 no disponible; se usa el TTS del sistema", t);
        } finally {
            cargando = false;
        }
    }

    /**
     * Sintetiza y reproduce un texto. El foco de audio ya debe estar pedido por
     * TextToSpeechManager. Las devoluciones de llamada llegan en el hilo principal.
     */
    public void speak(String texto, int sid, Callback cb) {
        if (texto == null || texto.trim().length() == 0) {
            avisarDone(cb, generacion);
            return;
        }
        if (!estaListo()) {
            avisarError(cb, "Supertonic 3 aún no está listo", generacion);
            return;
        }
        detenerReproduccion();
        final long token = ++generacion;
        final OfflineTts motor = tts;
        final int speaker = sid < 0 ? 0 : (sid > SPEAKER_MAX ? SPEAKER_MAX : sid);
        ocupada = true;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        GenerationConfig gc = new GenerationConfig();
                        gc.setSid(speaker);
                        gc.setSpeed(0.98f);
                        gc.setNumSteps(5);
                        // Supertonic multilingual: por defecto es "en". Sin esto
                        // lee el español con fonética inglesa.
                        Map<String, String> extra = new HashMap<String, String>();
                        extra.put("lang", "es");
                        gc.setExtra(extra);

                        long inicio = System.currentTimeMillis();
                        Log.i(TAG, "Generando con supertonic-3-int8 sid=" + speaker
                                + " chars=" + texto.length());
                        final int[] chunks = { 0 };
                        GeneratedAudio audio = motor.generateWithConfigAndCallback(texto, gc,
                                new Function1<float[], Integer>() {
                                    @Override
                                    public Integer invoke(float[] samples) {
                                        chunks[0]++;
                                        return 1; // continuar
                                    }
                                });
                        if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                            throw new IOException("Supertonic 3 devolvió audio vacío");
                        }
                        if (token != generacion) { avisarDone(cb, token); return; }
                        reproducir(audio.getSamples(), audio.getSampleRate() > 0
                                ? audio.getSampleRate() : sampleRate, token);
                        Log.i(TAG, "Síntesis supertonic-3-int8 sid=" + speaker
                                + " ms=" + (System.currentTimeMillis() - inicio)
                                + " muestras=" + audio.getSamples().length
                                + " chunks=" + chunks[0]);
                        avisarDone(cb, token);
                    } catch (Throwable t) {
                        Log.e(TAG, "Fallo al sintetizar con Supertonic 3", t);
                        avisarError(cb, "No se pudo sintetizar con la voz sin conexión", token);
                    } finally {
                        ocupada = false;
                    }
                }
            });
        } catch (Throwable t) {
            ocupada = false;
            avisarError(cb, "No se pudo iniciar la voz sin conexión", token);
        }
    }

    /** Corta la reproducción y cualquier síntesis en curso. */
    public void stop() {
        generacion++;
        detenerReproduccion();
    }

    private void detenerReproduccion() {
        AudioTrack t;
        synchronized (trackLock) {
            t = trackActiva;
            trackActiva = null;
        }
        if (t == null) return;
        try { t.stop(); } catch (Throwable ignored) {}
        try { t.release(); } catch (Throwable ignored) {}
    }

    private void reproducir(float[] samples, int sr, long token) {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min == AudioTrack.ERROR_BAD_VALUE || min == AudioTrack.ERROR_INVALID_OPERATION) {
                min = sr / 2;
            }
            int tam = Math.max(min, Math.max(4096, sr / 2));
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sr,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    tam, AudioTrack.MODE_STREAM);
            synchronized (trackLock) { trackActiva = track; }
            track.play();
            short[] buffer = new short[4096];
            int offset = 0;
            while (offset < samples.length && token == generacion) {
                int n = Math.min(buffer.length, samples.length - offset);
                for (int i = 0; i < n; i++) {
                    float v = samples[offset + i];
                    if (v > 1f) v = 1f;
                    if (v < -1f) v = -1f;
                    buffer[i] = (short) (v * 32767f);
                }
                int escritos = track.write(buffer, 0, n);
                if (escritos <= 0) break;
                offset += escritos;
            }
        } finally {
            synchronized (trackLock) {
                if (trackActiva == track) trackActiva = null;
            }
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void avisarDone(final Callback cb, final long token) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() { if (token == generacion) cb.onDone(); }
        });
    }

    private void avisarError(final Callback cb, final String msg, final long token) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() { if (token == generacion) cb.onError(msg); }
        });
    }

    private static boolean modeloCompleto(File dir) {
        return dir != null && archivosFaltantes(dir).isEmpty();
    }

    private static List<String> archivosFaltantes(File dir) {
        List<String> faltan = new ArrayList<String>();
        if (dir == null) return faltan;
        for (String nombre : ARCHIVOS) {
            File f = new File(dir, nombre);
            if (!f.isFile() || f.length() <= 0) faltan.add(nombre);
        }
        return faltan;
    }

    private boolean copiarDesdeAssetsSiExiste(Context c, File dir) {
        AssetManager am = c.getAssets();
        boolean copiado = false;
        if (!dir.exists()) dir.mkdirs();
        for (String nombre : ARCHIVOS) {
            File destino = new File(dir, nombre);
            if (destino.isFile() && destino.length() > 0) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = am.open(RUTA_ASSETS + "/" + nombre);
                File tmp = new File(dir, nombre + ".tmp");
                out = new FileOutputStream(tmp);
                byte[] b = new byte[32768];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                out.flush();
                out.close();
                out = null;
                if (!tmp.renameTo(destino)) throw new IOException("No se pudo mover " + nombre);
                copiado = true;
            } catch (Throwable t) {
                if (out != null) { try { out.close(); } catch (Throwable ignored) {} }
                return false;
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            }
        }
        return copiado;
    }

    /** Texto para el hook ADB y para el diagnóstico de Ajustes. */
    public String estadoDetallado(Context c) {
        File dir = directorioModelo(c);
        List<String> faltan = archivosFaltantes(dir);
        StringBuilder sb = new StringBuilder("Supertonic 3 ");
        sb.append(estado).append(" | API=").append(Build.VERSION.SDK_INT);
        sb.append(" | ocupada=").append(ocupada);
        sb.append(" | speakers=").append(speakerCount);
        sb.append(" | sampleRate=").append(sampleRate);
        sb.append(" | dir=").append(dir == null ? "null" : dir.getAbsolutePath());
        if (!faltan.isEmpty()) sb.append(" | faltan=").append(faltan);
        return sb.toString();
    }
}
