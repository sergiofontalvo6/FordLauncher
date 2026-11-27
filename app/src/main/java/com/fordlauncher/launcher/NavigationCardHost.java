package com.fordlauncher.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fordlauncher.launcher.nav.NavState;

/**
 * Tarjeta de navegación del HOME. Sin ruta ofrece Waze / Maps y accesos rápidos;
 * con ruta activa muestra la maniobra que publisha el lector de notificaciones.
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
    }

    /** El HOME vuelve al frente: el card ya refleja el estado, solo se redibuja. */
    public void onHostResumed() { refresh(); }

    public void refresh() {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                if (openTv != null) openTv.setText("Abrir " + appLabel());
                render(NavState.get());
            }
        });
    }

    @Override
    public void onNavState(final NavState.Data data) {
        activity.runOnUiThread(new Runnable() {
            public void run() { render(data); }
        });
    }

    private void render(NavState.Data d) {
        if (idle == null || route == null) return;
        if (d == null) {
            idle.setVisibility(View.VISIBLE);
            route.setVisibility(View.GONE);
            return;
        }
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
        appTv.setText(appLabel() + " · turn-by-turn");
        distanceTv.setText(dist.length() > 0 ? dist : "Ruta activa");
        maneuverTv.setText(man.length() > 0 ? man : "Navegando");
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

    private View buildIdle() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(0, (int) (34 * scale()), 0, (int) (12 * scale()));

        TextView title = text("NAVEGACIÓN", 12, 0xB3FFFFFF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        openTv = text("Abrir " + appLabel(), 18, Color.WHITE, true);
        openTv.setGravity(Gravity.CENTER);
        openTv.setBackground(new PlainRound(0xFFC62828, 12 * scale()));
        openTv.setPadding((int) (12 * scale()), (int) (10 * scale()),
                (int) (12 * scale()), (int) (10 * scale()));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                (int) (240 * scale()), (int) (52 * scale()));
        bp.topMargin = (int) (18 * scale());
        root.addView(openTv, bp);
        openTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openNavigation(""); }
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

    private View buildRoute() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int) (18 * scale()), (int) (44 * scale()),
                (int) (18 * scale()), (int) (16 * scale()));

        appTv = text("", 12, 0xB3FFFFFF, true);
        root.addView(appTv);

        LinearLayout main = new LinearLayout(activity);
        main.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = (int) (20 * scale());

        arrow = new ImageView(activity);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        main.addView(arrow, new LinearLayout.LayoutParams(
                (int) (76 * scale()), (int) (76 * scale())));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        distanceTv = text("—", 28, Color.WHITE, true);
        maneuverTv = text("Ruta activa", 14, Color.WHITE, false);
        col.addView(distanceTv);
        LinearLayout.LayoutParams mnp = new LinearLayout.LayoutParams(-1, -2);
        mnp.topMargin = (int) (6 * scale());
        col.addView(maneuverTv, mnp);
        main.addView(col, new LinearLayout.LayoutParams(-1, -2));
        root.addView(main, mp);

        etaTv = text("", 13, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = (int) (20 * scale());
        root.addView(etaTv, ep);

        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { bringToFront(); }
        });
        return root;
    }

    private void openHome() {
        String d = prefs.getString("nav_home", "");
        openNavigation(d.length() > 0 ? d : "casa");
    }

    private void openWork() {
        String d = prefs.getString("nav_work", "");
        openNavigation(d.length() > 0 ? d : "trabajo");
    }

    /** Abre la app elegida en Ajustes → Mapa y clima; con destino, arranca la ruta. */
    private void openNavigation(String destino) {
        boolean maps = "maps".equals(prefs.getString("nav_app", "waze"));
        String pkg = maps ? "com.google.android.apps.maps" : "com.waze";
        try {
            Uri uri;
            if (destino == null || destino.length() == 0) {
                uri = maps ? Uri.parse("geo:0,0") : Uri.parse("https://waze.com/ul");
            } else if (maps) {
                uri = Uri.parse("google.navigation:q=" + Uri.encode(destino) + "&mode=d");
            } else {
                uri = Uri.parse("https://waze.com/ul?q=" + Uri.encode(destino) + "&navigate=yes");
            }
            Intent i = new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) activity.startActivity(launch);
            } catch (Throwable ignored) { }
        }
    }

    private void bringToFront() {
        String pkg = "maps".equals(prefs.getString("nav_app", "waze"))
                ? "com.google.android.apps.maps" : "com.waze";
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(i);
            }
        } catch (Throwable ignored) { }
    }

    private String appLabel() {
        return "maps".equals(prefs.getString("nav_app", "waze")) ? "Google Maps" : "Waze";
    }

    private float scale() {
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
