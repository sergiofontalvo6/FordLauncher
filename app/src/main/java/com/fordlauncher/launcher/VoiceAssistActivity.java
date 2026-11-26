package com.fordlauncher.launcher;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Actividad transparente que recibe las invocaciones de voz del sistema
 * ({@code ACTION_ASSIST}, {@code ACTION_VOICE_ASSIST}, {@code ACTION_VOICE_COMMAND},
 * {@code WEB_SEARCH}, {@code VOICE_SEARCH_HANDS_FREE}) y las búsquedas de música.
 *
 * <p>Antes esos intent-filters vivían en {@link Main}, así que pulsar el botón
 * físico de voz del carro abría el Home del launcher. Ahora esta actividad no
 * dibuja nada: reenvía la orden al {@link VoiceOverlayService} (que escucha y
 * responde sobre la app que el conductor esté viendo) y se cierra de inmediato.
 */
public class VoiceAssistActivity extends Activity {

    private static final String TAG = "FordLauncher/VoiceAssist";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String action = in != null ? in.getAction() : null;
        try {
            if (esBusquedaDeMusica(action)) {
                String q = in != null ? in.getStringExtra(SearchManager.QUERY) : null;
                if (q == null && in != null) q = in.getStringExtra(Intent.EXTRA_TEXT);
                if (q != null && q.trim().length() > 0) {
                    Log.i(TAG, "Búsqueda de música -> SerMusic: " + q);
                    com.fordlauncher.launcher.ai.MusicVoiceController
                            .reproducirEnSerMusic(this, q.trim());
                }
            } else {
                // Voz del volante: exactamente el mismo flujo que el FAB flotante.
                Log.i(TAG, "Asistente de voz (" + action + ") -> VoiceOverlayService");
                VoiceOverlayService.toggleListen(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo atender la voz del sistema", t);
        }
        finish();
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }

    private static boolean esBusquedaDeMusica(String action) {
        return "android.media.action.MEDIA_PLAY_FROM_SEARCH".equals(action)
                || "com.google.android.googlequicksearchbox.MUSIC_SEARCH".equals(action);
    }
}
