package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.fordlauncher.launcher.ai.TextToSpeechManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Disparadores proactivos de anomalías con umbrales de fábrica del Ford Fiesta 1.6L Ti-VCT
 * (sistema Smart Charge). Avisa por voz: "He notado una anomalía: [detalle]".
 *
 * Solo corre con el botón flotante de IA activo (pref_enable_floating_ai_button).
 * Si se desactiva, Main llama a {@link #stop()} y el monitor queda detenido por completo.
 */
public class SensorAnomalyTriggerManager {

    public static final long TICK_MS = 5000L;
    public static final long COOLDOWN_MS = 15 * 60 * 1000L;
    public static final long DTC_POLL_MS = 120 * 1000L;
    public static final float TEMP_CRITICA = 112f;
    public static final float TEMP_CRITICA_HISTERESIS = 110f;

    private static final String TAG = "FordLauncher/Anomalia";
    private static final String PREF_FAB = "pref_enable_floating_ai_button";

    /** Datos mínimos para evaluar los disparadores (NaN = PID no disponible). */
    public static class Snapshot {
        public float coolantTemp = Float.NaN;
        public float speed = Float.NaN;
        public float rpm = Float.NaN;
        public float batteryVoltage = Float.NaN;
        public float runTimeMin = Float.NaN;
        public final List<String> nuevosDTCs = new ArrayList<String>();
    }

    private final Context ctx;
    private final ObdRepository repo;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, Long> ultimaAlerta = new HashMap<String, Long>();
    private final Set<String> dtcConocidos = new HashSet<String>();
    private final java.util.ArrayDeque<String> colaAvisos = new java.util.ArrayDeque<String>();
    private final TextToSpeechManager.Listener ttsListener = new TextToSpeechManager.Listener() {
        public void onSpeakingChanged(boolean speaking) {
            if (!speaking) {
                synchronized (SensorAnomalyTriggerManager.this) { hablando = false; }
                bombear();
            }
        }
        public void onError(String msg) {
            synchronized (SensorAnomalyTriggerManager.this) { hablando = false; }
            bombear();
        }
    };
    private boolean running;
    private boolean hablando;
    private int turno;
    private boolean dtcBaselineLista;
    private boolean criticoAvisado;
    private long motorEncendidoDesde;
    private volatile boolean consultandoDtc;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            if (!prefs.getBoolean(PREF_FAB, true)) { stop(); return; }
            try {
                if (repo == null || !repo.isConnected()) {
                    motorEncendidoDesde = 0;
                } else {
                    evaluarSnapshot(snapshotActual());
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick: " + t);
            }
            if (running) h.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable dtcTick = new Runnable() {
        public void run() {
            if (!running) return;
            if (!prefs.getBoolean(PREF_FAB, true)) { stop(); return; }
            if (repo != null && repo.isConnected() && !repo.isDemo() && !consultandoDtc) {
                consultandoDtc = true;
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            Health.EngineReport r = repo.report();
                            Set<String> activos = new LinkedHashSet<String>();
                            if (r != null) {
                                agregar(activos, r.stored);
                                agregar(activos, r.pending);
                                agregar(activos, r.permanent);
                            }
                            procesarDTCs(new ArrayList<String>(activos));
                        } catch (Throwable t) {
                            Log.w(TAG, "DTC: " + t);
                        } finally {
                            consultandoDtc = false;
                        }
                    }
                }, "AnomaliaDTC").start();
            }
            if (running) h.postDelayed(this, DTC_POLL_MS);
        }
    };

    public SensorAnomalyTriggerManager(Context c, ObdRepository repo) {
        this.ctx = c.getApplicationContext();
        this.repo = repo;
        this.prefs = this.ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** Arranca el monitoreo periódico (idempotente). */
    public synchronized void start() {
        if (running) return;
        running = true;
        dtcBaselineLista = false;
        dtcConocidos.clear();
        criticoAvisado = false;
        motorEncendidoDesde = 0;
        ultimaAlerta.clear();
        colaAvisos.clear();
        hablando = false;
        try { TextToSpeechManager.getInstance(ctx).addListener(ttsListener); } catch (Throwable ignored) {}
        h.post(tick);
        h.postDelayed(dtcTick, DTC_POLL_MS);
        Log.i(TAG, "Monitor de anomalías activo");
    }

    /** Detiene el monitoreo por completo (botón flotante desactivado o app destruida). */
    public synchronized void stop() {
        if (!running) {
            h.removeCallbacks(tick);
            h.removeCallbacks(dtcTick);
            return;
        }
        running = false;
        h.removeCallbacks(tick);
        h.removeCallbacks(dtcTick);
        motorEncendidoDesde = 0;
        criticoAvisado = false;
        dtcBaselineLista = false;
        dtcConocidos.clear();
        ultimaAlerta.clear();
        colaAvisos.clear();
        hablando = false;
        try { TextToSpeechManager.getInstance(ctx).removeListener(ttsListener); } catch (Throwable ignored) {}
        Log.i(TAG, "Monitor de anomalías detenido");
    }

    public boolean isRunning() { return running; }

    private Snapshot snapshotActual() {
        Snapshot s = new Snapshot();
        Map<String, Float> v = null;
        try { v = repo.getValues(); } catch (Throwable ignored) {}
        if (v != null) {
            Float c = v.get(Pids.COOLANT); if (c != null) s.coolantTemp = c;
            Float sp = v.get(Pids.SPEED); if (sp != null) s.speed = sp;
            Float r = v.get(Pids.RPM); if (r != null) s.rpm = r;
            Float vol = v.get(Pids.VOLTAGE); if (vol != null) s.batteryVoltage = vol;
            Float rt = v.get(Pids.RUN_TIME); if (rt != null) s.runTimeMin = rt / 60f;
        }
        if (Float.isNaN(s.runTimeMin)) s.runTimeMin = minutosMotorLocal(s.rpm);
        return s;
    }

    /** Tiempo de motor encendido medido por la app, para cuando el PID de RUN_TIME no está. */
    private float minutosMotorLocal(float rpm) {
        if (Float.isNaN(rpm)) return Float.NaN;
        if (rpm <= 0f) { motorEncendidoDesde = 0; return 0f; }
        if (motorEncendidoDesde == 0) motorEncendidoDesde = SystemClock.elapsedRealtime();
        return (SystemClock.elapsedRealtime() - motorEncendidoDesde) / 60000f;
    }

    /** Evalúa los umbrales calibrados para el Fiesta. */
    public synchronized void evaluarSnapshot(Snapshot d) {
        if (d == null) return;

        // A) Temperatura del refrigerante (ECT).
        if (!Float.isNaN(d.coolantTemp)) {
            if (d.coolantTemp >= TEMP_CRITICA) {
                if (!criticoAvisado) {
                    criticoAvisado = true;
                    hablarCritico("Atención: temperatura crítica a " + (int) d.coolantTemp + " grados, apaga el motor.");
                    Log.w(TAG, "temp_critica: " + d.coolantTemp);
                }
            } else {
                if (d.coolantTemp < TEMP_CRITICA_HISTERESIS) criticoAvisado = false;
                if (d.coolantTemp >= 106f) {
                    dispararAlerta("temp_alta", "temperatura del motor elevada a " + (int) d.coolantTemp + " grados");
                }
                if (d.coolantTemp < 70f && !Float.isNaN(d.speed) && d.speed > 30f
                        && !Float.isNaN(d.runTimeMin) && d.runTimeMin > 10f) {
                    dispararAlerta("temp_baja", "el motor no alcanza temperatura de trabajo, está en " + (int) d.coolantTemp + " grados");
                }
            }
        }

        // B) Sistema eléctrico / Smart Charge.
        if (!Float.isNaN(d.batteryVoltage) && d.batteryVoltage > 0f) {
            if (!Float.isNaN(d.rpm) && d.rpm > 600f && d.batteryVoltage < 12.5f) {
                dispararAlerta("alt_bajo", "el alternador no está cargando la batería, voltaje en "
                        + fmt(d.batteryVoltage) + " voltios");
            }
            if (d.batteryVoltage > 15.2f) {
                dispararAlerta("alt_alto", "sobrevoltaje en el alternador a " + fmt(d.batteryVoltage) + " voltios");
            }
            if (!Float.isNaN(d.rpm) && d.rpm == 0f && d.batteryVoltage < 11.8f) {
                dispararAlerta("bat_baja", "batería baja a " + fmt(d.batteryVoltage) + " voltios");
            }
        }

        // C) Códigos de falla nuevos.
        if (d.nuevosDTCs != null && !d.nuevosDTCs.isEmpty()) {
            String nuevo = d.nuevosDTCs.get(0);
            if (nuevo != null && nuevo.trim().length() > 0) {
                dispararAlerta("dtc_" + nuevo, "código de falla " + nuevo + " registrado en la computadora");
            }
        }
    }

    /** Avisa con cooldown de 15 minutos por tipo de alerta. */
    public synchronized void dispararAlerta(String clave, String detalleCorto) {
        long ahora = SystemClock.elapsedRealtime();
        Long prev = ultimaAlerta.get(clave);
        if (prev != null && ahora - prev < COOLDOWN_MS) return;
        ultimaAlerta.put(clave, ahora);
        hablar("He notado una anomalía: " + detalleCorto + ".");
        Log.w(TAG, clave + ": " + detalleCorto);
    }

    private void hablar(String texto) {
        colaAvisos.addLast(texto);
        bombear();
    }

    /** Emergencia: interrumpe lo que se esté diciendo y avisa de inmediato. */
    private synchronized void hablarCritico(String texto) {
        colaAvisos.clear();
        turno++;
        hablando = true;
        try {
            TextToSpeechManager.getInstance(ctx).hablarTextoLargo(texto);
        } catch (Throwable t) {
            hablando = false;
            Log.w(TAG, "TTS: " + t);
        }
    }

    /** Reproduce el siguiente aviso cuando la voz está libre, para no cortar mensajes. */
    private synchronized void bombear() {
        if (!running || hablando || colaAvisos.isEmpty()) return;
        TextToSpeechManager m;
        try { m = TextToSpeechManager.getInstance(ctx); } catch (Throwable t) { return; }
        if (m.isSpeaking()) return;
        final String texto = colaAvisos.pollFirst();
        if (texto == null) return;
        hablando = true;
        final int miTurno = ++turno;
        try {
            m.hablarTextoLargo(texto);
        } catch (Throwable t) {
            hablando = false;
            Log.w(TAG, "TTS: " + t);
            return;
        }
        // Red de seguridad por si el motor de voz no reporta el fin.
        h.postDelayed(new Runnable() {
            public void run() {
                synchronized (SensorAnomalyTriggerManager.this) {
                    if (turno != miTurno) return;
                    hablando = false;
                    turno++;
                }
                bombear();
            }
        }, 20000L);
    }

    /** Compara los DTC activos con los ya vistos; la primera lectura solo sirve de línea base. */
    private synchronized void procesarDTCs(List<String> activos) {
        if (!running) return;
        if (!dtcBaselineLista) {
            dtcConocidos.clear();
            dtcConocidos.addAll(activos);
            dtcBaselineLista = true;
            Log.i(TAG, "Línea base DTC: " + activos);
            return;
        }
        final List<String> nuevos = new ArrayList<String>();
        for (String c : activos) {
            if (c != null && c.trim().length() > 0 && !dtcConocidos.contains(c)) nuevos.add(c);
        }
        dtcConocidos.addAll(activos);
        if (nuevos.isEmpty()) return;
        h.post(new Runnable() {
            public void run() {
                Snapshot s = new Snapshot();
                s.nuevosDTCs.addAll(nuevos);
                evaluarSnapshot(s);
            }
        });
    }

    private static void agregar(Set<String> dst, List<String> src) {
        if (src == null) return;
        for (String c : src) if (c != null && c.trim().length() > 0) dst.add(c);
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.1f", v);
    }
}
