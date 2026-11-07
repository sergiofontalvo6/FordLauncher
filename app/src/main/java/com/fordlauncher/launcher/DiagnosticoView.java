package com.fordlauncher.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.fordlauncher.launcher.obd.Codes;
import com.fordlauncher.launcher.obd.Health;
import com.fordlauncher.launcher.obd.ObdRepository;
import com.fordlauncher.launcher.obd.Pids;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Taller OBD estilo LiteDash: tarjetas oscuras #161B22/#21262D, Grid 2x3 y switches.
 * Adaptado al tema y a las pantallas 1024x600 de FordLauncher (vistas programáticas).
 */
public class DiagnosticoView {
    private final Main host;
    private final ObdRepository repo;
    private final float s;
    private final Handler ui = new Handler();
    private TextView subtitleTv;
    private TextView statusTv;
    private final Map<String, TextView> valueByZone = new java.util.HashMap<String, TextView>();
    private final Map<String, View> dotByZone = new java.util.HashMap<String, View>();
    private CarTopView carView;

    private int BG() { return Main.BG; }
    private int CARD() { return Main.CARD; }
    private int INNER() { return Main.INNER; }
    private int MUTED() { return Main.MUTED; }
    private int SOFT() { return Main.SOFT; }
    private int TEXT() { return Main.TEXT; }
    private int BORDER() { return Main.BTN; }
    private int ACCENT() { return Main.RED; }

    public DiagnosticoView(Main h, float scale) { host = h; s = scale; repo = h.getObdRepo(); }

