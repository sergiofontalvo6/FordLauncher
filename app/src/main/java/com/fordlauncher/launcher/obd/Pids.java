package com.fordlauncher.launcher.obd;

import java.util.ArrayList;
import java.util.List;

/** Catálogo Modo 01 SAE J1979, portado fiel de Pids.kt (LiteDash). */
public final class Pids {
    private Pids() {}
    public static final String SPEED="speed", RPM="rpm", COOLANT="coolant_temp", FUEL="fuel", VOLTAGE="voltage",
        INTAKE="intake_temp", LOAD="load", MAP="intake_pressure", BARO="barometric_pressure", AMBIENT="ambient_temp",
        MAF="maf", FUEL_TRIM_SHORT="fuel_trim_short", FUEL_TRIM_LONG="fuel_trim_long", FUEL_PRESSURE="fuel_pressure",
        TIMING="timing_advance", THROTTLE="throttle", RUN_TIME="run_time", DISTANCE_MIL="distance_mil", OIL="oil_temp",
        FUEL_RATE="fuel_rate", CONSUMPTION="consumption", BOOST="boost",
        O2S11="o2_b1s1", O2S12="o2_b1s2", CAT_TEMP="cat_temp_b1s1";

    public interface Decoder { Float decode(int[] b); }

    public static class Pid {
        public final int code;
        public final String signal;
        public final boolean fast;
        public final Decoder decode;
        public Pid(int code, String signal, boolean fast, Decoder decode){ this.code=code; this.signal=signal; this.fast=fast; this.decode=decode; }
    }

    private static Float a(int[] b){ return b.length>0? (float)b[0] : null; }
    private static Float ab(int[] b){ return b.length>=2? (float)(b[0]*256+b[1]) : null; }

    public static final List<Pid> ALL = new ArrayList<Pid>();
    static {
        ALL.add(new Pid(0x0D, SPEED, true, b -> a(b)));
        ALL.add(new Pid(0x0C, RPM, true, b -> ab(b)!=null? ab(b)/4f : null));
        ALL.add(new Pid(0x04, LOAD, true, b -> a(b)!=null? a(b)*100f/255f : null));
        ALL.add(new Pid(0x0B, MAP, true, b -> a(b)));
        ALL.add(new Pid(0x10, MAF, true, b -> ab(b)!=null? ab(b)/100f : null));
        ALL.add(new Pid(0x05, COOLANT, false, b -> a(b)!=null? a(b)-40f : null));
        ALL.add(new Pid(0x0F, INTAKE, false, b -> a(b)!=null? a(b)-40f : null));
        ALL.add(new Pid(0x2F, FUEL, false, b -> a(b)!=null? a(b)*100f/255f : null));
        ALL.add(new Pid(0x42, VOLTAGE, false, b -> ab(b)!=null? ab(b)/1000f : null));
        ALL.add(new Pid(0x33, BARO, false, b -> a(b)));
        ALL.add(new Pid(0x46, AMBIENT, false, b -> a(b)!=null? a(b)-40f : null));
        ALL.add(new Pid(0x11, THROTTLE, true, b -> a(b)!=null? a(b)*100f/255f : null));
        ALL.add(new Pid(0x5E, FUEL_RATE, true, b -> ab(b)!=null? ab(b)/20f : null));
        ALL.add(new Pid(0x06, FUEL_TRIM_SHORT, false, b -> a(b)!=null? (a(b)-128)*100f/128f : null));
        ALL.add(new Pid(0x07, FUEL_TRIM_LONG, false, b -> a(b)!=null? (a(b)-128)*100f/128f : null));
        ALL.add(new Pid(0x0A, FUEL_PRESSURE, false, b -> a(b)!=null? a(b)*3f : null));
        ALL.add(new Pid(0x0E, TIMING, false, b -> a(b)!=null? a(b)/2f-64f : null));
        ALL.add(new Pid(0x1F, RUN_TIME, false, b -> ab(b)));
        ALL.add(new Pid(0x21, DISTANCE_MIL, false, b -> ab(b)));
        ALL.add(new Pid(0x5C, OIL, false, b -> a(b)!=null? a(b)-40f : null));
        ALL.add(new Pid(0x14, O2S11, true, b -> a(b)!=null? a(b)/200f : null));
        ALL.add(new Pid(0x15, O2S12, true, b -> a(b)!=null? a(b)/200f : null));
        ALL.add(new Pid(0x3C, CAT_TEMP, false, b -> ab(b)!=null? ab(b)/10f-40f : null));
    }

    public static class Monitor { public final String name; public final boolean complete; public Monitor(String n, boolean c){name=n;complete=c;} }
    public static class Readiness {
        public final boolean mil; public final int codes; public final List<Monitor> monitors;
        public Readiness(boolean m, int c, List<Monitor> ms){mil=m;codes=c;monitors=ms;}
        public List<Monitor> incomplete(){ List<Monitor> o=new ArrayList<Monitor>(); for(Monitor m:monitors) if(!m.complete) o.add(m); return o; }
    }
    public static Readiness parseReadiness(int[] d) {
        if (d.length < 4) return null;
        int a=d[0], b=d[1], c=d[2], e=d[3];
        List<Monitor> m = new ArrayList<Monitor>();
        String[] bNames = {"MISFIRE","FUEL","COMPONENTS"};
        for (int i=0;i<3;i++) if ((b & (1<<i))!=0) m.add(new Monitor(bNames[i], (b & (1<<(i+4)))==0));
        if ((b & 0x08)==0) {
            int[] bits={0,1,2,3,5,6,7}; String[] names={"CATALYST","HEATED_CATALYST","EVAP","SECONDARY_AIR","O2_SENSOR","O2_HEATER","EGR"};
            for (int i=0;i<bits.length;i++) if ((c & (1<<bits[i]))!=0) m.add(new Monitor(names[i], (e & (1<<bits[i]))==0));
        }
        return new Readiness((a & 0x80)!=0, a & 0x7F, m);
    }
}
