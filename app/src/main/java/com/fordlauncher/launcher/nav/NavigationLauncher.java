package com.fordlauncher.launcher.nav;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Lanzador de rutas por voz hacia la app predeterminada en Ajustes → Mapa y clima.
 * Siempre abre la navegación a pantalla completa: FordLauncher no crea ni
 * solicita ventanas flotantes (PiP) sobre el tablero.
 */
public final class NavigationLauncher {

    public static final String MAPS = "com.google.android.apps.maps";
    public static final String WAZE = "com.waze";

    private NavigationLauncher() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** App elegida en Ajustes → Mapa y clima. */
    public static boolean isMaps(Context c) {
        try { return "maps".equals(prefs(c).getString("nav_app", "waze")); }
        catch (Throwable t) { return false; }
    }

    public static String appLabel(Context c) { return isMaps(c) ? "Google Maps" : "Waze"; }

    /** Abre la app preferida; con destino arranca la ruta directamente. */
    public static boolean open(Context c, String destino) {
        boolean maps = isMaps(c);
        String pkg = maps ? MAPS : WAZE;
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
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            try {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    c.startActivity(launch);
                    return true;
                }
            } catch (Throwable ignored) { }
            return false;
        }
    }
}
