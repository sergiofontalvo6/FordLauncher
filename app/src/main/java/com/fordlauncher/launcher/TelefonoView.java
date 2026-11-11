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
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Módulo Teléfono (reemplaza al botón Cámara del dock): dos columnas asimétricas.
 * Columna izquierda fija (32%) con Favoritos; columna derecha (68%) con tres
 * pestañas (Recientes | Contactos | Teclado) y botón de cierre. En la pestaña
 * Recientes hay un switch estilo iOS para filtrar números desconocidos.
 *
 * <p>Las llamadas se emiten por <b>Bluetooth HFP real</b> ({@link HfpClient} →
 * {@code BluetoothHeadsetClient.dial()}), la misma vía que usa la app de
 * fábrica {@code com.autochips.bluetooth} (BTSuite / 8227L_demo) en su
 * {@code BtDialPadActivity}. Los broadcasts genéricos antiguos
 * ({@code com.autochips.bluetooth.action.DIAL}, {@code com.goodocom...},
 * etc.) <b>no</b> los escucha esta firmware y se conservan solo como fallback
 * silencioso. Un overlay propio de llamada en curso evita que la app del
 * sistema se superponga, pero el control de llamada (colgar/contestar) va por HFP.
 */
public class TelefonoView {
    private final Main host;
    private final float s;
    private final Handler ui = new Handler();
    private final java.util.concurrent.Executor bg = com.fordlauncher.launcher.ai.AppExecutors.get().diskIO;
    private volatile boolean contactsLoading;
    private volatile boolean contactsReady;
    private volatile boolean callsLoading;
    private volatile boolean callsReady;

    private static final String[] TAB_NAMES = {"Recientes", "Contactos", "Teclado"};
    private int tab = 0;
    private boolean unknownOnly;

