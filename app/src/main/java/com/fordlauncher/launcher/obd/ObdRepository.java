package com.fordlauncher.launcher.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repositorio OBD (port fiel de feature/obd/ObdRepository.kt de LiteDash, sin Compose/coroutines).
 * Estado de conexión, MAC guardada en SharedPreferences "obd" y polling 10 Hz / 2 s.
 * Por defecto usa la MAC del adaptador del usuario '00:10:CC:4F:36:03'.
 */
public class ObdRepository {
    public static final String DEFAULT_MAC = "00:10:CC:4F:36:03";
    public static final String PREFS = "obd";
    public enum State { OFF, CONNECTING, ON, ERROR }

    public interface Listener {
        void onState(State s, String err);
        void onValues(Map<String,Float> v);
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private State state = State.OFF;
    private String error;
    private String adapter;
    private final Set<Integer> supported = new HashSet<Integer>();
    private final Map<String,Float> values = new HashMap<String,Float>();
    private Listener listener;
    private final Object lock = new Object();
    private ElmLink link;
    private Elm327 elm;
    private Thread pollThread;
    private volatile boolean running;
    private Health.EngineReport lastReport;
    private volatile String targetAddress;
    private final java.util.Map<String, java.util.Deque<Float>> history = new java.util.HashMap<String, java.util.Deque<Float>>();
    private TripLog.TripRecorder tripRecorder;
    private TripLog.TripSummary lastTrip;
    private final TripLog.AccelTimer accel = new TripLog.AccelTimer();

    public ObdRepository(Context c) { ctx = c.getApplicationContext(); prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); targetAddress = readTarget(); }
    public void setListener(Listener l) { listener = l; }

    public State getState() { return state; }

    /** Verdadero solo con adaptador enlazado (o modo demo) y sin error de E/S. */
    public boolean isConnected() { return state == State.ON && (demo || elm != null); }
    public String getError() { return error; }
    public String getAdapter() { return adapter; }
    public Set<Integer> getSupported() { return new HashSet<Integer>(supported); }
    public Map<String,Float> getValues() { synchronized(values){ return new HashMap<String,Float>(values); } }
    public Health.EngineReport getLastReport() { return lastReport; }
    public String getTargetAddress() { return targetAddress; }
    public java.util.List<Float> getHistory(String signal) {
        synchronized(history) {
            java.util.Deque<Float> q = history.get(signal);
            java.util.List<Float> o = new ArrayList<Float>(); if (q != null) for (Float x : q) if (x != null) o.add(x); return o;
        }
    }
    public TripLog.AccelTimer getAccel() { return accel; }
    public TripLog.TripSummary getLastTrip() { return lastTrip; }

    private void setState(State s, String err) { state = s; error = err; if (listener != null) listener.onState(s, err); }

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
            // ordenar OBD primero (misma heurística que LiteDash OBD_NAMES)
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

    public synchronized void connect(final String mac) {
        if (state == State.CONNECTING) return;
        String addr = mac != null ? mac : targetOrDefault();
        targetAddress = addr;
        prefs.edit().putString("type","bt").putString("address",addr).putString("name",addr).apply();
        setState(State.CONNECTING, null);
        running = false;
        if (pollThread != null) pollThread.interrupt();
        new Thread(new Runnable() {
            public void run() {
                ElmLink l = null;
                try {
                    BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                    BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
                    if (a == null) throw new java.io.IOException("Bluetooth no disponible");
                    android.bluetooth.BluetoothDevice dev = a.getRemoteDevice(addr);
                    l = new Links.BluetoothLink(dev);
                    Elm327 e = new Elm327(l);
                    String version;
                    synchronized(lock) { version = e.init(); }
                    Set<Integer> pids;
                    synchronized(lock) { pids = e.supportedPids(); }
                    synchronized(lock) {
                        link = l; elm = e; supported.clear(); supported.addAll(pids); adapter = version;
                    }
                    setState(State.ON, null);
                    pollLoop();
                } catch (Exception ex) {
                    try { if (l != null) l.close(); } catch (Throwable ignored) {}
                    setState(State.ERROR, ex.getMessage() != null ? ex.getMessage() : ex.toString());
                    synchronized(lock) { values.clear(); }
                    if (listener != null) listener.onValues(new HashMap<String,Float>());
                }
            }
        }).start();
    }

