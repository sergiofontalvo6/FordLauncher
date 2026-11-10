package com.fordlauncher.launcher;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Ventana de llamada estilo iPhone: tarjeta compacta, centrada y opaca, que se
 * muestra durante la llamada HFP. Cubre la ventana de fábrica de BTSuite (que se
 * crea en otro proceso y no se puede quitar desde FordLauncher), de modo que el
 * usuario ve solo esta interfaz.
 *
 * <p>Estados HFP ({@code BluetoothHeadsetClientCall}): 0 Activa, 1 Retenida,
 * 2 Marcando, 3 Sonando, 4 Entrante, 5 En espera, 7 Terminada.
 */
final class ThemedCallWindow {

    private static final int CARD_DP = 420;
    private static final int CARD_MIN_H_DP = 460;
    private static final long END_DELAY_MS = 2500L;

    private final Main host;
    private final float s;
    private final HfpClient hfp;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private WindowManager.LayoutParams params;
    private boolean showing;
    private boolean dtmfOpen;
    private boolean timing;
    private int callCounter;
    private int ticks;
    private int state = -1;
    private String curNumber, curName;

    private TextView avatar, tvName, tvNumber, tvState, tvTime, dtmfDisplay;
    private ImageView circMute, circSpk, circHold, circDtmf, circAnswer, circHang;
    private TextView lblMute, lblSpk, lblHold;
    private LinearLayout rowAnswer, dtmfGrid, colHold;

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!showing) return;
            ticks++;
            if (timing) {
                callCounter++;
                if (tvTime != null) tvTime.setText(format(callCounter));
            }
            // Si el otro cuelga, el estado puede no llegar: comprobamos las llamadas activas.
            if (state != 7 && ticks >= 4 && hfp != null && !hfp.hasActiveCall()) {
                finish();
                return;
            }
            ui.postDelayed(this, 1000L);
        }
    };
    private final Runnable hideLater = new Runnable() {
        @Override public void run() { hide(); }
    };

    ThemedCallWindow(Main host, float scale, HfpClient hfp) {
        this.host = host;
        this.s = scale;
        this.hfp = hfp;
        this.wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
    }

    private int CARD() { return Main.CARD; }
    private int INNER() { return Main.INNER; }
    private int TEXT() { return Main.TEXT; }
    private int MUTED() { return Main.MUTED; }
    private int RED() { return Main.RED; }
    private int BG() { return Main.BG; }

    private GradientDrawable rounded(int fill, int rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(rDp * s);
        return g;
    }

    private GradientDrawable circle(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        return g;
    }

    private TextView txt(String t, float sp, int col, boolean bold) {
        TextView v = new TextView(host);
        v.setText(t);
        v.setTextColor(col);
        v.setTextSize(sp);
        v.setIncludeFontPadding(false);
        v.setTypeface(android.graphics.Typeface.create(bold ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        return v;
    }

    static boolean canDrawOverlays(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        try { return Settings.canDrawOverlays(c); } catch (Throwable t) { return false; }
    }

    /** Muestra (o actualiza) la tarjeta. Llamar desde el hilo UI. */
    void show(String number, String name, int hfpState) {
        if (!canDrawOverlays(host)) {
            android.util.Log.w("ThemedCallWindow", "sin permiso de superposición");
            return;
        }
        ui.removeCallbacks(hideLater);
        if (name == null) name = lookupName(number);
        if (showing) {
            update(number, name, hfpState);
        } else {
            ticks = 0;
            if (root == null) build();
            update(number, name, hfpState);
            if (!addWindow(2038)) addWindow(2002);
        }
    }

    /**
     * Vuelve a poner la tarjeta encima de la ventana de BTSuite (mismo tipo: la última añadida queda arriba).
     * Se construye una tarjeta nueva y se añade antes de quitar la anterior, así no hay parpadeo.
     */
    private void raise() {
        if (!showing || root == null) return;
        View old = root;
        String dtmf = dtmfDisplay != null ? dtmfDisplay.getText().toString() : "";
        build();
        update(curNumber, curName, state);
        dtmfOpen = dtmfOpen && dtmfGrid != null;
        if (dtmfGrid != null) dtmfGrid.setVisibility(dtmfOpen ? View.VISIBLE : View.GONE);
        if (dtmfDisplay != null) {
            dtmfDisplay.setText(dtmf);
            dtmfDisplay.setVisibility(dtmfOpen ? View.VISIBLE : View.GONE);
        }
        root.setAlpha(0f);
        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            root = old;
            return;
        }
        final View fresh = root;
        fresh.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                fresh.getViewTreeObserver().removeOnPreDrawListener(this);
                fresh.setAlpha(1f);
                try { wm.removeViewImmediate(old); } catch (Throwable ignored) {}
                return true;
            }
        });
    }

    private boolean addWindow(int type) {
        try {
            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            wm.addView(root, params);
            showing = true;
            ui.removeCallbacks(timerTick);
            ui.postDelayed(timerTick, 1000L);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("ThemedCallWindow", "addView falló tipo " + type, t);
            return false;
        }
    }

    /** Llamada terminada: muestra el estado un momento y luego se oculta. */
    void finish() {
        if (!showing) return;
        state = 7;
        timing = false;
        tvState.setText("Llamada terminada");
        ui.removeCallbacks(hideLater);
        ui.postDelayed(hideLater, END_DELAY_MS);
    }

    void hide() {
        ui.removeCallbacks(hideLater);
        ui.removeCallbacks(timerTick);
        if (showing) {
            try { wm.removeViewImmediate(root); } catch (Throwable ignored) {}
        }
        showing = false;
        timing = false;
        callCounter = 0;
        ticks = 0;
        state = -1;
        dtmfOpen = false;
        if (dtmfGrid != null) dtmfGrid.setVisibility(View.GONE);
        if (dtmfDisplay != null) { dtmfDisplay.setText(""); dtmfDisplay.setVisibility(View.GONE); }
    }

    void update(String number, String name, int hfpState) {
        state = hfpState;
        curNumber = number;
        curName = name;
        String shown = (name != null && name.length() > 0) ? name : (number != null && number.length() > 0 ? number : "Desconocido");
        tvName.setText(shown);
        avatar.setText(initial(shown));
        boolean hasName = name != null && name.length() > 0;
        tvNumber.setText(hasName && number != null ? number : "");
        tvNumber.setVisibility(hasName && number != null ? View.VISIBLE : View.GONE);
        tvState.setText(stateText(hfpState));

        boolean incoming = hfpState == 4 || hfpState == 5;
        boolean active = hfpState == 0 || hfpState == 1;
        if (active && !timing) { timing = true; callCounter = 0; }
        tvTime.setText(timing ? format(callCounter) : "");
        tvTime.setVisibility(timing ? View.VISIBLE : View.GONE);

        rowAnswer.setVisibility(incoming ? View.VISIBLE : View.GONE);
        colHold.setVisibility(active ? View.VISIBLE : View.GONE);
        lblHold.setText(hfpState == 1 ? "Reanudar" : "Retener");
        paintHold(hfpState == 1);

        refreshAudio();
    }

    private void paintKey(ImageView circ, boolean on, int onIcon, int offIcon) {
        if (circ == null) return;
        circ.setBackground(circle(on ? TEXT() : INNER()));
        circ.setImageBitmap(IosIcons.bitmap(on ? onIcon : offIcon, iconPx(60), on ? BG() : TEXT()));
    }

    private void paintHold(boolean resume) {
        if (circHold == null) return;
        circHold.setImageBitmap(IosIcons.bitmap(resume ? IosIcons.PLAY : IosIcons.PAUSE, iconPx(60), TEXT()));
    }

    private final Runnable audioRefresh = new Runnable() {
        @Override public void run() { refreshAudio(); }
    };

    private void refreshAudio() {
        try {
            AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
            paintKey(circMute, am.isMicrophoneMute(), IosIcons.MIC_OFF, IosIcons.MIC);
            boolean onRadio = hfp != null && hfp.getAudioState() == 2;
            paintKey(circSpk, onRadio, IosIcons.SPEAKER, IosIcons.SPEAKER);
            if (lblSpk != null) lblSpk.setText(onRadio ? "Radio" : "Teléfono");
        } catch (Throwable ignored) {}
    }

    private int iconPx(int dp) {
        return Math.round(dp * s * 0.5f);
    }

    private static String initial(String name) {
        if (name == null || name.length() == 0) return "?";
        char c = name.charAt(0);
        return Character.isLetter(c) ? String.valueOf(Character.toUpperCase(c)) : "#";
    }

    private static String format(int secs) {
        return String.format("%02d:%02d", (secs / 60) % 60, secs % 60);
    }

    private String lookupName(String number) {
        if (number == null || number.length() == 0) return null;
        Cursor c = null;
        try {
            c = host.getContentResolver().query(
                    Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable ignored) {
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String stateText(int st) {
        switch (st) {
            case 0: return "En llamada";
            case 1: return "Retenida";
            case 2: return "Marcando…";
            case 3: return "Sonando…";
            case 4: return "Llamada entrante";
            case 5: return "En espera";
            case 7: return "Llamada terminada";
            default: return "Llamando…";
        }
    }

    private LinearLayout keyColumn(View circ, TextView label, int dp) {
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(circ, new LinearLayout.LayoutParams((int) (dp * s), (int) (dp * s)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (6 * s);
        col.addView(label, lp);
        return col;
    }

    private ImageView circleKey(int icon, int dp, int fill, int color) {
        ImageView v = new ImageView(host);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(circle(fill));
        v.setImageBitmap(IosIcons.bitmap(icon, iconPx(dp), color));
        return v;
    }

    private void build() {
        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        // Efecto glass: fondo semitransparente con borde suave
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xB31E2128);
        glass.setCornerRadius(34 * s);
        glass.setStroke(Math.max(1, (int) s), 0x40FFFFFF);
        wrap.setBackground(glass);
        wrap.setPadding((int) (18 * s), (int) (10 * s), (int) (18 * s), (int) (20 * s));
        wrap.setMinimumHeight((int) (CARD_MIN_H_DP * s));

        // Asa para arrastrar (como la ventana de fábrica)
        View grab = new View(host);
        grab.setBackground(rounded(MUTED(), 3));
        wrap.addView(grab, new LinearLayout.LayoutParams((int) (40 * s), (int) (5 * s)));

        avatar = txt("?", 32, TEXT(), true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(circle(INNER()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams((int) (84 * s), (int) (84 * s));
        alp.topMargin = (int) (14 * s);
        wrap.addView(avatar, alp);

        tvName = txt("", 22, TEXT(), true);
        tvName.setGravity(Gravity.CENTER);
        tvName.setMaxLines(1);
        tvName.setEllipsize(TextUtils.TruncateAt.END);
        tvName.setPadding(0, (int) (10 * s), 0, 0);
        wrap.addView(tvName, new LinearLayout.LayoutParams(-1, -2));

        tvNumber = txt("", 14, MUTED(), false);
        tvNumber.setGravity(Gravity.CENTER);
        tvNumber.setMaxLines(1);
        wrap.addView(tvNumber, new LinearLayout.LayoutParams(-1, -2));

        tvState = txt("Llamando…", 15, RED(), true);
        tvState.setGravity(Gravity.CENTER);
        tvState.setPadding(0, (int) (4 * s), 0, 0);
        wrap.addView(tvState, new LinearLayout.LayoutParams(-1, -2));

        tvTime = txt("", 15, MUTED(), false);
        tvTime.setGravity(Gravity.CENTER);
        tvTime.setVisibility(View.GONE);
        wrap.addView(tvTime, new LinearLayout.LayoutParams(-1, -2));

        // Fila de teclas: silenciar, teclado, altavoz, retener
        LinearLayout keys = new LinearLayout(host);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER);

        circMute = circleKey(IosIcons.MIC, 60, INNER(), TEXT());
        lblMute = txt("Silenciar", 12, TEXT(), false);
        circMute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
                    boolean m = !am.isMicrophoneMute();
                    am.setMicrophoneMute(m);
                    paintKey(circMute, m, IosIcons.MIC_OFF, IosIcons.MIC);
                } catch (Throwable ignored) {}
            }
        });
        keys.addView(keyColumn(circMute, lblMute, 60), keyWeight());

        circDtmf = circleKey(IosIcons.KEYPAD, 60, INNER(), TEXT());
        TextView lblDtmf = txt("Teclado", 12, TEXT(), false);
        circDtmf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dtmfOpen = !dtmfOpen;
                if (dtmfGrid != null) dtmfGrid.setVisibility(dtmfOpen ? View.VISIBLE : View.GONE);
                if (dtmfDisplay != null) dtmfDisplay.setVisibility(dtmfOpen ? View.VISIBLE : View.GONE);
            }
        });
        keys.addView(keyColumn(circDtmf, lblDtmf, 60), keyWeight());

        circSpk = circleKey(IosIcons.SPEAKER, 60, INNER(), TEXT());
        lblSpk = txt("Teléfono", 12, TEXT(), false);
        circSpk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { if (hfp != null) hfp.toggleAudioRoute(); } catch (Throwable ignored) {}
                ui.postDelayed(audioRefresh, 600);
                ui.postDelayed(audioRefresh, 1500);
            }
        });
        keys.addView(keyColumn(circSpk, lblSpk, 60), keyWeight());

        circHold = circleKey(IosIcons.PAUSE, 60, INNER(), TEXT());
        lblHold = txt("Retener", 12, TEXT(), false);
        circHold.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { if (hfp != null) hfp.acceptCall(1); } catch (Throwable ignored) {} }
        });
        colHold = keyColumn(circHold, lblHold, 60);
        colHold.setVisibility(View.GONE);
        keys.addView(colHold, keyWeight());

        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-1, -2);
        kp.topMargin = (int) (18 * s);
        wrap.addView(keys, kp);

        // Teclado DTMF (oculto hasta pulsar «Teclado»)
        dtmfDisplay = txt("", 20, TEXT(), true);
        dtmfDisplay.setGravity(Gravity.CENTER);
        dtmfDisplay.setVisibility(View.GONE);
        wrap.addView(dtmfDisplay, new LinearLayout.LayoutParams(-1, -2));

        dtmfGrid = new LinearLayout(host);
        dtmfGrid.setOrientation(LinearLayout.VERTICAL);
        dtmfGrid.setVisibility(View.GONE);
        final String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        for (int r = 0; r < 4; r++) {
            LinearLayout line = new LinearLayout(host);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final String d = digits[r * 3 + c];
                TextView b = txt(d, 20, TEXT(), true);
                b.setGravity(Gravity.CENTER);
                b.setBackground(rounded(INNER(), 14));
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { if (hfp != null) hfp.sendDtmf(d.charAt(0)); } catch (Throwable ignored) {}
                        String cur = dtmfDisplay.getText().toString();
                        if (cur.length() < 24) dtmfDisplay.setText(cur + d);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (44 * s), 1f);
                lp.leftMargin = (int) (4 * s); lp.rightMargin = (int) (4 * s);
                lp.topMargin = (int) (4 * s); lp.bottomMargin = (int) (4 * s);
                line.addView(b, lp);
            }
            dtmfGrid.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        wrap.addView(dtmfGrid, new LinearLayout.LayoutParams(-1, -2));

        // Fila inferior: contestar (solo entrante) y colgar
        LinearLayout bottom = new LinearLayout(host);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);

        rowAnswer = new LinearLayout(host);
        circAnswer = circleKey(IosIcons.HANDSET, 72, 0xFF34C759, Color.WHITE);
        circAnswer.setRotation(135f);
        circAnswer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { try { if (hfp != null) hfp.acceptCall(0); } catch (Throwable ignored) {} }
        });
        rowAnswer.addView(keyColumn(circAnswer, txt("Contestar", 12, TEXT(), false), 72), new LinearLayout.LayoutParams(-2, -2));
        rowAnswer.setVisibility(View.GONE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.rightMargin = (int) (28 * s);
        bottom.addView(rowAnswer, ap);

        circHang = circleKey(IosIcons.HANDSET, 72, 0xFFFF3B30, Color.WHITE);
        circHang.setRotation(135f);
        circHang.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { if (hfp != null) hfp.hangUp(); } catch (Throwable ignored) {}
                finish();
            }
        });
        bottom.addView(keyColumn(circHang, txt("Colgar", 12, TEXT(), false), 72), new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = (int) (20 * s);
        wrap.addView(bottom, bp);

        // Tarjeta con margen opaco alrededor para cubrir la ventana de fábrica sin tapar toda la pantalla.
        FrameLayout frame = new FrameLayout(host);
        frame.setBackgroundColor(0xFF14161B);
        int pad = (int) (18 * s);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(wrap, new FrameLayout.LayoutParams((int) (CARD_DP * s), -2));
        root = frame;
    }

    private LinearLayout.LayoutParams keyWeight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    boolean isShowing() { return showing; }
}
