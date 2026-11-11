package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Integra la distancia recorrida (km) a partir de la velocidad (OBD2 o GPS)
 * y persiste el odómetro en disco, notificando a la UI cada 100 m.
 */
public final class OilLifeManager {

    public interface Listener { void onOdometerChanged(float odometroKm); }

    private static final String TAG = "FordLauncher/OilLife";
    private static final String PREFS = "launcher";
    private static final String KEY_ODO = "odometro_actual_km";
    private static final String KEY_OIL = "oil_current_odometer";
    private static final String KEY_ODO_TOTAL = "odo_km_total";

    private static volatile OilLifeManager instance;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private long lastTimestampMs = 0L;
    private double kmAcumuladosSesion = 0.0;

    private OilLifeManager(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences(PREFS, 0);
    }

    public static synchronized OilLifeManager getInstance(Context c) {
        if (instance == null) instance = new OilLifeManager(c);
        return instance;
    }

    public void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized void onSpeedUpdate(float speedKmh) {
        long now = System.currentTimeMillis();
        if (lastTimestampMs == 0L) {
            lastTimestampMs = now;
            if (speedKmh == 0f) flushRemanente();
            return;
        }

        long deltaMs = now - lastTimestampMs;
        lastTimestampMs = now;

        // Detenido o sin lectura útil: volcar remanente.
        if (speedKmh < 2.0f) {
            flushRemanente();
            if (speedKmh == 0f) lastTimestampMs = 0L;
            return;
        }
        if (deltaMs <= 0 || deltaMs > 10000) {
            return;
        }

        double deltaKm = speedKmh * (deltaMs / 3600000.0);
        kmAcumuladosSesion += deltaKm;

        if (kmAcumuladosSesion >= 0.1) {
            float odometroActual = getOdometro();
            odometroActual += (float) kmAcumuladosSesion;
            kmAcumuladosSesion = 0.0;
            setOdometro(odometroActual);
            for (Listener l : listeners) {
                try { l.onOdometerChanged(odometroActual); } catch (Throwable ignored) {}
            }
        }
    }

    /** Volcar cualquier remanente (<0.1 km) a disco. */
    public synchronized void flush() {
        if (kmAcumuladosSesion <= 0.0) return;
        float odometroActual = getOdometro();
        odometroActual += (float) kmAcumuladosSesion;
        kmAcumuladosSesion = 0.0;
        setOdometro(odometroActual);
        for (Listener l : listeners) {
            try { l.onOdometerChanged(odometroActual); } catch (Throwable ignored) {}
        }
    }

    private void flushRemanente() {
        if (kmAcumuladosSesion > 0.0) flush();
    }

    public float getOdometro() {
        try {
            float v = prefs.getFloat(KEY_ODO, -1f);
            if (v >= 0f) return v;
        } catch (ClassCastException e) {
            // fall through
        } catch (Throwable t) {
            return 0f;
        }
        try { return prefs.getInt(KEY_OIL, prefs.getInt(KEY_ODO_TOTAL, 0)); } catch (Throwable t) { return 0f; }
    }

    private void setOdometro(float v) {
        try {
            prefs.edit()
                    .putFloat(KEY_ODO, v)
                    .putInt(KEY_OIL, (int) v)
                    .putInt(KEY_ODO_TOTAL, (int) v)
                    .apply();
        } catch (Throwable t) {
            Log.w(TAG, "persist: " + t);
        }
    }
}