    private void pollLoop() {
        running = true;
        int failures = 0;
        Map<String,Float> last = new HashMap<String,Float>();
        Map<String,Float> smoothed = new HashMap<String,Float>();
        long lastSlow = 0;
        pollThread = Thread.currentThread();
        try {
            while (running && elm != null) {
                boolean slow = System.currentTimeMillis() - lastSlow > 2000;
                if (slow) lastSlow = System.currentTimeMillis();
                List<Pids.Pid> wanted = new ArrayList<Pids.Pid>();
                for (Pids.Pid p : Pids.ALL) if (p.fast || slow) wanted.add(p);
                Map<String,Float> batch = new HashMap<String,Float>();
                for (Pids.Pid p : wanted) {
                    if (!running) break;
                    try {
                        int[] data;
                        synchronized(lock) { data = elm.pid(p.code); }
                        Float v = data != null ? p.decode.decode(data) : null;
                        if (v != null) { last.put(p.signal, v); }
                        batch.put(p.signal, v);
                        failures = 0;
                    } catch (java.io.IOException e) {
                        if (++failures >= 3) throw e;
                    } catch (Throwable ignored) {}
                    // NPE/parse (NO DATA, STOPPED, vacio): se salta ese PID esta vuelta, sin desconectar
                }
                if (slow) {
                    try {
                        boolean hasPid42 = supported.contains(0x42);
                        if (!hasPid42) {
                            Float volt;
                            synchronized(lock) { volt = elm.voltage(); }
                            if (volt != null) { last.put(Pids.VOLTAGE, volt); batch.put(Pids.VOLTAGE, volt); }
                        }
                    } catch (Exception ignored) {}
                }
                // derivados (consumo/boost con suavizado 0.2 como Derived.kt)
                for (Derived.DerivedSignal d : Derived.ALL) {
                    boolean need = true;
                    for (String n : d.needs) if (!last.containsKey(n)) need = false;
                    if (!need) continue;
                    Float raw = null; try { raw = d.compute.compute(new HashMap<String,Float>(last)); } catch (Throwable ignored) { raw = null; }
                    Float prev = smoothed.get(d.signal);
                    Float val;
                    if (raw == null || prev == null || !(d.smoothing > 0f)) val = raw;
                    else { float rp = raw; float pp = prev; val = pp + (rp - pp) * d.smoothing; }
                    if (val != null) smoothed.put(d.signal, val); else smoothed.remove(d.signal);
                    batch.put(d.signal, val);
                }
                synchronized(values) { for (Map.Entry<String,Float> e : batch.entrySet()) { if (e.getValue() != null) values.put(e.getKey(), e.getValue()); } }
                synchronized(history) {
                    for (Map.Entry<String,Float> e : batch.entrySet()) {
                        if (e.getValue() == null) continue;
                        java.util.Deque<Float> q = history.get(e.getKey());
                        if (q == null) { q = new java.util.ArrayDeque<Float>(); history.put(e.getKey(), q); }
                        q.addLast(e.getValue());
                        while (q.size() > 60) q.removeFirst();
                    }
                }
                if (tripRecorder == null) tripRecorder = new TripLog.TripRecorder(System.currentTimeMillis());
                Map<String,Float> cur = getValues();
                tripRecorder.add(System.currentTimeMillis(), cur.get(Pids.SPEED), cur.get(Pids.RPM), cur.get(Pids.COOLANT), cur.get(Pids.MAF), cur.get(Pids.FUEL_RATE));
                Float spd = cur.get(Pids.SPEED);
                if (spd != null) accel.update(System.currentTimeMillis(), spd);
                if (listener != null) listener.onValues(getValues());
                Thread.sleep(60);
            }
        } catch (Exception e) {
            if (running) {
                synchronized(lock) {
                    try { if (link != null) link.close(); } catch (Throwable ignored) {}
                    link = null; elm = null;
                }
                running = false;
                setState(State.ERROR, e.getMessage() != null ? e.getMessage() : "Se perdió la conexión con el adaptador");
            }
        }
    }

