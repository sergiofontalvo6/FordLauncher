package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.fordlauncher.launcher.ai.AppExecutors;
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
 * Disparadores proactivos de anomalías con umbrales de fábrica del Ford Fiesta
 * 1.6L Ti-VCT (sistema Smart Charge).
 *
 * <p>Vive mientras el proceso está vivo: lo arranca {@link ObdForegroundService}
 * (servicio en primer plano que ya corre desde el botón flotante), NO depende
 * del interruptor del botón de IA ni de internet. Cada alerta se entrega por
 * {@link VehicleAlertManager}: notificación local de alta prioridad, banner
 * flotante sobre cualquier app y voz 100 % offline.
 */
public class SensorAnomalyTriggerManager {

    public static final long TICK_MS = 5000L;
    public static final long COOLDOWN_MS = 15 * 60 * 1000L;
    public static final long DTC_POLL_MS = 120 * 1000L;
    public static final float TEMP_CRITICA = 112f;
    public static final float TEMP_CRITICA_HISTERESIS = 110f;

    private static final String TAG = "FordLauncher/Anomalia";
    private static volatile SensorAnomalyTriggerManager instance;

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
    private long tickDelay = TICK_MS;
    private boolean hablando;
    private int turno;
    private boolean dtcBaselineLista;
    private boolean criticoAvisado;
    private long motorEncendidoDesde;
    private volatile boolean consultandoDtc;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            try {
                if (repo == null || !repo.isConnected()) {
                    motorEncendidoDesde = 0;
                } else {
                    Snapshot snap = snapshotActual();
                    evaluarSnapshot(snap);
                    if (!Float.isNaN(snap.rpm) && snap.rpm <= 0f) tickDelay = 20000L;
                    else tickDelay = TICK_MS;
                }
            } catch (Throwable t) {
                Log.w(TAG, "tick: " + t);
            }
            if (running) h.postDelayed(this, tickDelay);
        }
    };

    private final Runnable dtcTick = new Runnable() {
        public void run() {
            if (!running) return;
            if (repo != null && repo.isConnected() && !repo.isDemo() && !consultandoDtc) {
                consultandoDtc = true;
                // Pool local dedicado a OBD: no espera red ni a Gemini.
                AppExecutors.get().obd.execute(new Runnable() {
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
                });
            }
            if (running) h.postDelayed(this, DTC_POLL_MS);
        }
    };

    private SensorAnomalyTriggerManager(Context c, ObdRepository repo) {
        this.ctx = c.getApplicationContext();
        this.repo = repo;
    }

    /** Instancia única del monitor (la arranca el servicio OBD en primer plano). */
    public static SensorAnomalyTriggerManager get(Context c, ObdRepository repo) {
        if (instance == null) {
            synchronized (SensorAnomalyTriggerManager.class) {
                if (instance == null) instance = new SensorAnomalyTriggerManager(c, repo);
            }
        }
        return instance;
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
        Log.i(TAG, "Monitor de anomalías activo (independiente de red y del FAB)");
    }

    /** Detiene el monitoreo (solo al destruir el proceso/servicio). */
    public synchronized void stop() {
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
                    alertaCritica("temp_critica",
                            "Temperatura del motor crítica",
                            (int) d.coolantTemp + " °C · apaga el motor",
                            "Atención: temperatura crítica a " + (int) d.coolantTemp
                                    + " grados, apaga el motor.");
                    Log.w(TAG, "temp_critica: " + d.coolantTemp);
                }
            } else {
                if (d.coolantTemp < TEMP_CRITICA_HISTERESIS) criticoAvisado = false;
                if (d.coolantTemp >= 106f) {
                    dispararAlerta("temp_alta",
                            "Temperatura elevada",
                            "Motor a " + (int) d.coolantTemp + " °C",
                            "He notado una anomalía: temperatura del motor elevada a "
                                    + (int) d.coolantTemp + " grados.");
                }
                if (d.coolantTemp < 70f && !Float.isNaN(d.speed) && d.speed > 30f
                        && !Float.isNaN(d.runTimeMin) && d.runTimeMin > 10f) {
                    dispararAlerta("temp_baja",
                            "Motor frío en marcha",
                            (int) d.coolantTemp + " °C · no alcanza temperatura de trabajo",
                            "He notado una anomalía: el motor no alcanza temperatura de trabajo, está en "
                                    + (int) d.coolantTemp + " grados.");
                }
            }
        }

        // B) Sistema eléctrico / Smart Charge.
        if (!Float.isNaN(d.batteryVoltage) && d.batteryVoltage > 0f) {
            if (!Float.isNaN(d.rpm) && d.rpm > 600f && d.batteryVoltage < 12.5f) {
                dispararAlerta("alt_bajo",
                        "La batería no está cargando",
                        fmt(d.batteryVoltage) + " V con el motor encendido",
                        "He notado una anomalía: el alternador no está cargando la batería, voltaje en "
                                + fmt(d.batteryVoltage) + " voltios.");
            }
            if (d.batteryVoltage > 15.2f) {
                dispararAlerta("alt_alto",
                        "Sobrecarga del alternador",
                        fmt(d.batteryVoltage) + " V · revisa el sistema de carga",
                        "He notado una anomalía: sobrevoltaje en el alternador a "
                                + fmt(d.batteryVoltage) + " voltios.");
            }
            if (!Float.isNaN(d.rpm) && d.rpm == 0f && d.batteryVoltage < 11.8f) {
                dispararAlerta("bat_baja",
                        "Batería baja",
                        fmt(d.batteryVoltage) + " V · carga o cambia la batería",
                        "He notado una anomalía: batería baja a "
                                + fmt(d.batteryVoltage) + " voltios.");
            }
        }

        // C) Códigos de falla nuevos.
        if (d.nuevosDTCs != null && !d.nuevosDTCs.isEmpty()) {
            String nuevo = d.nuevosDTCs.get(0);
            if (nuevo != null && nuevo.trim().length() > 0) {
                dispararAlerta("dtc_" + nuevo,
                        "Código de falla nuevo",
                        nuevo.trim() + " · revisa el motor",
                        "He notado una anomalía: código de falla " + nuevo.trim()
                                + " registrado en la computadora.");
            }
        }
    }

    /**
     * Emergencia: notificación + banner + voz local inmediata, sin cooldown y
     * cortando cualquier locución en curso.
     */
    public synchronized void alertaCritica(String clave, String titulo, String detalle, String voz) {
        colaAvisos.clear();
        turno++;
        hablando = true;
        try {
            VehicleAlertManager.get(ctx).alert(clave, titulo, detalle, voz);
        } catch (Throwable t) {
            hablando = false;
            Log.w(TAG, "Alerta crítica: " + t);
        }
    }

    /** Puertas/CANBUS: cualquier módulo puede llamar aquí al detectar una puerta abierta. */
    public void alertaPuerta(String puerta) {
        String p = (puerta == null || puerta.trim().length() == 0) ? "una puerta" : puerta.trim();
        alertaCritica("puerta_abierta",
                "Puerta abierta",
                p + " abierta",
                "Atención: " + p + " está abierta.");
    }

    /** Aviso no crítico: banner/notificación + voz encolada, con cooldown de 15 minutos. */
    public synchronized void dispararAlerta(String clave, String titulo, String detalleCorto, String voz) {
        long ahora = SystemClock.elapsedRealtime();
        Long prev = ultimaAlerta.get(clave);
        if (prev != null && ahora - prev < COOLDOWN_MS) return;
        ultimaAlerta.put(clave, ahora);
        try {
            VehicleAlertManager.get(ctx).notifyAndShow(clave, titulo, detalleCorto);
        } catch (Throwable t) {
            Log.w(TAG, "Aviso: " + t);
        }
        hablar(voz);
        Log.w(TAG, clave + ": " + detalleCorto);
    }

    private void hablar(String texto) {
        colaAvisos.addLast(texto);
        bombear();
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
            m.hablarAlertaCritica(texto);
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

    /**
     * Hook de pruebas ADB:
     * {@code --es alert test_temp|test_battery|test_dtc|test_door}.
     * Dispara la misma ruta que una alerta real, sin necesitar el adaptador.
     */
    public void simular(String tipo) {
        String t = tipo == null ? "" : tipo.trim().toLowerCase(Locale.ROOT);
        if (t.contains("temp")) {
            alertaCritica("test_temp",
                    "Temperatura del motor crítica",
                    "113 °C · apaga el motor",
                    "Atención: temperatura crítica a 113 grados, apaga el motor.");
        } else if (t.contains("battery") || t.contains("bateria") || t.contains("volt")) {
            alertaCritica("test_battery",
                    "Batería baja",
                    "11.2 V · carga o cambia la batería",
                    "Atención: batería baja a 11.2 voltios, carga o cambia la batería.");
        } else if (t.contains("dtc") || t.contains("falla")) {
            alertaCritica("test_dtc",
                    "Código de falla nuevo",
                    "P0301 · fallo de encendido en el cilindro 1",
                    "Atención: código de falla P cero tres cero uno, fallo de encendido en el cilindro uno.");
        } else if (t.contains("door") || t.contains("puerta")) {
            alertaPuerta("la puerta del conductor");
        } else {
            alertaCritica("test_alerta",
                    "Alerta de prueba",
                    "Simulación del sistema de avisos",
                    "Esta es una alerta de prueba del sistema de avisos del vehículo.");
        }
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
