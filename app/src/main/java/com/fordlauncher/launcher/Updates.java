package com.fordlauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Busca versiones nuevas en GitHub Releases.
 * Orden: API de GitHub → update.json del repositorio (raw) → update.json por jsDelivr (también http,
 * para pantallas con Android 4.x que no logran el cifrado de GitHub).
 */
public class Updates {

    public static final String OWNER = "lordnordic-debug";
    public static final String REPO = "FordLauncher";
    public static final String REPO_URL = "https://github.com/" + OWNER + "/" + REPO;
    public static final String RELEASES_URL = REPO_URL + "/releases/latest";
    public static final String ISSUES_URL = REPO_URL + "/issues";
    public static final String DONATE_DEFAULT = REPO_URL + "#-apoya-el-proyecto";

    static final String[] JSON_URLS = {
        "https://raw.githubusercontent.com/" + OWNER + "/" + REPO + "/main/update.json",
        "https://cdn.jsdelivr.net/gh/" + OWNER + "/" + REPO + "@main/update.json",
        "http://cdn.jsdelivr.net/gh/" + OWNER + "/" + REPO + "@main/update.json",
    };

    public static class Release {
        public String source;
        public String version;   // "2.8.0"
        public String notes = "";
        public String pageUrl = RELEASES_URL;
        public String apkUrl;
    }

    public interface Listener { void onResult(Release newer, String error); }

    static String lastError = "";
    /** Resultado de cada fuente en la última búsqueda (para mostrarlo en Acerca de). */
    static String lastReport = "";

    static String shortErr(Throwable e) {
        String m = String.valueOf(e);
        if (m.contains("SSL") || m.contains("andshake")) return "cifrado no compatible";
        if (m.contains("UnknownHost")) return "sin internet";
        if (m.contains("imeout")) return "tiempo agotado";
        if (m.contains("HTTP ")) return m.substring(m.indexOf("HTTP "));
        return "error de red";
    }

    public static String donateUrl(Context c) {
        String u = c.getSharedPreferences("launcher", 0).getString("donate_url", "");
        return u.length() > 0 ? u : DONATE_DEFAULT;
    }

    public static String currentVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable e) { return "0"; }
    }

    /** true si la app vino de F-Droid: allí las actualizaciones las entrega F-Droid. */
    public static boolean fromFdroid(Context c) {
        try {
            String i = c.getPackageManager().getInstallerPackageName(c.getPackageName());
            return i != null && (i.startsWith("org.fdroid") || i.contains("droidify") || i.contains("neostore") || i.contains("foxydroid"));
        } catch (Throwable e) { return false; }
    }

    /** Busca en segundo plano. newer = null si ya está al día. */
    public static void check(final Context ctx, final Listener l) {
        new Thread(new Runnable() {
            public void run() {
                // Se consultan todas las fuentes y gana la versión más alta: una copia en caché (jsDelivr)
                // puede estar atrasada horas, y en Android 4.x algunas fuentes fallan por el cifrado.
                Release r = null;
                StringBuilder rep = new StringBuilder();
                String[] names = {"GitHub", "GitHub (raw)", "jsDelivr", "jsDelivr (http)"};
                for (int i = 0; i <= JSON_URLS.length; i++) {
                    Release c = null;
                    try { c = i == 0 ? fromApi() : fromJson(ctx, JSON_URLS[i - 1]); }
                    catch (Throwable e) { lastError = names[i] + ": " + e; rep.append(names[i]).append(": ").append(shortErr(e)).append('\n'); }
                    if (c == null) continue;
                    rep.append(names[i]).append(": ").append(c.version).append('\n');
                    if (r == null || compare(c.version, r.version) > 0) { r = c; r.source = names[i]; }
                    if (i == 0) break; // la API de GitHub siempre está al día
                }
                lastReport = rep.toString().trim();
                SharedPreferences p = ctx.getSharedPreferences("launcher", 0);
                if (r == null) { l.onResult(null, lastError); return; }
                p.edit().putLong("upd_last", System.currentTimeMillis()).putString("upd_latest", r.version).apply();
                l.onResult(compare(r.version, currentVersion(ctx)) > 0 ? r : null, null);
            }
        }).start();
    }

    private static Release fromApi() throws Exception {
        JSONObject o = new JSONObject(new String(Net.get("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest", 10000), "UTF-8"));
        Release r = new Release();
        r.version = clean(o.getString("tag_name"));
        r.notes = o.optString("body", "");
        r.pageUrl = o.optString("html_url", RELEASES_URL);
        JSONArray a = o.optJSONArray("assets");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String u = a.getJSONObject(i).optString("browser_download_url", "");
            if (u.endsWith(".apk")) { r.apkUrl = u; break; }
        }
        return r;
    }

    private static Release fromJson(Context c, String url) throws Exception {
        JSONObject o = new JSONObject(new String(Net.get(url, 10000), "UTF-8"));
        Release r = new Release();
        r.version = clean(o.getString("version"));
        r.notes = o.optString("notes", "");
        r.pageUrl = o.optString("url", RELEASES_URL);
        r.apkUrl = o.optString("apk", null);
        String d = o.optString("donate_url", "");
        if (d.length() > 0) c.getSharedPreferences("launcher", 0).edit().putString("donate_url", d).apply();
        return r;
    }

    static String clean(String tag) {
        String t = tag.trim();
        while (t.length() > 0 && !Character.isDigit(t.charAt(0))) t = t.substring(1);
        return t;
    }

    /** Compara "2.10.1" con "2.9": >0 si a es más nueva. */
    public static int compare(String a, String b) {
        String[] x = clean(a).split("[^0-9]+"), y = clean(b).split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && x[i].length() > 0 ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && y[i].length() > 0 ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    /** Notas de la versión sin marcas de Markdown, para mostrarlas en pantalla. */
    public static String plain(String md) {
        if (md == null) return "";
        StringBuilder b = new StringBuilder();
        for (String line : md.replace("\r", "").split("\n")) {
            String t = line.replaceAll("^#+\\s*", "").replaceAll("\\*\\*|__|`", "")
                    .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            if (t.trim().startsWith("- ") || t.trim().startsWith("* ")) t = "• " + t.trim().substring(2);
            b.append(t).append('\n');
        }
        return b.toString().trim();
    }
}
