package com.fordlauncher.launcher;

import android.graphics.Color;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fordlauncher.launcher.obd.Pids;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Modo estacionado (Parked Screen / screensaver): tras N minutos sin tocar el inicio
 * y sin ir en movimiento se ve un reloj grande o la imagen del auto. Un toque lo quita.
 * Programatico (FordLauncher no usa layouts XML).
 */
public class ParkedModeManager {
    public static final long DEFAULT_TIMEOUT_MS = 300000L; // 5 min
    private static final Locale ES = new Locale("es", "CO");

    private final Main host;
    private final Handler h = new Handler();
    private FrameLayout overlay;
    private LinearLayout clockBox;
    private ImageView imageView;
    private TextView bigClock;
    private TextView dateLine;
    private boolean showing;
    private boolean paused = true;
    private Float savedBright;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (showing) updateClock();
            if (showing) h.postDelayed(tick, 1000);
        }
    };

    private final Runnable fire = new Runnable() {
        public void run() {
            if (paused || showing) return;
            if (!isEnabled()) return;
            // Solo si esta quieto: si se mueve, reintentar en 30s.
            if (currentSpeedKmh() > 0f) { h.postDelayed(fire, 30000L); return; }
            show();
        }
    };

    public ParkedModeManager(Main host) { this.host = host; }

    public boolean isShowing() { return showing; }
    public boolean isEnabled() { return prefs().getBoolean("parked_mode_enabled", false); }
    private long timeoutMs() {
        try { return Math.max(1, prefs().getInt("parked_mode_timeout_min", 5)) * 60000L; }
        catch (Throwable t) { return DEFAULT_TIMEOUT_MS; }
    }
    private SharedPreferences prefs() { return host.getPrefs(); }
    private float s() { return host.parkedScale(); }

    /** Velocidad actual: OBD PID 010D si hay, si no GPS. */
    public float currentSpeedKmh() {
        try {
            com.fordlauncher.launcher.obd.ObdRepository repo = host.getObdRepo();
            if (repo != null && repo.getState() == com.fordlauncher.launcher.obd.ObdRepository.State.ON) {
                Map<String, Float> v = repo.getValues();
                if (v != null) {
                    Float spd = v.get(Pids.SPEED);
                    if (spd != null) return Math.max(0f, spd);
                }
            }
        } catch (Throwable ignored) {}
        return host.lastGpsKmh();
    }

    /** Crea el overlay (fondo negro puro) y lo deja encima de todo dentro de root. */
    public void attach(FrameLayout root) {
        if (overlay != null) { try { root.removeView(overlay); } catch (Throwable ignored) {} }
        overlay = new FrameLayout(host);
        overlay.setBackgroundColor(Color.BLACK);
        overlay.setElevation(1000f);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);

        clockBox = new LinearLayout(host);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(-2, -2);
        cLp.gravity = Gravity.CENTER;
        bigClock = new TextView(host);
        bigClock.setTextColor(Color.WHITE);
        bigClock.setTextSize(s() * 72f / 1f > 72f * s() ? 72f : 72f);
        bigClock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 72);
        bigClock.setIncludeFontPadding(false);
        bigClock.setGravity(Gravity.CENTER_HORIZONTAL);
        clockBox.addView(bigClock);
        dateLine = new TextView(host);
        dateLine.setTextColor(0xFF8B9099);
        dateLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        dateLine.setGravity(Gravity.CENTER_HORIZONTAL);
        dateLine.setPadding(0, (int)(6 * s()), 0, 0);
        clockBox.addView(dateLine);
        overlay.addView(clockBox, cLp);

        imageView = new ImageView(host);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(View.GONE);
        overlay.addView(imageView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    public void onResume() { paused = false; if (overlay != null && showing) { } else arm(); }
    public void onPause() { paused = true; h.removeCallbacks(fire); h.removeCallbacks(tick); }
    public void onDestroy() { h.removeCallbacks(fire); h.removeCallbacks(tick); restoreBright(); }

    /** Llamado desde Main.dispatchTouchEvent. Devuelve true si consumio el evento (salvaparallas activo). */
    public boolean dispatchTouch(MotionEvent ev) {
        if (showing) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                hideWithFade();
                return true;
            }
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && !paused && isEnabled()) arm();
        return false;
    }

    private void arm() {
        h.removeCallbacks(fire);
        if (paused || showing || !isEnabled()) return;
        h.postDelayed(fire, timeoutMs());
    }

    private void show() {
        if (overlay == null || showing) return;
        showing = true;
        boolean useImage = "image".equals(prefs().getString("parked_mode_type", "clock"));
        if (useImage) {
            Bitmap b = null;
            try { b = host.loadParkedImage(); } catch (Throwable ignored) {}
            if (b != null) {
                imageView.setImageBitmap(b);
                imageView.setVisibility(View.VISIBLE);
                clockBox.setVisibility(View.GONE);
            } else {
                imageView.setVisibility(View.GONE);
                clockBox.setVisibility(View.VISIBLE);
            }
        } else {
            imageView.setVisibility(View.GONE);
            clockBox.setVisibility(View.VISIBLE);
        }
        updateClock();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        AlphaAnimation a = new AlphaAnimation(0f, 1f);
        a.setDuration(400);
        overlay.startAnimation(a);
        dimBright();
        h.postDelayed(tick, 1000);
    }

    private void hideWithFade() {
        if (!showing || overlay == null) return;
        showing = false;
        h.removeCallbacks(tick);
        restoreBright();
        final FrameLayout o = overlay;
        AlphaAnimation a = new AlphaAnimation(1f, 0f);
        a.setDuration(300);
        a.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation animation) {}
            public void onAnimationEnd(Animation animation) {
                o.setVisibility(View.GONE);
                o.setAlpha(1f);
            }
            public void onAnimationRepeat(Animation animation) {}
        });
        o.startAnimation(a);
        arm();
    }

    public void hide() { if (showing) { showing = false; h.removeCallbacks(tick); restoreBright(); if (overlay != null) overlay.setVisibility(View.GONE); } }

    private void updateClock() {
        if (bigClock == null) return;
        Date now = new Date();
        String fmt = prefs().getString("clock", "auto");
        boolean h24 = "24".equals(fmt) || ("auto".equals(fmt) && android.text.format.DateFormat.is24HourFormat(host));
        bigClock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", ES).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        dateLine.setText(d.substring(0, 1).toUpperCase(ES) + d.substring(1) + (h24 ? "" : ""));
    }

    private void dimBright() {
        try {
            Window w = host.getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            if (savedBright == null) savedBright = lp.screenBrightness;
            lp.screenBrightness = 0.25f;
            w.setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private void restoreBright() {
        try {
            Window w = host.getWindow();
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = savedBright;
            w.setAttributes(lp);
            savedBright = null;
        } catch (Throwable ignored) {}
    }
}
