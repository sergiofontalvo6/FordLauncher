package com.fordlauncher.launcher.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.fordlauncher.launcher.ai.AppExecutors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Repositorio OBD global (singleton ligado al ApplicationContext).
 *
 * - Vive fuera del ciclo de vida de las vistas: Main/DiagnosticoView solo se
 *   suscriben y se desuscriben.
 * - Un único hilo "obd-io" ejecuta TODOS los comandos ELM327 en cola
 *   (SingleThreadExecutor), por lo que nunca hay ráfagas paralelas.
 * - El bucle de polling corre en su propio hilo, pero cada comando se envía a
 *   la cola secuencial y se espera su resultado.
 * - Estado unificado con ventana de gracia de 12 s desde la última trama
 *   válida, para que el asistente no diga "desconectado" durante un reconexión.
 */
public class ObdRepository {
    public static final String DEFAULT_MAC = "00:10:CC:4F:36:03";
    public static final String PREFS = "obd";
    private static final String TAG = "FordLauncher/Obd";
    private static final long GRACE_MS = 12000;      // ventana de gracia de telemetría
    private static final long BOOT_DELAY_MS = 8000;  // estabilización Bluetooth
    private static final long IO_TIMEOUT_MS = 2500;  // margen sobre el timeout del ELM (1500 ms)

    public enum State { OFF, CONNECTING, ON, ERROR }
    public enum LinkState { DISCONNECTED, CONNECTING, CONNECTED }

    public interface Listener {
        void onState(State s, String err);
        void onValues(Map<String,Float> v);
    }

    private static volatile ObdRepository INSTANCE;

    public static ObdRepository getInstance(Context c) {
        if (INSTANCE == null) {
            synchronized (ObdRepository.class) {
                if (INSTANCE == null) INSTANCE = new ObdRepository(c.getApplicationContext());
            }
        }
        return INSTANCE;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final SharedPreferences appPrefs;
    private final long processStartElapsed = SystemClock.elapsedRealtime();

    private volatile State state = State.OFF;
    private volatile String error;
    private volatile String adapter;
    private final Set<Integer> supported = new HashSet<Integer>();
    private final Map<String,Float> values = new HashMap<String,Float>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    /** Protege link/elm/supported/adapter. */
    private final Object lock = new Object();
    /** Mutex estricto de conexión: jamás dos sockets RFCOMM a la vez. */
    private final Object connectMutex = new Object();
    /** Notificaciones de cambio de estado para esperarConexion(). */
    private final Object stateLock = new Object();

    /** Único hilo que habla con el ELM327 (cola secuencial de comandos). */
    private final ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "obd-io");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private ElmLink link;
    private Elm327 elm;
    private Thread pollThread;
    private volatile boolean running;
    private volatile boolean conectando;
    private volatile boolean pausado;      // true mientras se leen DTC/VIN (pausa el polling)
    private volatile int generation;       // invalida bucles de polling viejos
    private volatile int connectGeneration; // invalida handshakes si el usuario desconecta
    private volatile long lastFrameMs;     // última trama válida (ventana de gracia)
    private volatile String targetAddress;

    private final AtomicBoolean autoEnCurso = new AtomicBoolean(false);
    private final AtomicBoolean reintentoProgramado = new AtomicBoolean(false);
    private volatile int reconnectAttempt;

    private Health.EngineReport lastReport;
    private final java.util.Map<String, java.util.Deque<Float>> history = new java.util.HashMap<String, java.util.Deque<Float>>();
    private TripLog.TripRecorder tripRecorder;
    private TripLog.TripSummary lastTrip;
    private final TripLog.AccelTimer accel = new TripLog.AccelTimer();

    private ObdRepository(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        appPrefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        targetAddress = readTarget();
    }

    // ───────────────────────── listeners ─────────────────────────

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    /** Compatibilidad: reemplaza la lista de listeners. */
    public void setListener(Listener l) {
        listeners.clear();
        if (l != null) listeners.add(l);
    }

    private void notifyValues(Map<String,Float> v) {
        for (Listener l : listeners) {
            try { l.onValues(v); } catch (Throwable ignored) {}
        }
    }

