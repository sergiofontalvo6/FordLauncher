package com.fordlauncher.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/** Funciones de Android 6+ (solo se llaman cuando el equipo las tiene). */
public class Modern {

    /** Pide los permisos que el launcher necesita (Android 6+). Devuelve true si ya estaban todos. */
    public static boolean ensurePermissions(Activity a, int req) {
        java.util.ArrayList<String> need = new java.util.ArrayList<String>();
        String[] all = android.os.Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, "android.permission.READ_MEDIA_IMAGES"}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE};
        for (String p : all) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) return true;
        a.requestPermissions(need.toArray(new String[0]), req);
        return false;
    }

    /** Android 8+: permiso por app para instalar APKs. */
    public static boolean canInstall(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        if (a.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) { }
        return false;
    }

    public static boolean notificationAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }
}
