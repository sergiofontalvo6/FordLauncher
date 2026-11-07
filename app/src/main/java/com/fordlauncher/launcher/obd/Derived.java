package com.fordlauncher.launcher.obd;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Señales derivadas, portadas de Derived.kt (LiteDash). */
public final class Derived {
    private Derived() {}
    private static final boolean DEBUG = false;
    public static class DerivedSignal {
        public final String signal; public final List<String> needs, anyOf, extras; public final float smoothing;
        public interface Compute { Float compute(Map<String,Float> v); }
        public final Compute compute;
        public DerivedSignal(String s, List<String> n, List<String> any, List<String> ex, float sm, Compute c){signal=s;needs=n;anyOf=any;extras=ex;smoothing=sm;compute=c;}
    }
    private static final float AIR_FUEL = 14.7f;
    private static final float FUEL_GRAMS_PER_LITER = 737f;
    private static final float SEA_LEVEL_KPA = 101.3f;
    public static Float consumption(float mafGramsPerSecond, float speedKmh) {
        if (speedKmh < 5f || mafGramsPerSecond <= 0f) return null;
        float litersPerHour = mafGramsPerSecond * 3600f / (AIR_FUEL * FUEL_GRAMS_PER_LITER);
        return speedKmh / litersPerHour;
    }
    public static final List<DerivedSignal> ALL = new ArrayList<DerivedSignal>();
    static {
        ALL.add(new DerivedSignal(Pids.CONSUMPTION,
            java.util.Arrays.asList(Pids.SPEED), java.util.Arrays.asList(Pids.FUEL_RATE, Pids.MAF), new ArrayList<String>(), 0.2f,
            v -> {
                Float speed = v.get(Pids.SPEED);
                if (speed == null) return null;
                Float lph = v.get(Pids.FUEL_RATE);
                if (lph != null && lph > 0f) return speed >= 5f ? speed / lph : null;
                Float maf = v.get(Pids.MAF);
                return maf != null ? consumption(maf, speed) : null;
            }));
        ALL.add(new DerivedSignal(Pids.BOOST,
            java.util.Arrays.asList(Pids.MAP), new ArrayList<String>(), java.util.Arrays.asList(Pids.BARO), 1f,
            v -> {
                Float map = v.get(Pids.MAP);
                if (map == null) return null;
                Float baro = v.get(Pids.BARO);
                return map - (baro != null ? baro : SEA_LEVEL_KPA);
            }));
    }
}
