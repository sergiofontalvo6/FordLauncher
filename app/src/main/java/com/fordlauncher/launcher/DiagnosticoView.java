package com.fordlauncher.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.fordlauncher.launcher.obd.CarVoiceSpeaker;
import com.fordlauncher.launcher.obd.Codes;
import com.fordlauncher.launcher.obd.Derived;
import com.fordlauncher.launcher.obd.EdgeVoiceSpeaker;
import com.fordlauncher.launcher.obd.GeminiDiagnosticClient;
import com.fordlauncher.launcher.obd.Health;
import com.fordlauncher.launcher.obd.ObdRepository;
import com.fordlauncher.launcher.obd.Pids;
import com.fordlauncher.launcher.obd.TripLog;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Taller OBD: fiel a LiteDash (Workshop.kt + Health.kt + WorkshopParts.kt +
 * WorkshopAdvanced.kt), adaptado a vistas programaticas sin Compose.
 * Desconectado: Salud en gris + Adaptador + Alertas + Viajes.
 * Conectado: 5 pestanas [Salud][Codigos][Sensores][Graficas][Mas].
 * Todo el color sale de Main (tema dinamico + acento RED), sin fondos oscuros fijos.
 * 1024x600: textos de tarjetas con maxLines=1/ellipsize a 11-15sp.
 */
public class DiagnosticoView {
    private final Main host;
    private final ObdRepository repo;
    private final float s;
    private final Handler ui = new Handler();

    private static final int GOOD = 0xFF3FB27F;
    private static final int WARN = 0xFFD29922;
    private static final int DANGER = 0xFFF44336;
    private static final int CHART2 = 0xFF4CA6FF;
    private static final int SERIES_POINTS = 60; // historial del repositorio (3600ms aprox)

    private static final String[] TAB_NAMES = {"Salud", "Códigos de falla", "Sensores", "Gráficas", "Más"};
    private int currentTab;
    private Health.EngineReport report;
    private boolean reading;
    private boolean readingBg;

    private LinearLayout root;
    private LinearLayout tabBar;
    private final List<TextView> tabViews = new ArrayList<TextView>();
    private LinearLayout content;

    // Salud
    private TextView summaryLabel;
    private TextView summaryBig;
    private TextView summaryLine;
    private CarTopView carView;
    private TextView readAgainBtn;
    private final Map<Health.Zone, TileW> healthTiles = new LinkedHashMap<Health.Zone, TileW>();
    private List<Health.SystemHealth> lastHealth;

    // Codigos
    private TextView codesBody;
    private TextView codesCleared;
    private TextView inspectPass;
    private LinearLayout monitorsCol;

    // Sensores
    private final Map<String, TileW> sensorTiles = new LinkedHashMap<String, TileW>();

    // Graficas
    private final List<String> chosen = new ArrayList<String>();
    private LinearLayout chartLegend;
    private ChartView chartView;
    private LinearLayout chipsCol;
    private Float touchFrac;

    // Mas
    private TextView adapterStatus;
    private TextView adapterHelp;
    private LinearLayout adapterBtns;
    private ObdRepository.State lastAdapterState;
    private ObdRepository.State lastAdapterBuilt;
    private TextView accelTime;
    private TextView accelState;
    private TextView accelSpeed;
    private TextView accelBest;
    private LinearLayout tripsCol;

    // DiagnÃ³stico inteligente IA (Gemini + voz)
    private static final String AI_TASK_DEFAULT = "Analiza estos datos OBD2 y códigos de falla";
    private static final String AI_TASK_SENSORS = "Para cada uno de estos sensores indica en una lÃ­nea: nombre, su funciÃ³n en el motor y si su comportamiento es normal o una anomalÃ­a (escribe ANOMALÃA cuando aplique). Compara cada valor con sus parÃ¡metros normales incluidos en los datos (campo ref)";
    private static final String AI_TASK_CHARTS = "Para cada uno de estos sensores seleccionados en la grÃ¡fica indica en una lÃ­nea: nombre, su funciÃ³n y su comportamiento en la grÃ¡fica (sube, baja o estable). Compara cada valor con sus parÃ¡metros normales incluidos en los datos (campo ref) y marca ANOMALÃA cuando salga de rango";
    private CarVoiceSpeaker speaker;
    private EdgeVoiceSpeaker edgeSpeaker;
    private String edgeSpeakerVoice;
    private String pendingCloudText;
    private TextView aiResult;
    private TextView aiStatus;
    private TextView aiAnalyzeBtn;
    private TextView aiSpeakBtn;
    private String lastAiText;
    private final java.util.Map<String, String> aiLastByMode = new java.util.LinkedHashMap<String, String>();
    private boolean aiBusy;

    /** Rangos normales/anormales de referencia por seÃ±al (Ford Fiesta, motor 1.6). */
    private static String refFor(String sig) {
        if (Pids.RPM.equals(sig)) return "normal 780-900 rpm en ralantí";
        if (Pids.COOLANT.equals(sig)) return "normal 85-105 °C; anomalía >115 °C";
        if (Pids.VOLTAGE.equals(sig)) return "normal 13,5-14,8 V con motor encendido; anomalía <12,4 V o >15 V";
        if (Pids.SPEED.equals(sig)) return "0 km/h detenido; coincide con GPS";
        if (Pids.LOAD.equals(sig)) return "normal 20-30 % en ralantí";
        if (Pids.INTAKE.equals(sig)) return "normal: ambient +10 a +25 °C";
        if (Pids.MAP.equals(sig)) return "normal 28-35 kPa en ralantí";
        if (Pids.MAF.equals(sig)) return "normal 2-4 g/s en ralantí (1.6 L)";
        if (Pids.THROTTLE.equals(sig)) return "0 % en ralantí";
        if (Pids.FUEL.equals(sig)) return "0-100 %; baja si consume";
        if (Pids.OIL.equals(sig)) return "normal 85-110 °C";
        if (Pids.FUEL_TRIM_SHORT.equals(sig)) return "normal ±10 %; anomalía >±25 %";
        if (Pids.FUEL_TRIM_LONG.equals(sig)) return "normal ±10 %; anomalía >±25 % (mezcla pobre/rica)";
        if (Pids.TIMING.equals(sig)) return "normal 8-15 grados en ralantí";
        if (Pids.O2S11.equals(sig)) return "normal: oscilando 0,1-0,9 V; anomalía si plano o clavado (ej. 0,45 V fijo)";
        if (Pids.O2S12.equals(sig)) return "normal: oscilando 0,1-0,6 V tras catalizador";
        if (Pids.BARO.equals(sig)) return "normal ~barométrica (85-105 kPa)";
        if (Pids.AMBIENT.equals(sig)) return "ambiente del exterior";
        if (Pids.CAT_TEMP.equals(sig)) return "normal 400-700 °C";
        if (Pids.RUN_TIME.equals(sig)) return "crece con el motor encendido";
        return "";
    }

    private static final class TileW {
        View dot;
        TextView name;
        TextView value;
        TextView detail;
        Sparkline spark;
        TileW(View d, TextView n, TextView v, TextView det, Sparkline sp) { dot=d; name=n; value=v; detail=det; spark=sp; }
    }

    private int BG() { return Main.BG; }
    private int CARD() { return Main.CARD; }
    private int INNER() { return Main.INNER; }
    private int MUTED() { return Main.MUTED; }
    private int SOFT() { return Main.SOFT; }
    private int TEXT() { return Main.TEXT; }
    private int BORDER() { return Main.BTN; }
    private int ACCENT() { return Main.RED; }

    public DiagnosticoView(Main h, float scale) {
        host = h; s = scale; repo = h.getObdRepo();
        try { currentTab = host.getPrefs().getInt("workshop_tab", 0); } catch (Throwable t) { currentTab = 0; }
        if (currentTab < 0 || currentTab > 4) currentTab = 0;
        report = repo.getLastReport();
    }

    public LinearLayout build() {
        root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG());
        root.setPadding((int)(10*s),(int)(10*s),(int)(10*s),(int)(10*s));

