package com.fordlauncher.launcher.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import java.util.concurrent.Executors;

/**
 * Resuelve un contacto o número dictado por voz y ejecuta la llamada por Bluetooth.
 */
public final class VoiceCallHelper {

    private static final String TAG = "FordLauncher/VoiceCall";

    private VoiceCallHelper() {}

    public static void procesarLlamadaPorVoz(Context context, String target, String speechConfirmation) {
        if (context == null || target == null || target.trim().length() == 0) return;
        String t = target.trim();

        // Confirmar por TTS brevemente.
        try {
            TextToSpeechManager.getInstance(context).hablarTextoLargo(
                    speechConfirmation != null && speechConfirmation.trim().length() > 0
                            ? speechConfirmation : ("Llamando a " + t));
        } catch (Throwable ignored) {}

        // Resolver número en hilo de fondo para no trabar la UI.
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            public void run() {
                final String numeroFinal = resolverNumero(context, t);

                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(new Runnable() {
                    public void run() {
                        if (numeroFinal != null && numeroFinal.trim().length() > 0) {
                            TelefonoManager.marcarNumero(context, numeroFinal);
                        } else {
                            try {
                                TextToSpeechManager.getInstance(context).hablarTextoLargo("No encontré a " + t + " en tus contactos");
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            }
        });
    }

    private static String resolverNumero(Context context, String query) {
        if (query == null) return null;
        String soloDigitos = query.replaceAll("[^0-9+]", "");
        if (soloDigitos.length() >= 7) {
            return soloDigitos;
        }

        ContentResolver cr = context.getContentResolver();
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query));
        String[] projection = new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER};

        try (Cursor cursor = cr.query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER);
                String num = cursor.getString(idx);
                if (num != null && num.trim().length() > 0) return num.trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "Contactos: " + e);
        }

        return null;
    }
}
