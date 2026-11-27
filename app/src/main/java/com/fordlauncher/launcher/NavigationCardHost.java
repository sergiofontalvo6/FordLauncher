package com.fordlauncher.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fordlauncher.launcher.nav.NavState;
import com.fordlauncher.launcher.nav.NavigationLauncher;

import java.util.Locale;

/**
 * Tarjeta de navegación del HOME. Sin ruta ofrece Waze / Maps y accesos rápidos;
 * con ruta activa muestra el turn-by-turn completo (sin hueco interno) en la
 * columna central, mientras el sistema dibuja el PiP de Maps a la derecha.
 * No crea ventanas flotantes ni servicios propios.
 */
public final class NavigationCardHost implements NavState.Listener {

    private final Activity activity;
    private final FrameLayout card;
    private final SharedPreferences prefs;

    private View idle;
    private View route;
    private ImageView arrow;
    private TextView distanceTv, maneuverTv, etaTv, appTv, openTv;
    private Bitmap icon;
    private String routePkg, lastDistance, lastManeuver;
    private boolean compact;

    public NavigationCardHost(Activity activity, FrameLayout card) {
        this.activity = activity;
        this.card = card;
        this.prefs = activity.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public void attach() {
        idle = buildIdle();
        route = buildRoute();
        card.addView(idle, new FrameLayout.LayoutParams(-1, -1));
        card.addView(route, new FrameLayout.LayoutParams(-1, -1));
        NavState.addListener(this);
        render(NavState.get());
    }

    public void release() {
        NavState.removeListener(this);
        icon = null;
        routePkg = null;
        lastDistance = null;
        lastManeuver = null;
    }

    /** El HOME vuelve al frente: el card ya refleja el estado, solo se redibuja. */
    public void onHostResumed() { refresh(); }

    public void refresh() {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (openTv != null) openTv.setText("Abrir " + NavigationLauncher.appLabel(activity));
                render(NavState.get());
            }
        });
    }

    /** Lo llama el HOME cuando el card se estrecha para dejar sitio al PiP. */
    public void setCompact(final boolean value) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                compact = value;
                applyCompact();
            }
        });
    }

    private void applyCompact() {
        if (arrow == null || distanceTv == null || maneuverTv == null) return;
        ViewGroup.LayoutParams base = arrow.getLayoutParams();
        if (base != null) {
            int d = (int) ((compact ? 50 : 58) * scale());
            base.width = d;
            base.height = d;
            arrow.setLayoutParams(base);
        }
        distanceTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, compact ? 24 : 26);
        maneuverTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 13);
        maneuverTv.setMaxLines(compact ? 3 : 2);
    }

    @Override
    public void onNavState(final NavState.Data data) {
        activity.runOnUiThread(new Runnable() {
            public void run() { render(data); }
        });
    }

    private void render(NavState.Data d) {
        if (idle == null || route == null) return;
        if (!NavState.isActive() || d == null) {
            routePkg = null;
            lastDistance = null;
            lastManeuver = null;
            idle.setVisibility(View.VISIBLE);
            route.setVisibility(View.GONE);
            return;
        }
        if (!d.pkg.equals(routePkg)) {
            lastDistance = null;
            lastManeuver = null;
        }
        routePkg = d.pkg;
        idle.setVisibility(View.GONE);
        route.setVisibility(View.VISIBLE);

        String dist = d.title;
        String man = d.text;
        String eta = d.subText;
        if (!looksDistance(dist) && looksDistance(man)) {
            String t = dist;
            dist = man;
            man = t;
        }
        // Maps publica actualizaciones sin el campo de distancia: conservar la
        // última maniobra conocida evita que el card vuelva a «Ruta activa».
        if (dist.length() == 0) dist = lastDistance == null ? "" : lastDistance;
        else lastDistance = dist;
        if (man.length() == 0) man = lastManeuver == null ? "" : lastManeuver;
        else lastManeuver = man;

        String etiqueta = NavigationLauncher.appLabelFor(routePkg);
        if (etiqueta == null) etiqueta = NavigationLauncher.appLabel(activity);
        appTv.setText(etiqueta + " · turn-by-turn");
        distanceTv.setText(formatoDistancia(dist.length() > 0 ? dist : "Ruta activa"));
        maneuverTv.setText(nombreCalle(man.length() > 0 ? man : "Navegando"));
        etaTv.setText(eta.length() > 0 ? eta : "");

        if (d.icon != null) icon = d.icon;
        if (icon != null && !icon.isRecycled()) arrow.setImageBitmap(icon);
        else arrow.setImageDrawable(new ArrowDrawable(0xFFF5F5F5));
    }

    private static boolean looksDistance(String s) {
        if (s == null || s.length() == 0 || s.length() > 18) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == ',' || c == ' '
                    || c == 'm' || c == 'M' || c == 'k' || c == 'K')) return false;
        }
        return true;
    }

    /** "1.4 km" → "1,4 km" para que coincida con el formato del card en español. */
    private static String formatoDistancia(String s) {
        return s == null ? "" : s.trim().replace('.', ',');
    }

    /** Quita el prefijo de la notificación para que la calle ocupe solo 2 líneas. */
    private static String nombreCalle(String s) {
        if (s == null) return "";
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        String[] prefijos = {"en dirección a ", "en direccion a ", "hacia ",
                "gire a la derecha en ", "gire a la izquierda en ", "continue en "};
        for (String p : prefijos) {
            if (lower.startsWith(p)) return t.substring(p.length()).trim();
        }
        return t;
    }

    private View buildIdle() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(0, (int) (34 * scale()), 0, (int) (12 * scale()));

        TextView title = text("NAVEGACIÓN", 12, 0xB3FFFFFF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        openTv = text("Abrir " + NavigationLauncher.appLabel(activity), 18, Color.WHITE, true);
        openTv.setGravity(Gravity.CENTER);
        openTv.setBackground(new PlainRound(0xFFC62828, 12 * scale()));
        openTv.setPadding((int) (12 * scale()), (int) (10 * scale()),
                (int) (12 * scale()), (int) (10 * scale()));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                (int) (240 * scale()), (int) (52 * scale()));
        bp.topMargin = (int) (18 * scale());
        root.addView(openTv, bp);
        openTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { NavigationLauncher.open(activity, ""); }
        });

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, (int) (44 * scale()));
        rp.topMargin = (int) (14 * scale());
        root.addView(row, rp);

        addSmall(row, "Casa", new View.OnClickListener() {
            public void onClick(View v) { openHome(); }
        });
        addSmall(row, "Trabajo", new View.OnClickListener() {
            public void onClick(View v) { openWork(); }
        });
        addSmall(row, "Buscar por voz", new View.OnClickListener() {
            public void onClick(View v) {
                try { VoiceTrigger.fire(activity, "buscar destino"); }
                catch (Throwable ignored) { }
            }
        });

        TextView hint = text("Los giros aparecen aquí cuando haya una ruta activa",
                11, 0x99FFFFFF, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = (int) (18 * scale());
        root.addView(hint, hp);
        return root;
    }

    private void addSmall(LinearLayout row, String label, View.OnClickListener click) {
        TextView b = text(label, 12, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new PlainRound(0x33FFFFFF, 9 * scale()));
        b.setPadding((int) (4 * scale()), (int) (8 * scale()),
                (int) (4 * scale()), (int) (8 * scale()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = (int) (5 * scale());
        lp.rightMargin = (int) (5 * scale());
        b.setOnClickListener(click);
        row.addView(b, lp);
    }

    /** Card con ruta: la guía ocupa el 100% del ancho; el PiP va fuera, a la derecha. */
    private View buildRoute() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (12 * scale()), (int) (26 * scale()),
                (int) (12 * scale()), (int) (12 * scale()));
        root.addView(buildTurnSide(), new LinearLayout.LayoutParams(-1, -1));
        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bringToFront(); }
        });
        return root;
    }

    /** Columna central: app, flecha grande, distancia, calle en 2–3 líneas y ETA. */
    private LinearLayout buildTurnSide() {
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);

        appTv = text("", 11, 0xB3FFFFFF, true);
        left.addView(appTv);

        LinearLayout main = new LinearLayout(activity);
        main.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = (int) (14 * scale());

        arrow = new ImageView(activity);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        main.addView(arrow, new LinearLayout.LayoutParams(
                (int) (58 * scale()), (int) (58 * scale())));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        distanceTv = text("—", 26, Color.WHITE, true);
        maneuverTv = text("Ruta activa", 13, Color.WHITE, false);
        maneuverTv.setMaxLines(3);
        maneuverTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(distanceTv);
        LinearLayout.LayoutParams mnp = new LinearLayout.LayoutParams(-1, -2);
        mnp.topMargin = (int) (4 * scale());
        col.addView(maneuverTv, mnp);
        main.addView(col, new LinearLayout.LayoutParams(-1, -2));
        left.addView(main, mp);

        etaTv = text("", 12, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = (int) (14 * scale());
        left.addView(etaTv, ep);
        return left;
    }

    private void openHome() {
        String d = prefs.getString("nav_home", "");
        NavigationLauncher.open(activity, d.length() > 0 ? d : "casa");
    }

    private void openWork() {
        String d = prefs.getString("nav_work", "");
        NavigationLauncher.open(activity, d.length() > 0 ? d : "trabajo");
    }

    private void bringToFront() { NavigationLauncher.bringToFront(activity, routePkg); }

    private float scale() {
        if (compact) return 1f;
        return card.getWidth() > 0 ? card.getWidth() / 431f : 1.2f;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView tv = new TextView(activity);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    /** Fondo redondeado sin GradientDrawable: un drawable pequeño y sin estado extra. */
    private static final class PlainRound extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;

        PlainRound(int color, float radius) {
            paint.setColor(color);
            this.radius = radius;
        }

        @Override public void draw(Canvas c) {
            c.drawRoundRect(new android.graphics.RectF(getBounds()), radius, radius, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Flecha de giro dibujada en canvas cuando Waze/Maps no envían largeIcon. */
    private static final class ArrowDrawable extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ArrowDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float x = b.left + b.width() * 0.32f;
            float y = b.bottom - b.height() * 0.16f;
            float r = Math.min(b.width(), b.height()) * 0.34f;
            c.drawLine(x, y, x, y - r, paint);
            c.drawLine(x, y - r, x + r, y - r, paint);
            float s = Math.max(10f, r * 0.42f);
            c.drawLine(x + r, y - r, x + r - s, y - r - s * 0.7f, paint);
            c.drawLine(x + r, y - r, x + r - s, y - r + s * 0.7f, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
