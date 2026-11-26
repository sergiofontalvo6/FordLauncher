package com.fordlauncher.launcher.ai;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.provider.MediaStore;
import android.util.Log;

/**
 * Peticiones de reproducción por voz hacia SerMusic ({@code com.sermusic.app}).
 *
 * <p>La orden llega como JSON del asistente ({@code PLAY_MUSIC}) o como comando local,
 * y siempre se entrega mediante {@code MEDIA_PLAY_FROM_SEARCH} con el extra
 * {@link SearchManager#QUERY} y {@link Intent#FLAG_ACTIVITY_NEW_TASK}.
 */
public final class MusicVoiceController {

    private static final String TAG = "FordLauncher/Music";
    public static final String SERMUSIC_PACKAGE = "com.sermusic.app";

    private MusicVoiceController() {}

    /** Reproduce en SerMusic; mantiene el nombre histórico del método para los llamadores existentes. */
    public static boolean reproducirEnSegundoPlano(Context context, String query) {
        return reproducirEnSerMusic(context, query);
    }

    /** Abre SerMusic con la búsqueda indicada (camino explícito y fallback del asistente). */
    public static boolean reproducirEnSerMusic(Context context, String query) {
        return reproducirEnSerMusic(context, query, false);
    }

    /**
     * Igual que {@link #reproducirEnSerMusic(Context, String)} pero, sin internet,
     * marca la orden para que SerMusic solo busque en la música descargada.
     */
    public static boolean reproducirEnSerMusic(Context context, String query, boolean offlineOnly) {
        if (context == null) return false;
        String q = query == null ? "" : query.trim();
        if (q.length() == 0) return false;

        Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        intent.setPackage(SERMUSIC_PACKAGE);
        intent.putExtra(SearchManager.QUERY, q);
        if (offlineOnly) intent.putExtra("offline_only", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            Log.i(TAG, "PLAY_MUSIC -> " + SERMUSIC_PACKAGE + " query=\"" + q
                    + "\" offline_only=" + offlineOnly);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SerMusic no respondió a MEDIA_PLAY_FROM_SEARCH", e);
            return false;
        }
    }
}
