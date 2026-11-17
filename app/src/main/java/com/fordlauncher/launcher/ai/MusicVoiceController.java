package com.fordlauncher.launcher.ai;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Peticiones de reproducción por voz hacia Seldia Music.
 *
 * <p>Dos caminos:
 * <ul>
 *   <li>{@link #reproducirEnSegundoPlano}: broadcast al receptor de Seldia, que busca y
 *       reproduce sin abrir ninguna Activity (FiestaLauncher sigue visible).</li>
 *   <li>{@link #reproducirEnSeldaMusic}: abre la UI de Seldia con la búsqueda (solo cuando
 *       el usuario pide explícitamente «abre Seldia» o si el receptor no existe).</li>
 * </ul>
 */
public final class MusicVoiceController {

    private static final String TAG = "FordLauncher/Music";
    private static final String SELDIA_FALLBACK = "com.seldia.music";

    /** Acción que atiende Seldia en segundo plano (VoicePlaybackReceiver). */
    public static final String ACTION_VOICE_PLAY = "com.seldia.music.action.VOICE_PLAY";
    public static final String RECEIVER_CLASS = "com.seldia.music.playback.VoicePlaybackReceiver";
    public static final String SERVICE_CLASS = "com.seldia.music.playback.PlaybackService";
    public static final String EXTRA_QUERY = "query";

    private MusicVoiceController() {}

    /**
     * Reproduce en Seldia Music <b>sin traerla al primer plano</b>.
     * Devuelve true si la petición se entregó (o se abrió la UI como último recurso).
     */
    public static boolean reproducirEnSegundoPlano(Context context, String query) {
        if (context == null) return false;
        String q = query == null ? "" : query.trim();
        if (q.length() == 0) return false;

        PackageManager pm = context.getPackageManager();
        String seldaPackage = detectarPaquete(pm);
        if (seldaPackage == null) seldaPackage = SELDIA_FALLBACK;

        // Arranca el servicio de reproducción desde el launcher (que sí está en primer
        // plano), para que Seldia pueda sonar en segundo plano con su notificación y
        // no se la lleve por delante el sistema. Android 8 prohíbe que un receptor en
        // segundo plano inicie el servicio por su cuenta.
        try {
            Intent svc = new Intent();
            svc.setClassName(seldaPackage, SERVICE_CLASS);
            svc.putExtra("com.fordlauncher.bg_play", true);
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
            Log.i(TAG, "PlaybackService en primer plano -> " + seldaPackage);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar PlaybackService", t);
        }

        Intent i = new Intent(ACTION_VOICE_PLAY);
        i.setPackage(seldaPackage);
        // Componente explícito: Android 8 no entrega broadcasts implícitos a receptores
        // de manifiesto de apps en segundo plano, y Seldia casi siempre está en segundo plano.
        i.setClassName(seldaPackage, RECEIVER_CLASS);
        // Tras "Forzar detención" (o un reinicio) la app queda en estado detenido y Android
        // descarta los broadcasts dirigidos a ella salvo que se incluya este flag.
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        i.putExtra(EXTRA_QUERY, q);
        i.putExtra("com.fordlauncher.theme", temaActual(context));

        boolean hayReceptor = false;
        try {
            List<ResolveInfo> rr = pm.queryBroadcastReceivers(i, 0);
            hayReceptor = rr != null && !rr.isEmpty();
        } catch (Throwable ignored) {}

        if (hayReceptor) {
            try {
                context.sendBroadcast(i);
                Log.i(TAG, "VOICE_PLAY (segundo plano) -> " + seldaPackage + " query=\"" + q + "\"");
                return true;
            } catch (Throwable e) {
                Log.w(TAG, "No se pudo enviar VOICE_PLAY", e);
            }
        }

        // Seldia sin receptor (versión vieja): se usa la UI como antes.
        Log.i(TAG, "Seldia sin receptor de voz; abriendo UI");
        return reproducirEnSeldaMusic(context, q);
    }

    /** Abre la UI de Seldia Music con la búsqueda (camino explícito «abre Seldia»). */
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