        tabBar = new LinearLayout(host);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, 0, 0, (int)(8*s));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = txt(TAB_NAMES[i], 12, TEXT(), true);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding((int)(6*s),(int)(8*s),(int)(6*s),(int)(8*s));
            t.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v) {
                    currentTab = idx;
                    try { host.getPrefs().edit().putInt("workshop_tab", idx).apply(); } catch (Throwable ignored) {}
                    render();
                }
            });
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            tabViews.add(t);
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        sc.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        repo.setListener(new ObdRepository.Listener(){
            public void onState(ObdRepository.State st, String err) {
                ui.post(new Runnable(){ public void run(){ render(); } });
            }
            public void onValues(Map<String,Float> v) {
                ui.post(new Runnable(){ public void run(){ refreshLive(); } });
            }
        });
        render();
        return root;
    }

    private void render() {
        if (root == null || content == null) return;
        root.setBackgroundColor(BG());
        content.removeAllViews();
        healthTiles.clear();
        sensorTiles.clear();
        lastHealth = null;
        lastAdapterBuilt = null; lastAdapterState = null;
        adapterStatus = null; adapterBtns = null;
        carView = null;
        aiResult = null; aiStatus = null; aiAnalyzeBtn = null; aiSpeakBtn = null;
        monochrome();
        boolean on = repo.getState() == ObdRepository.State.ON;
        tabBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            buildHealthCard(content, false);
            buildAiCard(content);
            buildAdapterCard(content);
            buildAlertsCard(content);
            buildTripsCard(content);
        } else {
            paintTabs();
            switch (currentTab) {
                case 0: buildHealthCard(content, true); buildAiCard(content, AI_TASK_DEFAULT); break;
                case 1: buildCodesCard(content); buildInspectionCard(content); buildAiCard(content, AI_TASK_DEFAULT); refreshCodes(); break;
                case 2: buildSensorsCard(content); buildAiCard(content, AI_TASK_SENSORS); break;
                case 3: buildChartsCard(content); buildAiCard(content, AI_TASK_CHARTS); break;
                default: buildAdapterCard(content); buildAlertsCard(content); buildAccelCard(content); buildTripsCard(content); break;
            }
        }
        refreshLive();
    }

    private void monochrome() {
        for (TextView t : tabViews) {
            if (t.getParent() != null && t.getParent() == tabBar) { /* keep */ }
        }
    }

    private void paintTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean sel = i == currentTab;
            t.setBackground(rounded(sel ? ACCENT() : INNER(), 10, 0));
            t.setTextColor(sel ? Color.WHITE : TEXT());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.leftMargin = (int)(4*s);
            lp.rightMargin = (int)(4*s);
        }
    }

    // ---------- util ----------
    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * s);
        if (stroke != 0) g.setStroke(Math.max(1, (int)s), stroke);
        return g;
    }

    private TextView txt(String t, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(host);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        else v.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        return v;
    }

    private LinearLayout card(LinearLayout col, String titleSmall) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD(), 16, 0));
        card.setPadding((int)(14*s),(int)(12*s),(int)(14*s),(int)(12*s));
        TextView h = txt(titleSmall, 11, MUTED(), true);
        h.setText(titleSmall.toUpperCase());
        h.setMaxLines(1);
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(h, new LinearLayout.LayoutParams(-1, -2));
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.bottomMargin = (int)(10*s);
        return card;
    }

    private TextView button(LinearLayout row, String label, boolean primary, final Runnable r) {
        TextView b = txt(label, 12, primary ? Color.WHITE : TEXT(), true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(primary ? ACCENT() : INNER(), 10, primary ? 0 : BORDER()));
        b.setPadding((int)(10*s),(int)(8*s),(int)(10*s),(int)(8*s));
        b.setMaxLines(1);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (row != null) {
            b.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ r.run(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = (int)(4*s); lp.rightMargin = (int)(4*s);
            row.addView(b, lp);
        } else {
            b.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ r.run(); } });
        }
        return b;
    }

    // ---------- SALUD (Health.kt) ----------
    private static final Health.Zone[] MAIN_ZONES = {Health.Zone.ENGINE, Health.Zone.INTAKE, Health.Zone.COOLING, Health.Zone.BATTERY, Health.Zone.TRANSMISSION, Health.Zone.EXHAUST};

    private void buildHealthCard(LinearLayout col, boolean connected) {
        LinearLayout card = card(col, "Salud del auto");
        summaryBig = txt("", 24, TEXT(), true);
        summaryBig.setMaxLines(2);
        summaryBig.setPadding(0, (int)(2*s), 0, 0);
        card.addView(summaryBig, new LinearLayout.LayoutParams(-1, -2));
        summaryLine = txt("", 11, MUTED(), false);
        summaryLine.setMaxLines(1);
        summaryLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summaryLine.setPadding(0, (int)(2*s), 0, (int)(8*s));
        card.addView(summaryLine, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        carView = new CarTopView();
        LinearLayout.LayoutParams carLp = new LinearLayout.LayoutParams((int)(150*s), (int)(220*s));
        carLp.rightMargin = (int)(12*s);
        row.addView(carView, carLp);

        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                Health.Zone z = MAIN_ZONES[r*3+c];
                line.addView(statTile(z, connected), new LinearLayout.LayoutParams(0, -2, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(grid, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        if (connected) {
            LinearLayout btnRow = new LinearLayout(host);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(0, (int)(8*s), 0, 0);
            readAgainBtn = button(btnRow, "Leer otra vez", false, new Runnable(){ public void run(){ readReport(); } });
            readAgainBtn.setGravity(Gravity.CENTER);
            card.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));
        }
        refreshHealth();
    }

    private LinearLayout statTile(Health.Zone z, boolean connected) {
        LinearLayout b = new LinearLayout(host);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(rounded(INNER(), 10, 0));
        b.setPadding((int)(10*s),(int)(8*s),(int)(10*s),(int)(8*s));
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(rounded(MUTED(), 5, 0));
        head.addView(dot, new LinearLayout.LayoutParams((int)(8*s),(int)(8*s)));
        TextView name = txt("● " + zoneName(z), 11, MUTED(), false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding((int)(6*s), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        b.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView value = txt("--", 15, TEXT(), true);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setPadding(0, (int)(4*s), 0, 0);
        b.addView(value, new LinearLayout.LayoutParams(-1, -2));
        TextView detail = txt("", 11, MUTED(), false);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setVisibility(View.GONE);
        b.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        Sparkline spark = null;
        String sig = signalOf(z);
        if (connected && sig != null) {
            spark = new Sparkline();
            spark.setColor(ACCENT());
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, (int)(24*s));
            slp.topMargin = (int)(4*s);
            b.addView(spark, slp);
        }
        healthTiles.put(z, new TileW(dot, name, value, detail, spark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = (int)(4*s); lp.rightMargin = (int)(4*s); lp.topMargin = (int)(4*s); lp.bottomMargin = (int)(4*s);
        b.setLayoutParams(lp);
        if (connected) {
            b.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                TileW w = healthTiles.get(z);
                if (w != null && w.detail != null && w.detail.getVisibility() == View.VISIBLE) {
                    currentTab = 1;
                    try { host.getPrefs().edit().putInt("workshop_tab", 1).apply(); } catch (Throwable ignored) {}
                    render();
                }
            } });
        }
        return b;
    }

    private String signalOf(Health.Zone z) {
        if (z == Health.Zone.COOLING) return Pids.COOLANT;
        if (z == Health.Zone.BATTERY) return Pids.VOLTAGE;
        if (z == Health.Zone.ENGINE) return Pids.RPM;
        return null;
    }

    private static String zoneName(Health.Zone z) {
        switch (z) {
            case ENGINE: return "Motor";
            case INTAKE: return "Combustible y aire";
            case COOLING: return "Enfriamiento";
            case BATTERY: return "Batería y carga";
            case TRANSMISSION: return "Transmisión";
            case EXHAUST: return "Emisiones";
            case WHEELS: return "Ruedas";
            case BODY: return "Carrocería";
            default: return z.name();
        }
    }

    private List<Health.SystemHealth> computeHealth(boolean connected) {
        Map<String,Float> v = repo.getValues();
        Float coolant = v.get(Pids.COOLANT);
        Float volt = v.get(Pids.VOLTAGE);
        Float rpm = v.get(Pids.RPM);
        EngineReportShim r = connected ? null : null;
        Health.EngineReport rep = connected ? report : null;
        String alert = connected ? engineAlert(coolant, volt, rpm) : null;
        if (connected && rep == null) rep = repo.getLastReport();
        if (!connected) return Health.healthOf(null, null, null, null, null);
        return Health.healthOf(rep, alert, coolant, volt, rpm);
    }

    // helper shim to keep code simple
    private static final class EngineReportShim {}

    private static String engineAlert(Float coolant, Float voltage, Float rpm) {
        if (coolant != null && coolant >= 110f) return "HOT";
        return null;
    }

    private static int issuesCount(List<Health.SystemHealth> hs) {
        int n = 0;
        for (Health.SystemHealth h : hs) if (h.level == Health.HealthLevel.WARN || h.level == Health.HealthLevel.FAULT) n++;
        return n;
    }

    private void refreshHealth() {
        boolean connected = repo.getState() == ObdRepository.State.ON;
        if (summaryBig == null) return;
        List<Health.SystemHealth> hs = computeHealth(connected);
        lastHealth = hs;
        int issues = issuesCount(hs);
        boolean known = false, fault = false;
        for (Health.SystemHealth h : hs) {
            if (h.level != Health.HealthLevel.UNKNOWN) known = true;
            if (h.level == Health.HealthLevel.FAULT) fault = true;
        }
        String text; int color;
        if (!connected) { text = "Conecta tu adaptador"; color = MUTED(); }
        else if (reading && !known) { text = "Leyendo…"; color = MUTED(); }
        else if (issues == 0 && known) { text = "Todo bien"; color = GOOD; }
        else if (issues > 0) { text = issues == 1 ? "1 aviso" : (issues + " avisos"); color = fault ? DANGER : WARN; }
        else { text = "Sin datos"; color = MUTED(); }
        summaryBig.setText(text);
        summaryBig.setTextColor(color);
        if (summaryLine != null) {
            if (connected) {
                String t = repo.getTargetAddress() != null ? repo.getTargetAddress() : repo.targetOrDefault();
                String ad = repo.getAdapter();
                String vin = report != null ? report.vin : null;
                if (vin == null && repo.getLastReport() != null) vin = repo.getLastReport().vin;
                List<String> parts = new ArrayList<String>();
                if (t != null) parts.add(t);
                if (ad != null && ad.length() > 0) parts.add(ad);
                if (vin != null && vin.length() > 0) parts.add("VIN " + vin);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) { if (i > 0) sb.append(" · "); sb.append(parts.get(i)); }
                summaryLine.setText(sb.toString());
                summaryLine.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
            } else {
                summaryLine.setText("");
                summaryLine.setVisibility(View.GONE);
            }
        }
        Map<String,Float> v = repo.getValues();
        Health.EngineReport rep = report != null ? report : repo.getLastReport();
        for (Health.SystemHealth h : hs) {
            TileW w = healthTiles.get(h.zone);
            if (w == null) continue;
            int c = h.level == Health.HealthLevel.GOOD ? GOOD : h.level == Health.HealthLevel.WARN ? WARN : h.level == Health.HealthLevel.FAULT ? DANGER : MUTED();
            w.dot.setBackground(rounded(c, 5, 0));
            String sig = signalOf(h.zone);
            Float live = sig != null ? v.get(sig) : null;
            Float value = live;
            if (value == null && h.zone == Health.Zone.BATTERY && rep != null) value = rep.voltage;
            String word;
            switch (h.level) {
                case FAULT: word = "Falla"; break;
                case WARN: word = "Atención"; break;
                case UNKNOWN: word = "—"; break;
                default:
                    if (value != null && sig != null) word = sensorText(sig, value);
                    else word = "Bien";
                    break;
            }
            w.value.setText(word);
            if (h.level == Health.HealthLevel.GOOD && value != null && sig != null) w.value.setTextColor(TEXT());
            else if (h.level == Health.HealthLevel.UNKNOWN) w.value.setTextColor(MUTED());
            else w.value.setTextColor(c);
            String detail = null;
            if (!h.codes.isEmpty()) detail = join(h.codes, " · ");
            else if (!h.pendingCodes.isEmpty()) detail = join(h.pendingCodes, " · ") + " · pendiente";
            else if (h.level == Health.HealthLevel.WARN && h.alert == null) detail = "Luz MIL";
            else if (h.alert != null && value != null && sig != null) detail = sensorText(sig, value);
            else if (h.zone == Health.Zone.EXHAUST && rep != null && rep.readiness != null) {
                int done = 0;
                for (Pids.Monitor m : rep.readiness.monitors) if (m.complete) done++;
                detail = "Monitores " + done + "/" + rep.readiness.monitors.size();
            }
            if (detail != null) {
                w.detail.setText(detail);
                w.detail.setVisibility(View.VISIBLE);
                w.detail.setTextColor((h.level == Health.HealthLevel.WARN || h.level == Health.HealthLevel.FAULT) ? c : MUTED());
            } else {
                w.detail.setVisibility(View.GONE);
            }
            if (w.spark != null && sig != null) {
                w.spark.setPoints(repo.getHistory(sig));
                w.spark.setColor(h.level == Health.HealthLevel.GOOD ? MUTED() : c);
            }
        }
        if (carView != null) carView.setHealth(hs);
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(sep); sb.append(list.get(i)); }
        return sb.toString();
    }

    private void readReport() {
        if (readingBg) return;
        readingBg = true;
        reading = true;
        refreshHealth();
        new Thread(new Runnable(){ public void run(){
            try {
                final Health.EngineReport r = repo.report();
                ui.post(new Runnable(){ public void run(){ report = r; reading = false; readingBg = false; refreshHealth(); if (currentTab == 1) refreshCodes(); } });
            } catch (final Throwable e) {
                ui.post(new Runnable(){ public void run(){ reading = false; readingBg = false; refreshHealth(); if (currentTab == 1) refreshCodes(); } });
            }
        }}).start();
    }

    // ---------- DIAGNÃ“STICO INTELIGENTE IA (Gemini + voz) ----------
    private void buildAiCard(LinearLayout col) {
        buildAiCard(col, AI_TASK_DEFAULT);
    }

    private void buildAiCard(LinearLayout col, final String task) {
        LinearLayout card = card(col, "Diagnóstico inteligente IA");
        aiStatus = txt("", 12, MUTED(), false);
        aiStatus.setVisibility(View.GONE);
        aiStatus.setPadding(0, (int)(4*s), 0, 0);
        card.addView(aiStatus, new LinearLayout.LayoutParams(-1, -2));
        aiResult = txt("Toca «Analizar con IA» para combinar DTCs y sensores con Gemini.", 13, TEXT(), false);
        String prev = aiLastByMode.get(task);
        if (prev != null) aiResult.setText(prev);
        aiResult.setPadding(0, (int)(4*s), 0, (int)(8*s));
        card.addView(aiResult, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        aiAnalyzeBtn = button(row, "Analizar con IA", true, new Runnable(){ public void run(){ runAiAnalyze(task); } });
        aiSpeakBtn = button(row, "🔊 Escuchar", false, new Runnable(){ public void run(){ toggleAiSpeak(); } });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        updateAiSpeakLabel();
    }

    private boolean anySpeaking() {
        return (speaker != null && speaker.isSpeaking()) || (edgeSpeaker != null && edgeSpeaker.isSpeaking());
    }

    private void updateAiSpeakLabel() {
        if (aiSpeakBtn == null) return;
        aiSpeakBtn.setText(anySpeaking() ? "â•¹ Detener" : "🔊 Escuchar");
    }

    private String edgeVoiceName() {
        try {
            String v = host.getPrefs().getString("tts_voice", null);
            if (v != null && v.startsWith("edge:") && v.length() > 5) return v.substring(5);
        } catch (Throwable ignored) {}
        return null;
    }

    private EdgeVoiceSpeaker ensureEdgeSpeaker() {
        String voice = edgeVoiceName();
        if (voice == null) return null;
        if (edgeSpeaker != null && !voice.equals(edgeSpeakerVoice)) {
            try { edgeSpeaker.shutdown(); } catch (Throwable ignored) {}
            edgeSpeaker = null;
        }
        if (edgeSpeaker == null) {
            edgeSpeakerVoice = voice;
            edgeSpeaker = new EdgeVoiceSpeaker(host, voice);
            edgeSpeaker.setListener(new EdgeVoiceSpeaker.Listener(){
                public void onSpeaking(boolean speaking) { ui.post(new Runnable(){ public void run(){ updateAiSpeakLabel(); } }); }
                public void onError(String msg) { ui.post(new Runnable(){ public void run(){
                    // Respaldo: voz en el dispositivo si la nube falla.
                    String text = pendingCloudText;
                    pendingCloudText = null;
                    try {
                        if (text != null) ensureSpeaker().speak(text);
                    } catch (Throwable ignored) {}
                    updateAiSpeakLabel();
                } }); }
            });
        }
        return edgeSpeaker;
    }

    private CarVoiceSpeaker ensureSpeaker() {
        if (speaker != null) return speaker;
        speaker = new CarVoiceSpeaker(host);
        speaker.setListener(new CarVoiceSpeaker.Listener(){
            public void onSpeaking(boolean speaking) { ui.post(new Runnable(){ public void run(){ updateAiSpeakLabel(); } }); }
            public void onError(String msg) { ui.post(new Runnable(){ public void run(){
                if (aiResult != null && lastAiText != null) aiResult.setText(msg + "\n\n" + lastAiText);
                else if (aiResult != null) aiResult.setText(msg);
                updateAiSpeakLabel();
            } }); }
        });
        return speaker;
    }

    private void runsAiSpeak(String text) {
        if (text == null) return;
        if (edgeVoiceName() != null) {
            EdgeVoiceSpeaker edge = ensureEdgeSpeaker();
            if (edge != null) {
                pendingCloudText = text;
                edge.speak(text);
                updateAiSpeakLabel();
                return;
            }
        }
        ensureSpeaker().speak(text);
        updateAiSpeakLabel();
    }

    private void toggleAiSpeak() {
        if (anySpeaking()) {
            try { if (speaker != null) speaker.stop(); } catch (Throwable ignored) {}
            try { if (edgeSpeaker != null) edgeSpeaker.stop(); } catch (Throwable ignored) {}
            pendingCloudText = null;
            updateAiSpeakLabel();
            return;
        }
        if (lastAiText == null || lastAiText.length() == 0) return;
        runsAiSpeak(lastAiText);
    }

    private void setAiBusy(boolean busy) {
        aiBusy = busy;
        if (aiStatus != null) {
            aiStatus.setText(busy ? "Consultando telemetría..." : "");
            aiStatus.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (aiAnalyzeBtn != null) aiAnalyzeBtn.setEnabled(!busy);
    }

    private void runAiAnalyze(final String task) {
        if (aiBusy) return;
        String snap = buildAiSnapshot(task);
        setAiBusy(true);
        if (aiResult != null) aiResult.setText("Consultando telemetría...");
        boolean detailed = AI_TASK_SENSORS.equals(task) || AI_TASK_CHARTS.equals(task);
        GeminiDiagnosticClient.analyze(host, snap, task, detailed, new GeminiDiagnosticClient.Callback(){
            public void onDone(final String text, final String error) {
                ui.post(new Runnable(){ public void run(){
                    setAiBusy(false);
                    if (error != null) {
                        if (aiResult != null) aiResult.setText(error);
                        lastAiText = null;
                        updateAiSpeakLabel();
                        return;
                    }
                    lastAiText = text;
                    aiLastByMode.put(task, text);
                    if (aiResult != null) aiResult.setText(text);
                    updateAiSpeakLabel();
                    try {
                        if (host.getPrefs().getBoolean("tts_auto_read", true)) runsAiSpeak(text);
                    } catch (Throwable ignored) {}
                } });
            }
        });
    }

    /** Snapshot segÃºn la pestaÃ±a: DTC (Salud/CÃ³digos), sensores actuales (Sensores) o serie de grÃ¡fica (GrÃ¡ficas). */
    private String buildAiSnapshot(String task) {
        if (AI_TASK_SENSORS.equals(task)) return buildSensorsSnapshotJson();
        if (AI_TASK_CHARTS.equals(task)) return buildChartsSnapshotJson();
        return buildSnapshotJson();
    }

    /** Todos los sensores de la pestaÃ±a Sensores con su valor actual y su rango normal de referencia. */
    private String buildSensorsSnapshotJson() {
        Map<String, Float> v = null;
        try { v = repo.getValues(); } catch (Throwable ignored) {}
        if (v == null) v = new LinkedHashMap<String, Float>();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"sensores\":{");
        boolean first = true;
        for (int i = 0; i < SENSOR_16.length; i++) {
            Float f = v.get(SENSOR_16[i]);
            if (f == null) continue;
            if (!first) sb.append(",");
            first = false;
            String entry = "\"" + jsonEscape(sensorName(SENSOR_16[i])) + "\":" + "{\"actual\":" + f.floatValue() + ",\"ref\":\"" + jsonEscape(refFor(SENSOR_16[i])) + "\"}";
            sb.append(entry);

        }
        sb.append("}}");
        return sb.toString();
    }

    /** Comportamiento en la grÃ¡fica: por cada seÃ±al elegida, mÃ­nimo/mÃ¡ximo/Ãºltimo de su historial y su rango normal de referencia. */
    private String buildChartsSnapshotJson() {
        Map<String, Float> v = null;
        try { v = repo.getValues(); } catch (Throwable ignored) {}
        if (v == null) v = new LinkedHashMap<String, Float>();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"grafica\":{");
        boolean first = true;
        for (String sig : chosen.isEmpty() ? readableSignals() : chosen) {
            java.util.List<Float> pts = repo.getHistory(sig);
            String name = sensorName(sig);
            String ref = refFor(sig);
            if (pts == null || pts.isEmpty()) {
                Float f = v.get(sig);
                if (f == null) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(jsonEscape(name)).append("\":{").append("\"actual\":").append(f.floatValue()).append(",\"ref\":\"").append(jsonEscape(ref)).append("\"}");
                continue;
            }
            float mn = pts.get(0), mx = pts.get(0);
            for (Float p : pts) { if (p != null) { mn = Math.min(mn, p); mx = Math.max(mx, p); } }
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(name)).append("\":{")
              .append("\"actual\":").append(pts.get(pts.size() - 1).floatValue())
              .append(",\"min\":").append(mn)
              .append(",\"max\":").append(mx)
              .append(",\"puntos\":").append(pts.size())
              .append(",\"ref\":\"").append(jsonEscape(ref)).append("\"}");
        }
        sb.append("}}");
        return sb.toString();
    }

    private String buildSnapshotJson() {
        Health.EngineReport r = report != null ? report : repo.getLastReport();
        Map<String, Float> v = null;
        try { v = repo.getValues(); } catch (Throwable ignored) {}
        if (v == null) v = new LinkedHashMap<String, Float>();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"dtc_stored\":"); appendCodeArray(sb, r != null ? r.stored : null);
        sb.append(",\"dtc_pending\":"); appendCodeArray(sb, r != null ? r.pending : null);
        sb.append(",\"dtc_permanent\":"); appendCodeArray(sb, r != null ? r.permanent : null);
        sb.append(",\"mil\":"); sb.append(r != null && r.readiness != null && r.readiness.mil ? "true" : "false");
        if (r != null && r.vin != null) sb.append(",\"vin\":\"").append(jsonEscape(r.vin)).append("\"");
        if (r != null && r.voltage != null) sb.append(",\"voltage_report\":").append(r.voltage.floatValue());
        String[] keys = {Pids.RPM, Pids.COOLANT, Pids.VOLTAGE, Pids.SPEED, Pids.LOAD, Pids.INTAKE, Pids.MAP, Pids.MAF, Pids.THROTTLE, Pids.OIL, Pids.FUEL_TRIM_SHORT, Pids.FUEL_TRIM_LONG};
        for (int i = 0; i < keys.length; i++) {
            Float f = v.get(keys[i]);
            if (f != null) sb.append(",\"").append(jsonEscape(keys[i])).append("\":").append(f.floatValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendCodeArray(StringBuilder sb, List<String> codes) {
        sb.append("[");
        if (codes != null) {
            for (int i = 0; i < codes.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(buildCodeDesc(codes.get(i)));
            }
        }
        sb.append("]");
    }

    private static String buildCodeDesc(String code) {
        if (code == null) return "\"\"";
        String desc = null;
        try { desc = Codes.describe(code); } catch (Throwable ignored) {}
        if (desc == null || desc.length() == 0) desc = code;
        else desc = code + ": " + desc;
        return "\"" + jsonEscape(desc) + "\"";
    }

    private static String jsonEscape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') o.append("\\\"");
            else if (c == '\\') o.append("\\\\");
            else if (c == '\n') o.append("\\n");
            else if (c == '\r') o.append("\\r");
            else if (c == '\t') o.append("\\t");
            else if (c < 0x20) o.append(String.format("\\u%04x", (int) c));
            else o.append(c);
        }
        return o.toString();
    }

    public void onDestroy() {
        try { if (speaker != null) speaker.shutdown(); } catch (Throwable ignored) {}
        speaker = null;
        try { if (edgeSpeaker != null) edgeSpeaker.shutdown(); } catch (Throwable ignored) {}
        edgeSpeaker = null;
        edgeSpeakerVoice = null;
    }

    // ---------- CODIGOS (Workshop.kt CodesCard + InspectionCard) ----------
    private void buildCodesCard(LinearLayout col) {
        LinearLayout card = card(col, "Códigos de falla");
        TextView help = txt("Toque a un código para ver dónde está en el auto. Borrar reinicia la verificación.", 12, MUTED(), false);
        help.setPadding(0, (int)(4*s), 0, (int)(8*s));
        card.addView(help, new LinearLayout.LayoutParams(-1, -2));
        codesBody = txt("", 13, TEXT(), false);
        codesBody.setPadding(0, 0, 0, (int)(4*s));
        card.addView(codesBody, new LinearLayout.LayoutParams(-1, -2));
        codesCleared = txt("", 11, MUTED(), false);
        codesCleared.setVisibility(View.GONE);
        card.addView(codesCleared, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int)(6*s), 0, 0);
        button(row, "Borrar códigos", false, new Runnable(){ public void run(){ confirmClear(); } });
        button(row, "Leer de nuevo", false, new Runnable(){ public void run(){ readReport(); } });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildInspectionCard(LinearLayout col) {
        LinearLayout card = card(col, "¿Pasas la verificación?");
        inspectPass = txt("", 15, TEXT(), true);
        inspectPass.setMaxLines(2);
        card.addView(inspectPass, new LinearLayout.LayoutParams(-1, -2));
        monitorsCol = new LinearLayout(host);
        monitorsCol.setOrientation(LinearLayout.VERTICAL);
        monitorsCol.setPadding(0, (int)(6*s), 0, 0);
        card.addView(monitorsCol, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshCodes() {
        if (codesBody == null) return;
        Health.EngineReport r = report != null ? report : repo.getLastReport();
        if (reading && r == null) {
            codesBody.setText("Leyendo…");
        } else if (r == null) {
            codesBody.setText("Sin reporte. Toca «Leer de nuevo».");
        } else {
            Map<String, Set<String>> grouped = new LinkedHashMap<String, Set<String>>();
            for (String c : r.stored) addKind(grouped, c, "Guardado");
            if (r.permanent != null) for (String c : r.permanent) addKind(grouped, c, "Permanente");
            for (String c : r.pending) addKind(grouped, c, "Pendiente");
            if (grouped.isEmpty()) {
                codesBody.setText("Sin códigos. MIL: " + (r.readiness != null ? (r.readiness.mil ? "ENCENDIDA" : "apagada") : "?"));
            } else {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Set<String>> e : grouped.entrySet()) {
                    sb.append("• ").append(e.getKey()).append(" — ").append(Codes.describe(e.getKey())).append("\n  ").append(join(new ArrayList<String>(e.getValue()), " · ")).append(" · ").append(zoneName(Health.zoneOf(e.getKey()))).append("\n");
                }
                codesBody.setText(sb.toString().trim());
            }
        }
        if (inspectPass != null && monitorsCol != null) {
            monitorsCol.removeAllViews();
            if (r == null || r.readiness == null) {
                inspectPass.setText(reading ? "Leyendo…" : "Sin datos de monitores.");
                inspectPass.setTextColor(MUTED());
            } else {
                Pids.Readiness rd = r.readiness;
                int incomplete = rd.incomplete().size();
                boolean pass = !rd.mil && incomplete <= 1;
                inspectPass.setText(pass ? "Pasaría la verificación" : "No pasaría la verificación");
                inspectPass.setTextColor(pass ? GOOD : DANGER);
                for (Pids.Monitor m : rd.monitors) {
                    monitorsCol.addView(monitorRow(m), new LinearLayout.LayoutParams(-1, -2));
                }
                if (rd.monitors.isEmpty()) {
                    TextView none = txt("Sin monitores reportados.", 12, MUTED(), false);
                    monitorsCol.addView(none, new LinearLayout.LayoutParams(-1, -2));
                }
            }
        }
    }

    private void addKind(Map<String, Set<String>> g, String code, String kind) {
        Set<String> set = g.get(code);
        if (set == null) { set = new LinkedHashSet<String>(); g.put(code, set); }
        set.add(kind);
    }

    private LinearLayout monitorRow(Pids.Monitor m) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(rounded(m.complete ? GOOD : WARN, 5, 0));
        row.addView(dot, new LinearLayout.LayoutParams((int)(8*s),(int)(8*s)));
        TextView name = txt(monitorName(m.name), 12, TEXT(), false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding((int)(10*s), (int)(4*s), (int)(10*s), (int)(4*s));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView st = txt(m.complete ? "Listo" : "Pendiente", 11, MUTED(), false);
        row.addView(st, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static String monitorName(String n) {
        if ("MISFIRE".equals(n)) return "Fallas encendido";
        if ("FUEL".equals(n)) return "Combustible";
        if ("COMPONENTS".equals(n)) return "Componentes";
        if ("CATALYST".equals(n)) return "Catalizador";
        if ("HEATED_CATALYST".equals(n)) return "Catalizador calorizado";
        if ("EVAP".equals(n)) return "Evaporación";
        if ("SECONDARY_AIR".equals(n)) return "Aire secundario";
        if ("O2_SENSOR".equals(n)) return "Sensor O2";
        if ("O2_HEATER".equals(n)) return "Calentador O2";
        if ("EGR".equals(n)) return "EGR";
        return n;
    }

    private void confirmClear() {
        final Health.EngineReport r = report != null ? report : repo.getLastReport();
        if (r == null || (r.stored.isEmpty() && r.pending.isEmpty() && (r.readiness == null || !r.readiness.mil))) {
            if (codesCleared != null) { codesCleared.setText("No hay códigos para borrar."); codesCleared.setVisibility(View.VISIBLE); }
            return;
        }
        new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Borrar códigos")
            .setMessage("¿Seguro? Borrar también reinicia los monitores de la verificación.")
            .setPositiveButton("Sí, borrar", new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable(){ public void run(){
                        try { repo.clearCodes(); } catch (Throwable ignored) {}
                        try { final Health.EngineReport nr = repo.report(); ui.post(new Runnable(){ public void run(){ report = nr; if (codesCleared != null) { codesCleared.setText("Códigos borrados."); codesCleared.setVisibility(View.VISIBLE); } refreshCodes(); refreshHealth(); } }); }
                        catch (Throwable e) { ui.post(new Runnable(){ public void run(){ if (codesCleared != null) { codesCleared.setText("No se pudo borrar."); codesCleared.setVisibility(View.VISIBLE); } } }); }
                    }}).start();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    // ---------- SENSORES (Workshop.kt LiveSensors) ----------
    private static final String[] SENSOR_16 = {
        Pids.RPM, Pids.COOLANT, Pids.VOLTAGE, Pids.CONSUMPTION, Pids.SPEED, Pids.LOAD,
        Pids.MAF, Pids.INTAKE, Pids.FUEL, Pids.MAP, Pids.AMBIENT, Pids.THROTTLE,
        Pids.FUEL_TRIM_SHORT, Pids.FUEL_TRIM_LONG, Pids.TIMING, Pids.RUN_TIME,
        Pids.O2S11, Pids.O2S12, Pids.CAT_TEMP
    };

    private void buildSensorsCard(LinearLayout col) {
        LinearLayout card = card(col, "Sensores en vivo");
        TextView help = txt(SENSOR_16.length + " PIDs en vivo con su tendencia. Solo se leen mientras ves esta pestaña.", 12, MUTED(), false);
        help.setPadding(0, (int)(4*s), 0, (int)(8*s));
        card.addView(help, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        int rows = (SENSOR_16.length + 3) / 4;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 4; c++) {
                int idx = r*4+c;
                if (idx >= SENSOR_16.length) break;
                String sig = SENSOR_16[idx];
                line.addView(sensorTile(sig), new LinearLayout.LayoutParams(0, -2, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        refreshSensors();
    }

    private LinearLayout sensorTile(String sig) {
        LinearLayout b = new LinearLayout(host);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(rounded(INNER(), 10, 0));
        b.setPadding((int)(8*s),(int)(6*s),(int)(8*s),(int)(6*s));
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(rounded(ACCENT(), 5, 0));
        head.addView(dot, new LinearLayout.LayoutParams((int)(8*s),(int)(8*s)));
        TextView name = txt(sensorName(sig), 11, MUTED(), false);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding((int)(6*s), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        b.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView value = txt("--", 14, TEXT(), true);
        value.setMaxLines(1);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setPadding(0, (int)(4*s), 0, 0);
        b.addView(value, new LinearLayout.LayoutParams(-1, -2));
        Sparkline spark = new Sparkline();
        spark.setColor(ACCENT());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, (int)(22*s));
        slp.topMargin = (int)(2*s);
        b.addView(spark, slp);
        sensorTiles.put(sig, new TileW(dot, name, value, null, spark));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = (int)(4*s); lp.rightMargin = (int)(4*s); lp.topMargin = (int)(4*s); lp.bottomMargin = (int)(4*s);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshSensors() {
        Map<String,Float> v = repo.getValues();
        for (Map.Entry<String, TileW> e : sensorTiles.entrySet()) {
            Float f = v.get(e.getKey());
            e.getValue().value.setText(f != null ? sensorText(e.getKey(), f) : "--");
            e.getValue().spark.setPoints(repo.getHistory(e.getKey()));
        }
    }

    private List<String> readableSignals() {
        Set<Integer> supported = repo.getSupported();
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Pids.Pid p : Pids.ALL) { if (seen.add(p.signal) && canRead(p.signal, supported)) out.add(p.signal); }
        for (Derived.DerivedSignal d : Derived.ALL) { if (seen.add(d.signal) && canRead(d.signal, supported)) out.add(d.signal); }
        List<String> order = new ArrayList<String>();
        String[] prio = {Pids.RPM, Pids.COOLANT, Pids.VOLTAGE, Pids.CONSUMPTION, Pids.SPEED, Pids.LOAD, Pids.OIL, Pids.BOOST};
        for (String p : prio) if (out.contains(p)) order.add(p);
        for (String s : out) if (!order.contains(s)) order.add(s);
        return order;
    }

    private boolean canRead(String signal, Set<Integer> supported) {
        if (Pids.VOLTAGE.equals(signal)) return true;
        for (Derived.DerivedSignal d : Derived.ALL) {
            if (d.signal.equals(signal)) {
                for (String n : d.needs) if (!canRead(n, supported)) return false;
                if (!d.anyOf.isEmpty()) {
                    boolean any = false;
                    for (String a : d.anyOf) if (canRead(a, supported)) any = true;
                    if (!any) return false;
                }
                return true;
            }
        }
        for (Pids.Pid p : Pids.ALL) if (p.signal.equals(signal)) {
            if (supported.isEmpty()) return true;
            return supported.contains(p.code);
        }
        return false;
    }

    static String sensorName(String signal) {
        if (Pids.SPEED.equals(signal)) return "Velocidad";
        if (Pids.RPM.equals(signal)) return "Revoluciones";
        if (Pids.LOAD.equals(signal)) return "Carga";
        if (Pids.MAP.equals(signal)) return "Presión MAP";
        if (Pids.MAF.equals(signal)) return "Flujo MAF";
        if (Pids.COOLANT.equals(signal)) return "Temp. motor";
        if (Pids.INTAKE.equals(signal)) return "Temp. admisión";
        if (Pids.FUEL.equals(signal)) return "Gasolina";
        if (Pids.VOLTAGE.equals(signal)) return "Voltaje";
        if (Pids.BARO.equals(signal)) return "Presión barométrica";
        if (Pids.CONSUMPTION.equals(signal)) return "Consumo";
        if (Pids.BOOST.equals(signal)) return "Turbo";
        if (Pids.FUEL_TRIM_SHORT.equals(signal)) return "Trim corto";
        if (Pids.FUEL_TRIM_LONG.equals(signal)) return "Trim largo";
        if (Pids.FUEL_PRESSURE.equals(signal)) return "Presión comb.";
        if (Pids.TIMING.equals(signal)) return "Avance encendido";
        if (Pids.THROTTLE.equals(signal)) return "Acelerador";
        if (Pids.RUN_TIME.equals(signal)) return "Tiempo motor";
        if (Pids.DISTANCE_MIL.equals(signal)) return "Distancia";
        if (Pids.OIL.equals(signal)) return "Temp. aceite";
        if (Pids.FUEL_RATE.equals(signal)) return "Gasto gasolina";
        if (Pids.AMBIENT.equals(signal)) return "Temp. exterior";
        if (Pids.O2S11.equals(signal)) return "O2 B1S1";
        if (Pids.O2S12.equals(signal)) return "O2 B1S2 Post-Cat";
        if (Pids.CAT_TEMP.equals(signal)) return "Temp. Catalizador";
        return signal;
    }

    static String sensorText(String signal, float v) {
        if (Pids.SPEED.equals(signal)) return String.format("%.0f km/h", v);
        if (Pids.RPM.equals(signal)) return String.format("%.0f rpm", v);
        if (Pids.LOAD.equals(signal) || Pids.FUEL.equals(signal) || Pids.THROTTLE.equals(signal)) return String.format("%.0f %%", v);
        if (Pids.MAP.equals(signal) || Pids.BARO.equals(signal) || Pids.FUEL_PRESSURE.equals(signal)) return String.format("%.0f kPa", v);
        if (Pids.MAF.equals(signal)) return String.format("%.1f g/s", v);
        if (Pids.FUEL_TRIM_SHORT.equals(signal) || Pids.FUEL_TRIM_LONG.equals(signal)) return String.format("%+.1f %%", v);
        if (Pids.TIMING.equals(signal)) return String.format("%.1f°", v);
        if (Pids.RUN_TIME.equals(signal)) { int m = (int)(v / 60); return String.format("%d:%02d", m / 60, m % 60); }
        if (Pids.DISTANCE_MIL.equals(signal)) return String.format("%.0f km", v);
        if (Pids.VOLTAGE.equals(signal)) return String.format("%.1f V", v);
        if (Pids.CONSUMPTION.equals(signal)) return String.format("%.1f km/L", v);
        if (Pids.BOOST.equals(signal)) return String.format("%.1f psi", v * 0.145f);
        if (Pids.FUEL_RATE.equals(signal)) return String.format("%.1f L/h", v);
        if (Pids.O2S11.equals(signal) || Pids.O2S12.equals(signal)) return String.format("%.2f V", v);
        return String.format("%.0f°C", v);
    }

    private static float minSpanFor(String signal) {
        if (Pids.RPM.equals(signal)) return 200f;
        if (Pids.SPEED.equals(signal)) return 10f;
        if (Pids.COOLANT.equals(signal) || Pids.INTAKE.equals(signal) || Pids.AMBIENT.equals(signal) || Pids.OIL.equals(signal)) return 10f;
        if (Pids.VOLTAGE.equals(signal)) return 1f;
        if (Pids.CONSUMPTION.equals(signal)) return 2f;
        if (Pids.LOAD.equals(signal) || Pids.FUEL.equals(signal) || Pids.THROTTLE.equals(signal)) return 10f;
        if (Pids.MAF.equals(signal)) return 5f;
        if (Pids.MAP.equals(signal) || Pids.BARO.equals(signal)) return 10f;
        if (Pids.FUEL_TRIM_SHORT.equals(signal) || Pids.FUEL_TRIM_LONG.equals(signal)) return 5f;
        if (Pids.TIMING.equals(signal)) return 5f;
        if (Pids.BOOST.equals(signal)) return 10f;
        if (Pids.O2S11.equals(signal) || Pids.O2S12.equals(signal)) return 0.5f;
        if (Pids.CAT_TEMP.equals(signal)) return 50f;
        return 0f;
    }

    // ---------- GRAFICAS (WorkshopAdvanced.kt LiveCharts) ----------
    private void buildChartsCard(LinearLayout col) {
        LinearLayout card = card(col, "Gráficas");
        TextView help = txt("Trazado continuo de las últimas lecturas. Toca la gráfica para ver un valor.", 12, MUTED(), false);
        help.setPadding(0, (int)(4*s), 0, (int)(6*s));
        card.addView(help, new LinearLayout.LayoutParams(-1, -2));
        if (chosen.isEmpty()) {
            List<String> rs = readableSignals();
            if (rs.contains(Pids.RPM)) chosen.add(Pids.RPM);
            if (rs.contains(Pids.SPEED)) chosen.add(Pids.SPEED);
            if (chosen.isEmpty() && !rs.isEmpty()) chosen.add(rs.get(0));
        }
        chartLegend = new LinearLayout(host);
        chartLegend.setOrientation(LinearLayout.VERTICAL);
        card.addView(chartLegend, new LinearLayout.LayoutParams(-1, -2));
        chartView = new ChartView();
        chartView.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(220*s)));
        card.addView(chartView, new LinearLayout.LayoutParams(-1, (int)(220*s)));
        touchFrac = null;
        chartView.setOnTouchListener(new View.OnTouchListener(){
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                    float f = e.getX() / Math.max(1, v.getWidth());
                    touchFrac = f < 0f ? 0f : f > 1f ? 1f : f;
                    refreshCharts();
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) { touchFrac = null; refreshCharts(); return true; }
                return false;
            }
        });
        HorizontalScrollView hsv = new HorizontalScrollView(host);
        hsv.setScrollbarFadingEnabled(true);
        chipsCol = new LinearLayout(host);
        chipsCol.setOrientation(LinearLayout.HORIZONTAL);
        chipsCol.setPadding(0, (int)(8*s), 0, 0);
        hsv.addView(chipsCol, new LinearLayout.LayoutParams(-2, -2));
        card.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
        buildChips();
        refreshCharts();
    }

    private void buildChips() {
        if (chipsCol == null) return;
        chipsCol.removeAllViews();
        List<String> signals = readableSignals();
        for (final String sig : signals) {
            boolean sel = chosen.contains(sig);
            TextView chip = txt(sensorName(sig), 12, sel ? Color.WHITE : TEXT(), true);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(rounded(sel ? ACCENT() : INNER(), 12, sel ? 0 : BORDER()));
            chip.setPadding((int)(10*s),(int)(6*s),(int)(10*s),(int)(6*s));
            chip.setMaxLines(1);
            chip.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                if (chosen.contains(sig)) chosen.remove(sig);
                else if (chosen.size() < 10) chosen.add(sig);
                else { chosen.remove(0); chosen.add(sig); }
                touchFrac = null;
                buildChips();
                if (chartLegend != null) refreshCharts();
            } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = (int)(6*s);
            chipsCol.addView(chip, lp);
        }
    }

    private static final int[] CHART_COLORS = {0xFFF44336, 0xFF4CA6FF, 0xFF9CCC65, 0xFFEAC54F, 0xFFBA68C8, 0xFF4DD0E1, 0xFFFF8A65, 0xFFA1887F, 0xFFF06292, 0xFFD29922};

    private int chartColor(int i) {
        if (i == 0) return ACCENT();
        if (i == 1) return CHART2;
        return CHART_COLORS[i % CHART_COLORS.length];
    }

    private void refreshCharts() {
        if (chartLegend == null || chartView == null) return;
        chartLegend.removeAllViews();
        List<List<Float>> series = new ArrayList<List<Float>>();
        for (String sig : chosen) series.add(repo.getHistory(sig));
        for (int i = 0; i < chosen.size(); i++) {
            String sig = chosen.get(i);
            List<Float> pts = series.get(i);
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(host);
            dot.setBackground(rounded(chartColor(i), 5, 0));
            row.addView(dot, new LinearLayout.LayoutParams((int)(8*s),(int)(8*s)));
            TextView name = txt(sensorName(sig), 12, TEXT(), false);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding((int)(8*s), (int)(3*s), (int)(8*s), (int)(3*s));
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            if (!pts.isEmpty()) {
                float mn = 0f, mx = 0f; boolean any = false;
                for (Float f : pts) { if (f == null) continue; if (!any) { mn = f; mx = f; any = true; } else { if (f < mn) mn = f; if (f > mx) mx = f; } }
                if (!any) { TextView nd = txt("--", 13, MUTED(), true); nd.setPadding((int)(8*s), 0, 0, 0); row.addView(nd, new LinearLayout.LayoutParams(-2, -2)); chartLegend.addView(row, new LinearLayout.LayoutParams(-1, -2)); continue; }
                TextView range = txt(sensorText(sig, mn) + " … " + sensorText(sig, mx), 11, MUTED(), false);
                range.setMaxLines(1);
                range.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(range, new LinearLayout.LayoutParams(-2, -2));
                Float shown = null;
                if (touchFrac != null) shown = pointAt(pts, touchFrac);
                else shown = pts.get(pts.size()-1);
                TextView cur = txt(shown != null ? sensorText(sig, shown) : "—", 13, chartColor(i), true);
                cur.setMaxLines(1);
                cur.setPadding((int)(8*s), 0, 0, 0);
                row.addView(cur, new LinearLayout.LayoutParams(-2, -2));
            } else {
                TextView cur = txt("—", 13, MUTED(), true);
                cur.setPadding((int)(8*s), 0, 0, 0);
                row.addView(cur, new LinearLayout.LayoutParams(-2, -2));
            }
            chartLegend.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        chartView.setSeries(series);
        int[] cc = new int[series.size()];
        for (int i = 0; i < series.size(); i++) cc[i] = chartColor(i);
        chartView.setColors(cc);
        chartView.setTouch(touchFrac);
        chartView.invalidate();
    }

    private static Float pointAt(List<Float> points, float fraction) {
        if (points.isEmpty() || SERIES_POINTS <= 1) return null;
        int k = Math.round(fraction * (SERIES_POINTS - 1)) - (SERIES_POINTS - points.size());
        return (k >= 0 && k < points.size()) ? points.get(k) : null;
    }

    // ---------- ADAPTADOR / MAS ----------
    private void buildAdapterCard(LinearLayout col) {
        LinearLayout card = card(col, "Adaptador OBD");
        adapterStatus = txt("", 14, TEXT(), true);
        adapterStatus.setMaxLines(2);
        card.addView(adapterStatus, new LinearLayout.LayoutParams(-1, -2));
        adapterHelp = txt("Empareja el ELM327 (ej. " + ObdRepository.DEFAULT_MAC + ") en Ajustes de Android. Bluetooth SPP con ATZ/ATE0/ATL0/ATS0/ATH0/ATAT1/ATSP0.", 12, MUTED(), false);
        adapterHelp.setPadding(0, (int)(4*s), 0, (int)(8*s));
        card.addView(adapterHelp, new LinearLayout.LayoutParams(-1, -2));
        adapterBtns = new LinearLayout(host);
        adapterBtns.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(adapterBtns, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout demoRow = new LinearLayout(host);
        demoRow.setOrientation(LinearLayout.HORIZONTAL);
        demoRow.setGravity(Gravity.CENTER);
        demoRow.setPadding(0, (int)(10*s), 0, 0);
        TextView demoBtn = txt(repo.isDemo() ? "â•¹ Detener simulaciÃ³n" : "🎛 Simular sensores (demo)", 13, TEXT(), true);
        demoBtn.setGravity(Gravity.CENTER);
        demoBtn.setBackground(rounded(repo.isDemo() ? ACCENT() : INNER(), 14, 0));
        demoBtn.setTextColor(repo.isDemo() ? Color.WHITE : TEXT());
        demoBtn.setPadding((int)(16*s),(int)(8*s),(int)(16*s),(int)(8*s));
        demoBtn.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
            try { repo.setDemo(!repo.isDemo()); } catch (Throwable ignored) {}
            host.toast(repo.isDemo() ? "Simulación activa" : "Simulación detenida");
            render();
        } });
        demoRow.addView(demoBtn, new LinearLayout.LayoutParams(-2, -2));
        card.addView(demoRow, new LinearLayout.LayoutParams(-1, -2));
        refreshAdapter();
    }

    private void refreshAdapter() {
        if (adapterStatus == null) return;
        ObdRepository.State st = repo.getState();
        String status;
        int color;
        if (st == ObdRepository.State.OFF) { status = "Sin adaptador"; color = MUTED(); }
        else if (st == ObdRepository.State.CONNECTING) { status = "Conectando a " + repo.targetOrDefault() + "…"; color = TEXT(); }
        else if (st == ObdRepository.State.ON) { status = "Conectado a " + repo.targetOrDefault() + (repo.getAdapter() != null && repo.getAdapter().length() > 0 ? " · " + repo.getAdapter() : ""); color = GOOD; }
        else { status = "Error: " + (repo.getError() != null ? repo.getError() : "sin respuesta"); color = DANGER; }
        adapterStatus.setText(status);
        adapterStatus.setTextColor(color);
        if (st == lastAdapterBuilt) return;
        lastAdapterState = st; lastAdapterBuilt = st;
        adapterBtns.removeAllViews();
        if (st == ObdRepository.State.ON || st == ObdRepository.State.CONNECTING) {
            button(adapterBtns, "Desconectar", false, new Runnable(){ public void run(){ repo.disconnect(); } });
            button(adapterBtns, "Usar otro", false, new Runnable(){ public void run(){ repo.disconnect(); showPairedDialog(); } });
        } else {
            button(adapterBtns, "Conectar (" + repo.targetOrDefault() + ")", true, new Runnable(){ public void run(){ repo.connect(null); } });
            button(adapterBtns, "Buscar Bluetooth", false, new Runnable(){ public void run(){ showPairedDialog(); } });
        }
        // lista de emparejados como chips
        List<String[]> devs = repo.pairedDevices();
        if (devs.isEmpty()) {
            TextView none = txt("Sin adaptadores emparejados. Empareja el ELM327 en Ajustes de Android.", 11, MUTED(), false);
            none.setPadding(0, (int)(6*s), 0, 0);
            ((LinearLayout) adapterBtns.getParent()).addView(none, new LinearLayout.LayoutParams(-1, -2));
        } else {
            LinearLayout devRow = new LinearLayout(host);
            devRow.setOrientation(LinearLayout.HORIZONTAL);
            devRow.setPadding(0, (int)(6*s), 0, 0);
            for (final String[] d : devs) {
                button(devRow, d[1] + " (" + d[0] + ")", false, new Runnable(){ public void run(){ repo.connect(d[0]); } });
            }
            ((LinearLayout) adapterBtns.getParent()).addView(devRow, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void showPairedDialog() {
        final List<String[]> devs = repo.pairedDevices();
        String[] items;
        if (devs.isEmpty()) items = new String[]{"Sin adaptadores emparejados"};
        else { items = new String[devs.size()]; for (int i = 0; i < devs.size(); i++) items[i] = devs.get(i)[1] + " (" + devs.get(i)[0] + ")"; }
        new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Buscar Bluetooth (ELM327)")
            .setItems(items, new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d, int w){ if (!devs.isEmpty()) repo.connect(devs.get(w)[0]); } })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private void buildAlertsCard(LinearLayout col) {
        LinearLayout card = card(col, "Alertas del motor");
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView d = txt("Avisa en el inicio si el motor se calienta de más o la batería no carga.", 12, SOFT(), false);
        d.setSingleLine(false);
        row.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(host);
        sw.setChecked(host.getPrefs().getBoolean("obd_alerts", false));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c){
                host.getPrefs().edit().putBoolean("obd_alerts", c).apply();
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildAccelCard(LinearLayout col) {
        LinearLayout card = card(col, "De 0 a 100 km/h");
        TextView help = txt("Cronómetro con la velocidad del OBD (PID 010D). Detén el auto, luego acelera.", 12, MUTED(), false);
        help.setPadding(0, (int)(4*s), 0, (int)(6*s));
        card.addView(help, new LinearLayout.LayoutParams(-1, -2));
        accelTime = txt("0.00 s", 38, TEXT(), true);
        accelTime.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(accelTime, new LinearLayout.LayoutParams(-1, -2));
        accelState = txt("", 13, SOFT(), false);
        accelState.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(accelState, new LinearLayout.LayoutParams(-1, -2));
        accelSpeed = txt("", 12, MUTED(), false);
        accelSpeed.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(accelSpeed, new LinearLayout.LayoutParams(-1, -2));
        accelBest = txt("", 12, ACCENT(), false);
        accelBest.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(accelBest, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int)(8*s), 0, 0);
        button(row, "Reiniciar", false, new Runnable(){ public void run(){ repo.getAccel().reset(); refreshAccel(); } });
        button(row, "Borrar mejor tiempo", false, new Runnable(){ public void run(){ host.getPrefs().edit().putFloat("obd_best_accel", 0f).apply(); refreshAccel(); } });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        refreshAccel();
    }

    private void refreshAccel() {
        if (accelTime == null) return;
        TripLog.AccelTimer t = repo.getAccel();
        Map<String,Float> v = repo.getValues();
        Float speed = v.get(Pids.SPEED);
        float shown = 0f;
        String stateTxt;
        if (t.state == TripLog.AccelTimer.RUNNING) { shown = (System.currentTimeMillis() - t.startMs) / 1000f; stateTxt = "Midiendo…"; }
        else if (t.state == TripLog.AccelTimer.DONE) { shown = t.seconds; stateTxt = "Listo"; }
        else if (t.state == TripLog.AccelTimer.READY) { stateTxt = "Listo para arrancar (0 km/h)"; }
        else { stateTxt = "Detén el auto para armar"; }
        accelTime.setText(String.format("%.2f s", shown));
        accelTime.setTextColor(t.state == TripLog.AccelTimer.DONE ? ACCENT() : TEXT());
        accelState.setText(stateTxt);
        accelSpeed.setText((speed != null ? String.format("%.0f km/h", speed) : "—") + " · OBD");
        float best = 0f;
        try { best = host.getPrefs().getFloat("obd_best_accel", 0f); } catch (Throwable ignored) {}
        if (t.state == TripLog.AccelTimer.DONE && (best <= 0f || t.seconds < best)) {
            best = t.seconds;
            try { host.getPrefs().edit().putFloat("obd_best_accel", best).apply(); } catch (Throwable ignored) {}
        }
        accelBest.setText(best > 0f ? String.format("Mejor: %.2f s", best) : "");
    }

    private void buildTripsCard(LinearLayout col) {
        LinearLayout card = card(col, "Viajes");
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView d = txt("Guarda un resumen de cada viaje: distancia, tiempo, velocidades, temperatura y gasolina.", 12, SOFT(), false);
        d.setSingleLine(false);
        row.addView(d, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(host);
        sw.setChecked(host.getPrefs().getBoolean("obd_trip_log", false));
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener(){
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c){
                host.getPrefs().edit().putBoolean("obd_trip_log", c).apply();
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        tripsCol = new LinearLayout(host);
        tripsCol.setOrientation(LinearLayout.VERTICAL);
        tripsCol.setPadding(0, (int)(6*s), 0, 0);
        card.addView(tripsCol, new LinearLayout.LayoutParams(-1, -2));
        refreshTrips();
    }

    private void refreshTrips() {
        if (tripsCol == null) return;
        tripsCol.removeAllViews();
        List<TripLog.TripSummary> trips;
        try { trips = new TripLog.TripStore(host).all(); }
        catch (Throwable t) { trips = new ArrayList<TripLog.TripSummary>(); }
        if (trips.isEmpty()) {
            TextView none = txt("Sin viajes guardados.", 12, MUTED(), false);
            tripsCol.addView(none, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        DateFormat dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        int n = Math.min(trips.size(), 10);
        for (int i = 0; i < n; i++) {
            TripLog.TripSummary t = trips.get(i);
            TextView line = txt(dates.format(new Date(t.start)) + "  ·  " + String.format("%.1f km · %d:%02d h", t.km, t.seconds/60/60, (t.seconds/60)%60), 12, TEXT(), false);
            line.setMaxLines(1);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tripsCol.addView(line, new LinearLayout.LayoutParams(-1, -2));
            List<String> extras = new ArrayList<String>();
            extras.add("prom " + String.format("%.0f km/h", t.avgKmh));
            extras.add("máx " + String.format("%.0f km/h", t.maxKmh));
            if (t.maxRpm > 0f) extras.add(String.format("%.0f rpm", t.maxRpm));
            if (t.maxCoolant != null) extras.add(String.format("%.0f°C", t.maxCoolant));
            if (t.kmPerLiter() != null) extras.add(String.format("%.1f km/L", t.kmPerLiter()));
            TextView sub = txt(join(extras, " · "), 11, MUTED(), false);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setPadding(0, 0, 0, (int)(6*s));
            tripsCol.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void refreshLive() {
        if (root == null) return;
        // Tab 0 Salud
        if (!healthTiles.isEmpty()) refreshHealth();
        if (!sensorTiles.isEmpty()) refreshSensors();
        if (chartView != null && chartLegend != null) refreshCharts();
        if (accelTime != null) refreshAccel();
        if (adapterStatus != null) refreshAdapter();
        if (codesBody != null && currentTab == 1) refreshCodes();
    }

    // ---------- Vistas canvas ----------
    public class CarTopView extends View {
        private List<Health.SystemHealth> marks;
        public CarTopView() { super(host); }
        public void setHealth(List<Health.SystemHealth> hs) { marks = hs; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float sx = w / 200f, sy = h / 420f, sc = Math.min(sx, sy);
            c.save();
            c.scale(sc, sc);
            float ox = (w - 200*sc)/2/sc, oy = (h - 420*sc)/2/sc;
            c.translate(ox, oy);
            Paint sh = new Paint(); sh.setColor(Color.argb(110,0,0,0));
            c.drawCircle(100, 216, 118, sh);
            Paint tire = new Paint(); tire.setColor(0xFF2A2E34);
            float[][] wheels = {{14,118},{186,118},{14,302},{186,302}};
            for (float[] wh : wheels) { RectF r = new RectF(wh[0]-9, wh[1]-32, wh[0]+9, wh[1]+32); c.drawRoundRect(r, 6, 6, tire); }
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
            Paint bodyP = new Paint(); bodyP.setColor(ACCENT()); bodyP.setAntiAlias(true);
            bodyP.setAlpha(Main.LIGHT ? 120 : 170);
            bodyP.setColorFilter(new android.graphics.PorterDuffColorFilter(Main.LIGHT ? 0xFFBFD4E6 : 0xFF2A4A6B, android.graphics.PorterDuff.Mode.SRC_ATOP));
            c.drawPath(body, bodyP);
            Path mirrors = new Path();
            mirrors.moveTo(14,146); mirrors.lineTo(3,142); mirrors.cubicTo(0,143, 0,157, 3,158); mirrors.lineTo(14,160); mirrors.close();
            mirrors.moveTo(186,146); mirrors.lineTo(197,142); mirrors.cubicTo(200,143, 200,157, 197,158); mirrors.lineTo(186,160); mirrors.close();
            c.drawPath(mirrors, bodyP);
            Path ws = new Path(); ws.moveTo(36,124); ws.cubicTo(72,114,128,114,164,124); ws.lineTo(158,178); ws.cubicTo(128,171,72,171,42,178); ws.close();
            Path roof = new Path(); roof.moveTo(42,178); roof.cubicTo(72,171,128,171,158,178); roof.lineTo(158,298); roof.cubicTo(128,305,72,305,42,298); roof.close();
            Path rear = new Path(); rear.moveTo(42,298); rear.cubicTo(72,305,128,305,158,298); rear.lineTo(162,344); rear.cubicTo(128,337,72,337,38,344); rear.close();
            Paint glass = new Paint(); glass.setColor(0xFF1A202A); glass.setAntiAlias(true);
            c.drawPath(ws, glass); c.drawPath(rear, glass);
            Paint roofP = new Paint(); roofP.setColor(Color.argb(26,255,255,255)); c.drawPath(roof, roofP);
            Paint edge = new Paint(); edge.setColor(Color.argb(36,255,255,255)); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1f);
            c.drawPath(body, edge);
            c.restore();
            if (marks != null) {
                for (Health.SystemHealth hh : marks) {
                    if (hh.level != Health.HealthLevel.WARN && hh.level != Health.HealthLevel.FAULT) continue;
                    int col = hh.level == Health.HealthLevel.FAULT ? DANGER : WARN;
                    float mx = hh.zone.x * w, my = hh.zone.y * h;
                    float r = w * 0.06f;
                    Paint halo = new Paint();
                    halo.setColor(col); halo.setAlpha(99); halo.setStyle(Paint.Style.FILL);
                    c.drawCircle(mx, my, r * 3f, halo);
                    Paint ring = new Paint(); ring.setColor(col); ring.setAlpha(153); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Math.max(2f, r * 0.22f));
                    c.drawCircle(mx, my, r * 2f, ring);
                    Paint core = new Paint(); core.setColor(col); core.setStyle(Paint.Style.FILL);
                    c.drawCircle(mx, my, r * 0.55f, core);
                }
            }
        }
    }

    static List<Float> sanitize(List<Float> p) { List<Float> o = new ArrayList<Float>(); if (p != null) for (Float f : p) if (f != null) o.add(f); return o; }

    public class Sparkline extends View {
        private List<Float> points;
        private int color;
        public Sparkline() { super(host); }
        public void setPoints(List<Float> p) { points = sanitize(p); invalidate(); }
        public void setColor(int c) { color = c; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (points == null || points.size() < 2) return;
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            int cap = SERIES_POINTS;
            float mn = 0f, mx = 0f; boolean any = false;
            for (Float f : points) { if (f == null) continue; if (!any) { mn = f; mx = f; any = true; } else { if (f < mn) mn = f; if (f > mx) mx = f; } }
            if (!any) return;
            float half = Math.max((mx - mn) / 2f, 5e-4f);
            float lo = (mn + mx) / 2f - half;
            Paint strokeP = new Paint(); strokeP.setColor(color); strokeP.setStrokeWidth(Math.max(2f, 1.6f * s)); strokeP.setAntiAlias(true);
            strokeP.setStrokeCap(Paint.Cap.ROUND); strokeP.setStrokeJoin(Paint.Join.ROUND); strokeP.setStyle(Paint.Style.STROKE);
            Path line = new Path();
            boolean first=true;
            for (int k = 0; k < points.size(); k++) {
                Float fv = points.get(k); if (fv == null) continue;
                float x = w * (cap - points.size() + k) / (cap - 1);
                float frac = (fv - lo) / (2 * half);
                float y = h * (1f - frac) * 0.8f + h * 0.1f;
                if (first) { line.moveTo(x, y); first = false; } else line.lineTo(x, y);
            }
            if (first) return;
            Paint fill = new Paint(); fill.setColor(color); fill.setAlpha(55); fill.setStyle(Paint.Style.FILL); fill.setAntiAlias(true);
            Path area = new Path(area(line, w, h, cap, points.size()));
            c.drawPath(area, fill);
            c.drawPath(line, strokeP);
        }
        private Path area(Path line, float w, float h, int cap, int n) {
            Path p = new Path(line);
            p.lineTo(w, h); p.lineTo(w * (cap - n) / (cap - 1), h); p.close();
            return p;
        }
    }

    public class ChartView extends View {
        private List<List<Float>> series = new ArrayList<List<Float>>();
        private int[] colors = new int[]{ACCENT(), CHART2};
        private Float touch;
        public ChartView() { super(host); }
        public void setSeries(List<List<Float>> s2) { series = new ArrayList<List<Float>>(); if (s2 != null) for (List<Float> p : s2) series.add(sanitize(p)); invalidate(); }
        public void setColors(int[] c) { colors = c; }
        public void setTouch(Float f) { touch = f; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            Paint grid = new Paint(); grid.setColor(BORDER()); grid.setStrokeWidth(1f);
            for (int k = 0; k <= 4; k++) {
                float y = h * k / 4f;
                c.drawLine(0, y, w, y, grid);
            }
            for (int i = 0; i < series.size(); i++) {
                List<Float> pts = series.get(i);
                if (pts == null || pts.size() < 2) continue;
                float mn = 0f, mx = 0f; boolean any = false;
                for (Float f : pts) { if (f == null) continue; if (!any) { mn = f; mx = f; any = true; } else { if (f < mn) mn = f; if (f > mx) mx = f; } }
                if (!any) continue;
                float hi = (mx - mn < 1e-3f) ? mn + 1f : mx;
                float lo = mn;
                int col = i < colors.length ? colors[i] : ACCENT();
                Paint strokeP = new Paint(); strokeP.setColor(col); strokeP.setStrokeWidth(Math.max(2f, 2f * s));
                strokeP.setAntiAlias(true); strokeP.setStrokeCap(Paint.Cap.ROUND); strokeP.setStrokeJoin(Paint.Join.ROUND); strokeP.setStyle(Paint.Style.STROKE);
                Path line = new Path();
                boolean first = true;
                for (int k = 0; k < pts.size(); k++) {
                    Float pv = pts.get(k); if (pv == null) continue;
                    float x = w * (SERIES_POINTS - pts.size() + k) / (SERIES_POINTS - 1);
                    float frac = (pv - lo) / (hi - lo);
                    float y = h * (1f - frac) * 0.9f + h * 0.05f;
                    if (first) { line.moveTo(x, y); first = false; } else line.lineTo(x, y);
                }
                c.drawPath(line, strokeP);
                if (touch != null) {
                    Float v = pointAt(pts, touch);
                    if (v != null) {
                        float x = w * touch;
                        float frac = (v - lo) / (hi - lo);
                        float y = h * (1f - frac) * 0.9f + h * 0.05f;
                        Paint dotP = new Paint(); dotP.setColor(col); dotP.setStyle(Paint.Style.FILL);
                        c.drawCircle(x, y, 5f * s, dotP);
                    }
                }
            }
            if (touch != null) {
                Paint cur = new Paint(); cur.setColor(MUTED()); cur.setStrokeWidth(1f);
                float x = w * touch;
                c.drawLine(x, 0, x, h, cur);
            }
        }
    }

    // Queda para compatibilidad con codigo previo que llamaba a scanCodes().
    public void scanCodes() { readReport(); currentTab = 1; try { host.getPrefs().edit().putInt("workshop_tab", 1).apply(); } catch (Throwable ignored) {} render(); }
}
