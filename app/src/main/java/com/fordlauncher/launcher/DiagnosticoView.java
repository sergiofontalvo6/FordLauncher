package com.fordlauncher.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.fordlauncher.launcher.obd.Codes;
import com.fordlauncher.launcher.obd.Health;
import com.fordlauncher.launcher.obd.ObdRepository;
import com.fordlauncher.launcher.obd.Pids;
import java.util.List;
import java.util.Map;

/**
 * Taller OBD (port UI lógica de LiteDash a vistas programáticas Java).
 * 4 secciones: Escanear Códigos, Salud en Vivo, Viaje y Consumo, Conexión.
 * Tema oscuro automotriz; se muestra como overlay central sin solapar top/dock.
 */
public class DiagnosticoView {
    private final Main host;
    private final ObdRepository repo;
    private final float s;
    private final Handler ui = new Handler();
    private LinearLayout liveBox, codesBox, tripBox, connBox;
    private TextView statusTv;

    private static final int CARD = 0xFF1E2128, INNER = 0xFF262A33, MUTED = 0xFF8B9099, TEXT = 0xFFF2F3F5, SOFT = 0xFFC5C8CE, RED = 0xFFFE3211;

    public DiagnosticoView(Main h, float scale) { host = h; s = scale; repo = h.getObdRepo(); }

