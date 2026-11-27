package com.fordlauncher.launcher.nav;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Lanzador único de rutas del HOME y de los comandos de voz.
 *
 * Google Maps es la única app que deja una miniatura flotante (PiP) sobre el
 * lado derecho del card mientras se conduce; Waze publica el turn-by-turn en
 * segundo plano. {@link #open(Context, String, boolean)} permite forzar Maps
 * cuando el conductor pide explícitamente el mini mapa.
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

    public static String appLabelFor(String pkg) {
        if (MAPS.equals(pkg)) return "Google Maps";
        if (WAZE.equals(pkg)) return "Waze";
        return null;
    }

    /** Abre la app preferida; con destino arranca la ruta. */
    public static boolean open(Context c, String destino) {
        return open(c, destino, isMaps(c));
    }

    /**
     * @param forceMaps true cuando se pide el mini mapa PiP: Google Maps es la
     *                  única navegador que puede dejar la ventana flotante.
     */
    public static boolean open(Context c, String destino, boolean forceMaps) {
        boolean maps = forceMaps;
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
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(launch);
                    return true;
                }
            } catch (Throwable ignored) { }
            return false;
        }
    }

    /** Trae al frente la app que está publicando la ruta (o la preferida). */
    public static boolean bringToFront(Context c, String pkg) {
        String target = pkg != null ? pkg : (isMaps(c) ? MAPS : WAZE);
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(target);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(i);
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Sirve para la nota de Ajustes y para el texto guía del hueco derecho. */
    public static boolean wantsPip(String pkg) { return MAPS.equals(pkg); }
}
