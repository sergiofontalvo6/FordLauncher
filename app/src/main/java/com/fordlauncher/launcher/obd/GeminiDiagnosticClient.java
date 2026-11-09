package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Cliente del diagnostico por IA (Google Gemini 1.5 Flash gratuito). */
public class GeminiDiagnosticClient {
    public interface Callback { void onDone(String text, String error); }

    // Cached (no single): un DNS/TCP colgado en API 27 ignora el timeout de
    // HttpURLConnection; cada analyze debe poder terminar en su propio plazo.
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final ExecutorService NET = Executors.newCachedThreadPool();
    public static final String ERR_CONN = "Sin conexión a internet o clave API no válida.";

    static String apiKey(Context c) {
        try {
            String k = c.getSharedPreferences("launcher", 0).getString("gemini_api_key", "");
            return k == null ? "" : k.trim();
        } catch (Throwable t) { return ""; }
    }

    public static void analyze(final Context ctx, final String jsonSnapshot, final Callback callback) {
        analyze(ctx, jsonSnapshot, null, callback);
    }

    public static void analyze(final Context ctx, final String jsonSnapshot, final String task, final Callback callback) {
        final String key = apiKey(ctx);
        final String snap = jsonSnapshot == null ? "{}" : jsonSnapshot;
        final String taskText = task != null && task.length() > 0
                ? task
                : "Analiza estos datos OBD2 y códigos de falla";
        POOL.execute(new Runnable() {
            public void run() {
                if (key.length() == 0) {
                    if (callback != null) callback.onDone(null, "Falta la clave API de Gemini. Guardala en Ajustes > Apariencia > Diagnostico Inteligente.");
                    return;
                }
                try {
                    // Streaming (SSE): el primer token llega en segundos aunque la
                    // generación total tarde más; se acumula por fragmentos.
                    // Alias "latest" lite: responde en ~1 s; los modelos 3.8+ tardan
                    // >50 s con esta clave y los 2.5 están vetados para usuarios nuevos.
                    String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:streamGenerateContent?alt=sse&key=" + key;
                    String prompt = "Eres el copiloto y mecanico automotriz especialista de un Ford Fiesta. " + taskText + ": " + snap
                            + ". Explica en espanol en exactamente 3 puntos concretos, claros y breves (maximo 65 palabras en total): 1. Diagnostico exacto del problema. 2. Nivel de gravedad (Bajo/Medio/Alto). 3. Que revisar o reparar en el taller. No uses asteriscos, vinetas complejas ni tecnicismos confusos.";
                    StringBuilder b = new StringBuilder();
                    b.append("{\"contents\":[{\"parts\":[{\"text\":\"");
                    b.append(escape(prompt));
                    b.append("\"}]}]"); // cierra text, parte, parts y contenido
                    b.append(",\"generationConfig\":{\"maxOutputTokens\":250}}");
                    final byte[] body = b.toString().getBytes("UTF-8");
                    final String reqUrl = url;
                    // Plazo total: 2 intentos de 20 s (fábrica propia + sistema) en
                    // Net.postJson. HttpURLConnection en API 27 puede ignorar el
                    // timeout si DNS/TCP/TLS se cuelga; el Future lo corta igual.
                    Future<String> fut = NET.submit(new Callable<String>() {
                        public String call() throws Exception {
                            byte[] out = com.fordlauncher.launcher.Net.postJson(reqUrl, body, 20000);
                            return extractSse(new String(out, "UTF-8"));
                        }
                    });
                    String text;
                    try {
                        text = fut.get(45000, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        try { fut.cancel(true); } catch (Throwable ignored) {}
                        Log.w("FordLauncher/AI", "Gemini timeout 45s: " + e);
                        if (callback != null) callback.onDone(null, ERR_CONN);
                        return;
                    } catch (CancellationException e) {
                        return;
                    } catch (ExecutionException e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        Log.w("FordLauncher/AI", "Gemini error: " + c);
                        if (callback != null) callback.onDone(null, ERR_CONN);
                        return;
                    }
                    if (text == null || text.length() == 0) {
                        Log.w("FordLauncher/AI", "Gemini empty response");
                        if (callback != null) callback.onDone(null, "Gemini no devolvio texto. Revisa la clave API o tu conexion.");
                        return;
                    }
                    Log.i("FordLauncher/AI", "Gemini OK (" + text.length() + " chars)");
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

    /** Acumula el texto de una respuesta SSE (data: {...}) o JSON simple. */
    static String extractSse(String full) {
        if (full == null) return null;
        String t = full.trim();
        if (!t.startsWith("data:")) return extractText(full);
        StringBuilder o = new StringBuilder();
        int i = 0;
        while (i < full.length()) {
            int nl = full.indexOf('\n', i);
            String line = nl < 0 ? full.substring(i) : full.substring(i, nl);
            i = nl < 0 ? full.length() : nl + 1;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.length() == 0 || "[DONE]".equals(data)) continue;
            String piece = extractText(data);
            if (piece != null) o.append(piece);
        }
        return o.length() > 0 ? o.toString() : null;
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