    public LinearLayout build() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CARD);
        root.setPadding((int)(16*s),(int)(12*s),(int)(16*s),(int)(8*s));

        ScrollView sc = new ScrollView(host);
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col, new android.widget.FrameLayout.LayoutParams(-1,-2));
        root.addView(sc, new LinearLayout.LayoutParams(-1,0,1f));

        addSection(col, "1 · Escanear Códigos");
        codesBox = addSectionBox(col);
        button(codesBox, "Escanear DTCs (03/07/0A)", new Runnable(){ public void run(){ scanCodes(); } });
        line(codesBox, "Lista de códigos con descripción y borrado MIL.");
        addSection(col, "2 · Salud y Sensores en Vivo");
        liveBox = addSectionBox(col);
        addSection(col, "3 · Viaje y Consumo");
        tripBox = addSectionBox(col);
        addSection(col, "4 · Conexión ELM327");
        connBox = addSectionBox(col);

        statusTv = new TextView(host);
        statusTv.setTextColor(SOFT);
        statusTv.setTextSize(13*s);
        connBox.addView(statusTv);
        refreshConn();
        repo.setListener(new ObdRepository.Listener(){
            public void onState(ObdRepository.State st, String err){ ui.post(new Runnable(){ public void run(){ refreshConn(); } }); }
            public void onValues(Map<String,Float> v){ ui.post(new Runnable(){ public void run(){ refreshLive(); } }); }
        });
        refreshLive();
        return root;
    }

    private void addSection(LinearLayout col, String title) {
        TextView t = new TextView(host);
        t.setText(title.toUpperCase());
        t.setTextColor(MUTED);
        t.setTextSize(11*s);
        t.setPadding(0,(int)(10*s),0,(int)(6*s));
        col.addView(t, new LinearLayout.LayoutParams(-1,-2));
    }
    private LinearLayout addSectionBox(LinearLayout col) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(INNER);
        box.setPadding((int)(14*s),(int)(8*s),(int)(14*s),(int)(8*s));
        col.addView(box, new LinearLayout.LayoutParams(-1,-2));
        return box;
    }
    private TextView line(LinearLayout box, String txt) {
        TextView t = new TextView(host);
        t.setText(txt);
        t.setTextColor(SOFT);
        t.setTextSize(13*s);
        t.setSingleLine(false);
        t.setPadding(0,(int)(4*s),0,(int)(4*s));
        box.addView(t, new LinearLayout.LayoutParams(-1,-2));
        return t;
    }
    private void button(LinearLayout box, String label, final Runnable r) {
        TextView b = new TextView(host);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(RED);
        b.setPadding(0,(int)(10*s),0,(int)(10*s));
        b.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=(int)(8*s);
        box.addView(b, lp);
    }
    private void refreshConn() {
        if (connBox == null) return;
        final ObdRepository.State st = repo.getState();
        final String mac = repo.targetOrDefault();
        statusTv.setText("Estado: " + st + " · Adaptador: " + (repo.getAdapter()!=null?repo.getAdapter():mac) + (repo.getError()!=null? "\nError: "+repo.getError():""));
        // Recrea botones sin borrar statusTv (primer hijo)
        connBox.removeAllViews();
        connBox.addView(statusTv);
        List<String[]> devs = repo.pairedDevices();
        if (!devs.isEmpty()) {
            for (final String[] d : devs) {
                button(connBox, d[1] + "  (" + d[0] + ")", new Runnable(){ public void run(){ repo.connect(d[0]); } });
            }
        } else {
            line(connBox, "Sin adaptadores emparejados. Empareja el ELM327 en Ajustes de Android, o pulsa Conectar (MAC por defecto " + ObdRepository.DEFAULT_MAC + ").");
        }
        button(connBox, "Conectar (" + mac + ")", new Runnable(){ public void run(){ repo.connect(mac); } });
        button(connBox, "Desconectar", new Runnable(){ public void run(){ repo.disconnect(); } });
    }
    private void refreshLive() {
        if (liveBox == null) return;
        liveBox.removeAllViews();
        Map<String,Float> v = repo.getValues();
        if (v.isEmpty()) { line(liveBox, "Sin datos aún. Conecta el ELM327 para ver RPM, velocidad, coolant, voltaje, MAF/MAP y carga."); }
        else {
            line(liveBox, fmt(v, Pids.RPM, "RPM", ""));
            line(liveBox, fmt(v, Pids.SPEED, "Velocidad", " km/h"));
            line(liveBox, fmt(v, Pids.COOLANT, "Coolant", " °C"));
            line(liveBox, fmt(v, Pids.VOLTAGE, "Voltaje", " V"));
            line(liveBox, fmt(v, Pids.MAF, "MAF", " g/s"));
            line(liveBox, fmt(v, Pids.MAP, "MAP", " kPa"));
            line(liveBox, fmt(v, Pids.LOAD, "Carga", " %"));
            line(liveBox, fmt(v, Pids.CONSUMPTION, "Consumo", " km/L"));
        }
        refreshTrip();
    }
    private String fmt(Map<String,Float> v, String sig, String name, String unit) {
        Float f = v.get(sig);
        return name + ": " + (f!=null? String.format("%.0f", f) : "--") + unit;
    }
    private void refreshTrip() {
        if (tripBox == null) return;
        // Se rellena desde el recorder del repositorio (si existe) + promedio simple
        tripBox.removeAllViews();
        line(tripBox, "Tiempo de marcha y distancia parcial se calculan mientras OBD está conectado (TripLog).");
        line(tripBox, "Consumo L/100km: " + consumptionText());
    }
    private String consumptionText() {
        Map<String,Float> v = repo.getValues();
        Float cons = v.get(Pids.CONSUMPTION);
        if (cons != null && cons > 0) return String.format("%.1f L/100km (%.1f km/L)", 100f/cons, cons);
        return "--";
    }
    public void scanCodes() {
        codesBox.removeAllViews();
        line(codesBox, "Leyendo 03/07/0A…");
        new Thread(new Runnable(){ public void run(){
            try {
                final Health.EngineReport r = repo.report();
                ui.post(new Runnable(){ public void run(){
                    codesBox.removeAllViews();
                    if (r.stored.isEmpty() && r.pending.isEmpty() && r.permanent.isEmpty()) line(codesBox, "Sin códigos. MIL: " + (r.readiness!=null? (r.readiness.mil?"ENCENDIDA":"apagada"):"?"));
                    addCodes(codesBox, "Guardados (03)", r.stored);
                    addCodes(codesBox, "Pendientes (07)", r.pending);
                    addCodes(codesBox, "Permanentes (0A)", r.permanent);
                    if (r.vin != null) line(codesBox, "VIN: " + r.vin);
                    if (r.voltage != null) line(codesBox, "Batería: " + String.format("%.2f V", r.voltage));
                    button(codesBox, "Borrar Códigos (MIL Reset)", new Runnable(){ public void run(){
                        new Thread(new Runnable(){ public void run(){
                            try { final boolean ok = repo.clearCodes(); ui.post(new Runnable(){ public void run(){ line(codesBox, ok? "Códigos borrados (MIL apagado)" : "No se pudo borrar"); } }); }
                            catch(final Exception e){ ui.post(new Runnable(){ public void run(){ line(codesBox, "Error: "+e.getMessage()); } }); }
                        }}).start();
                    }});
                }});
            } catch (final Exception e) {
                ui.post(new Runnable(){ public void run(){ codesBox.removeAllViews(); line(codesBox, "Error: " + e.getMessage()); } });
            }
        }}).start();
    }
    private void addCodes(LinearLayout box, String header, List<String> codes) {
        line(box, header + ": " + codes.size());
        for (String c : codes) line(box, "• " + c + " — " + Codes.describe(c));
    }
}