    private static final class Contact {
        String key, name, number;
        Uri photoUri;
        boolean starred;
        long contactId = -1;
    }
    public static final class CallRec {
        String number, name;
        int type;
        long date;
        long duration;
        boolean known;
    }
    private final List<Contact> contacts = new ArrayList<Contact>();
    private final android.util.LruCache<String, Bitmap> photoCache = new android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getByteCount();
        }
    };
    private final List<CallRec> calls = new ArrayList<CallRec>();
    private final Map<String, String> nameCache = new HashMap<String, String>();
    private LinearLayout content;
    private LinearLayout favList;
    private LinearLayout tabBar;
    private FrameLayout callOverlay;
    private Chronometer callTimer;
    private EditText searchEt;
    private TextView dialNumber;
    private TextView btStatus;
    private String dialText = "";
    private ToneGenerator tone;
    private HfpClient hfp;
    private ThemedCallWindow themedWindow;
    private String btStatusText = "Bluetooth HFP: buscando celular…";

    // Privacidad: la limpieza automática la hace PhonePrivacy (en Main); aquí solo se refleja.
    private boolean contactsHidden;
    private boolean phoneOn;

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
            TextView h = txt(titleSmall, 13, MUTED(), true);
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

    public TelefonoView(Main h, float scale) {
        host = h; s = scale * 1.22f; // rediseño: panel más grande (antes se veía muy pequeño)
        contactsHidden = host.getPrefs().getBoolean(PhonePrivacy.K_HIDDEN, false);
        hfp = new HfpClient(host);
        hfp.setListener(new HfpClient.CallListener() {
            @Override public void onDialResult(boolean success, String detail) {
                setBtStatus(detail);
                if (!success) {
                    try { host.callWindow().hide(); } catch (Throwable ignored) {}
                    toast(detail);
                    return;
                }
                // Al colgar, el historial del radio tardará un momento en registrar
                // la llamada; recargamos Recientes poco después.
                ui.postDelayed(new Runnable() {
                    public void run() {
                        callsReady = false;
                        if (tab == 0 && content != null && content.isAttachedToWindow()) render();
                    }
                }, 5000);
            }
            @Override public void onConnectionChanged(String deviceName) {
                phoneOn = deviceName != null;
                if (deviceName != null) setBtStatus("Bluetooth HFP: " + deviceName + " conectado");
                else setBtStatus("Bluetooth HFP: celular no conectado");
                if (!phoneOn) {
                    if (themedWindow != null) themedWindow.hide();
                    if (autoClean()) { contacts.clear(); calls.clear(); contactsReady = false; callsReady = false; }
                }
                if (content != null && content.isAttachedToWindow()) render();
                // PhonePrivacy (Main) puede limpiar datos en este mismo instante: refrescamos después.
                ui.postDelayed(new Runnable() {
                    public void run() { syncPrivacyState(); }
                }, 1500);
            }
            @Override public void onCallChanged(String state, String number) {
                if (state != null && state.length() > 0) setBtStatus("Llamada: " + state);
                // Se deja la ventana de fábrica (BTSuite) como única UI de llamada.
                if ("7".equals(state)) {
                    callsReady = false; // terminada: refresca Recientes
                    if (tab == 0 && content != null && content.isAttachedToWindow()) render();
                }
            }
        });
        hfp.requestProxy();
        themedWindow = null;
    }

    private void setBtStatus(String t) {
        btStatusText = t;
        if (btStatus != null) btStatus.setText(t);
    }

    private String currentMac() {
        try {
            if (hfp != null && hfp.getConnectedDevice() != null) return hfp.getConnectedDevice().getAddress();
        } catch (Throwable ignored) {}
        return null;
    }

    private void saveBool(String key, boolean value) {
        try { host.getPrefs().edit().putBoolean(key, value).apply(); } catch (Throwable ignored) {}
    }

    private void savePref(String key, String value) {
        try { host.getPrefs().edit().putString(key, value).apply(); } catch (Throwable ignored) {}
    }

    // ---------- PRIVACIDAD ----------

    /** Diálogo con borrado manual y limpieza automática al desconectar. */
    private void showPrivacyDialog() {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * s);
        col.setPadding(pad, pad, pad, pad);

        TextView info = txt("Al prestar el carro o conectar otro celular, los contactos y el historial pueden mezclarse. Aquí puedes borrarlos cuando quieras.", 14, MUTED(), false);
        info.setMaxLines(4);
        col.addView(info, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout autoRow = new LinearLayout(host);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, (int) (16 * s), 0, (int) (4 * s));
        TextView autoLabel = txt("Limpiar al desconectar el celular", 15, TEXT(), false);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch autoSw = new Switch(host);
        autoSw.setChecked(autoClean());
        try {
            autoSw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
            autoSw.setTrackTintList(android.content.res.ColorStateList.valueOf((ACCENT() & 0x00FFFFFF) | 0x66000000));
        } catch (Throwable ignored) {}
        autoSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { toggleAutoClean(on); }
        });
        autoRow.addView(autoSw, new LinearLayout.LayoutParams(-2, -2));
        col.addView(autoRow, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = txt("Automático: borra contactos e historial 8 segundos después de desconectarse el celular. Si vuelve a conectar el mismo celular, los contactos se muestran otra vez.", 12, MUTED(), false);
        hint.setMaxLines(4);
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        new android.app.AlertDialog.Builder(host, Main.dlg())
                .setTitle("Privacidad del teléfono")
                .setView(col)
                .setPositiveButton("Borrar ahora", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { confirmClean(); }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private boolean autoClean() {
        return host.getPrefs().getBoolean(PhonePrivacy.K_AUTO, false);
    }

    private void toggleAutoClean(boolean on) {
        saveBool(PhonePrivacy.K_AUTO, on);
        if (!on) {
            saveBool(PhonePrivacy.K_PENDING, false);
        } else if (host.getPrefs().getString(PhonePrivacy.K_OWNER, null) == null) {
            String mac = currentMac();
            if (mac != null) savePref(PhonePrivacy.K_OWNER, mac);
        }
        toast(on ? "Limpieza automática activada" : "Limpieza automática desactivada");
    }

    /** Vuelve a leer el estado de privacidad (puede haber cambiado en segundo plano). */
    private void syncPrivacyState() {
        boolean hidden = host.getPrefs().getBoolean(PhonePrivacy.K_HIDDEN, false);
        if (hidden != contactsHidden || hidden) {
            contactsHidden = hidden;
            if (hidden) { contacts.clear(); calls.clear(); }
            contactsReady = false;
            callsReady = false;
            if (content != null && content.isAttachedToWindow()) render();
        }
    }

    private void confirmClean() {
        new android.app.AlertDialog.Builder(host, Main.dlg())
                .setTitle("¿Borrar los datos del teléfono?")
                .setMessage("Se borrarán el historial de llamadas, los favoritos y los contactos mostrados. Los contactos de la cuenta de Google no se eliminan: solo se ocultan hasta que toques «Mostrar contactos» o «Sincronizar».")
                .setPositiveButton("Borrar", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) { performClean(true); }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Borra el historial Bluetooth y el CallLog estándar, olvida los favoritos y
     * oculta los contactos. Los contactos del sistema no se tocan para no
     * sincronizar el borrado con la cuenta de Google.
     */
    private void performClean(final boolean manual) {
        int deleted = PhonePrivacy.clean(host);
        contactsHidden = true;

        contacts.clear();
        calls.clear();
        photoCache.evictAll();
        nameCache.clear();
        contactsReady = false;
        callsReady = false;
        contactsLoading = false;
        callsLoading = false;

        if (content != null && content.isAttachedToWindow()) render();

        if (manual) toast(deleted > 0 ? "Datos borrados (" + deleted + " llamadas)" : "Datos borrados");
    }

    public FrameLayout build() {
        FrameLayout wrap = new FrameLayout(host);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG());
        root.setPadding((int) (10 * s), (int) (10 * s), (int) (10 * s), (int) (10 * s));
        wrap.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // ---------- COLUMNA IZQUIERDA: Favoritos ----------
        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, (int) (10 * s), 0);
        TextView leftTitle = txt("★ Favoritos", 18, TEXT(), true);
        leftTitle.setPadding((int) (6 * s), (int) (4 * s), 0, (int) (10 * s));
        left.addView(leftTitle, new LinearLayout.LayoutParams(-1, -2));
        ScrollView favSc = new ScrollView(host);
        favSc.setVerticalScrollBarEnabled(false);
        favList = new LinearLayout(host);
        favList.setOrientation(LinearLayout.VERTICAL);
        favSc.addView(favList, new FrameLayout.LayoutParams(-1, -2));
        left.addView(favSc, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 0.32f));

        // ---------- DIVISORIA ----------
        View div = new View(host);
        div.setBackgroundColor(0x22FFFFFF);
        root.addView(div, new LinearLayout.LayoutParams(Math.max(1, (int) s), -1));

        // ---------- COLUMNA DERECHA ----------
        LinearLayout right = new LinearLayout(host);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 0.68f);
        rp.leftMargin = (int) (10 * s);
        root.addView(right, rp);

        // Cabecera compacta (máx 38dp): 3 pestañas + cierre
        LinearLayout header = new LinearLayout(host);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding((int) (6 * s), 0, (int) (6 * s), (int) (6 * s));
        tabBar = new LinearLayout(host);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            TextView t = txt(TAB_NAMES[i], 15, TEXT(), true);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding((int) (4 * s), (int) (6 * s), (int) (4 * s), (int) (6 * s));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectTab(idx); }
            });
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        header.addView(tabBar, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView trash = txt("🗑", 18, MUTED(), false);
        trash.setGravity(Gravity.CENTER);
        trash.setPadding((int) (10 * s), (int) (6 * s), (int) (10 * s), (int) (6 * s));
        trash.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPrivacyDialog(); }
        });
        header.addView(trash, new LinearLayout.LayoutParams(-2, -2));
        TextView close = txt("✕", 20, MUTED(), false);
        close.setGravity(Gravity.CENTER);
        close.setPadding((int) (12 * s), (int) (6 * s), (int) (12 * s), (int) (6 * s));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.closePhone(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(-2, -2));
        right.addView(header, new LinearLayout.LayoutParams(-1, (int) (46 * s)));

        // Estado Bluetooth HFP (la vía real de llamada en el 8227L_demo)
        btStatus = txt(btStatusText, 13, MUTED(), false);
        btStatus.setPadding((int) (6 * s), 0, (int) (6 * s), (int) (6 * s));
        btStatus.setMaxLines(1);
        btStatus.setEllipsize(TextUtils.TruncateAt.END);
        right.addView(btStatus, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sc = new ScrollView(host);
        sc.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        sc.addView(content, new FrameLayout.LayoutParams(-1, -2));
        right.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));

        callOverlay = buildCallOverlay();
        callOverlay.setVisibility(View.GONE);
        wrap.addView(callOverlay, new FrameLayout.LayoutParams(-1, -1));

        render();
        return wrap;
    }

    private void paintTabs() {
        if (tabBar == null) return;
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View v = tabBar.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            TextView t = (TextView) v;
            boolean sel = tab == i;
            t.setBackground(rounded(sel ? ACCENT() : INNER(), 9, 0));
            t.setTextColor(sel ? Color.WHITE : TEXT());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.leftMargin = (int) (4 * s);
            lp.rightMargin = (int) (4 * s);
        }
    }

    private void selectTab(int i) {
        // Al abrir Recientes se recarga el historial real del radio (BTSuite),
        // así aparecen las llamadas hechas/recibidas desde la última vez.
        if (i == 0) callsReady = false;
        tab = i; render();
    }

    public void refresh() { render(); }

    private void render() {
        if (content == null) return;
        paintTabs();
        renderFavorites();
        content.removeAllViews();
        if (privacyLocked() && tab != 2) { lockedCard(); return; }
        if (!host.phonePermsGranted() && tab != 2) {
            permCard();
            return;
        }
        switch (tab) {
            case 0: recentsTab(); break;
            case 1: contactsTab(); break;
            default: keypadTab(); break;
        }
    }

    /** Con limpieza automática, sin el celular conectado no se muestran contactos ni historial. */
    private boolean privacyLocked() { return autoClean() && !phoneOn; }

    private void lockedCard() {
        LinearLayout c = card(content, "Privacidad");
        TextView t = txt("Conecta tu celular por Bluetooth para ver contactos e historial. Con la limpieza automática activa, sin celular conectado no se muestran.", 14, MUTED(), false);
        t.setMaxLines(4);
        t.setPadding(0, (int) (6 * s), 0, 0);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Carga contactos en segundo plano (nunca en el hilo de UI). */
    private void loadContactsAsync() {
        if (contactsHidden || contactsLoading || contactsReady) return;
        contactsLoading = true;
        bg.execute(new Runnable() {
            public void run() {
                loadContacts();
                contactsReady = true;
                contactsLoading = false;
                ui.post(new Runnable() { public void run() { if (favList != null && favList.isAttachedToWindow()) render(); } });
            }
        });
    }

    /** Carga el historial de llamadas en segundo plano. */
    private void loadRecentsAsync() {
        if (callsLoading || callsReady) return;
        callsLoading = true;
        bg.execute(new Runnable() {
            public void run() {
                loadRecents();
                callsReady = true;
                callsLoading = false;
                ui.post(new Runnable() { public void run() { if (content != null && content.isAttachedToWindow()) render(); } });
            }
        });
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

    // ---------- FAVORITOS (columna izquierda) ----------
    private void renderFavorites() {
        if (favList == null) return;
        favList.removeAllViews();
        if (privacyLocked()) {
            TextView t = txt("Conecta el celular para ver favoritos.", 14, MUTED(), false);
            t.setPadding((int) (6 * s), (int) (6 * s), 0, 0);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (!host.phonePermsGranted()) {
            TextView t = txt("Sin permiso de contactos.", 14, MUTED(), false);
            t.setPadding((int) (6 * s), (int) (6 * s), 0, 0);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (contactsHidden) {
            TextView t = txt("Contactos ocultos por privacidad.", 14, MUTED(), false);
            t.setPadding((int) (6 * s), (int) (6 * s), 0, 0);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (!contactsReady) {
            TextView t = txt("Cargando contactos…", 14, MUTED(), false);
            t.setPadding((int) (6 * s), (int) (6 * s), 0, 0);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
            loadContactsAsync();
            return;
        }
        List<Contact> favs = new ArrayList<Contact>();
        for (Contact ct : contacts) if (ct.starred) favs.add(ct);
        if (favs.isEmpty()) {
            TextView t = txt("Sin favoritos. En Contactos toca la estrella o usa «Importar favoritos».", 14, MUTED(), false);
            t.setPadding((int) (6 * s), (int) (6 * s), 0, 0);
            favList.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (final Contact ct : favs) favList.addView(favRow(ct), new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout favRow(final Contact ct) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (4 * s), (int) (8 * s), (int) (4 * s), (int) (8 * s));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        ImageView photo = avatar(ct, (int) (50 * s));
        row.addView(photo, new LinearLayout.LayoutParams((int) (50 * s), (int) (50 * s)));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (10 * s), 0, (int) (6 * s), 0);
        TextView nm = txt(ct.name, 18, TEXT(), true);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        TextView nb = txt(ct.number, 14, MUTED(), false);
        nb.setMaxLines(1);
        nb.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nb, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cb = txt("📞", 20, Color.WHITE, false);
        cb.setGravity(Gravity.CENTER);
        cb.setBackground(rounded(ACCENT(), 22, 0));
        cb.setPadding((int) (12 * s), (int) (10 * s), (int) (12 * s), (int) (10 * s));
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        row.addView(cb, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    // ---------- llamadas ----------
    private void toast(String m) { try { android.widget.Toast.makeText(host, m, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {} }

    /** Marcar por HFP real (BluetoothHeadsetClient), la vía del radio. */
    void callNumber(String number) {
        if (number == null) return;
        String n = cleanNumber(number);
        if (n == null) { toast("Número vacío"); return; }
        if (hfp == null) { toast("Bluetooth no disponible"); return; }
        // HfpClient comprueba el dispositivo conectado y avisa por
        // onDialResult (estado + toast) si el celular no está conectado.
        hfp.dial(n);
        // Interfaz propia de llamada desde el primer momento (no esperar al evento HFP).
        try { host.callWindow().show(n, null, 2); } catch (Throwable ignored) {}
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

    // ---------- OVERLAY DE LLAMADA EN CURSO ----------
    private TextView ovName, ovNumber;
    private ImageView ovAvatar;
    private TextView ovMute, ovSpeaker;

    private FrameLayout buildCallOverlay() {
        FrameLayout ov = new FrameLayout(host);
        ov.setBackgroundColor(0xAA000000);
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD(), 18, 0));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding((int) (24 * s), (int) (20 * s), (int) (24 * s), (int) (20 * s));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams((int) (380 * s), -2);
        clp.gravity = Gravity.CENTER;
        ov.addView(card, clp);

        ovAvatar = new ImageView(host);
        card.addView(ovAvatar, new LinearLayout.LayoutParams((int) (72 * s), (int) (72 * s)));
        ovName = txt("", 20, TEXT(), true);
        ovName.setGravity(Gravity.CENTER);
        ovName.setMaxLines(1);
        ovName.setEllipsize(TextUtils.TruncateAt.END);
        ovName.setPadding(0, (int) (10 * s), 0, 0);
        card.addView(ovName, new LinearLayout.LayoutParams(-1, -2));
        ovNumber = txt("", 15, MUTED(), false);
        ovNumber.setGravity(Gravity.CENTER);
        card.addView(ovNumber, new LinearLayout.LayoutParams(-1, -2));
        callTimer = new Chronometer(host);
        callTimer.setTextColor(TEXT());
        callTimer.setTextSize(18);
        callTimer.setGravity(Gravity.CENTER);
        callTimer.setPadding(0, (int) (4 * s), 0, (int) (14 * s));
        card.addView(callTimer, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout midRow = new LinearLayout(host);
        midRow.setOrientation(LinearLayout.HORIZONTAL);
        midRow.setGravity(Gravity.CENTER);
        ovMute = txt("🔇 Mute", 13, TEXT(), true);
        ovMute.setGravity(Gravity.CENTER);
        ovMute.setBackground(rounded(INNER(), 14, 0));
        ovMute.setPadding((int) (14 * s), (int) (8 * s), (int) (14 * s), (int) (8 * s));
        ovMute.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    AudioManager am = (AudioManager) host.getSystemService(android.content.Context.AUDIO_SERVICE);
                    boolean m = !am.isMicrophoneMute();
                    am.setMicrophoneMute(m);
                    ovMute.setText(m ? "🔈 Activar" : "🔇 Mute");
                } catch (Throwable ignored) { toast("Mute no disponible"); }
            }
        });
        midRow.addView(ovMute, new LinearLayout.LayoutParams(-2, -2));
        TextView dtmf = txt("DTMF", 13, TEXT(), true);
        dtmf.setGravity(Gravity.CENTER);
        dtmf.setBackground(rounded(INNER(), 14, 0));
        dtmf.setPadding((int) (14 * s), (int) (8 * s), (int) (14 * s), (int) (8 * s));
        dtmf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dtmfDialog(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = (int) (10 * s);
        midRow.addView(dtmf, dlp);
        ovSpeaker = txt("🔊 Altavoz", 13, TEXT(), true);
        ovSpeaker.setGravity(Gravity.CENTER);
        ovSpeaker.setBackground(rounded(INNER(), 14, 0));
        ovSpeaker.setPadding((int) (14 * s), (int) (8 * s), (int) (14 * s), (int) (8 * s));
        ovSpeaker.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    AudioManager am = (AudioManager) host.getSystemService(android.content.Context.AUDIO_SERVICE);
                    boolean spk = !am.isSpeakerphoneOn();
                    am.setSpeakerphoneOn(spk);
                    ovSpeaker.setText(spk ? "📞 Auricular" : "🔊 Altavoz");
                } catch (Throwable ignored) { toast("Altavoz no disponible"); }
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = (int) (10 * s);
        midRow.addView(ovSpeaker, slp);
        card.addView(midRow, new LinearLayout.LayoutParams(-1, -2));

        TextView hang = txt("📞 Colgar", 18, Color.WHITE, true);
        hang.setGravity(Gravity.CENTER);
        hang.setBackground(rounded(0xFFF44336, 26, 0));
        hang.setPadding(0, (int) (14 * s), 0, (int) (14 * s));
        hang.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { hangUp(); }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = (int) (20 * s);
        card.addView(hang, hlp);
        return ov;
    }

    private void showCallOverlay(String number) {
        // Se deja la ventana de fábrica (BTSuite PhoneCallActivity) como única UI de llamada.
        // No se muestra overlay interno ni flotante de FordLauncher para evitar duplicados.
        String name = lookupName(number);
        // Solo actualizamos textos por si se reutiliza en el futuro, pero no hacemos visible nada.
        try {
            ovName.setText(name != null ? name : number);
            ovNumber.setText(name != null ? number : "Llamando…");
        } catch (Throwable ignored) {}
        try { if (callOverlay != null) callOverlay.setVisibility(View.GONE); } catch (Throwable ignored) {}
        try { if (themedWindow != null) themedWindow.hide(); } catch (Throwable ignored) {}
    }

    private void hangUp() {
        try { if (hfp != null) hfp.hangUp(); } catch (Throwable ignored) {}
        try { host.sendBroadcast(new Intent("com.autochips.bluetooth.action.HANGUP")); } catch (Throwable ignored) {}
        try { host.sendBroadcast(new Intent("com.goodocom.gocsdk.HANGUP")); } catch (Throwable ignored) {}
        try { callTimer.stop(); } catch (Throwable ignored) {}
        try {
            AudioManager am = (AudioManager) host.getSystemService(android.content.Context.AUDIO_SERVICE);
            am.setMicrophoneMute(false);
            am.setSpeakerphoneOn(false);
            ovMute.setText("🔇 Mute");
            ovSpeaker.setText("🔊 Altavoz");
        } catch (Throwable ignored) {}
        callOverlay.setVisibility(View.GONE);
        try { if (themedWindow != null) themedWindow.hide(); } catch (Throwable ignored) {}
    }

    private void dtmfDialog() {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * s);
        col.setPadding(pad, pad, pad, pad);
        final String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String d = digits[r * 3 + c];
                TextView b = txt(d, 20, TEXT(), true);
                b.setGravity(Gravity.CENTER);
                b.setBackground(rounded(INNER(), 12, 0));
                b.setPadding((int) (16 * s), (int) (12 * s), (int) (16 * s), (int) (12 * s));
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        beepDigit(d);
                        try { if (hfp != null) hfp.sendDtmf(d.charAt(0)); } catch (Throwable ignored) {}
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.leftMargin = (int) (4 * s); lp.rightMargin = (int) (4 * s);
                lp.topMargin = (int) (4 * s); lp.bottomMargin = (int) (4 * s);
                line.addView(b, lp);
            }
            col.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        new android.app.AlertDialog.Builder(host, Main.dlg())
            .setTitle("DTMF")
            .setView(col)
            .setNegativeButton("Cerrar", null)
            .show();
    }

    // ---------- RECIENTES ----------
    private void recentsTab() {
        if (!callsReady) {
            loadRecentsAsync();
            LinearLayout c = card(content, "Historial de llamadas");
            TextView t = txt("Cargando historial…", 13, MUTED(), false);
            t.setPadding(0, (int) (6 * s), 0, 0);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout c = card(content, "Historial de llamadas");

        // Switch superior estilo iOS: Números desconocidos
        LinearLayout swRow = new LinearLayout(host);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        swRow.setPadding(0, (int) (4 * s), 0, (int) (10 * s));
        View spacer = new View(host);
        swRow.addView(spacer, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView swLabel = txt("Números desconocidos", 15, MUTED(), false);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(-2, -2));
        Switch sw = new Switch(host);
        sw.setPadding((int) (8 * s), 0, 0, 0);
        sw.setChecked(unknownOnly);
        try {
            sw.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT()));
            sw.setTrackTintList(android.content.res.ColorStateList.valueOf((ACCENT() & 0x00FFFFFF) | 0x66000000));
        } catch (Throwable ignored) {}
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (unknownOnly != on) { unknownOnly = on; render(); }
            }
        });
        swRow.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        c.addView(swRow, new LinearLayout.LayoutParams(-1, -2));

        List<CallRec> shown = new ArrayList<CallRec>();
        for (CallRec r : calls) {
            // Desconocido: no está en contactos Y nunca se contestó ni se llamó (duración 0).
            // Los contactos (aunque la llamada fuera perdida) y los desconocidos
            // contestados quedan en el historial regular.
            boolean isUnknown = !r.known && r.duration == 0;
            boolean a = unknownOnly ? isUnknown : !isUnknown;
            if (a) shown.add(r);
        }
        if (shown.isEmpty()) {
            TextView t = txt(unknownOnly ? "Sin números desconocidos." : "Sin llamadas en este grupo.", 15, MUTED(), false);
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
        row.setPadding(0, (int) (11 * s), 0, (int) (11 * s));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(r.number); }
        });
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        String title = r.name != null ? r.name : (r.number != null ? r.number : "Desconocido");
        TextView nm = txt(title, 18, missed ? 0xFFF44336 : TEXT(), !missed);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        String sub = recTypeName(r.type) + " · " + relDate(r.date);
        if (r.name != null && r.number != null) sub = recTypeName(r.type) + " · " + r.number + " · " + relDate(r.date);
        TextView sb = txt(sub, 13, MUTED(), false);
        sb.setMaxLines(1);
        sb.setEllipsize(TextUtils.TruncateAt.END);
        sb.setPadding(0, (int) (2 * s), 0, 0);
        col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView cb = txt("📞", 20, Color.WHITE, false);
        cb.setGravity(Gravity.CENTER);
        cb.setBackground(rounded(ACCENT(), 22, 0));
        cb.setPadding((int) (12 * s), (int) (10 * s), (int) (12 * s), (int) (10 * s));
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
        if (contactsHidden) {
            LinearLayout hc = card(content, "Privacidad");
            TextView ht = txt("Los contactos están ocultos para que no se mezclen con los de otro celular. Toca «Mostrar contactos» para volver a cargarlos desde el teléfono conectado.", 14, MUTED(), false);
            ht.setMaxLines(4);
            ht.setPadding(0, (int) (6 * s), 0, (int) (10 * s));
            hc.addView(ht, new LinearLayout.LayoutParams(-1, -2));
            TextView show = txt("Mostrar contactos", 15, Color.WHITE, true);
            show.setGravity(Gravity.CENTER);
            show.setBackground(rounded(ACCENT(), 14, 0));
            show.setPadding((int) (18 * s), (int) (9 * s), (int) (18 * s), (int) (9 * s));
            show.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!phoneOn) { toast("Conecta tu celular para mostrar contactos"); return; }
                    contactsHidden = false;
                    saveBool("phone_contacts_hidden", false);
                    contactsReady = false;
                    loadContacts();
                    contactsReady = true;
                    render();
                    toast("Contactos visibles de nuevo");
                }
            });
            hc.addView(show, new LinearLayout.LayoutParams(-2, -2));
            return;
        }

        if (!contactsReady) {
            loadContactsAsync();
            LinearLayout lc = card(content, "Contactos del equipo");
            TextView lt = txt("Cargando contactos…", 13, MUTED(), false);
            lt.setPadding(0, (int) (6 * s), 0, 0);
            lc.addView(lt, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        LinearLayout c = card(content, "Contactos del equipo");

        // Buscador con limpiar (X)
        searchEt = new EditText(host);
        searchEt.setSingleLine(true);
        KeyboardUtils.configureEditText(searchEt);
        searchEt.setTextColor(TEXT());
        searchEt.setHintTextColor(MUTED());
        searchEt.setHint("Buscar contacto o número…");
        searchEt.setTextSize(16);
        final LinearLayout colHolder = new LinearLayout(host);
        colHolder.setOrientation(LinearLayout.HORIZONTAL);
        colHolder.setGravity(Gravity.CENTER_VERTICAL);
        searchEt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s2, int a, int b, int d) { }
            public void onTextChanged(CharSequence s2, int a, int b, int d) { renderContactList(colHolder); }
            public void afterTextChanged(Editable e) { }
        });
        colHolder.addView(searchEt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = txt("✕", 18, MUTED(), false);
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
        TextView sync = txt("⟳  Sincronizar", 15, TEXT(), true);
        sync.setGravity(Gravity.CENTER);
        sync.setBackground(rounded(INNER(), 16, 0));
        sync.setPadding((int) (14 * s), (int) (9 * s), (int) (14 * s), (int) (9 * s));
        sync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!phoneOn) { toast("Conecta tu celular para sincronizar contactos"); return; }
                if (contactsHidden) {
                    contactsHidden = false;
                    saveBool("phone_contacts_hidden", false);
                }
                contactsReady = false;
                loadContacts();
                contactsReady = true;
                render();
                toast("Contactos sincronizados");
            }
        });
        btnRow.addView(sync);
        TextView imp = txt("★  Importar favoritos", 15, Color.WHITE, true);
        imp.setGravity(Gravity.CENTER);
        imp.setBackground(rounded(ACCENT(), 16, 0));
        imp.setPadding((int) (14 * s), (int) (9 * s), (int) (14 * s), (int) (9 * s));
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = importFavorites(true);
                int total = 0;
                for (Contact ct : contacts) if (ct.starred) total++;
                if (n > 0) toast("Favoritos importados: " + n);
                else if (total > 0) toast("Favoritos actualizados (" + total + ")");
                else toast("Tu celular no expone favoritos. Marca contactos con la estrella.");
                render();
            }
        });
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(-2, -2);
        impLp.leftMargin = (int) (8 * s);
        btnRow.addView(imp, impLp);
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
            TextView t = txt("Sin resultados.", 15, MUTED(), false);
            t.setTag("contactRow");
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int limit = q.length() == 0 ? Math.min(shown.size(), 40) : shown.size();
        for (int i = 0; i < limit; i++) c.addView(contactRow(shown.get(i)), new LinearLayout.LayoutParams(-1, -2));
        if (limit < shown.size()) {
            TextView more = txt("Mostrando " + limit + " de " + shown.size() + " — escribe para buscar en el resto.", 13, MUTED(), false);
            more.setTag("contactRow");
            more.setPadding(0, (int) (6 * s), 0, 0);
            c.addView(more, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout contactRow(final Contact ct) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (9 * s), 0, (int) (9 * s));
        row.setTag("contactRow");
        row.setClickable(true);
        // Toda la fila llama: antes solo el iconito 📞 respondía y parecía "no hacer nada".
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        ImageView photo = avatar(ct, (int) (52 * s));
        photo.setPadding(0, 0, (int) (10 * s), 0);
        row.addView(photo, new LinearLayout.LayoutParams((int) (52 * s), (int) (52 * s)));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = txt(ct.name, 18, TEXT(), true);
        nm.setMaxLines(1);
        nm.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nm, new LinearLayout.LayoutParams(-1, -2));
        TextView nb = txt(ct.number, 14, MUTED(), false);
        nb.setMaxLines(1);
        nb.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nb, new LinearLayout.LayoutParams(-1, -2));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView star = txt(ct.starred ? "★" : "☆", 26, ct.starred ? ACCENT() : MUTED(), false);
        star.setGravity(Gravity.CENTER);
        star.setPadding((int) (10 * s), (int) (6 * s), (int) (10 * s), (int) (6 * s));
        star.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleStar(ct); render(); }
        });
        row.addView(star, new LinearLayout.LayoutParams(-2, -2));
        TextView cb = txt("📞", 20, Color.WHITE, false);
        cb.setGravity(Gravity.CENTER);
        cb.setBackground(rounded(ACCENT(), 22, 0));
        cb.setPadding((int) (12 * s), (int) (10 * s), (int) (12 * s), (int) (10 * s));
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { callNumber(ct.number); }
        });
        row.addView(cb, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    // ---------- TECLADO AMPLIADO (rediseño) ----------
    private void keypadTab() {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (10 * s), (int) (4 * s), (int) (10 * s), (int) (8 * s));
        content.addView(col, new LinearLayout.LayoutParams(-1, -2));

        // Display grande con botón Borrar bien visible (antes era un texto minúsculo).
        LinearLayout displayBox = new LinearLayout(host);
        displayBox.setOrientation(LinearLayout.HORIZONTAL);
        displayBox.setGravity(Gravity.CENTER_VERTICAL);
        displayBox.setBackground(rounded(INNER(), 16, 0));
        displayBox.setPadding((int) (14 * s), (int) (8 * s), (int) (10 * s), (int) (8 * s));
        dialNumber = txt("", 32, TEXT(), true);
        dialNumber.setGravity(Gravity.CENTER);
        dialNumber.setMaxLines(1);
        dialNumber.setEllipsize(TextUtils.TruncateAt.END);
        displayBox.addView(dialNumber, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView bs = txt("⌫  Borrar", 17, Color.WHITE, true);
        bs.setGravity(Gravity.CENTER);
        bs.setBackground(rounded(0xFFE53935, 14, 0));
        bs.setPadding((int) (18 * s), (int) (12 * s), (int) (18 * s), (int) (12 * s));
        bs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dialText.length() > 0) { dialText = dialText.substring(0, dialText.length() - 1); dialNumber.setText(dialText); } }
        });
        bs.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { dialText = ""; dialNumber.setText(""); return true; }
        });
        LinearLayout.LayoutParams bslp = new LinearLayout.LayoutParams(-2, -2);
        bslp.leftMargin = (int) (10 * s);
        displayBox.addView(bs, bslp);
        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(-1, -2);
        dbp.bottomMargin = (int) (10 * s);
        col.addView(displayBox, dbp);

        final String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0+", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int colI = 0; colI < 3; colI++) {
                final String d = digits[r * 3 + colI];
                TextView b = txt(d, 28, TEXT(), true);
                b.setGravity(Gravity.CENTER);
                b.setBackground(rounded(INNER(), 16, 0));
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
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (46 * s), 1f);
                lp.leftMargin = (int) (4 * s); lp.rightMargin = (int) (4 * s);
                lp.topMargin = (int) (3 * s); lp.bottomMargin = (int) (3 * s);
                line.addView(b, lp);
            }
            col.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }

        TextView callBtn = txt("📞  Llamar", 22, Color.WHITE, true);
        callBtn.setGravity(Gravity.CENTER);
        callBtn.setBackground(rounded(ACCENT(), 18, 0));
        callBtn.setPadding(0, (int) (12 * s), 0, (int) (12 * s));
        callBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { final String n = dialText.trim(); if (n.length() > 0) callNumber(n); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = (int) (8 * s);
        col.addView(callBtn, clp);
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
        Map<Long, Boolean> starMap = new HashMap<Long, Boolean>();
        Map<Long, Uri> thumbMap = new HashMap<Long, Uri>();
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
                ct.contactId = contactId;
                ct.photoUri = contactId >= 0 ? thumbMap.get(contactId) : null;
                if (ct.name.length() > 0 && ct.number.length() > 0) out.add(ct);
            }
        } catch (Throwable ignored) { }
        try { if (c != null) c.close(); } catch (Throwable ignored) {}

        contacts.clear();
        contacts.addAll(out);

        // Favoritos: primero los que ya marcó el usuario. La primera vez se
        // intenta traer las estrellas del teléfono; si el PBAP no las expone
        // (iPhone), se usan los números más llamados como aproximación.
        Set<String> favs = prefsFav();
        if (!host.getPrefs().getBoolean("phone_fav_imported", false)) {
            importFavorites(true);
            favs = prefsFav();
        }
        for (Contact ct : out) {
            Boolean st = ct.contactId >= 0 ? starMap.get(ct.contactId) : null;
            ct.starred = (st != null && st.booleanValue()) || favs.contains(ct.key);
        }
        contacts.clear();
        contacts.addAll(out);
    }

    /** Historial real de llamadas del radio (BTSuite / HFP). */
    static final Uri BT_CALLLOG_URI =
            Uri.parse("content://com.autochips.bluetooth.calllog/bluetooth_calllog_table");

    /**
     * Importa favoritos desde el teléfono. Primero intenta las estrellas de la
     * agenda del sistema; si no hay ninguna (el PBAP del iPhone no expone el
     * atributo "favorito"), usa los números más llamados del historial HFP.
     */
    private int importFavorites(boolean includeTopCalled) {
        int added = 0;
        try {
            Set<String> favs = prefsFav();
            int before = favs.size();
            Set<Long> starredIds = new HashSet<Long>();
            Cursor c = null;
            try {
                c = host.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                        new String[]{ContactsContract.Contacts._ID},
                        ContactsContract.Contacts.STARRED + "=1", null, null);
                if (c != null) while (c.moveToNext()) {
                    try { starredIds.add(c.getLong(0)); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
            for (Contact ct : contacts) {
                if (ct.contactId >= 0 && starredIds.contains(ct.contactId)) {
                    if (favs.add(ct.key)) added++;
                }
            }
            if (includeTopCalled && favs.size() == before) {
                final Map<String, Integer> freq = readBtCallFreq();
                List<Contact> ranked = new ArrayList<Contact>(contacts);
                Collections.sort(ranked, new Comparator<Contact>() {
                    public int compare(Contact a, Contact b) {
                        int fa = freqOf(freq, a.number);
                        int fb = freqOf(freq, b.number);
                        if (fa != fb) return fb - fa;
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
                for (Contact ct : ranked) {
                    if (added >= 8) break;
                    if (freqOf(freq, ct.number) <= 0) continue;
                    if (favs.add(ct.key)) added++;
                }
            }
            if (added > 0) {
                host.getPrefs().edit()
                        .putStringSet("phone_fav_ids", favs)
                        .putBoolean("phone_fav_imported", true).apply();
                for (Contact ct : contacts) if (favs.contains(ct.key)) ct.starred = true;
            }
        } catch (Throwable ignored) {}
        return added;
    }

    private static int freqOf(Map<String, Integer> freq, String number) {
        String n = cleanNumber(number);
        if (n == null) return 0;
        Integer v = freq.get(n);
        return v != null ? v.intValue() : 0;
    }

    private Map<String, Integer> readBtCallFreq() {
        Map<String, Integer> freq = new HashMap<String, Integer>();
        Cursor c = null;
        try {
            c = host.getContentResolver().query(BT_CALLLOG_URI,
                    new String[]{"phone_number"}, null, null, null);
            if (c != null) while (c.moveToNext()) {
                String n = cleanNumber(c.getString(0));
                if (n == null) continue;
                Integer v = freq.get(n);
                freq.put(n, v == null ? 1 : v.intValue() + 1);
            }
        } catch (Throwable ignored) {}
        try { if (c != null) c.close(); } catch (Throwable ignored) {}
        return freq;
    }

    private static String safe(String s, String def) { return s != null ? s : def; }

    private void toggleStar(Contact ct) {
        ct.starred = !ct.starred;
        Set<String> favs = prefsFav();
        String key = ct.key;
        if (ct.starred) favs.add(key); else favs.remove(key);
        try { host.getPrefs().edit().putStringSet("phone_fav_ids", favs).apply(); } catch (Throwable ignored) {}
        // Mejor esfuerzo: persistir también en la agenda del sistema para que el
        // favorito quede marcado ahí (puede ser de solo lectura en contactos PBAP).
        try {
            if (ct.contactId >= 0) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(ContactsContract.Contacts.STARRED, ct.starred ? 1 : 0);
                host.getContentResolver().update(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, String.valueOf(ct.contactId)), cv, null, null);
            }
        } catch (Throwable ignored) {}
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
        List<CallRec> out = new ArrayList<CallRec>();
        Map<String, CallRec> seen = new HashMap<String, CallRec>();

        // 1) Historial real del radio: BTSuite (HeadsetClientService) guarda aquí
        //    las llamadas entrantes/salientes/perdidas del Bluetooth.
        Cursor c = null;
        try {
            c = host.getContentResolver().query(BT_CALLLOG_URI,
                    new String[]{"phone_number", "display_name", "call_type", "call_time"},
                    null, null, "call_time DESC");
            if (c != null) while (c.moveToNext()) {
                String num = null;
                try { num = c.getString(0); } catch (Throwable ignored) {}
                if (num == null || num.trim().length() == 0) continue;
                CallRec r = new CallRec();
                r.number = num.trim();
                String type = null;
                try { type = c.getString(2); } catch (Throwable ignored) {}
                r.type = mapBtCallType(type);
                // El historial Bluetooth no guarda duración: una perdida = 0 y
                // el resto se considera contestada, así se conserva la lógica
                // de "Números desconocidos" (no conocidos y no contestados).
                r.duration = "missed".equalsIgnoreCase(type) ? 0 : 1;
                String when = null;
                try { when = c.getString(3); } catch (Throwable ignored) {}
                r.date = parseBtTime(when);
                String dn = null;
                try { dn = c.getString(1); } catch (Throwable ignored) {}
                r.name = (dn != null && dn.length() > 0 && !"NULL".equalsIgnoreCase(dn)) ? dn : lookupName(r.number);
                r.known = r.name != null;
                String key = cleanNumber(r.number) + "|" + (r.date / 60000L);
                if (!seen.containsKey(key)) {
                    seen.put(key, r);
                    out.add(r);
                }
                if (out.size() >= 100) break;
            }
        } catch (Throwable ignored) {}
        try { if (c != null) c.close(); } catch (Throwable ignored) {}

        // 2) CallLog estándar (por si el firmware también lo usa).
        c = null;
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
                if (r.number == null || r.number.trim().length() == 0) continue;
                String cached = null;
                try { cached = c.getString(1); } catch (Throwable ignored) { }
                try { r.type = c.getInt(2); } catch (Throwable ignored) { r.type = 0; }
                try { r.date = c.getLong(3); } catch (Throwable ignored) { r.date = 0; }
                try { r.duration = c.getLong(4); } catch (Throwable ignored) { r.duration = 0; }
                r.name = (cached != null && cached.length() > 0) ? cached : lookupName(r.number);
                r.known = r.name != null;
                String key = cleanNumber(r.number) + "|" + (r.date / 60000L);
                if (!seen.containsKey(key)) {
                    seen.put(key, r);
                    out.add(r);
                }
                if (out.size() >= 100) break;
            }
        } catch (Throwable ignored) { }
        try { if (c != null) c.close(); } catch (Throwable ignored) {}

        Collections.sort(out, new Comparator<CallRec>() {
            public int compare(CallRec a, CallRec b) {
                if (a.date == b.date) return 0;
                return a.date < b.date ? 1 : -1;
            }
        });
        calls.clear();
        calls.addAll(out);
    }

    private static int mapBtCallType(String type) {
        if (type == null) return CallLog.Calls.MISSED_TYPE;
        if (type.equalsIgnoreCase("incoming")) return CallLog.Calls.INCOMING_TYPE;
        if (type.equalsIgnoreCase("outgoing")) return CallLog.Calls.OUTGOING_TYPE;
        if (type.equalsIgnoreCase("missed")) return CallLog.Calls.MISSED_TYPE;
        return CallLog.Calls.MISSED_TYPE;
    }

    private static long parseBtTime(String when) {
        if (when == null || when.length() < 15) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            f.setLenient(true);
            Date d = f.parse(when);
            return d != null ? d.getTime() : 0;
        } catch (Throwable t) {
            return 0;
        }
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
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            opts.inSampleSize = 2; // fotos de contacto suelen ser >192px; reduce memoria
                            Bitmap raw = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size(), opts);
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
        try { photoCache.evictAll(); } catch (Throwable ignored) {}
        try { if (tone != null) tone.release(); } catch (Throwable ignored) {}
        try { if (themedWindow != null) themedWindow.hide(); } catch (Throwable ignored) {}
        try { if (hfp != null) hfp.close(); } catch (Throwable ignored) {}
    }
}
