package com.fordlauncher.launcher;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Handler;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Módulo Teléfono (reemplaza al botón Cámara del dock): Favoritos, Recientes,
 * Contactos y Teclado. Estilo programático como DiagnosticoView, colores del tema de Main.
 */
public class TelefonoView {
    private final Main host;
    private final float s;
    private final Handler ui = new Handler();

    private static final String[] TAB_NAMES = {"Favoritos", "Recientes", "Contactos", "Teclado"};
    private static final String[] REC_FILTERS = {"Todas", "Llamadas", "Números desconocidos", "Perdidas"};
    private int tab = 1;
    private int recFilter;

    private static final class Contact {
        String key, name, number;
        Uri photoUri;
        boolean starred;
    }
    public static final class CallRec {
        String number, name;
        int type;
        long date;
        long duration;
        boolean known;
    }
    private final List<Contact> contacts = new ArrayList<Contact>();
    private final Map<String, Bitmap> photoCache = new HashMap<String, Bitmap>();
    private final List<CallRec> calls = new ArrayList<CallRec>();
    private final Map<String, String> nameCache = new HashMap<String, String>();
    private LinearLayout content;
    private EditText searchEt;
    private TextView dialNumber;
    private String dialText = "";
    private ToneGenerator tone;

    private int BG() { return Main.BG; }
    private int CARD() { return Main.CARD; }
    private int INNER() { return Main.INNER; }
    private int MUTED() { return Main.MUTED; }
    private int SOFT() { return Main.SOFT; }
    private int TEXT() { return Main.TEXT; }
    private int BORDER() { return Main.BTN; }
    private int ACCENT() { return Main.RED; }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusDp * s);
        if (stroke != 0) g.setStroke(Math.max(1, (int) s), stroke);
        return g;
    }

    private TextView txt(String t, float sizeSp, int color, boolean bold) {
        TextView v = new TextView(host);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        v.setIncludeFontPadding(false);
        v.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        return v;
    }

    private LinearLayout card(LinearLayout col, String titleSmall) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD(), 16, 0));
        card.setPadding((int) (14 * s), (int) (12 * s), (int) (14 * s), (int) (12 * s));
        if (titleSmall != null) {
            TextView h = txt(titleSmall, 11, MUTED(), true);
            h.setText(titleSmall.toUpperCase());
            h.setMaxLines(1);
            h.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(h, new LinearLayout.LayoutParams(-1, -2));
        }
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
        lp.bottomMargin = (int) (10 * s);
        return card;
    }

    public TelefonoView(Main h, float scale) { host = h; s = scale; }

    public LinearLayout build() {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG());
        root.setPadding((int) (10 * s), (int) (10 * s), (int) (10 * s), (int) (10 * s));

        // Barra superior de navegación (chips)
        LinearLayout tabBar = new LinearLayout(host);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, 0, 0, (int) (8 * s));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = txt(TAB_NAMES[i], 13, TEXT(), true);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding((int) (6 * s), (int) (9 * s), (int) (6 * s), (int) (9 * s));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectTab(idx); }
            });
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (i > 0) { /* márgenes */ }
        }
        root.addView(tabBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        sc.addView(content, new FrameLayout.LayoutParams(-1, -2));
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        render();
        return root;
    }

    private void paintTabs(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView t = (TextView) v;
            boolean sel = (TAB_NAMES[i].length() > 0) && tab == i;
            t.setBackground(rounded(sel ? ACCENT() : INNER(), 10, 0));
            t.setTextColor(sel ? Color.WHITE : TEXT());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.leftMargin = (int) (4 * s);
            lp.rightMargin = (int) (4 * s);
        }
    }

    private void selectTab(int i) { tab = i; render(); }

    public void refresh() { render(); }

    private void render() {
        if (content == null) return;
        LinearLayout parent = (LinearLayout) content.getParent().getParent();
        if (parent != null && parent.getChildCount() > 0) paintTabs((LinearLayout) parent.getChildAt(0));
        content.removeAllViews();
        if (!host.phonePermsGranted() && tab != 3) {
            permCard();
            return;
        }
        switch (tab) {
            case 0: favTab(); break;
            case 1: recentsTab(); break;
            case 2: contactsTab(); break;
            default: keypadTab(); break;
        }
    }

    private void permCard() {
        LinearLayout c = card(content, "Permisos");
        TextView t = txt("Para ver contactos e historial necesito permisos (teléfono y contactos). Puedo usarlos solo siempre que abras Teléfono.", 13, MUTED(), false);
        t.setPadding(0, (int) (6 * s), 0, (int) (10 * s));
        t.setMaxLines(3);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView b = txt("Permitir", 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(ACCENT(), 14, 0));
        b.setPadding((int) (20 * s), (int) (8 * s), (int) (20 * s), (int) (8 * s));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.ensurePhonePerms(); }
        });
        row.addView(b, new LinearLayout.LayoutParams(-2, -2));
        c.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------- llamadas ----------
    private void toast(String m) { try { android.widget.Toast.makeText(host, m, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {} }

    void callNumber(String number) {
        if (number == null) return;
        String n = cleanNumber(number);
        if (n == null) { toast("Número vacío"); return; }
        // Canales BT de la radio (AutoChips / GooCo).
        try { host.sendBroadcast(new Intent("com.autochips.bluetooth.action.DIAL").putExtra("phone_number", n)); } catch (Throwable ignored) {}
        try { host.sendBroadcast(new Intent("com.goodocom.gocsdk.DIAL").putExtra("dial_number", n)); } catch (Throwable ignored) {}
        // Llamada directa con el marcado del sistema.
        Intent call = new Intent(Intent.ACTION_CALL);
        call.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        call.setData(Uri.parse("tel:" + Uri.encode(n)));
        try {
            host.startActivity(call);
        } catch (SecurityException e) {
            // Sin permiso de llamada: abrir el dialer y avisar.
            toast("Sin permiso de llamada: abriendo discado");
            try {
                host.startActivity(new Intent(Intent.ACTION_DIAL).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(Uri.parse("tel:" + Uri.encode(n))));
            } catch (Throwable t2) { toast("No se pudo llamar"); }
        } catch (Throwable t) { toast("No se pudo llamar"); }
    }

    private static String cleanNumber(String n) {
        if (n == null) return null;
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= '0' && c <= '9') || c == '+' || c == '#' || c == '*') o.append(c);
        }
        return o.length() > 0 ? o.toString() : null;
    }

    // ---------- FAVORITOS ----------
    private void favTab() {
        loadContacts();
        LinearLayout c = card(content, "Favoritos (toca para llamar)");
        List<Contact> favs = new ArrayList<Contact>();
        for (Contact ct : contacts) if (ct.starred) favs.add(ct);
        if (favs.isEmpty()) {
            TextView t = txt("Sin favoritos todavía. En Contactos toca la estrella para marcar.", 13, MUTED(), false);
            t.setPadding(0, (int) (6 * s), 0, 0);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        int cols = 5;
        for (int i = 0; i < favs.size(); i += cols) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols && i + j < favs.size(); j++) line.addView(favTile(favs.get(i + j)), new LinearLayout.LayoutParams(0, -2, 1f));
            grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        c.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout favTile(final Contact ct) {
        LinearLayout t = new LinearLayout(host);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (4 * s), (int) (10 * s), (int) (4 * s), (int) (10 * s));
        ImageView photo = avatar(ct, (int) (54 * s));
        photo.setOnClickListener(null);
        t.addView(photo, new LinearLayout.LayoutParams((int) (54 * s), (int) (54 * s)));
        TextView name = txt(ct.name, 12, TEXT(), true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, (int) (4 * s), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(-1, -2));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        return t;
    }

    // ---------- RECIENTES ----------
    private void recentsTab() {
        loadRecents();
        LinearLayout c = card(content, "Historial de llamadas");

        // Filtro superior estilo chips (Todas / Llamadas / Desconocidos / Perdidas)
        LinearLayout filt = new LinearLayout(host);
        filt.setOrientation(LinearLayout.HORIZONTAL);
        filt.setPadding(0, (int) (4 * s), 0, (int) (10 * s));
        for (int i = 0; i < REC_FILTERS.length; i++) {
            final int idx = i;
            boolean sel = recFilter == i;
            TextView chip = txt(REC_FILTERS[i], 12, sel ? Color.WHITE : TEXT(), true);
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setBackground(rounded(sel ? ACCENT() : INNER(), 14, 0));
            chip.setPadding((int) (10 * s), (int) (6 * s), (int) (10 * s), (int) (6 * s));
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { recFilter = idx; render(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = (int) (4 * s);
            lp.rightMargin = (int) (4 * s);
            filt.addView(chip, lp);
        }
        c.addView(filt, new LinearLayout.LayoutParams(-1, -2));

        List<CallRec> shown = new ArrayList<CallRec>();
        for (CallRec r : calls) {
            boolean a = true;
            if (recFilter == 1) a = r.known || (r.duration > 0);           // Llamadas
            else if (recFilter == 2) a = !r.known;                            // Números desconocidos
            else if (recFilter == 3) a = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE; // Perdidas
            if (a) shown.add(r);
        }
        if (shown.isEmpty()) {
            TextView t = txt("Sin llamadas en este grupo.", 13, MUTED(), false);
            t.setPadding(0, (int) (4 * s), 0, 0);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (final CallRec r : shown) c.addView(recRow(r), new LinearLayout.LayoutParams(-1, -2));
    }

    private String recTypeName(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "Entrante";
            case CallLog.Calls.OUTGOING_TYPE: return "Saliente";
            case CallLog.Calls.MISSED_TYPE: return "Perdida";
            case CallLog.Calls.REJECTED_TYPE: return "Rechazada";
            default: return "Llamada";
        }
    }

    private LinearLayout recRow(final CallRec r) {
        boolean missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE;
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * s), 0, (int) (10 * s));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        String title = r.name != null ? r.name : (r.number != null ? r.number : "Desconocido");
        TextView nm = txt(title, 15, missed ? 0xFFF44336 : TEXT(), !missed);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        String sub = recTypeName(r.type) + " · " + relDate(r.date);
        if (r.name != null && r.number != null) sub = recTypeName(r.type) + " · " + r.number + " · " + relDate(r.date);
        TextView sb = txt(sub, 11, MUTED(), false);
        sb.setMaxLines(1);
        sb.setEllipsize(TextUtils.TruncateAt.END);
        sb.setPadding(0, (int) (2 * s), 0, 0);
        col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cb = txt("📞", 16, ACCENT(), false);
        cb.setGravity(Gravity.CENTER);
        cb.setPadding((int) (12 * s), (int) (8 * s), (int) (12 * s), (int) (8 * s));
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(r.number); }
        });
        row.addView(cb, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static String relDate(long ms) {
        long diff = System.currentTimeMillis() - ms;
        if (diff < 60L * 1000) return "ahora";
        if (diff < 60L * 60L * 1000) return "hace " + (diff / 60000L) + " min";
        if (diff < 24L * 3600L * 1000) return "hace " + (diff / 3600000L) + " h";
        long days = diff / (24L * 3600L * 1000);
        if (days < 7) return "hace " + days + " días";
        try { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(ms)); }
        catch (Throwable t) { return String.valueOf(ms); }
    }

    // ---------- CONTACTOS ----------
    private void contactsTab() {
        loadContacts();
        LinearLayout c = card(content, "Contactos del equipo");

        // Buscador con limpiar (X)
        searchEt = new EditText(host);
        searchEt.setSingleLine(true);
        searchEt.setTextColor(TEXT());
        searchEt.setHintTextColor(MUTED());
        searchEt.setHint("Buscar contacto o número…");
        searchEt.setTextSize(14);
        final LinearLayout colHolder = new LinearLayout(host);
        colHolder.setOrientation(LinearLayout.HORIZONTAL);
        colHolder.setGravity(Gravity.CENTER_VERTICAL);
        searchEt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s2, int a, int b, int d) { }
            public void onTextChanged(CharSequence s2, int a, int b, int d) { renderContactList(colHolder); }
            public void afterTextChanged(Editable e) { }
        });
        colHolder.addView(searchEt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = txt("✕", 16, MUTED(), false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding((int) (10 * s), 0, (int) (10 * s), 0);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { searchEt.setText(""); }
        });
        colHolder.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        colHolder.setBackground(rounded(INNER(), 14, 0));
        colHolder.setPadding((int) (12 * s), (int) (4 * s), (int) (12 * s), (int) (4 * s));
        c.addView(colHolder, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btnRow = new LinearLayout(host);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, (int) (8 * s), 0, (int) (4 * s));
        TextView sync = txt("Sincronizar contactos", 13, TEXT(), true);
        sync.setGravity(Gravity.CENTER);
        sync.setBackground(rounded(INNER(), 14, 0));
        sync.setPadding((int) (14 * s), (int) (6 * s), (int) (14 * s), (int) (6 * s));
        sync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { loadContacts(); renderContactList(colHolder); toast("Contactos sincronizados"); }
        });
        btnRow.addView(sync);
        c.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        renderContactList(colHolder);
    }

    private void renderContactList(LinearLayout colHolder) {
        String q = searchEt != null ? searchEt.getText().toString().trim().toLowerCase(java.util.Locale.getDefault()) : "";
        LinearLayout c = (colHolder.getParent() instanceof LinearLayout) ? (LinearLayout) colHolder.getParent() : null;
        if (c == null) return;
        List<Integer> removals = new ArrayList<Integer>();
        for (int i = c.getChildCount() - 1; i >= 0; i--) {
            View v = c.getChildAt(i);
            if (v.getTag() != null && "contactRow".equals(v.getTag())) removals.add(i);
        }
        for (int idx : removals) c.removeViewAt(idx);
        List<Contact> shown = new ArrayList<Contact>();
        for (Contact ct : contacts) {
            String hay = (ct.name + " " + ct.number).toLowerCase(java.util.Locale.getDefault());
            if (q.length() == 0 || hay.contains(q)) shown.add(ct);
        }
        if (shown.isEmpty()) {
            TextView t = txt("Sin resultados.", 13, MUTED(), false);
            t.setTag("contactRow");
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (final Contact ct : shown) c.addView(contactRow(ct), new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout contactRow(final Contact ct) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * s), 0, (int) (8 * s));
        row.setTag("contactRow");
        ImageView photo = avatar(ct, (int) (40 * s));
        photo.setPadding(0, 0, (int) (10 * s), 0);
        row.addView(photo, new LinearLayout.LayoutParams((int) (40 * s), (int) (40 * s)));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = txt(ct.name, 15, TEXT(), true);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        TextView nb = txt(ct.number, 12, MUTED(), false);
        nb.setMaxLines(1);
        nb.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nb, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView star = txt(ct.starred ? "★" : "☆", 20, ct.starred ? ACCENT() : MUTED(), false);
        star.setGravity(Gravity.CENTER);
        star.setPadding((int) (10 * s), (int) (6 * s), (int) (10 * s), (int) (6 * s));
        star.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleStar(ct); render(); }
        });
        row.addView(star, new LinearLayout.LayoutParams(-2, -2));
        TextView cb = txt("📞", 18, ACCENT(), false);
        cb.setGravity(Gravity.CENTER);
        cb.setPadding((int) (12 * s), (int) (6 * s), (int) (12 * s), (int) (6 * s));
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        row.addView(cb, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    // ---------- TECLADO ----------
    private void keypadTab() {
        LinearLayout c = card(content, "Teclado");
        dialNumber = txt("", 26, TEXT(), true);
        dialNumber.setGravity(Gravity.CENTER);
        dialNumber.setMinHeight((int) (34 * s));
        c.addView(dialNumber, new LinearLayout.LayoutParams(-1, -2));

        TextView bs = txt("⌫ Borrar", 13, MUTED(), false);
        bs.setGravity(Gravity.CENTER);
        bs.setPadding(0, (int) (2 * s), 0, (int) (4 * s));
        bs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dialText.length() > 0) { dialText = dialText.substring(0, dialText.length() - 1); dialNumber.setText(dialText); } }
        });
        bs.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { dialText = ""; dialNumber.setText(""); return true; }
        });
        c.addView(bs, new LinearLayout.LayoutParams(-1, -2));

        final String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0+", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                final String d = digits[r * 3 + col];
                TextView b = txt(d, 22, TEXT(), true);
                b.setGravity(Gravity.CENTER);
                b.setPadding((int) (10 * s), (int) (10 * s), (int) (10 * s), (int) (10 * s));
                b.setBackground(rounded(INNER(), 14, 0));
                b.setSoundEffectsEnabled(true);
                b.setOnTouchListener(new View.OnTouchListener() {
                    public boolean onTouch(View view, android.view.MotionEvent ev) {
                        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                            beepDigit(d);
                            view.setBackground(rounded(ACCENT(), 14, 0));
                            ((TextView) view).setTextColor(Color.WHITE);
                        } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                            view.setBackground(rounded(INNER(), 14, 0));
                            ((TextView) view).setTextColor(TEXT());
                        }
                        return false;
                    }
                });
                final String key = d.equals("0+") ? "0" : d;
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String next = (key.equals("0") && dialText.length() == 0) ? "+" + dialText : dialText + key;
                        if (next.length() > 25) next = next.substring(0, 25);
                        dialText = next;
                        dialNumber.setText(dialText);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.leftMargin = (int) (4 * s); lp.rightMargin = (int) (4 * s);
                lp.topMargin = (int) (4 * s); lp.bottomMargin = (int) (4 * s);
                line.addView(b, lp);
            }
            c.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView callBtn = txt("📞  Llamar", 18, Color.WHITE, true);
        callBtn.setGravity(Gravity.CENTER);
        callBtn.setBackground(rounded(0xFF2DBE6C, 16, 0));
        callBtn.setPadding(0, (int) (12 * s), 0, (int) (12 * s));
        callBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { final String n = dialText.trim(); if (n.length() > 0) callNumber(n); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = (int) (10 * s);
        c.addView(callBtn, clp);
    }
    private void beepDigit(String d) {
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_DTMF, 30);
            int t = ToneGenerator.TONE_DTMF_0;
            if ("1".equals(d)) t = ToneGenerator.TONE_DTMF_1;
            else if ("2".equals(d)) t = ToneGenerator.TONE_DTMF_2;
            else if ("3".equals(d)) t = ToneGenerator.TONE_DTMF_3;
            else if ("4".equals(d)) t = ToneGenerator.TONE_DTMF_4;
            else if ("5".equals(d)) t = ToneGenerator.TONE_DTMF_5;
            else if ("6".equals(d)) t = ToneGenerator.TONE_DTMF_6;
            else if ("7".equals(d)) t = ToneGenerator.TONE_DTMF_7;
            else if ("8".equals(d)) t = ToneGenerator.TONE_DTMF_8;
            else if ("9".equals(d)) t = ToneGenerator.TONE_DTMF_9;
            else if ("*".equals(d)) t = ToneGenerator.TONE_DTMF_S;
            else if ("#".equals(d)) t = ToneGenerator.TONE_DTMF_P;
            tone.startTone(t, 80);
        } catch (Throwable ignored) {}
    }

    // ---------- datos ----------
    private Set<String> prefsFav() {
        try { return new HashSet<String>(host.getPrefs().getStringSet("phone_fav_ids", new HashSet<String>())); }
        catch (Throwable t) { return new HashSet<String>(); }
    }

    private void loadContacts() {
        if (!host.phonePermsGranted()) return;
        List<Contact> out = new ArrayList<Contact>();
        java.util.Map<Long, Boolean> starMap = new HashMap<Long, Boolean>();
        java.util.Map<Long, Uri> thumbMap = new HashMap<Long, Uri>();
        Cursor cst = null;
        try {
            cst = host.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI}, null, null, null);
            if (cst != null) while (cst.moveToNext()) {
                Long id = cst.getLong(0);
                starMap.put(id, cst.getInt(1) == 1);
                String th = null;
                try { th = cst.getString(2); } catch (Throwable ignored) { }
                thumbMap.put(id, th != null ? Uri.parse(th) : null);
            }
        } catch (Throwable ignored) { }
        try { if (cst != null) cst.close(); } catch (Throwable ignored) {}
        Cursor c = null;
        Set<String> favs = prefsFav();
        try {
            c = host.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone._ID,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    }, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (c != null) while (c.moveToNext()) {
                Contact ct = new Contact();
                try { ct.key = String.valueOf(c.getLong(0)); } catch (Throwable ignored) { ct.key = ""; }
                ct.name = safe(c.getString(1), "Desconocido");
                ct.number = safe(c.getString(2), "");
                long contactId = -1;
                try { contactId = c.getLong(3); } catch (Throwable ignored) { }
                ct.photoUri = contactId >= 0 ? thumbMap.get(contactId) : null;
                Boolean st = contactId >= 0 ? starMap.get(contactId) : null;
                ct.starred = (st != null && st.booleanValue()) || favs.contains(ct.key);
                if (ct.name.length() > 0 && ct.number.length() > 0) out.add(ct);
            }
        } catch (Throwable ignored) { }
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
        contacts.clear();
        contacts.addAll(out);
    }

    private static String safe(String s, String def) { return s != null ? s : def; }

    private void toggleStar(Contact ct) {
        ct.starred = !ct.starred;
        Set<String> favs = prefsFav();
        String key = ct.key;
        if (ct.starred) favs.add(key); else favs.remove(key);
        try { host.getPrefs().edit().putStringSet("phone_fav_ids", favs).apply(); } catch (Throwable ignored) {}
    }

    private String lookupName(String number) {
        if (number == null) return null;
        String n = cleanNumber(number.trim());
        if (n == null) return null;
        if (nameCache.containsKey(n)) return nameCache.get(n);
        Cursor c = null;
        try {
            c = host.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n.trim())),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) { nameCache.put(n, name); return name; }
            }
        } catch (Throwable ignored) { }
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
        return null;
    }

    private void loadRecents() {
        if (!host.phonePermsGranted()) return;
        List<CallRec> out = new ArrayList<CallRec>();
        Cursor c = null;
        try {
            c = host.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{
                            CallLog.Calls.NUMBER,
                            CallLog.Calls.CACHED_NAME,
                            CallLog.Calls.TYPE,
                            CallLog.Calls.DATE,
                            CallLog.Calls.DURATION,
                    }, null, null, CallLog.Calls.DATE + " DESC");
            if (c != null) while (c.moveToNext()) {
                CallRec r = new CallRec();
                try { r.number = c.getString(0); } catch (Throwable ignored) { r.number = null; }
                String cached = null;
                try { cached = c.getString(1); } catch (Throwable ignored) { }
                try { r.type = c.getInt(2); } catch (Throwable ignored) { r.type = 0; }
                try { r.date = c.getLong(3); } catch (Throwable ignored) { r.date = 0; }
                try { r.duration = c.getLong(4); } catch (Throwable ignored) { r.duration = 0; }
                r.name = cached != null ? cached : lookupName(r.number);
                r.known = r.name != null;
                out.add(r);
                if (out.size() >= 100) break;
            }
        } catch (Throwable ignored) { }
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
        calls.clear();
        calls.addAll(out);
    }

    // ---------- avatar ----------
    private ImageView avatar(final Contact ct, final int px) {
        ImageView iv = new ImageView(host);
        Bitmap bmp = photoCache.get(ct.key);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
        } else {
            iv.setImageDrawable(placeholder(ct.name, px));
            loadPhotoAsync(ct, iv, px);
        }
        return iv;
    }

    private BitmapDrawable placeholder(String name, int px) {
        int color = INNER();
        float letter = px * 0.4f;
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float r = px / 2f;
        RectF rf = new RectF(0, 0, px, px);
        cv.drawRoundRect(rf, r, r, p);
        String init = (name != null && name.length() > 0) ? String.valueOf(Character.toUpperCase(name.charAt(0))) : "?";
        p.setColor(MUTED());
        p.setTextSize(letter);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(init, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p);
        return new BitmapDrawable(host.getResources(), b);
    }

    private void loadPhotoAsync(final Contact ct, final ImageView iv, final int px) {
        if (ct.photoUri == null) return;
        final Object tag = ct.key;
        iv.setTag(tag);
        new Thread(new Runnable() {
            public void run() {
                Bitmap bmp = photoCache.get(ct.key);
                if (bmp == null) {
                    try {
                        InputStream in = host.getContentResolver().openInputStream(ct.photoUri);
                        if (in != null) {
                            byte[] data = new byte[8192];
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            int n;
                            while ((n = in.read(data)) > 0) bos.write(data, 0, n);
                            in.close();
                            Bitmap raw = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size());
                            if (raw != null) {
                                bmp = circularCrop(raw, px);
                                photoCache.put(ct.key, bmp);
                            }
                        }
                    } catch (Throwable ignored) { }
                }
                final Bitmap f = bmp;
                ui.post(new Runnable() {
                    public void run() {
                        if (f != null && tag.equals(iv.getTag())) iv.setImageBitmap(f);
                    }
                });
            }
        }).start();
    }

    private Bitmap circularCrop(Bitmap src, int px) {
        Bitmap dst = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(dst);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Path path = new Path();
        path.addOval(new RectF(0, 0, px, px), Path.Direction.CW);
        cv.clipPath(path);
        float scale = Math.max(px / (float) src.getWidth(), px / (float) src.getHeight());
        int w = Math.round(src.getWidth() * scale), h = Math.round(src.getHeight() * scale);
        RectF dest = new RectF((px - w) / 2f, (px - h) / 2f, (px + w) / 2f, (px + h) / 2f);
        RectF srcRect = new RectF(0, 0, src.getWidth(), src.getHeight());
        cv.drawBitmap(src, new Rect(Math.round(srcRect.left), Math.round(srcRect.top), Math.round(srcRect.right), Math.round(srcRect.bottom)),
                new Rect(Math.round(dest.left), Math.round(dest.top), Math.round(dest.right), Math.round(dest.bottom)), p);
        return dst;
    }

    public void destroy() {
        try { if (tone != null) tone.release(); } catch (Throwable ignored) {}
    }
}
