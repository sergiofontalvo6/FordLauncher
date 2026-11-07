package com.fordlauncher.launcher.obd;

import android.content.Context;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Registro de viajes, portado de TripLog.kt (LiteDash). */
public final class TripLog {
    private TripLog() {}
    public static class TripSummary {
        public final long start; public final int seconds; public final float km, maxKmh, avgKmh, maxRpm; public final Float maxCoolant, liters;
        public TripSummary(long s,int sec,float km,float mx,float avg,float rpm,Float mc,Float lit){start=s;seconds=sec;this.km=km;maxKmh=mx;avgKmh=avg;maxRpm=rpm;maxCoolant=mc;liters=lit;}
        private static final float MIN_LITERS = 0.05f;
        public Float kmPerLiter(){ return liters!=null && liters>MIN_LITERS ? km/liters : null; }
        public String toCsv(){ return String.format(Locale.US, "%d,%d,%.2f,%.0f,%.0f,%.0f,%s,%s", start, seconds, km, maxKmh, avgKmh, maxRpm, maxCoolant!=null? String.format(Locale.US,"%.0f",maxCoolant) : "", liters!=null? String.format(Locale.US,"%.2f",liters) : ""); }
        public static TripSummary fromCsv(String line){
            try { String[] f=line.split(","); if(f.length<8) return null; return new TripSummary(Long.parseLong(f[0]), Integer.parseInt(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]), Float.parseFloat(f[4]), Float.parseFloat(f[5]), f[6].isEmpty()?null:Float.parseFloat(f[6]), f[7].isEmpty()?null:Float.parseFloat(f[7])); } catch(Exception e){ return null; }
        }
    }
    private static final float LITERS_PER_GRAM_AIR = 1f/(14.7f*737f);
    private static final long MAX_GAP_MS = 5000L;
    private static final int MIN_SECONDS = 60;
    private static final float MIN_KM = 0.2f;
    private static final float MOVING_KMH = 3f;

    public static class TripRecorder {
        private long last, start; private float seconds, movingSeconds, km, maxKmh, maxRpm, liters; private Float maxCoolant; private boolean hasFuel;
        public TripRecorder(long start){ this.start=start; last=start; }
        public void add(long now, Float speedKmh, Float rpm, Float coolant, Float maf, Float fuelRate){
            long dt = Math.max(0, now-last); last=now;
            if (dt > MAX_GAP_MS) return;
            float h = dt/3600000f; seconds += dt/1000f;
            if (speedKmh!=null){ km += speedKmh*h; if(speedKmh>=MOVING_KMH) movingSeconds+=dt/1000f; maxKmh=Math.max(maxKmh,speedKmh); }
            if (rpm!=null) maxRpm=Math.max(maxRpm,rpm);
            if (coolant!=null) maxCoolant = maxCoolant!=null? Math.max(maxCoolant,coolant) : coolant;
            Float lph=null;
            if (fuelRate!=null && fuelRate>=0) lph=fuelRate;
            else if (maf!=null && maf>=0) lph=maf*3600f*LITERS_PER_GRAM_AIR;
            if (lph!=null){ liters+=lph*h; hasFuel=true; }
        }
        public TripSummary summary(){
            if (seconds<MIN_SECONDS||km<MIN_KM) return null;
            return new TripSummary(start,(int)seconds,km,maxKmh, movingSeconds>0? km/(movingSeconds/3600f):0f, maxRpm, maxCoolant, hasFuel? liters : null);
        }
    }

    public static class TripStore {
        private final File file;
        public TripStore(Context c){ file=new File(c.getFilesDir(), "trips.csv"); }
        public List<TripSummary> all(){
            List<TripSummary> out=new ArrayList<TripSummary>();
            try { BufferedReader br=new BufferedReader(new FileReader(file)); String line; boolean first=true;
                while((line=br.readLine())!=null){ if(first){first=false; continue;} TripSummary s=TripSummary.fromCsv(line.trim()); if(s!=null) out.add(0,s); } br.close();
            } catch(Exception ignored){}
            return out;
        }
        public void add(TripSummary trip) throws Exception {
            List<TripSummary> kept = all();
            List<TripSummary> rev=new ArrayList<TripSummary>();
            for(int i=kept.size()-1;i>=0;i--) rev.add(kept.get(i));
            rev.add(trip);
            while(rev.size()>200) rev.remove(0);
            BufferedWriter bw=new BufferedWriter(new FileWriter(file));
            bw.write("start_ms,seconds,km,max_kmh,avg_kmh,max_rpm,max_coolant_c,liters\n");
            for(TripSummary t: rev){ bw.write(t.toCsv()); bw.newLine(); }
            bw.close();
        }
        public String csv(){ try { BufferedReader br=new BufferedReader(new FileReader(file)); StringBuilder sb=new StringBuilder(); String l; while((l=br.readLine())!=null){sb.append(l).append("\n");} br.close(); return sb.toString(); } catch(Exception e){ return "start_ms,seconds,km,max_kmh,avg_kmh,max_rpm,max_coolant_c,liters\n"; } }
    }

    public static class AccelTimer {
        public static final int WAIT_STOP=0, READY=1, RUNNING=2, DONE=3;
        public int state=WAIT_STOP;
        public long startMs;
        public float seconds;
        private long prevMs=-1; private float prevKmh;
        public void reset(){ state=WAIT_STOP; }
        public void update(long nowMs, float kmh){
            if (state==WAIT_STOP||state==DONE){ if(kmh<1f) state=READY; }
            else if(state==READY){ if(kmh>=1f){ startMs=cross(nowMs,kmh,1f); state=RUNNING; } }
            else if(state==RUNNING){
                if(kmh>=100f){ seconds=(cross(nowMs,kmh,100f)-startMs)/1000f; state=DONE; }
                else if(kmh<1f) state=READY;
            }
            prevMs=nowMs; prevKmh=kmh;
        }
        private long cross(long nowMs, float nowKmh, float target){
            if(prevMs<0||nowKmh<=prevKmh) return nowMs;
            float f=(target-prevKmh)/(nowKmh-prevKmh); if(f<0) f=0; if(f>1) f=1;
            return prevMs+(long)((nowMs-prevMs)*f);
        }
    }
}