    public Health.EngineReport report() throws Exception {
        Elm327 e;
        synchronized(lock) { e = elm; }
        if (e == null) throw new IllegalStateException("Sin conexión");
        Pids.Readiness readiness = null;
        try { int[] d; synchronized(lock){ d = e.pid(0x01); } if (d != null) readiness = Pids.parseReadiness(d); } catch (Exception ignored) {}
        List<String> stored, pending, permanent;
        synchronized(lock) { stored = e.troubleCodes("03"); }
        synchronized(lock) { pending = e.troubleCodes("07"); }
        try { synchronized(lock){ permanent = e.troubleCodes("0A"); } } catch(Exception ex){ permanent = new ArrayList<String>(); }
        String vin = null; try { synchronized(lock){ vin = e.vin(); } } catch(Exception ignored) {}
        Float volt = null; try { synchronized(lock){ volt = e.voltage(); } } catch(Exception ignored) {}
        lastReport = new Health.EngineReport(readiness, stored, pending, permanent, vin, volt);
        return lastReport;
    }

    public boolean clearCodes() throws Exception {
        synchronized(lock) { if (elm == null) return false; return elm.clearCodes(); }
    }

    public void disconnect() {
        running = false;
        if (pollThread != null) pollThread.interrupt();
        synchronized(lock) {
            try { if (link != null) link.close(); } catch (Throwable ignored) {}
            link = null; elm = null;
        }
        setState(State.OFF, null);
        synchronized(values) { values.clear(); }
        if (listener != null) listener.onValues(new HashMap<String,Float>());
    }

    // ---------- Modo demo: sensores simulados (sin adaptador) ----------
    private volatile boolean demo;
    private Thread demoThread;

    public boolean isDemo() { return demo; }

    public void setDemo(boolean on) {
        if (demo == on) return;
        demo = on;
        if (on) {
            synchronized(lock) { supported.clear(); for (Pids.Pid p : Pids.ALL) supported.add(p.code); }
            adapter = "Demo ELM327";
            setState(State.ON, null);
            if (demoThread != null) demoThread.interrupt();
            demoThread = new Thread(new Runnable() {
                public void run() {
                    long t0 = System.currentTimeMillis();
                    int tick = 0;
                    java.util.Random rnd = new java.util.Random(42L);
                    int runTime = 420;
                    while (demo && state == State.ON) {
                        tick++;
                        runTime += 2;
                        Map<String,Float> batch = new HashMap<String,Float>();
                        float rpm = 850f + (float)(rnd.nextGaussian() * 25f);
                        float coolant = Math.min(118f, 108f + tick * 0.5f); // anomalía: sube hacia sobrecalentamiento
                        batch.put(Pids.RPM, rpm);
                        batch.put(Pids.COOLANT, coolant);
                        batch.put(Pids.INTAKE, 34f);
                        batch.put(Pids.VOLTAGE, 11.3f); // anomalía: batería baja
                        batch.put(Pids.FUEL, 68f);
                        batch.put(Pids.SPEED, 0f);
                        batch.put(Pids.LOAD, 24f);
                        batch.put(Pids.MAP, 32f);
                        batch.put(Pids.MAF, 2.2f);
                        batch.put(Pids.THROTTLE, 0f);
                        batch.put(Pids.FUEL_TRIM_SHORT, 4f);
                        batch.put(Pids.FUEL_TRIM_LONG, 22f); // anomalía: mezcla pobre
                        batch.put(Pids.TIMING, 10f);
                        batch.put(Pids.RUN_TIME, (float) runTime);
                        batch.put(Pids.O2S11, 0.45f); // anomalía: O2 pegado
                        batch.put(Pids.O2S12, 0.44f);
                        batch.put(Pids.BARO, 101f);
                        batch.put(Pids.AMBIENT, 19f);
                        batch.put(Pids.CAT_TEMP, 640f + coolant * 2f);
                        batch.put(Pids.FUEL_RATE, 1.4f);
                        synchronized(values) { for (Map.Entry<String,Float> e : batch.entrySet()) if (e.getValue() != null) values.put(e.getKey(), e.getValue()); }
                        synchronized(history) {
                            for (Map.Entry<String,Float> e : batch.entrySet()) {
                                if (e.getValue() == null) continue;
                                java.util.Deque<Float> q = history.get(e.getKey());
                                if (q == null) { q = new java.util.ArrayDeque<Float>(); history.put(e.getKey(), q); }
                                q.addLast(e.getValue());
                                while (q.size() > 60) q.removeFirst();
                            }
                        }
                        if (listener != null) listener.onValues(getValues());
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                }
            });
            demoThread.start();
        } else {
            if (demoThread != null) demoThread.interrupt();
            synchronized(values) { values.clear(); }
            synchronized(history) { history.clear(); }
            setState(elm != null ? state : State.OFF, null);
            if (listener != null) listener.onValues(new HashMap<String,Float>());
        }
    }
}
