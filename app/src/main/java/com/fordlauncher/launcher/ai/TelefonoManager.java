package com.fordlauncher.launcher.ai;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Marcación por voz a través del teléfono conectado por Bluetooth (HFP).
 *
 * <p>La vía principal es {@code HfpClient} (BluetoothHeadsetClient), la misma que usa la
 * app de fábrica. Si HFP no está disponible se cae al intent de llamada del sistema para
 * no dejar la petición sin efecto.
 */
public final class TelefonoManager {

    private static final String TAG = "FordLauncher/Call";

    private TelefonoManager() {}

    public static void marcarNumero(Context context, String number) {
        if (context == null || number == null || number.trim().length() == 0) return;
        final String n = number.trim();

        // Si el contexto es el launcher, usar la vía HFP propia (overlay de llamada incluido).
        if (context instanceof com.fordlauncher.launcher.Main) {
            try {
                boolean ok = ((com.fordlauncher.launcher.Main) context).callNumber(n);
                if (ok) {
                    Log.i(TAG, "Marcando por HFP: " + n);
                    return;
                }
                Log.w(TAG, "HFP no disponible; usando intent de llamada del sistema");
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
            Log.i(TAG, "Marcando por intent: " + n);
            return;
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo marcar con ACTION_CALL: " + t);
        }

        // Último recurso: abrir el marcador con el número puesto.
        try {
            Intent i = new Intent(Intent.ACTION_DIAL);
            i.setData(Uri.parse("tel:" + n));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            Log.i(TAG, "Marcador abierto con: " + n);
        } catch (Throwable t2) {
            Log.w(TAG, "Tampoco se pudo abrir el marcador: " + t2);
        }
    }
}