    private void setState(State s, String err) {
        state = s;
        error = err;
        synchronized (stateLock) { stateLock.notifyAll(); }
        for (Listener l : listeners) {
            try { l.onState(s, err); } catch (Throwable ignored) {}
        }
    }

    private void marcarTrama() {
        lastFrameMs = System.currentTimeMillis();
        synchronized (stateLock) { stateLock.notifyAll(); }
    }

    // ───────────────────────── estado unificado ─────────────────────────

    public State getState() { return state; }

    /** Enlace realmente activo (sin ventana de gracia). */
    public boolean esEnlaceActivo() {
        return state == State.ON && (demo || elm != null);
    }

    /**
     * Estado unificado para el asistente y las vistas:
     * CONNECTED si hay enlace o si la última trama válida fue hace <= 12 s
     * (aunque el servicio esté reconectando).
     */
    public boolean isConnected() {
        if (esEnlaceActivo()) return true;
        if (demo) return true;
        if (state == State.OFF) return false;
        return lastFrameMs > 0 && (System.currentTimeMillis() - lastFrameMs) <= GRACE_MS;
    }

    public boolean isConnecting() { return state == State.CONNECTING || conectando; }

    public LinkState getLinkState() {
        if (isConnected()) return LinkState.CONNECTED;
        if (isConnecting()) return LinkState.CONNECTING;
        return LinkState.DISCONNECTED;
    }

    public long getLastFrameMs() { return lastFrameMs; }

    public boolean hasRecentValues(long maxAgeMs) {
        return lastFrameMs > 0 && (System.currentTimeMillis() - lastFrameMs) <= maxAgeMs;
    }

