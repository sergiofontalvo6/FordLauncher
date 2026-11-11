package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Marcación por voz a través del teléfono conectado por Bluetooth (HFP).
 */
public final class TelefonoManager {

    private static final String TAG = "FordLauncher/Call";

    private TelefonoManager() {}

    public static void marcarNumero(Context context, String number) {
        if (context == null || number == null || number.trim().length() == 0) return;
        String n = number.trim();

        // Si el contexto es el launcher, usar la vía HFP propia (overlay de llamada incluido).
        if (context instanceof com.fordlauncher.launcher.Main) {
            try {
                ((com.fordlauncher.launcher.Main) context).callNumber(n);
                Log.i(TAG, "Marcando por HFP: " + n);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "HFP falló, fallback a intent: " + t);
            }
        }

        // Fallback: intent de llamada del sistema.
        try {
            Intent i = new Intent(Intent.ACTION_CALL);
            i.setData(Uri.parse("tel:" + n));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo marcar: " + t);
        }
    }
}
