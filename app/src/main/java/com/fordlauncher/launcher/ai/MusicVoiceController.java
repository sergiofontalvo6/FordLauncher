package com.fordlauncher.launcher.ai;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.MediaStore;
import android.util.Log;
import java.util.Locale;

/**
 * Envía una petición de reproducción a Seldia Music por voz.
 */
public final class MusicVoiceController {

    private static final String TAG = "FordLauncher/Music";
    private static final String SELDIA_FALLBACK = "com.seldia.music";

    private MusicVoiceController() {}

    public static boolean reproducirEnSeldaMusic(Context context, String query) {
        if (context == null) return false;
        if (query == null) query = "";
        query = query.trim();

        PackageManager pm = context.getPackageManager();
        String seldaPackage = detectarPaquete(pm);
        if (seldaPackage == null) seldaPackage = SELDIA_FALLBACK;

        String tema = temaActual(context);

        // Intent prioritario dirigido a Seldia Music
        Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
        intent.putExtra("com.fordlauncher.theme", tema);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setPackage(seldaPackage);

        try {
            context.startActivity(intent);
            Log.i(TAG, "PLAY_MUSIC enviado a " + seldaPackage + " query=\"" + query + "\"");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Seldia no respondió al action search, fallback a launch intent", e);
        }

        // Fallback: abrir Seldia Music y pasar la consulta por extras
        try {
            Intent launchIntent = pm.getLaunchIntentForPackage(seldaPackage);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                launchIntent.putExtra("com.fordlauncher.theme", tema);
                launchIntent.putExtra("com.seldia.music.extra.SEARCH_QUERY", query);
                launchIntent.putExtra(SearchManager.QUERY, query);
                context.startActivity(launchIntent);
                Log.i(TAG, "Seldia abierto con query fallback: " + query);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo abrir Seldia Music", e);
        }

        return false;
    }

    private static String detectarPaquete(PackageManager pm) {
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai == null || ai.packageName == null) continue;
                String pkg = ai.packageName.toLowerCase(Locale.ROOT);
                String label = "";
                try { label = pm.getApplicationLabel(ai).toString().toLowerCase(Locale.ROOT); } catch (Throwable ignored) {}
                if (pkg.contains("selda") || label.contains("selda")) {
                    return ai.packageName;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String temaActual(Context context) {
        try {
            android.content.SharedPreferences p = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
            String mode = p.getString("theme", "dark");
            if ("auto".equals(mode)) {
                int hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                return (hr >= 6 && hr < 18) ? "light" : "dark";
            }
            return "light".equals(mode) ? "light" : "dark";
        } catch (Throwable ignored) {
            return "dark";
        }
    }
}
