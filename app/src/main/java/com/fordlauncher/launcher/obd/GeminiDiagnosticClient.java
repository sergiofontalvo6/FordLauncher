package com.fordlauncher.launcher.obd;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cliente del diagnostico por IA (Google Gemini 1.5 Flash gratuito). */
public class GeminiDiagnosticClient {
    public interface Callback { void onDone(String text, String error); }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    public static final String ERR_CONN = "Sin conexión a internet o clave API no válida.";

    static String apiKey(Context c) {
        try {
            String k = c.getSharedPreferences("launcher", 0).getString("gemini_api_key", "");
            return k == null ? "" : k.trim();
        } catch (Throwable t) { return ""; }
    }

    public static void analyze(final Context ctx, final String jsonSnapshot, final Callback callback) {
        final String key = apiKey(ctx);
        final String snap = jsonSnapshot == null ? "{}" : jsonSnapshot;
        POOL.execute(new Runnable() {
            public void run() {
                if (key.length() == 0) {
                    if (callback != null) callback.onDone(null, "Falta la clave API de Gemini. Guardala en Ajustes > Apariencia > Diagnostico Inteligente.");
                    return;
                }
                try {
                    String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + key;
                    String prompt = "Eres el copiloto y mecanico automotriz especialista de un Ford Fiesta. Analiza estos datos OBD2 y codigos de falla: " + snap
                            + ". Explica en espanol en exactamente 3 puntos concretos, claros y breves (maximo 65 palabras en total): 1. Diagnostico exacto del problema. 2. Nivel de gravedad (Bajo/Medio/Alto). 3. Que revisar o reparar en el taller. No uses asteriscos, vinetas complejas ni tecnicismos confusos.";
                    StringBuilder b = new StringBuilder();
                    b.append("{\"contents\":[{\"parts\":[{\"text\":\"");
                    b.append(escape(prompt));
                    b.append("\"}]}]}");
                    byte[] out = com.fordlauncher.launcher.Net.postJson(url, b.toString().getBytes("UTF-8"), 8000);
                    String resp = new String(out, "UTF-8");
                    String text = extractText(resp);
                    if (text == null || text.length() == 0) {
                        if (callback != null) callback.onDone(null, "Gemini no devolvio texto. Revisa la clave API o tu conexion.");
                        return;
                    }
                    if (callback != null) callback.onDone(text, null);
                } catch (Throwable e) {
                    if (callback != null) callback.onDone(null, ERR_CONN);
                }
            }
        });
    }

    private static String escape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') o.append("\\\"");
            else if (c == '\\') o.append("\\\\");
            else if (c < 0x20) o.append(String.format("\\u%04x", (int) c));
            else o.append(c);
        }
        return o.toString();
    }

    static String extractText(String resp) {
        String k = "\"text\"";
        int i = resp.indexOf(k);
        if (i < 0) return null;
        int colon = resp.indexOf(':', i + k.length());
        if (colon < 0) return null;
        int q = resp.indexOf('"', colon);
        if (q < 0) return null;
        StringBuilder o = new StringBuilder();
        for (int j = q + 1; j < resp.length(); j++) {
            char c = resp.charAt(j);
            if (c == '"') return o.toString();
            if (c == '\\' && j + 1 < resp.length()) {
                char n = resp.charAt(++j);
                if (n == 'n') o.append('\n');
                else if (n == 't') o.append('\t');
                else if (n == 'u' && j + 4 < resp.length()) {
                    try { o.append((char) Integer.parseInt(resp.substring(j + 1, j + 5), 16)); j += 4; } catch (Throwable ignored) {}
                } else o.append(n);
            } else o.append(c);
        }
        return o.toString().length() > 0 ? o.toString() : null;
    }
}