    /**
     * Espera hasta timeoutMs a que el estado unificado sea CONNECTED.
     * Se usa desde el asistente durante una reconexión (máx. 2.5 s).
     */
    public boolean esperarConexion(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (stateLock) {
            while (!isConnected() && System.currentTimeMillis() < deadline) {
                long rest = deadline - System.currentTimeMillis();
                if (rest <= 0) break;
                try {
                    stateLock.wait(Math.min(rest, 250));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return isConnected();
    }

    public String getError() { return error; }
    public String getAdapter() { return adapter; }
    public Set<Integer> getSupported() { return new HashSet<Integer>(supported); }
    public Map<String,Float> getValues() { synchronized(values){ return new HashMap<String,Float>(values); } }
    public Health.EngineReport getLastReport() { return lastReport; }
    public String getTargetAddress() { return targetAddress; }

    public java.util.List<Float> getHistory(String signal) {
        synchronized(history) {
            java.util.Deque<Float> q = history.get(signal);
            java.util.List<Float> o = new ArrayList<Float>();
            if (q != null) for (Float x : q) if (x != null) o.add(x);
            return o;
        }
    }

    public TripLog.AccelTimer getAccel() { return accel; }
    public TripLog.TripSummary getLastTrip() { return lastTrip; }

    // ───────────────────────── autoconexión ─────────────────────────

    /**
     * Autoconexión silenciosa. Mantiene 8 s de estabilización Bluetooth tras
     * el arranque (boot/ACC ON/primer arranque del proceso) antes del primer
     * intento, y usa un mutex para que solo haya un intento en curso.
     */
    public void iniciarAutoconexion(final Context context) {
        if (esEnlaceActivo() || isConnecting() || conectando) return;
        if (!autoEnCurso.compareAndSet(false, true)) return;
        final Context c = (context != null ? context : ctx).getApplicationContext();
        long wait = delayEstabilizacion();
        if (wait > 0) {
            Log.i(TAG, "Autoconexión OBD2 en " + wait + " ms (estabilización Bluetooth)");
        }
        try {
            AppExecutors.get().scheduler.schedule(new Runnable() {
                public void run() { resolverYConectar(c); }
            }, wait, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            autoEnCurso.set(false);
            Log.w(TAG, "No se pudo programar la autoconexión: " + t);
        }
    }

    /** Retardo de estabilización: 8 s desde boot, evento BOOT o inicio del proceso. */
    private long delayEstabilizacion() {
        long wait = 0;
        long sinceBoot = SystemClock.elapsedRealtime();
        if (sinceBoot < BOOT_DELAY_MS) wait = BOOT_DELAY_MS - sinceBoot;
        long sinceProcess = SystemClock.elapsedRealtime() - processStartElapsed;
        if (sinceProcess < BOOT_DELAY_MS) wait = Math.max(wait, BOOT_DELAY_MS - sinceProcess);
        try {
            long bootEvent = appPrefs.getLong("boot_event_ms", 0);
            if (bootEvent > 0) {
                long sinceEvent = System.currentTimeMillis() - bootEvent;
                if (sinceEvent >= 0 && sinceEvent < BOOT_DELAY_MS) {
                    wait = Math.max(wait, BOOT_DELAY_MS - sinceEvent);
                }
            }
        } catch (Throwable ignored) {}
        return wait;
    }

    private void resolverYConectar(final Context c) {
        try {
            if (esEnlaceActivo() || isConnecting() || conectando) {
                autoEnCurso.set(false);
                return;
            }
            String mac = targetOrDefault();
            String saved = readTarget();
            if (saved == null || saved.trim().length() == 0) {
                for (String[] d : pairedDevices()) {
                    String name = d[1] != null ? d[1].toUpperCase() : "";
                    if (name.contains("OBD") || name.contains("ELM327") || name.contains("VLINK") || name.contains("OBDII")) {
                        mac = d[0];
                        try {
                            prefs.edit().putString("type", "bt").putString("address", mac).putString("name", mac).apply();
                        } catch (Throwable ignored) {}
                        targetAddress = mac;
                        Log.i(TAG, "Adaptador OBD emparejado detectado: " + name + " [" + mac + "]");
                        break;
                    }
                }
            } else {
                mac = saved;
            }
            if (mac == null || mac.trim().length() == 0) {
                autoEnCurso.set(false);
                Log.w(TAG, "No hay adaptador OBD2 emparejado ni configurado.");
                return;
            }
            Log.i(TAG, "Autoconexión OBD2 -> " + mac);
            connect(mac);
            try {
                AppExecutors.get().scheduler.schedule(new Runnable() {
                    public void run() { verificarAuto(c); }
                }, 1800, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            autoEnCurso.set(false);
            Log.w(TAG, "Error en autoconexión: " + t);
            programarReconexion();
        }
    }

    private void verificarAuto(final Context c) {
        if (esEnlaceActivo()) {
            autoEnCurso.set(false);
            reconnectAttempt = 0;
            Log.i(TAG, "OBD2 conectado en segundo plano.");
            return;
        }
        if (isConnecting() || conectando) {
            try {
                AppExecutors.get().scheduler.schedule(new Runnable() {
                    public void run() { verificarAuto(c); }
                }, 1200, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
            return;
        }
        autoEnCurso.set(false);
        programarReconexion();
    }

    /** Backoff de reconexión real: 5 s -> 10 s -> 20 s -> 30 s. */
    private void programarReconexion() {
        if (esEnlaceActivo()) return;
        if (!reintentoProgramado.compareAndSet(false, true)) return;
        final int intento = ++reconnectAttempt;
        long delay = backoffSegundos(intento);
        Log.w(TAG, "OBD2 sin enlace. Reintento #" + intento + " en " + delay + " s.");
        try {
            AppExecutors.get().scheduler.schedule(new Runnable() {
                public void run() {
                    reintentoProgramado.set(false);
                    iniciarAutoconexion(ctx);
                }
            }, delay, TimeUnit.SECONDS);
        } catch (Throwable t) {
            reintentoProgramado.set(false);
            Log.w(TAG, "No se pudo programar el reintento OBD2: " + t);
        }
    }

    private static long backoffSegundos(int intento) {
        switch (intento) {
            case 1: return 5;
            case 2: return 10;
            case 3: return 20;
            default: return 30;
        }
    }

    // ───────────────────────── conexión ─────────────────────────

    private String readTarget() {
        String t = prefs.getString("type", null);
        if ("bt".equals(t)) return prefs.getString("address", DEFAULT_MAC);
        return null;
    }

    public String targetOrDefault() { String t = readTarget(); return t != null ? t : DEFAULT_MAC; }

    public List<String[]> pairedDevices() {
        List<String[]> out = new ArrayList<String[]>();
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null) return out;
            for (android.bluetooth.BluetoothDevice d : a.getBondedDevices()) {
                String name = d.getName() != null ? d.getName() : d.getAddress();
                out.add(new String[]{d.getAddress(), name});
            }
            java.util.Collections.sort(out, new java.util.Comparator<String[]>() {
                public int compare(String[] x, String[] y) {
                    boolean bx = x[1].matches("(?i).*(obd|elm|vlink|vgate|icar|obdlink|konnwei).*");
                    boolean by = y[1].matches("(?i).*(obd|elm|vlink|vgate|icar|obdlink|konnwei).*");
                    return (bx?-1:0) - (by?-1:0);
                }
            });
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * Solicita conexión. Es idempotente: si ya hay un enlace activo o una
     * conexión en curso, no crea otro socket. La creación del socket ocurre
     * en el hilo secuencial "obd-io" bajo el mutex de conexión.
     */
    public void connect(final String mac) {
        final int cg;
        synchronized (connectMutex) {
            if (conectando) {
                Log.d(TAG, "connect() ignorado: ya hay una conexión en curso");
                return;
            }
            if (esEnlaceActivo() && !demo && (mac == null || mac.equals(targetAddress))) {
                Log.d(TAG, "connect() ignorado: ya conectado a " + targetAddress);
                return;
            }
            cg = ++connectGeneration;
            conectando = true;
        }
        final String addr = mac != null ? mac : targetOrDefault();
        targetAddress = addr;
        try {
            prefs.edit().putString("type","bt").putString("address",addr).putString("name",addr).apply();
        } catch (Throwable ignored) {}
        running = false;
        generation++;
        autoEnCurso.set(false);
        Thread pt = pollThread;
        if (pt != null) pt.interrupt();
        cerrarEnlace();
        setState(State.CONNECTING, null);
        Log.i(TAG, "Conectando a ELM327 " + addr);
        try {
            io.execute(new Runnable() {
                public void run() { conectarAhora(addr, cg); }
            });
        } catch (Throwable t) {
            conectando = false;
            setState(State.ERROR, mensaje(t));
            programarReconexion();
        }
    }

    /** Se ejecuta en el hilo "obd-io": un único socket RFCOMM a la vez. */
    private void conectarAhora(final String addr, final int cg) {
        ElmLink l = null;
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
            if (a == null) throw new IOException("Bluetooth no disponible");
            if (!a.isEnabled()) throw new IOException("Bluetooth apagado");
            android.bluetooth.BluetoothDevice dev = a.getRemoteDevice(addr);
            l = new Links.BluetoothLink(dev);
            Elm327 e = new Elm327(l);
            String version = e.init();
            Set<Integer> pids = e.supportedPids();
            // El usuario pudo desconectar o activar demo durante el handshake.
            if (cg != connectGeneration) {
                try { l.close(); } catch (Throwable ignored) {}
                Log.i(TAG, "Handshake OBD2 cancelado por el usuario");
                return;
            }
            synchronized (lock) {
                link = l;
                elm = e;
                supported.clear();
                supported.addAll(pids);
                adapter = version;
            }
            lastFrameMs = System.currentTimeMillis();
            reconnectAttempt = 0;
            reintentoProgramado.set(false);
            setState(State.ON, null);
            Log.i(TAG, "ELM327 listo (" + version + "), " + pids.size() + " PIDs soportados");
            startPollThread(e, l);
        } catch (Exception ex) {
            try { if (l != null) l.close(); } catch (Throwable ignored) {}
            synchronized (lock) {
                if (link == l) link = null;
            }
            if (cg == connectGeneration && !esEnlaceActivo()) {
                setState(State.ERROR, mensaje(ex));
                Log.e(TAG, "Fallo al conectar ELM327: " + mensaje(ex));
                programarReconexion();
            }
        } finally {
            conectando = false;
        }
    }

    private void cerrarEnlace() {
        synchronized (lock) {
            try { if (link != null) link.close(); } catch (Throwable ignored) {}
            link = null;
            elm = null;
            supported.clear();
        }
    }

    private void startPollThread(final Elm327 e, final ElmLink l) {
        Thread old = pollThread;
        if (old != null && old.isAlive()) {
            running = false;
            old.interrupt();
        }
        final int gen = ++generation;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { pollLoop(e, l, gen); }
        }, "obd-poll");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        pollThread = t;
        t.start();
    }

    // ───────────────────────── polling secuencial ─────────────────────────

    private void pollLoop(final Elm327 e, final ElmLink l, final int gen) {
        int fallosSinPrompt = 0;
        Map<String,Float> last = new HashMap<String,Float>();
        Map<String,Float> smoothed = new HashMap<String,Float>();
        long lastSlow = 0;
        long lastLog = 0;
        try {
            while (running && gen == generation) {
                if (pausado) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                boolean slow = System.currentTimeMillis() - lastSlow > 2000;
                if (slow) lastSlow = System.currentTimeMillis();
                List<Pids.Pid> wanted = new ArrayList<Pids.Pid>();
                for (Pids.Pid p : Pids.ALL) if (p.fast || slow) wanted.add(p);
                Map<String,Float> batch = new HashMap<String,Float>();
                boolean algunaTrama = false;

                for (Pids.Pid p : wanted) {
                    if (!running || gen != generation) break;
                    while (pausado && running && gen == generation) {
                        try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    try {
                        final int code = p.code;
                        int[] data = ioCall(new Callable<int[]>() {
                            public int[] call() throws Exception { return e.pid(code); }
                        }, IO_TIMEOUT_MS);
                        // Cualquier respuesta que terminó en '>' (incluido NO DATA o '?') es un éxito de enlace.
                        fallosSinPrompt = 0;
                        if (data != null) {
                            Float v = null;
                            try { v = p.decode.decode(data); } catch (Throwable ignored) {}
                            if (v != null) {
                                last.put(p.signal, v);
                                batch.put(p.signal, v);
                                algunaTrama = true;
                            }
                        }
                    } catch (Elm327.ElmTimeoutException te) {
                        fallosSinPrompt++;
                        Log.w(TAG, "Timeout aislado en PID " + p.code + " (" + fallosSinPrompt + "/5)");
                        if (fallosSinPrompt >= 5) throw te;
                        // Se descarta la muestra y se sigue con el siguiente PID.
                    } catch (Elm327.ElmClosedException ce) {
                        throw ce;
                    } catch (java.io.IOException ioe) {
                        throw ioe;
                    } catch (Throwable t) {
                        // Parseo/NPE (NO DATA, STOPPED, vacío): se salta ese PID.
                    }
                }

                if (slow) {
                    try {
                        boolean hasPid42 = supported.contains(0x42);
                        if (!hasPid42) {
                            Float volt = ioCall(new Callable<Float>() {
                                public Float call() throws Exception { return e.voltage(); }
                            }, IO_TIMEOUT_MS);
                            if (volt != null) {
                                last.put(Pids.VOLTAGE, volt);
                                batch.put(Pids.VOLTAGE, volt);
                                algunaTrama = true;
                            }
                        }
                    } catch (Elm327.ElmTimeoutException ignored) {
                    } catch (Exception ignored) {}
                }

                // Derivados (consumo/boost con suavizado 0.2 como Derived.kt)
                for (Derived.DerivedSignal d : Derived.ALL) {
                    boolean need = true;
                    for (String n : d.needs) if (!last.containsKey(n)) need = false;
                    if (!need) continue;
                    Float raw = null;
                    try { raw = d.compute.compute(new HashMap<String,Float>(last)); } catch (Throwable ignored) { raw = null; }
                    Float prev = smoothed.get(d.signal);
                    Float val;
                    if (raw == null || prev == null || !(d.smoothing > 0f)) val = raw;
                    else {
                        float rp = raw;
                        float pp = prev;
                        val = pp + (rp - pp) * d.smoothing;
                    }
                    if (val != null) smoothed.put(d.signal, val); else smoothed.remove(d.signal);
                    batch.put(d.signal, val);
                }

                synchronized(values) {
                    for (Map.Entry<String,Float> entry : batch.entrySet()) {
                        if (entry.getValue() != null) values.put(entry.getKey(), entry.getValue());
                    }
                }
                synchronized(history) {
                    for (Map.Entry<String,Float> entry : batch.entrySet()) {
                        if (entry.getValue() == null) continue;
                        java.util.Deque<Float> q = history.get(entry.getKey());
                        if (q == null) { q = new java.util.ArrayDeque<Float>(); history.put(entry.getKey(), q); }
                        q.addLast(entry.getValue());
                        while (q.size() > 60) q.removeFirst();
                    }
                }
                if (algunaTrama) marcarTrama();
                if (tripRecorder == null) tripRecorder = new TripLog.TripRecorder(System.currentTimeMillis());
                Map<String,Float> cur = getValues();
                tripRecorder.add(System.currentTimeMillis(), cur.get(Pids.SPEED), cur.get(Pids.RPM), cur.get(Pids.COOLANT), cur.get(Pids.MAF), cur.get(Pids.FUEL_RATE));
                Float spd = cur.get(Pids.SPEED);
                if (spd != null) accel.update(System.currentTimeMillis(), spd);
                notifyValues(getValues());

                long now = System.currentTimeMillis();
                if (now - lastLog > 5000) {
                    lastLog = now;
                    Log.d(TAG, "Polling OBD2 activo: " + batch.size() + " señales, estado=" + state + ", fallos=" + fallosSinPrompt);
                }
            }
        } catch (Exception ex) {
            ElmLink actual;
            synchronized (lock) { actual = link; }
            if (running && gen == generation && actual == l) {
                running = false;
                Log.e(TAG, "Caída real del enlace OBD2: " + mensaje(ex));
                synchronized (lock) {
                    try { if (link == l) link.close(); } catch (Throwable ignored) {}
                    if (link == l) link = null;
                    // No se borra elm: conserva la ventana de gracia de 12 s.
                }
                setState(State.ERROR, mensaje(ex));
                programarReconexion();
            }
        }
    }

    // ───────────────────────── informes / comandos ─────────────────────────

    /** Ejecuta un comando en el hilo secuencial y espera el resultado. */
    private <T> T ioCall(Callable<T> c, long timeoutMs) throws Exception {
        Future<T> f;
        try {
            f = io.submit(c);
        } catch (RejectedExecutionException re) {
            throw new IOException("OBD detenido");
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException(String.valueOf(cause));
        } catch (TimeoutException te) {
            f.cancel(true);
            throw new Elm327.ElmTimeoutException("Tiempo de espera agotado (" + timeoutMs + " ms)");
        } catch (InterruptedException ie) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            throw new Elm327.ElmClosedException("Lectura interrumpida");
        }
    }

    public Health.EngineReport report() throws Exception {
        pausado = true;
        try {
            return ioCall(new Callable<Health.EngineReport>() {
                public Health.EngineReport call() throws Exception {
                    Elm327 e;
                    synchronized (lock) { e = elm; }
                    if (e == null) throw new IllegalStateException("Sin conexión");
                    Pids.Readiness readiness = null;
                    try { int[] d = e.pid(0x01); if (d != null) readiness = Pids.parseReadiness(d); } catch (Exception ignored) {}
                    List<String> stored = e.troubleCodes("03");
                    List<String> pending = e.troubleCodes("07");
                    List<String> permanent;
                    try { permanent = e.troubleCodes("0A"); } catch (Exception ex) { permanent = new ArrayList<String>(); }
                    String vin = null;
                    try { vin = e.vin(); } catch (Exception ignored) {}
                    Float volt = null;
                    try { volt = e.voltage(); } catch (Exception ignored) {}
                    lastReport = new Health.EngineReport(readiness, stored, pending, permanent, vin, volt);
                    return lastReport;
                }
            }, 30000);
        } finally {
            pausado = false;
        }
    }

    public boolean clearCodes() throws Exception {
        pausado = true;
        try {
            return ioCall(new Callable<Boolean>() {
                public Boolean call() throws Exception {
                    Elm327 e;
                    synchronized (lock) { e = elm; }
                    if (e == null) return false;
                    return e.clearCodes();
                }
            }, 15000);
        } finally {
            pausado = false;
        }
    }

    /** Desconexión manual explícita (solo desde el botón del adaptador). */
    public void disconnect() {
        running = false;
        generation++;
        connectGeneration++;
        autoEnCurso.set(false);
        reintentoProgramado.set(false);
        reconnectAttempt = 0;
        Thread pt = pollThread;
        if (pt != null) pt.interrupt();
        cerrarEnlace();
        lastFrameMs = 0;
        setState(State.OFF, null);
        synchronized(values) { values.clear(); }
        synchronized(history) { history.clear(); }
        notifyValues(new HashMap<String,Float>());
        Log.i(TAG, "OBD2 desconectado manualmente");
    }

    private static String mensaje(Throwable t) {
        if (t == null) return "Error desconocido";
        String m = t.getMessage();
        return (m != null && m.length() > 0) ? m : t.toString();
    }

    // ───────────────────────── modo demo ─────────────────────────

    private volatile boolean demo;
    private Thread demoThread;

    public boolean isDemo() { return demo; }

    public void setDemo(boolean on) {
        if (demo == on) return;
        demo = on;
        if (on) {
            running = false;
            generation++;
            connectGeneration++;
            Thread pt = pollThread;
            if (pt != null) pt.interrupt();
            cerrarEnlace();
            lastFrameMs = System.currentTimeMillis();
            synchronized(lock) { supported.clear(); for (Pids.Pid p : Pids.ALL) supported.add(p.code); }
            adapter = "Demo ELM327";
            setState(State.ON, null);
            if (demoThread != null) demoThread.interrupt();
            demoThread = new Thread(new Runnable() {
                public void run() {
                    int tick = 0;
                    java.util.Random rnd = new java.util.Random(42L);
                    int runTime = 420;
                    while (demo && state == State.ON) {
                        tick++;
                        runTime += 2;
                        Map<String,Float> batch = new HashMap<String,Float>();
                        float rpm = 850f + (float)(rnd.nextGaussian() * 25f);
                        float coolant = Math.min(118f, 108f + tick * 0.5f);
                        batch.put(Pids.RPM, rpm);
                        batch.put(Pids.COOLANT, coolant);
                        batch.put(Pids.INTAKE, 34f);
                        batch.put(Pids.VOLTAGE, 11.3f);
                        batch.put(Pids.FUEL, 68f);
                        batch.put(Pids.SPEED, 0f);
                        batch.put(Pids.LOAD, 24f);
                        batch.put(Pids.MAP, 32f);
                        batch.put(Pids.MAF, 2.2f);
                        batch.put(Pids.THROTTLE, 0f);
                        batch.put(Pids.FUEL_TRIM_SHORT, 4f);
                        batch.put(Pids.FUEL_TRIM_LONG, 22f);
                        batch.put(Pids.TIMING, 10f);
                        batch.put(Pids.RUN_TIME, (float) runTime);
                        batch.put(Pids.O2S11, 0.45f);
                        batch.put(Pids.O2S12, 0.44f);
                        batch.put(Pids.BARO, 101f);
                        batch.put(Pids.AMBIENT, 19f);
                        batch.put(Pids.CAT_TEMP, 640f + coolant * 2f);
                        batch.put(Pids.FUEL_RATE, 1.4f);
                        synchronized(values) {
                            for (Map.Entry<String,Float> entry : batch.entrySet()) {
                                if (entry.getValue() != null) values.put(entry.getKey(), entry.getValue());
                            }
                        }
                        synchronized(history) {
                            for (Map.Entry<String,Float> entry : batch.entrySet()) {
                                if (entry.getValue() == null) continue;
                                java.util.Deque<Float> q = history.get(entry.getKey());
                                if (q == null) { q = new java.util.ArrayDeque<Float>(); history.put(entry.getKey(), q); }
                                q.addLast(entry.getValue());
                                while (q.size() > 60) q.removeFirst();
                            }
                        }
                        marcarTrama();
                        notifyValues(getValues());
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                }
            });
            demoThread.start();
        } else {
            if (demoThread != null) demoThread.interrupt();
            synchronized(values) { values.clear(); }
            synchronized(history) { history.clear(); }
            lastFrameMs = 0;
            setState(State.OFF, null);
            notifyValues(new HashMap<String,Float>());
        }
    }
}
