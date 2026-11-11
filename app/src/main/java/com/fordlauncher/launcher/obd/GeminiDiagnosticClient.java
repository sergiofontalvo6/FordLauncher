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
import java.util.regex.Pattern;

/** Cliente del diagnostico por IA (Google Gemini 1.5 Flash gratuito). */
public class GeminiDiagnosticClient {
    public interface Callback { void onDone(String text, String error); }

    // Cached (no single): un DNS/TCP colgado en API 27 ignora el timeout de
    // HttpURLConnection; cada analyze debe poder terminar en su propio plazo.
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final ExecutorService NET = Executors.newCachedThreadPool();
    public static final String ERR_CONN = "Sin conexión a internet o clave API no válida.";

    // Memoria conversacional corta: solo turnos user/model (las instrucciones van en systemInstruction).
    private static final java.util.List<String> HISTORY = new java.util.ArrayList<String>();
    private static final int MAX_TURNS = 8;
    private static final long SESSION_IDLE_MS = 15 * 60 * 1000L;
    private static final String PREFIX_VOZ = "El conductor pregunta por voz";
    private static volatile boolean saludadoEnSesion;
    private static volatile long ultimaInteraccionMs;

    public static void clearHistory() {
        synchronized (HISTORY) { HISTORY.clear(); }
    }

    /** Reinicia la sesión (memoria y saludo). Llamar al apagar el contacto del auto. */
    public static void resetSession() {
        synchronized (HISTORY) {
            HISTORY.clear();
            saludadoEnSesion = false;
            ultimaInteraccionMs = 0L;
        }
    }

    private static String turn(String role, String text) {
        return "{\"role\":\"" + role + "\",\"parts\":[{\"text\":\"" + escape(text) + "\"}]}";
    }

    /** Cuerpo de la petición: systemInstruction + historial + el turno actual del usuario. */
    private static String bodyJson(String systemText, String userText, boolean conversacion) {
        synchronized (HISTORY) {
            long ahora = System.currentTimeMillis();
            if (ultimaInteraccionMs > 0 && ahora - ultimaInteraccionMs > SESSION_IDLE_MS) {
                HISTORY.clear();
                saludadoEnSesion = false;
            }
            StringBuilder b = new StringBuilder();
            b.append("{\"systemInstruction\":{\"parts\":[{\"text\":\"").append(escape(systemText)).append("\"}]},");
            b.append("\"contents\":[");
            for (String t : HISTORY) b.append(t).append(",");
            b.append(turn("user", userText));
            b.append("]");
            b.append(",\"generationConfig\":{\"maxOutputTokens\":").append(conversacion ? 180 : 140)
             .append(",\"temperature\":0.4}}");
            return b.toString();
        }
    }

    /** Guarda el par usuario/modelo solo si hubo respuesta; a partir de aquí no se vuelve a saludar. */
    private static void registrarRespuesta(String userText, String text) {
        synchronized (HISTORY) {
            HISTORY.add(turn("user", userText));
            HISTORY.add(turn("model", text));
            while (HISTORY.size() > MAX_TURNS) HISTORY.remove(0);
            saludadoEnSesion = true;
            ultimaInteraccionMs = System.currentTimeMillis();
        }
    }

    private static String systemPrompt(String userName, String carName, boolean primera, boolean obdConectado) {
        String nombre = userName.length() > 0 ? userName : "amigo";
        String vehiculo = carName.length() > 0 ? carName : "Ford Fiesta";
        return "Eres el copiloto inteligente de " + nombre + " a bordo de su " + vehiculo + ". "
                + "PERSONALIDAD Y ALCANCE: eres un asistente conversacional completo, natural, cercano e inteligente. "
                + "Puedes hablar de cualquier tema (música, cultura, ciencia, viajes, rutas, tecnología, charla informal). "
                + "Conoces a fondo la mecánica y la telemetría de este vehículo. Cada mensaje trae datos actuales del auto en JSON "
                + "(rpm, coolant en grados, speed en km/h, voltage, etc.). Si te preguntan por el auto, sus sensores, fallas o estado, "
                + "analiza esos datos con precisión técnica; si un dato no viene, dilo sin inventarlo. "
                + "Si te preguntan de cualquier otra cosa, responde con soltura. "
                + "ESTADO DEL ESCÁNER OBD2: " + (obdConectado ? "CONECTADO" : "DESCONECTADO") + ". "
                + "REGLA CRÍTICA DE VEHÍCULO: si te preguntan por el estado mecánico, fallas o sensores del vehículo y el escáner está "
                + "DESCONECTADO, NO inventes datos: dile directamente que el escáner OBD2 está desconectado y pídele que lo conecte "
                + "para poder diagnosticarlo. Si la conversación es de cultura, música, dudas generales o charla informal, responde "
                + "normalmente sin importar el estado del escáner. "
                + "SALUDO: " + (primera
                    ? "esta es la primera interacción de la sesión; puedes saludar brevemente por su nombre al inicio."
                    : "ya lo saludaste en esta sesión. Está PROHIBIDO volver a decir 'Hola', 'Buenos días' o saludar por su nombre al inicio. Entra directo a responder.")
                + " FORMATO DE VOZ: sin asteriscos, viñetas ni markdown; frases cortas para leer en voz alta mientras conduce.";
    }