    public LinearLayout build() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG());
        root.setPadding((int)(12*s),(int)(12*s),(int)(12*s),(int)(12*s));

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col, new android.widget.FrameLayout.LayoutParams(-1,-2));
        root.addView(sc, new LinearLayout.LayoutParams(-1,0,1f));

        buildHealthCard(col);
        buildAdapterCard(col);
        buildSwitchCard(col, "Alertas del motor",
            "Avisa en el inicio si el motor se calienta de más o la batería no carga.",
            "obd_alerts");
        buildSwitchCard(col, "Registro de viajes (TripLog)",
            "Guarda un resumen de cada viaje: distancia, tiempo, velocidades, temperatura y gasolina.",
            "obd_trip_log");

        repo.setListener(new ObdRepository.Listener(){
            public void onState(ObdRepository.State st, String err){ ui.post(new Runnable(){ public void run(){ refreshAll(); } }); }
            public void onValues(Map<String,Float> v){ ui.post(new Runnable(){ public void run(){ refreshAll(); } }); }
        });
        refreshAll();
        return root;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp*s);
        if (stroke != 0) g.setStroke(1, stroke);
        return g;
    }
    private TextView txt(String t, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(host);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        v.setSingleLine(false);
        if (bold) v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return v;
    }
    private LinearLayout card(LinearLayout col, String titleSmall) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD(), 16, 0));
        card.setPadding((int)(14*s),(int)(12*s),(int)(14*s),(int)(12*s));
        TextView h = txt(titleSmall, 11, MUTED(), true);
        h.setText(titleSmall.toUpperCase());
        card.addView(h, new LinearLayout.LayoutParams(-1,-2));
        col.addView(card, new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.MarginLayoutParams lp = (LinearLayout.MarginLayoutParams) card.getLayoutParams();
        lp.bottomMargin = (int)(10*s);
        return card;
    }
    private TextView subtitle(LinearLayout card, String text) {
        TextView t = txt(text, 12, SOFT(), false);
        t.setPadding(0,(int)(4*s),0,(int)(8*s));
        card.addView(t, new LinearLayout.LayoutParams(-1,-2));
        return t;
    }

    /** Silueta cenital simplificada (200x420 LiteDash CarTop) en canvas. */
    public class CarTopView extends View {
        public CarTopView() { super(host); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w==0||h==0) return;
            float sx = w/200f, sy = h/420f, sc = Math.min(sx, sy);
            c.save();
            c.scale(sc, sc);
            float ox = (w - 200*sc)/2/sc, oy = (h - 420*sc)/2/sc;
            c.translate(ox, oy);
            // sombra suelo
            Paint sh = new Paint(); sh.setColor(Color.argb(110,0,0,0));
            c.drawCircle(100, 216, 118, sh);
            // ruedas
            Paint tire = new Paint(); tire.setColor(0xFF2A2E34);
            float[][] wheels = {{14,118},{186,118},{14,302},{186,302}};
            for (float[] wh : wheels) { RectF r = new RectF(wh[0]-9, wh[1]-32, wh[0]+9, wh[1]+32); c.drawRoundRect(r, 6,6, tire); }
            // carrocería (aprox. TopPaths.body)
            Path body = new Path();
            body.moveTo(42,26);
            body.cubicTo(64,12, 136,12, 158,26);
            body.cubicTo(178,40, 185,72, 186,122);
            body.lineTo(186,318);
            body.cubicTo(185,368, 178,394, 160,404);
            body.cubicTo(140,414, 60,414, 40,404);
            body.cubicTo(22,394, 15,368, 14,318);
            body.lineTo(14,122);
            body.cubicTo(15,72, 22,40, 42,26);
            body.close();
            Paint bodyP = new Paint(); bodyP.setColor(ACCENT()); bodyP.setAntiAlias(true); bodyP.setAlpha(Main.LIGHT? 120 : 170); bodyP.setColorFilter(new android.graphics.PorterDuffColorFilter(Main.LIGHT? 0xFFBFD4E6 : 0xFF2A4A6B, android.graphics.PorterDuff.Mode.SRC_ATOP));
            c.drawPath(body, bodyP);
            // parabrisas/techo/medallón
            Path ws = new Path(); ws.moveTo(36,124); ws.cubicTo(72,114,128,114,164,124); ws.lineTo(158,178); ws.cubicTo(128,171,72,171,42,178); ws.close();
            Path roof = new Path(); roof.moveTo(42,178); roof.cubicTo(72,171,128,171,158,178); roof.lineTo(158,298); roof.cubicTo(128,305,72,305,42,298); roof.close();
            Path rear = new Path(); rear.moveTo(42,298); rear.cubicTo(72,305,128,305,158,298); rear.lineTo(162,344); rear.cubicTo(128,337,72,337,38,344); rear.close();
            Paint glass = new Paint(); glass.setColor(0xFF1A202A); glass.setAntiAlias(true);
            c.drawPath(ws, glass); c.drawPath(rear, glass);
            Paint roofP = new Paint(); roofP.setColor(Color.argb(26,255,255,255)); c.drawPath(roof, roofP);
            c.restore();
            // puntos de zona
            for (Health.Zone z : Health.Zone.values()) {
                View dot = dotByZone.get(z.name());
                if (dot==null) continue;
                float dx = z.x * w, dy = z.y * h;
                dot.setX(dx-7*s); dot.setY(dy-7*s);
            }
        }
    }
    private void buildHealthCard(LinearLayout col) {
        LinearLayout card = card(col, "Salud del auto");
        subtitleTv = subtitle(card, subtitleText());
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        carView = new CarTopView();
        LinearLayout.LayoutParams carLp = new LinearLayout.LayoutParams((int)(160*s), (int)(220*s));
        carLp.rightMargin = (int)(10*s);
        row.addView(carView, carLp);
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        String[][] keys = {
            {"Motor", Health.Zone.ENGINE.name()},
            {"Combustible y aire", Health.Zone.INTAKE.name()},
            {"Enfriamiento", Health.Zone.COOLING.name()},
            {"Batería y carga", Health.Zone.BATTERY.name()},
            {"Transmisión", Health.Zone.TRANSMISSION.name()},
            {"Emisiones", Health.Zone.EXHAUST.name()},
        };
        for (int r=0;r<2;r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c=0;c<3;c++) {
                int idx=r*3+c;
                line.addView(miniCard(keys[idx][0], keys[idx][1]), new LinearLayout.LayoutParams(0,-2,1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1,-2));
        }
        row.addView(grid, new LinearLayout.LayoutParams(0,-2,1f));
        card.addView(row, new LinearLayout.LayoutParams(-1,-2));
        refreshHealth();
    }
    private LinearLayout miniCard(String titleName, String zoneKey) {
        LinearLayout b = new LinearLayout(host);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(rounded(INNER(), 10, BORDER()));
        b.setPadding((int)(8*s),(int)(8*s),(int)(8*s),(int)(8*s));
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(rounded(MUTED(), 6, 0));
        head.addView(dot, new LinearLayout.LayoutParams((int)(10*s),(int)(10*s)));
        TextView h = txt("● " + titleName, 11, SOFT(), false);
        h.setMaxLines(1);
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.setPadding((int)(6*s),0,0,0);
        head.addView(h);
        b.addView(head, new LinearLayout.LayoutParams(-1,-2));
        TextView v = txt("--", 11, TEXT(), true);
        v.setMaxLines(1);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setPadding(0,(int)(4*s),0,0);
        b.addView(v, new LinearLayout.LayoutParams(-1,-2));
        valueByZone.put(zoneKey, v);
        dotByZone.put(zoneKey, dot);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1f);
        lp.leftMargin = (int)(4*s); lp.rightMargin=(int)(4*s); lp.topMargin=(int)(4*s); lp.bottomMargin=(int)(4*s);
        b.setLayoutParams(lp);
        return b;
    }
    private String subtitleText() {
        return repo.getState()==ObdRepository.State.ON ? "Monitoreo en tiempo real activo" : "Conecta tu adaptador OBD para ver la salud de tu auto";
    }
    private void refreshHealth() {
        if (subtitleTv != null) subtitleTv.setText(subtitleText());
        Map<String,Float> v = repo.getValues();
        Health.EngineReport r = repo.getLastReport();
        Float coolant = v.get(Pids.COOLANT);
        Float volt = v.get(Pids.VOLTAGE);
        if (volt==null && r!=null) volt=r.voltage;
        Float rpm = v.get(Pids.RPM);
        List<Health.SystemHealth> hs = Health.healthOf(r, null, coolant, volt, rpm);
        for (Health.SystemHealth sh : hs) {
            TextView tv = valueByZone.get(sh.zone.name());
            View dot = dotByZone.get(sh.zone.name());
            if (tv!=null) tv.setText(zoneText(sh.zone, v, r));
            if (dot!=null) dot.setBackground(rounded(zoneColor(sh.level), 6, 0));
        }
        if (carView!=null) carView.invalidate();
    }
    private String zoneText(Health.Zone z, Map<String,Float> v, Health.EngineReport r) {
        switch (z) {
            case ENGINE: return fmt(v, Pids.RPM, " RPM");
            case INTAKE: {
                Float map = v.get(Pids.MAP);
                Float trim = v.get(Pids.FUEL_TRIM_SHORT);
                if (map==null && trim==null) return "--";
                return (map!=null? String.format("%.0f kPa",map):"") + (trim!=null? (map!=null?" · ":"") + String.format("%.0f%%",trim):"");
            }
            case COOLING: return fmt(v, Pids.COOLANT, " °C");
            case BATTERY: {
                Float volt = v.get(Pids.VOLTAGE);
                if (volt==null && r!=null) volt=r.voltage;
                return volt!=null? String.format("%.1f V",volt) : "--";
            }
            case TRANSMISSION: return "Sin fallas";
            case EXHAUST: {
                int n = r!=null? r.stored.size()+r.permanent.size() : 0;
                return n==0? "Listo" : "DTC: " + n;
            }
            default: return "--";
        }
    }
    private String fmt(Map<String,Float> v, String sig, String unit) {
        Float f = v.get(sig);
        return f!=null? String.format("%.0f",f)+unit : "--";
    }
    private int zoneColor(Health.HealthLevel l) {
        if (l==Health.HealthLevel.GOOD) return 0xFF4CAF50;
        if (l==Health.HealthLevel.WARN) return 0xFFD29922;
        if (l==Health.HealthLevel.FAULT) return 0xFFF44336;
        return MUTED();
    }

    private void buildAdapterCard(LinearLayout col) {
        LinearLayout card = card(col, "Adaptador OBD");
        statusTv = txt("", 14, TEXT(), true);
        card.addView(statusTv, new LinearLayout.LayoutParams(-1,-2));
        TextView help = txt("Empareja el ELM327 (ej. " + ObdRepository.DEFAULT_MAC + ") en Ajustes de Android, o escanea por Bluetooth / Wi-Fi. Conecta y escanea DTCs aquí.", 12, MUTED(), false);
        help.setSingleLine(false);
        help.setPadding(0,(int)(4*s),0,(int)(8*s));
        card.addView(help, new LinearLayout.LayoutParams(-1,-2));
        LinearLayout rowBtns = new LinearLayout(host);
        rowBtns.setOrientation(LinearLayout.HORIZONTAL);
        compactButton(rowBtns, "Conectar", new Runnable(){ public void run(){ repo.connect(null); } });
        compactButton(rowBtns, "Reconectar", new Runnable(){ public void run(){ repo.connect(null); } });
        compactButton(rowBtns, "Buscar Bluetooth", new Runnable(){ public void run(){ showPairedDialog(); } });
        compactButton(rowBtns, "Escanear Códigos DTC", new Runnable(){ public void run(){ scanCodes(); } });
        card.addView(rowBtns, new LinearLayout.LayoutParams(-1,-2));
        refreshConn();
    }
    private void compactButton(LinearLayout row, String label, final Runnable r) {
        TextView b = txt(label, 12, TEXT(), false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = rounded(INNER(), 10, BORDER());
        b.setBackground(g);
        b.setPadding((int)(10*s),(int)(9*s),(int)(10*s),(int)(9*s));
        b.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1f);
        lp.leftMargin=(int)(4*s); lp.rightMargin=(int)(4*s);
        row.addView(b, lp);
    }
    private void showPairedDialog() {
        final List<String[]> devs = repo.pairedDevices();
        String[] items;
        if (devs.isEmpty()) items = new String[]{"Sin adaptadores emparejados"};
        else { items = new String[devs.size()]; for(int i=0;i<devs.size();i++) items[i]=devs.get(i)[1]+" ("+devs.get(i)[0]+")"; }
        new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Buscar Bluetooth (ELM327)")
            .setItems(items, new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d, int w){ if (!devs.isEmpty()) repo.connect(devs.get(w)[0]); } })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private void buildSwitchCard(LinearLayout col, String title, String desc, final String prefKey) {
        LinearLayout card = card(col, title);
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView d = txt(desc, 12, SOFT(), false);
        d.setSingleLine(false);
        row.addView(d, new LinearLayout.LayoutParams(0,-2,1f));
        Switch sw = new Switch(host);
        sw.setChecked(host.getPrefs().getBoolean(prefKey, false));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c){
                host.getPrefs().edit().putBoolean(prefKey, c).apply();
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2,-2));
        card.addView(row, new LinearLayout.LayoutParams(-1,-2));
    }

    public void scanCodes() {
        new Thread(new Runnable(){ public void run(){
            try {
                final Health.EngineReport r = repo.report();
                ui.post(new Runnable(){ public void run(){
                    StringBuilder sb = new StringBuilder();
                    if (r.stored.isEmpty() && r.pending.isEmpty() && r.permanent.isEmpty()) sb.append("Sin códigos. MIL: ").append(r.readiness!=null? (r.readiness.mil?"ENCENDIDA":"apagada"):"?");
                    append(sb, "Guardados (03)", r.stored);
                    append(sb, "Pendientes (07)", r.pending);
                    append(sb, "Permanentes (0A)", r.permanent);
                    if (r.vin != null) sb.append("\nVIN: ").append(r.vin);
                    if (r.voltage != null) sb.append("\nBatería: ").append(String.format("%.2f V", r.voltage));
                    new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog)
                        .setTitle("Códigos DTC")
                        .setMessage(sb.toString())
                        .setPositiveButton("Borrar Códigos (MIL Reset)", new DialogInterface.OnClickListener(){
                            public void onClick(DialogInterface d, int w){
                                new Thread(new Runnable(){ public void run(){
                                    try { repo.clearCodes(); } catch(Throwable ignored){}
                                }}).start();
                            }
                        })
                        .setNegativeButton("Cerrar", null)
                        .show();
                } });
            } catch(final Exception e){
                ui.post(new Runnable(){ public void run(){ new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog).setTitle("Códigos DTC").setMessage("Error: "+e.getMessage()).setPositiveButton("OK", null).show(); } });
            }
        }}).start();
    }
    private void append(StringBuilder sb, String head, List<String> codes){
        sb.append("\n\n").append(head).append(": ").append(codes.size());
        for (String c: codes) sb.append("\n• ").append(c).append(" — ").append(Codes.describe(c));
    }
    private void refreshConn() {
        if (statusTv==null) return;
        String s2 = repo.getState()==ObdRepository.State.ON ? "Conectado a " + repo.targetOrDefault() : (repo.getState()==ObdRepository.State.CONNECTING? "Conectando…" : "Sin conectar");
        statusTv.setText(s2 + (repo.getError()!=null? " · " + repo.getError():""));
        refreshHealth();
    }
    private void refreshAll(){ refreshConn(); refreshHealth(); }
}
