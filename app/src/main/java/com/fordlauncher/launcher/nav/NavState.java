package com.fordlauncher.launcher.nav;

import android.graphics.Bitmap;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Estado de la navegación activa. Lo publica el lector de notificaciones y lo
 * consume el card del HOME. Es un singleton pequeño: una sola imagen de 64×64
 * y tres cadenas, sin cachés ni hilos propios.
 */
public final class NavState {

    public static final class Data {
        public final String pkg;
        public final String title;
        public final String text;
        public final String subText;
        public final Bitmap icon;
        public final long at;

        public Data(String pkg, String title, String text, String subText, Bitmap icon) {
            this.pkg = pkg;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.subText = subText == null ? "" : subText;
            this.icon = icon;
            this.at = System.currentTimeMillis();
        }
    }

    public interface Listener {
        void onNavState(Data data);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile Data current;

    private NavState() { }

    public static Data get() { return current; }

    public static void publish(Data data) {
        current = data;
        for (Listener l : LISTENERS) {
            try { l.onNavState(data); } catch (Throwable ignored) { }
        }
    }

    /** Quita el estado solo si la notificación cerrada pertenece a la ruta actual. */
    public static void clear(String pkg) {
        Data c = current;
        if (c == null) return;
        if (pkg != null && !pkg.equals(c.pkg)) return;
        publish(null);
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }
}