    /** Quita un saludo inicial repetido (p. ej. "Hola Sergio, ..."). Si no queda nada, devuelve el original. */
    static String quitarSaludo(String texto, String userName) {
        if (texto == null) return null;
        String t = texto.trim();
        String nombre = (userName != null && userName.length() > 0)
                ? "(?:" + Pattern.quote(userName) + ")?" : "";
        String re = "^(?i)(hola|saludos|buenos días|buenas tardes|buenas noches|buenas)[,!]?\\s*" + nombre + "[.,!]?\\s*";
        String limpio = t.replaceFirst(re, "");
        return limpio.trim().length() > 0 ? limpio : t;
    }

    static String apiKey(Context c) {
        try {
            String k = c.getSharedPreferences("launcher", 0).getString("gemini_api_key", "");
            return k == null ? "" : k.trim();
        } catch (Throwable t) { return ""; }
    }

    static String userName(Context c) {
        try {
            String n = c.getSharedPreferences("launcher", 0).getString("user_name", "");
            return n == null ? "" : n.trim();
        } catch (Throwable t) { return ""; }
    }

    static String carName(Context c) {
        try {
            String n = c.getSharedPreferences("launcher", 0).getString("car_name", "");
            return n == null ? "" : n.trim();
        } catch (Throwable t) { return ""; }
    }

    public static void analyze(final Context ctx, final String jsonSnapshot, final Callback callback) {
        analyze(ctx, jsonSnapshot, null, callback);
    }

    public static void analyze(final Context ctx, final String jsonSnapshot, final String task, final Callback callback) {
        analyze(ctx, jsonSnapshot, task, false, callback);
    }

    /** @param detailed true: respuesta "uno por uno" (un sensor por línea) con más tokens. */
    public static void analyze(final Context ctx, final String jsonSnapshot, final String task, final boolean detailed, final Callback callback) {
        final String key = apiKey(ctx);
        final String snap = jsonSnapshot == null ? "{}" : jsonSnapshot;
        final String userName = userName(ctx);
        final String carName = carName(ctx);
        final boolean conversacion = task != null && task.startsWith(PREFIX_VOZ);
        final boolean obdConectado = snap.contains("\"obd_conectado\":true");
        final String taskText = task != null && task.length() > 0
                ? task
                : "Analiza estos datos OBD2 y códigos de falla";
        final String formato = conversacion
                ? " Responde de forma natural y directa, en 1 a 3 oraciones."
                : detailed
                    ? " Responde breve: máximo 6 líneas, una por sensor o ítem relevante con estado (OK o ANOMALÍA) y causa probable. Sin asteriscos ni viñetas complejas."
                    : " Responde muy breve (máx 45 palabras) en 3 puntos: 1) diagnóstico, 2) gravedad (Bajo/Medio/Alto), 3) qué revisar. Sin asteriscos ni viñetas.";
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
                    final boolean primera = !saludadoEnSesion;
                    String systemPrompt = systemPrompt(userName, carName, primera, obdConectado);
                    String userText = taskText + ": " + snap + "." + formato;
                    String body = bodyJson(systemPrompt, userText, conversacion);
                    final byte[] bytes = body.getBytes("UTF-8");
                    final String reqUrl = url;
                    // Plazo reducido para priorizar respuesta rápida en la radio.
                    Future<String> fut = NET.submit(new Callable<String>() {
                        public String call() throws Exception {
                            byte[] out = com.fordlauncher.launcher.Net.postJson(reqUrl, bytes, 12000);
                            return extractSse(new String(out, "UTF-8"));
                        }
                    });
                    String text;
                    try {
                        text = fut.get(20000, TimeUnit.MILLISECONDS);
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
                    String limpio = primera ? text.trim() : quitarSaludo(text, userName);
                    registrarRespuesta(userText, limpio);
                    if (callback != null) callback.onDone(limpio, null);
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
