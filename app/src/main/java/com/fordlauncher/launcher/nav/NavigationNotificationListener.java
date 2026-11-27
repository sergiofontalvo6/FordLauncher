package com.fordlauncher.launcher.nav;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Lee la notificación de navegación de Waze / Google Maps y publica maniobra,
 * distancia, calle y ETA en {@link NavState}. No abre ventanas ni mantiene
 * procesos extra: solo reacciona a las notificaciones que el sistema ya envía.
 */
public final class NavigationNotificationListener extends NotificationListenerService {

    static final String WAZE = "com.waze";
    static final String MAPS = "com.google.android.apps.maps";
    private static final String TAG = "FordLauncher/NavNotif";
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    @Override
    public void onListenerConnected() {
        NavState.clear(null);
        Log.i(TAG, "listener conectado");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!WAZE.equals(pkg) && !MAPS.equals(pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        Bundle ex = bundleOf(n);
        if (ex == null) return;
        String title = textOf(ex, Notification.EXTRA_TITLE);
        String text = first(textOf(ex, Notification.EXTRA_TEXT), textOf(ex, Notification.EXTRA_BIG_TEXT));
        String sub = textOf(ex, Notification.EXTRA_SUB_TEXT);
        String channel = channelOf(n);

        // En Android 8.1 Waze y Maps suelen poner la maniobra dentro de RemoteViews.
        String[] rv = remoteTexts(n);
        if (title.length() == 0 && rv.length > 0) title = rv[0];
        if (text.length() == 0 && rv.length > 1) text = rv[1];
        if (sub.length() == 0 && rv.length > 2) sub = rv[2];
        if (isServiceNotice(title, text, sub, channel)) {
            // «Waze is running» no es una maniobra: no debe borrar ni reemplazar la ruta.
            return;
        }
        if (title.length() == 0 && text.length() == 0 && sub.length() == 0) {
            NavState.clear(pkg);
            return;
        }

        Bitmap icon = largeIcon(ex);
        NavState.publish(new NavState.Data(pkg, title, text, sub, icon));
        Log.i(TAG, "ruta " + pkg + " · " + title + " · " + text + " · " + sub);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();
        if (n != null) {
            Bundle ex = bundleOf(n);
            String title = ex == null ? "" : textOf(ex, Notification.EXTRA_TITLE);
            String text = ex == null ? "" : textOf(ex, Notification.EXTRA_TEXT);
            String sub = ex == null ? "" : textOf(ex, Notification.EXTRA_SUB_TEXT);
            if (isServiceNotice(title, text, sub, channelOf(n))) return;
        }
        NavState.clear(pkg);
        Log.i(TAG, "notificación cerrada: " + pkg);
    }

    private static String channelOf(Notification n) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                String id = n.getChannelId();
                return id == null ? "" : id;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** Waze publica un aviso de servicio que no debe confundirse con una ruta. */
    private static boolean isServiceNotice(String title, String text, String sub, String channel) {
        String all = (title + " " + text + " " + sub + " " + channel).toLowerCase(Locale.ROOT);
        return all.contains("waze is running") || all.contains("waze esta en")
                || all.contains("cerrar waze") || channel.toUpperCase(Locale.ROOT).contains("CLOSE_WAZE");
    }

    /** True cuando el usuario ya concedió acceso a notificaciones a Ford Launcher. */
    public static boolean isEnabled(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, NavigationNotificationListener.class);
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            return flat.contains(cn.flattenToString())
                    || flat.contains(cn.getPackageName() + "/" + cn.getClassName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Notification.extras solo existe desde API 19; en API 18 se lee por reflexión. */
    private static Bundle bundleOf(Notification n) {
        try {
            if (n.extras != null) return n.extras;
        } catch (Throwable ignored) { }
        Object o = field(n, "extras");
        if (o == null) o = field(n, "mExtras");
        return o instanceof Bundle ? (Bundle) o : null;
    }

    private static String textOf(Bundle ex, String key) {
        try {
            Object o = ex.get(key);
            return o == null ? "" : o.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String first(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }

    /**
     * Textos de RemoteViews. API 18 usa {@code mViews}; API 24+ usa {@code mActions}
     * con objetos {@code TextViewAction} cuyo texto está en el campo {@code mText}.
     * Se recorren los campos CharSequence de cada acción para cubrir ambas formas.
     */
    private static String[] remoteTexts(Notification n) {
        Object[] holders = {
                field(n, "contentView"), field(n, "mContentView"),
                field(n, "bigContentView"), field(n, "mBigContentView"),
                field(n, "headsUpContentView"), field(n, "mHeadsUpContentView")
        };
        ArrayList<String> out = new ArrayList<String>();
        for (Object h : holders) {
            if (h == null) continue;
            Class<?> c = h.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                    Object list = read(f, h);
                    if (!(list instanceof java.util.List)) continue;
                    for (Object action : (java.util.List<?>) list) {
                        collectText(action, out, 0);
                    }
                }
                c = c.getSuperclass();
            }
        }
        return out.toArray(new String[0]);
    }

    private static void collectText(Object o, ArrayList<String> out, int depth) {
        if (o == null || depth > 2) return;
        Class<?> c = o.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t != String.class && !CharSequence.class.isAssignableFrom(t)) continue;
                Object v = read(f, o);
                if (v == null) continue;
                String s = v.toString().trim();
                if (s.length() > 0 && s.length() < 160 && !out.contains(s)) out.add(s);
            }
            c = c.getSuperclass();
        }
    }

    private static Object read(Field f, Object o) {
        try {
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap largeIcon(Bundle ex) {
        Object o = null;
        try { o = ex.get(Notification.EXTRA_LARGE_ICON); } catch (Throwable ignored) { }
        if (o instanceof Bitmap) return scale((Bitmap) o);
        if (o != null && "android.graphics.drawable.Icon".equals(o.getClass().getName())) {
            try {
                Object b = o.getClass().getMethod("getBitmap").invoke(o);
                if (b instanceof Bitmap) return scale((Bitmap) b);
            } catch (Throwable ignored) { }
            try {
                Object d = o.getClass().getMethod("loadDrawable", Context.class).invoke(o, appContext);
                if (d instanceof android.graphics.drawable.BitmapDrawable)
                    return scale(((android.graphics.drawable.BitmapDrawable) d).getBitmap());
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Un único bitmap RGB_565 de 64×64: el icono más barato que cabe en el card. */
    private static Bitmap scale(Bitmap src) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        Bitmap out = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565);
        try {
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float f = Math.min(64f / src.getWidth(), 64f / src.getHeight());
            int w = Math.max(1, Math.round(src.getWidth() * f));
            int h = Math.max(1, Math.round(src.getHeight() * f));
            c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                    new Rect((64 - w) / 2, (64 - h) / 2, (64 + w) / 2, (64 + h) / 2), p);
            return out;
        } catch (Throwable t) {
            out.recycle();
            return null;
        }
    }

    private static Object field(Object o, String name) {
        if (o == null) return null;
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) { }
            c = c.getSuperclass();
        }
        return null;
    }
}
