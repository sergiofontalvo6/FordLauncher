package com.fordlauncher.launcher.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Resuelve un contacto o número dictado por voz y ejecuta la llamada por Bluetooth.
 * Busca en ContactsContract y en los contactos importados (.vcf) de FiestaLauncher.
 */
public final class VoiceCallHelper {

    private static final String TAG = "FordLauncher/VoiceCall";

    private VoiceCallHelper() {}

    public static void procesarLlamadaPorVoz(Context context, String target, String speechConfirmation) {
        if (context == null || target == null || target.trim().length() == 0) return;
        final String t = target.trim();

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

    public static String resolverNumero(Context context, String query) {
        if (query == null) return null;
        String q = query.trim();
        if (q.length() == 0) return null;

        String soloDigitos = q.replaceAll("[^0-9+]", "");
        // Si parece un número de teléfono, usarlo directamente
        if (soloDigitos.length() >= 7 && soloDigitos.length() >= q.replaceAll("\\s","").length() - 1) {
            return soloDigitos;
        }

        // 1) Búsqueda por filtro en ContactsContract
        String num = buscarEnProvider(context, q);
        if (num != null) return num;

        // 2) Búsqueda flexible en ContactsContract por nombre
        num = buscarFlexibleProvider(context, q);
        if (num != null) return num;

        // 3) Búsqueda en contactos importados (.vcf) guardados por FiestaLauncher
        num = buscarEnVcf(context, q);
        if (num != null) return num;

        // Si era solo dígitos cortos o mezcla, intentar devolver dígitos si hay suficientes
        if (soloDigitos.length() >= 7) return soloDigitos;
        return null;
    }

    private static String buscarEnProvider(Context context, String query) {
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
            Log.w(TAG, "Contactos filter: " + e);
        }
        return null;
    }

    private static String buscarFlexibleProvider(Context context, String query) {
        String nq = norm(query);
        if (nq.length() == 0) return null;
        ContentResolver cr = context.getContentResolver();
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        try (Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)) {
            if (cursor != null) {
                int in = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int ip = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                String bestNum = null;
                int bestScore = 0;
                while (cursor.moveToNext()) {
                    String name = in >= 0 ? cursor.getString(in) : null;
                    String number = ip >= 0 ? cursor.getString(ip) : null;
                    if (name == null || number == null) continue;
                    int score = score(nq, norm(name));
                    if (score > bestScore) { bestScore = score; bestNum = number.trim(); }
                }
                if (bestScore >= 60) return bestNum;
            }
        } catch (Exception e) {
            Log.w(TAG, "Contactos flex: " + e);
        }
        return null;
    }

    private static String buscarEnVcf(Context context, String query) {
        String nq = norm(query);
        if (nq.length() == 0) return null;
        try {
            String json = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                    .getString("phone_vcf_contacts", null);
            if (json == null) return null;
            org.json.JSONArray arr = new org.json.JSONArray(json);
            String bestNum = null;
            int bestScore = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                String number = o.optString("number", "");
                if (name.length() == 0 || number.length() == 0) continue;
                int score = score(nq, norm(name));
                if (score > bestScore) { bestScore = score; bestNum = number.trim(); }
            }
            if (bestScore >= 60) return bestNum;
        } catch (Exception e) {
            Log.w(TAG, "VCF lookup: " + e);
        }
        return null;
    }

    private static int score(String queryNorm, String nameNorm) {
        if (nameNorm.length() == 0) return 0;
        if (nameNorm.equals(queryNorm)) return 100;
        if (nameNorm.startsWith(queryNorm)) return 90;
        if (nameNorm.contains(queryNorm)) return 75;
        // Coincidencia por tokens
        String[] qtok = queryNorm.split(" ");
        int hits = 0;
        for (String t : qtok) {
            if (t.length() < 2) continue;
            if (nameNorm.contains(t)) hits++;
        }
        if (hits > 0 && hits == qtok.length) return 70;
        if (hits > 0) return 50;
        return 0;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT).trim();
        try { x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", ""); } catch (Throwable ignored) {}
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
