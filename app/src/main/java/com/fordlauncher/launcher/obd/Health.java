package com.fordlauncher.launcher.obd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Zonas y salud del motor, portadas de Health.kt (LiteDash). */
public final class Health {
    private Health() {}
    public enum Zone { ENGINE(0.5f,0.17f), INTAKE(0.69f,0.14f), COOLING(0.5f,0.065f), BATTERY(0.31f,0.14f), TRANSMISSION(0.5f,0.33f), EXHAUST(0.5f,0.9f), WHEELS(0.12f,0.24f), BODY(0.5f,0.62f);
        public final float x,y; Zone(float x,float y){this.x=x;this.y=y;} }
    public enum HealthLevel { GOOD, WARN, FAULT, UNKNOWN }
    public static class SystemHealth {
        public final Zone zone; public final HealthLevel level; public final List<String> codes; public final List<String> pendingCodes; public final String alert; public final Float value;
        public SystemHealth(Zone z, HealthLevel l, List<String> c, List<String> p, String a, Float v){zone=z;level=l;codes=c;pendingCodes=p;alert=a;value=v;}
    }
    public static class EngineReport {
        public final Pids.Readiness readiness; public final List<String> stored, pending, permanent; public final String vin; public final Float voltage;
        public EngineReport(Pids.Readiness r, List<String> s, List<String> p, List<String> pe, String v, Float volt){readiness=r;stored=s;pending=p;permanent=pe;vin=v;voltage=volt;}
    }
    private static final Set<String> COOLING_CODES = new HashSet<String>(java.util.Arrays.asList("P0115","P0116","P0117","P0118","P0119","P0125","P0126","P0128","P0217"));
    private static final Set<String> BATTERY_CODES = new HashSet<String>(java.util.Arrays.asList("P0560","P0561","P0562","P0563","P0620","P0621","P0622","P0625","P0626"));
    public static Zone zoneOf(String code){
        if (COOLING_CODES.contains(code)) return Zone.COOLING;
        if (BATTERY_CODES.contains(code)) return Zone.BATTERY;
        if (isOxygenSensor(code)) return Zone.EXHAUST;
        if (isTurbo(code)) return Zone.INTAKE;
        if (code.startsWith("C")) return Zone.WHEELS;
        if (code.startsWith("B")||code.startsWith("U")) return Zone.BODY;
        char t = code.length()>2? code.charAt(2) : '?';
        if (t=='1'||t=='2') return Zone.INTAKE;
        if (t=='4') return Zone.EXHAUST;
        if (t=='7'||t=='8'||t=='9') return Zone.TRANSMISSION;
        return Zone.ENGINE;
    }
    private static Integer genericNumber(String code){
        if (!code.startsWith("P0")||code.length()<4) return null;
        try { return Integer.parseInt(code.substring(2),16); } catch(Exception e){return null;}
    }
    private static boolean isOxygenSensor(String code){ Integer n=genericNumber(code); return n!=null && ((n>=0x030&&n<=0x032)||(n>=0x036&&n<=0x038)||(n>=0x040&&n<=0x044)||(n>=0x050&&n<=0x064)||(n>=0x130&&n<=0x167)); }
    private static boolean isTurbo(String code){ Integer n=genericNumber(code); return n!=null && ((n>=0x033&&n<=0x035)||(n>=0x039&&n<=0x03F)||(n>=0x045&&n<=0x04F)); }
    public static List<SystemHealth> healthOf(EngineReport report, String alert, Float coolantC, Float voltage, Float rpm){
        List<String> stored = new ArrayList<String>();
        if (report!=null){ stored.addAll(report.stored); stored.addAll(report.permanent); }
        List<String> storedD = distinct(stored);
        List<String> pending = new ArrayList<String>();
        if (report!=null) for(String p: report.pending) if(!storedD.contains(p)) pending.add(p);
        List<SystemHealth> out = new ArrayList<SystemHealth>();
        for(Zone zone: Zone.values()){
            List<String> codes = filterZone(storedD, zone);
            List<String> pend = filterZone(pending, zone);
            boolean hot = "HOT".equals(alert) && zone==Zone.COOLING;
            String alertHere = hot? alert : null;
            Float value = null;
            if (zone==Zone.COOLING) value=coolantC;
            else if (zone==Zone.BATTERY) value= voltage!=null? voltage : (report!=null? report.voltage : null);
            else if (zone==Zone.ENGINE) value=rpm;
            HealthLevel level;
            if (!codes.isEmpty() || hot) level=HealthLevel.FAULT;
            else if (!pend.isEmpty() || alertHere!=null) level=HealthLevel.WARN;
            else if (zone==Zone.ENGINE && report!=null && report.readiness!=null && report.readiness.mil && storedD.isEmpty()) level=HealthLevel.WARN;
            else if (report!=null || value!=null) level=HealthLevel.GOOD;
            else level=HealthLevel.UNKNOWN;
            out.add(new SystemHealth(zone, level, codes, pend, alertHere, value));
        }
        return out;
    }
    private static List<String> filterZone(List<String> in, Zone z){ List<String> o=new ArrayList<String>(); for(String c:in) if(zoneOf(c)==z) o.add(c); return o; }
    private static List<String> distinct(List<String> in){ List<String> o=new ArrayList<String>(); for(String c:in) if(!o.contains(c)) o.add(c); return o; }
    public static int issues(List<SystemHealth> in){ int n=0; for(SystemHealth h:in) if(h.level==HealthLevel.WARN||h.level==HealthLevel.FAULT) n++; return n; }
}
