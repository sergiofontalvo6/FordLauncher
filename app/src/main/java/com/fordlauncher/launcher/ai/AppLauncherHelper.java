package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Busca e inicia cualquier aplicación instalada en el dispositivo según la frase del usuario.
 */
public final class AppLauncherHelper {

    private static final String TAG = "FordLauncher/AppLaunch";

    private AppLauncherHelper() {}

    /**
     * Busca la app por nombre aproximado y la abre.
     * @return true si se encontró y lanzó la app; false en caso contrario.
     */
    public static boolean abrirAppPorNombre(Context context, String queryApp) {
        if (context == null || queryApp == null || queryApp.trim().length() == 0) return false;
        String pkg = buscarPaquete(context, queryApp);
        if (pkg == null) {
            Log.w(TAG, "No se encontró app para: " + queryApp);
            return false;
        }
        PackageManager pm = context.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
        // En esta radio Maps no expone actividad LAUNCHER: se abre con un intent geo.
        if (launchIntent == null && "com.google.android.apps.maps".equals(pkg)) {
            launchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"));
            launchIntent.setPackage(pkg);
        }
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                context.startActivity(launchIntent);
                Log.i(TAG, "App abierta: " + pkg + " (query=\"" + queryApp + "\")");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Fallo al abrir: " + pkg, e);
            }
        }
        return false;
    }

    public static String buscarPaquete(Context context, String queryApp) {
        if (context == null || queryApp == null) return null;
        String q = normalizar(queryApp);
        if (q.length() == 0) return null;

        PackageManager pm = context.getPackageManager();

        // Alias directos de la radio: se resuelven antes que cualquier búsqueda difusa.
        String directo = paquetePorAlias(q);
        if (directo != null && (pm.getLaunchIntentForPackage(directo) != null || paqueteInstalado(pm, directo))) {
            return directo;
        }

        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            return null;
        }

        String mejorPkg = null;
        int mejorPuntaje = 0;

        String[] alias = aliasKeywords(q);

        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            // Solo considerar apps que tengan intent de lanzamiento
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;

            CharSequence labelCs = pm.getApplicationLabel(ai);
            String label = labelCs == null ? "" : normalizar(labelCs.toString());
            String pkg = normalizar(ai.packageName);

            int puntaje = 0;

            // Coincidencia exacta de etiqueta
            if (label.equals(q)) puntaje = 100;
            else if (label.startsWith(q)) puntaje = 85;
            else if (label.contains(q)) puntaje = 70;
            else if (pkg.contains(q)) puntaje = 50;

            // Alias conocidos (ej. "mapas" -> "maps", "ajustes" -> "settings")
            if (puntaje < 80 && alias != null) {
                for (String kw : alias) {
                    if (kw == null || kw.length() == 0) continue;
                    if (label.equals(kw)) puntaje = Math.max(puntaje, 95);
                    else if (label.startsWith(kw)) puntaje = Math.max(puntaje, 80);
                    else if (label.contains(kw)) puntaje = Math.max(puntaje, 65);
                    else if (pkg.contains(kw)) puntaje = Math.max(puntaje, 45);
                }
            }

            // Coincidencia parcial por palabras: "google maps" también casa con "maps".
            for (String token : q.split(" ")) {
                if (token.length() < 3) continue;
                if (label.contains(token)) puntaje = Math.max(puntaje, 60);
                if (pkg.contains(token)) puntaje = Math.max(puntaje, 40);
            }

            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejorPkg = ai.packageName;
            }
        }

        return mejorPuntaje >= 40 ? mejorPkg : null;
    }

    private static boolean paqueteInstalado(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; } catch (Throwable t) { return false; }
    }

    /** Alias fijos de las apps de la radio, sin tildes ni mayúsculas (ya normalizados). */
    private static String paquetePorAlias(String q) {
        if (q.equals("maps") || q.equals("google maps") || q.equals("mapa") || q.equals("mapas")
                || q.equals("google mapas") || q.equals("navegacion")) return "com.google.android.apps.maps";
        if (q.equals("waze") || q.equals("waze navigation") || q.equals("guaze")) return "com.waze";
        if (q.equals("sermusic") || q.equals("ser music") || q.equals("musica")
                || q.equals("reproductor de musica")) return "com.sermusic.app";
        if (q.equals("youtube") || q.equals("google youtube")) return "com.google.android.youtube";
        if (q.equals("radio") || q.equals("radio fm") || q.equals("la radio") || q.equals("fm")) {
            return "com.hcn.autoradio";
        }
        return null;
    }

    private static String[] aliasKeywords(String q) {
        if (q.contains("mapa") || q.contains("navega") || q.contains("gps") || q.contains("ruta")) {
            return new String[]{"maps", "mapa", "waze", "naveg", "gps", "navigation"};
        }
        if (q.contains("ajuste") || q.contains("configura")) {
            return new String[]{"settings", "ajustes", "configura"};
        }
        if (q.contains("telefono") || q.contains("llamar") || q.contains("llamada")) {
            return new String[]{"phone", "telefono", "dialer", "marcador"};
        }
        if (q.contains("camara") || q.contains("foto")) {
            return new String[]{"camera", "camara", "gallery", "galeria"};
        }
        if (q.contains("correo") || q.contains("email") || q.contains("mail")) {
            return new String[]{"gmail", "correo", "email", "mail"};
        }
        if (q.contains("navegador") || q.contains("internet") || q.contains("web")) {
            return new String[]{"chrome", "browser", "navegador"};
        }
        if (q.contains("youtube")) return new String[]{"youtube"};
        if (q.contains("spotify")) return new String[]{"spotify"};
        if (q.contains("radio")) return new String[]{"radio"};
        if (q.contains("musica") || q.contains("cancion") || q.contains("reproductor")) {
            return new String[]{"sermusic", "music", "musica"};
        }
        return new String[]{q};
    }

    private static String normalizar(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT).trim();
        try {
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        } catch (Throwable ignored) {}
        return s.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
