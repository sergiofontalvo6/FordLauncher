package com.fordlauncher.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee SOLO la notificación de Open Headunit (pista + carátula de Android Auto).
 * Hay que activarlo una vez en Ajustes → Accesibilidad → "Carátula de Android Auto".
 */
public class NotifArt extends AccessibilityService {

    public interface Listener { void onAaNotification(Bitmap art, List<String> texts); }

    static Listener listener;
    static Bitmap lastArt;
    static List<String> lastTexts = new ArrayList<String>();
    static long lastAt;
    static boolean running;

    @Override
    protected void onServiceConnected() {
        running = true;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
            if (!"com.andrerinas.headunitrevived".equals(String.valueOf(e.getPackageName()))) return;
            Parcelable p = e.getParcelableData();
            if (!(p instanceof Notification)) return;
            Notification n = (Notification) p;
            List<String> texts = new ArrayList<String>();
            for (CharSequence cs : e.getText()) if (cs != null && cs.length() > 0) texts.add(cs.toString());
            collect(n.contentView, texts);
            try { collect(n.bigContentView, texts); } catch (Throwable ignored) { }
            if (n.tickerText != null) texts.add(n.tickerText.toString());
            lastArt = n.largeIcon;
            lastTexts = texts;
            lastAt = android.os.SystemClock.elapsedRealtime();
            if (listener != null) listener.onAaNotification(lastArt, texts);
        } catch (Throwable ignored) { }
    }

    private void collect(RemoteViews rv, List<String> out) {
        if (rv == null) return;
        try {
            FrameLayout parent = new FrameLayout(this);
            View v = rv.apply(this, parent);
            walk(v, out);
        } catch (Throwable ignored) { }
    }

    private void walk(View v, List<String> out) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() > 0) out.add(t.toString());
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), out);
        }
    }

    @Override
    public void onInterrupt() { }
}
