package com.fordlauncher.launcher.ai;

import android.util.Log;

import java.util.Locale;

/** Parsing robusto de las respuestas de Gemini, compartido por Main y el botón flotante global. */
public final class VoiceActionParser {

    public static final class Action {
        public final String type;
        public final String query;
        public final String speech;
        public final String appName;

        public Action(String type, String query, String speech, String appName) {
            this.type = type;
            this.query = query;
            this.speech = speech;
            this.appName = appName;
        }
    }

    private VoiceActionParser() {}

    public static Action parse(String text, String userSpeech) {
        if (text == null) return null;
        Log.d("FordLauncher/AI", "raw=" + text);
        String t = text.trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) {
            String json = t.substring(s, e + 1);
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                String type = o.optString("action", "").trim();
                if (type.length() > 0) {
                    return new Action(
                            type,
                            o.optString("query", "").trim(),
                            o.optString("speech", "").trim(),
                            o.optString("app_name", "").trim());
                }
            } catch (Throwable error) {
                Log.w("FordLauncher/AI", "JSON no válido=" + json, error);
            }
        }
        if (isMusicIntent(text, userSpeech)) {
            String q = extractMusicQuery(text.contains("SerMusic") || text.contains("sermusic") ? text : userSpeech);
            if (q.length() > 0) {
                Log.i("FordLauncher/AI", "fallback PLAY_MUSIC query=\"" + q + "\"");
                return new Action("PLAY_MUSIC", q, text.trim(), "");
            }
        }
        return null;
    }

    public static boolean isMusicIntent(String text, String userSpeech) {
        String t = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        String u = (userSpeech == null ? "" : userSpeech).toLowerCase(Locale.ROOT);
        boolean geminiMusic =
                t.startsWith("poniendo ") || t.startsWith("reproduciendo ") ||
                t.startsWith("sonando ") || t.startsWith("tocando ") ||
                (t.contains("en sermusic") && (t.contains("poniendo") || t.contains("reproduciendo")));
        boolean userMusic =
                u.startsWith("pon ") || u.startsWith("pone ") || u.startsWith("ponme ") ||
                u.startsWith("coloca ") || u.startsWith("coloque ") || u.startsWith("colocar ") ||
                u.startsWith("dame ") ||
                u.startsWith("reproduce ") || u.startsWith("reproducir ") ||
                u.startsWith("quiero escuchar ") ||
                u.startsWith("me gustaría escuchar ") || u.startsWith("me gustaria escuchar ") ||
                u.startsWith("escuchar ") || u.startsWith("escucha ") ||
                u.startsWith("toca ") || u.startsWith("tocar ");
        return geminiMusic || userMusic;
    }

    public static String extractMusicQuery(String fuente) {
        String s = fuente == null ? "" : fuente.trim();
        s = s.replaceFirst("(?i)^(poniendo|reproduciendo|sonando|tocando)\\s+", "");
        s = s.replaceFirst("(?i)^(coloca|coloque|colocar|dame|me gustar[íi]a escuchar)\\s+", "");
        s = s.replaceFirst("(?i)^(pon|pone|ponme|reproduce|reproducir|escuchar|escucha|toca|tocar|quiero escuchar)\\s+", "");
        s = s.replaceFirst("(?i)^(una|la|el|un|las|los|mi|mis)\\s+", "");
        s = s.replaceFirst("(?i)^(canci[óo]n|cancion|tema|m[úu]sica|musica|artista)\\s+(de\\s+)?", "");
        s = s.replaceFirst("(?i)^(de\\s+)", "");
        s = s.replaceFirst("(?i)\\s+(en|con|de)\\s+SerMusic\\s*$", "");
        s = s.replaceFirst("(?i)\\s+en\\s+sermusic\\s*$", "");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.length() > 0 && (s.endsWith("?") || s.endsWith(".") || s.endsWith("!"))) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }
}
