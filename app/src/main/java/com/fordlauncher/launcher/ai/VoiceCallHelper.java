package com.fordlauncher.launcher.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resuelve un contacto o número dictado por voz y ejecuta la llamada por Bluetooth HFP.
 *
 * <p>Búsqueda tolerante a mayúsculas, acentos, apodos («Fernanda (corazón)»), nombres
 * parciales y a los dos órdenes posibles («Nombre Apellido» / «Apellido Nombre»).
 * Si hay dos contactos muy parecidos pregunta antes de marcar en vez de elegir al azar.
 */
public final class VoiceCallHelper {

    private static final String TAG = "FordLauncher/VoiceCall";

    /** Puntaje mínimo para aceptar una coincidencia. */
    private static final int UMBRAL = 55;
    /** Diferencia máxima entre los dos mejores para considerar ambigüedad. */
    private static final int MARGEN_AMBIGUO = 6;

    private VoiceCallHelper() {}

    /** Un contacto candidato con su puntaje de coincidencia. */
    public static final class Cand {
        public final String name;
        public final String number;
        public final boolean starred;
        public int score;

        Cand(String name, String number, boolean starred) {
            this.name = name;
            this.number = number;
            this.starred = starred;
        }

        @Override public String toString() { return name + " (" + number + ")"; }
    }

    public static void procesarLlamadaPorVoz(final Context context, String target, final String speechConfirmation) {
        if (context == null || target == null || target.trim().length() == 0) return;
        final String t = target.trim();

        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final Context app = context.getApplicationContext();

        new Thread(new Runnable() {
            public void run() {
                final List<Cand> cands = buscarCandidatos(app, t);
                main.post(new Runnable() {
                    public void run() { ejecutar(context, t, speechConfirmation, cands); }
                });
            }
        }, "voice-call-resolve").start();
    }

    private static void ejecutar(Context context, String target, String speechConfirmation, List<Cand> cands) {
        if (cands.isEmpty()) {
            hablar(context, "No encontré a " + target + " en tus contactos");
            return;
        }

        Cand top = cands.get(0);
        Cand second = cands.size() > 1 ? cands.get(1) : null;

        boolean ambiguo = second != null
                && (top.score - second.score) <= MARGEN_AMBIGUO
                && !mismoNumero(top.number, second.number);

        if (ambiguo) {
            // Si solo uno es favorito o solo uno tiene móvil, no hace falta preguntar.
            List<Cand> empatados = new ArrayList<Cand>();
            for (Cand c : cands) {
                if (top.score - c.score <= MARGEN_AMBIGUO) empatados.add(c);
            }
            int favoritos = 0;
            Cand fav = null;
            for (Cand c : empatados) if (c.starred) { favoritos++; fav = c; }
            if (favoritos == 1) {
                marcar(context, fav, speechConfirmation);
                return;
            }
            int moviles = 0;
            Cand movil = null;
            for (Cand c : empatados) if (esMovil(c.number)) { moviles++; movil = c; }
            if (moviles == 1 && empatados.size() > 1) {
                marcar(context, movil, speechConfirmation);
                return;
            }
            if (context instanceof com.fordlauncher.launcher.Main) {
                com.fordlauncher.launcher.Main m = (com.fordlauncher.launcher.Main) context;
                if (m.preguntarContacto(empatados)) {
                    hablar(context, "Encontré varios contactos parecidos. ¿A cuál llamo?");
                    return;
                }
            }
            // Sin interfaz disponible: marcar el mejor.
            marcar(context, top, speechConfirmation);
            return;
        }

        marcar(context, top, speechConfirmation);
    }

    private static void marcar(final Context context, final Cand c, String speechConfirmation) {
        String nombre = ContactNames.formatear(c.name);
        String decir = speechConfirmation != null && speechConfirmation.trim().length() > 0
                ? speechConfirmation
                : ("Llamando a " + (nombre.length() > 0 ? nombre : c.number));
        hablar(context, decir);
        final String numero = c.number;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                TelefonoManager.marcarNumero(context, numero);
            }
        }, 650);
    }

    private static void hablar(Context context, String texto) {
        try {
            TextToSpeechManager.getInstance(context).hablarTextoLargo(texto);
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── resolución ─────────────────────────

    /** Devuelve el número del mejor contacto para la consulta (o null). */
    public static String resolverNumero(Context context, String query) {
        List<Cand> cands = buscarCandidatos(context, query);
        return cands.isEmpty() ? null : cands.get(0).number;
    }

    public static List<Cand> buscarCandidatos(Context context, String query) {
        List<Cand> out = new ArrayList<Cand>();
        if (context == null || query == null) return out;
        String q = query.trim();
        if (q.length() == 0) return out;

        // Número dictado directamente.
        if (ContactNames.esNumero(q)) {
            out.add(new Cand(q, ContactNames.soloDigitos(q), false));
            return out;
        }

        String nq = ContactNames.normSinApodos(q);
        if (nq.length() == 0) return out;

        List<Cand> todos = cargarContactos(context);
        for (Cand c : todos) {
            int sc = puntaje(nq, c.name);
            if (sc > 0) {
                c.score = sc;
                out.add(c);
            }
        }

        Collections.sort(out, new Comparator<Cand>() {
            public int compare(Cand a, Cand b) {
                if (a.score != b.score) return b.score - a.score;
                if (a.starred != b.starred) return a.starred ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        // Filtra los que no llegan al umbral.
        List<Cand> fin = new ArrayList<Cand>();
        for (Cand c : out) if (c.score >= UMBRAL) fin.add(c);
        // Quita duplicados exactos (mismo número) conservando el mejor.
        List<Cand> uniq = new ArrayList<Cand>();
        for (Cand c : fin) {
            boolean dup = false;
            for (Cand u : uniq) if (mismoNumero(u.number, c.number)) { dup = true; break; }
            if (!dup) uniq.add(c);
        }
        return uniq;
    }

    /** Agenda del sistema + contactos importados por .vcf, con nombre «Nombre Apellido». */
    private static List<Cand> cargarContactos(Context context) {
        List<Cand> out = new ArrayList<Cand>();
        ContentResolver cr = context.getContentResolver();

        // Nombres estructurados (GIVEN_NAME + FAMILY_NAME) por contacto.
        Map<Long, String> estructurado = new HashMap<Long, String>();
        Cursor sn = null;
        try {
            sn = cr.query(ContactsContract.Data.CONTENT_URI,
                    new String[]{
                            ContactsContract.Data.CONTACT_ID,
                            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                    },
                    ContactsContract.Data.MIMETYPE + "=?",
                    new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE},
                    null);
            if (sn != null) {
                int ic = sn.getColumnIndex(ContactsContract.Data.CONTACT_ID);
                int ig = sn.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME);
                int iff = sn.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME);
                while (sn.moveToNext()) {
                    long id = ic >= 0 ? sn.getLong(ic) : -1;
                    String g = ig >= 0 ? sn.getString(ig) : null;
                    String f = iff >= 0 ? sn.getString(iff) : null;
                    String full = ContactNames.unir(g, f);
                    if (id >= 0 && full.length() > 0) estructurado.put(id, full);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "StructuredName: " + e);
        } finally {
            try { if (sn != null) sn.close(); } catch (Throwable ignored) {}
        }

        Cursor c = null;
        try {
            c = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                            ContactsContract.Contacts.STARRED
                    }, null, null, null);
            if (c != null) {
                int in = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int ip = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                int ic = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID);
                int is = c.getColumnIndex(ContactsContract.Contacts.STARRED);
                while (c.moveToNext()) {
                    String raw = in >= 0 ? c.getString(in) : null;
                    String number = ip >= 0 ? c.getString(ip) : null;
                    if (number == null || number.trim().length() == 0) continue;
                    long id = ic >= 0 ? c.getLong(ic) : -1;
                    String name = id >= 0 ? estructurado.get(id) : null;
                    if (name == null || name.length() == 0) name = ContactNames.formatear(raw);
                    if (name.length() == 0) name = number;
                    boolean starred = is >= 0 && c.getInt(is) == 1;
                    out.add(new Cand(name, number.trim(), starred));
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Contactos: " + e);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }

        // Contactos importados desde .vcf (solo existen en FiestaLauncher).
        try {
            String json = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                    .getString("phone_vcf_contacts", null);
            if (json != null) {
                org.json.JSONArray arr = new org.json.JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = ContactNames.formatear(o.optString("name", ""));
                    String number = o.optString("number", "").trim();
                    if (name.length() == 0 || number.length() < 7) continue;
                    out.add(new Cand(name, number, false));
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "VCF: " + e);
        }
        return out;
    }

    // ───────────────────────── puntaje ─────────────────────────

    private static int puntaje(String nq, String nombre) {
        String nn = ContactNames.normSinApodos(nombre);
        if (nn.length() == 0) return 0;
        if (nn.equals(nq)) return 100;

        String nqJoin = nq.replace(" ", "");
        String nnJoin = nn.replace(" ", "");
        if (nqJoin.length() >= 3 && nqJoin.equals(nnJoin)) return 99;

        String[] qt = nq.split(" ");
        String[] nt = nn.split(" ");

        // Todos los tokens dictados aparecen como token del nombre (con tolerancia
        // a errores del reconocimiento: «jonatan» debe encontrar «Jonathan»).
        int hits = 0;
        int exactos = 0;
        for (String q : qt) {
            if (q.length() < 2) continue;
            for (String n : nt) {
                if (tokenCoincide(q, n)) {
                    hits++;
                    if (n.equals(q)) exactos++;
                    break;
                }
            }
        }
        if (hits == qt.length && hits > 0) {
            if (nt.length == qt.length) return 97;
            // Coincidencia exacta de todas las palabras por encima de una aproximada
            // («Jonathan» gana a «Jonatan» cuando el usuario dice «Jonathan»).
            return exactos == qt.length ? 94 : 91;
        }

        if (nn.startsWith(nq)) return 90;
        if (nq.startsWith(nn) && nn.length() >= 4) return 84;
        if (nn.contains(nq)) return 80;

        // Coincidencia invertida («Pérez Juan» contra «Juan Pérez»).
        for (String v : ContactNames.variantes(nombre)) {
            if (v.equals(nq)) return 96;
            if (v.startsWith(nq)) return 88;
            if (v.contains(nq)) return 78;
        }

        // Coincidencia difusa: tolera errores del reconocimiento de voz.
        int d = levenshtein(nqJoin, nnJoin);
        int tol = Math.max(1, nqJoin.length() / 5);
        if (d <= tol) return Math.max(UMBRAL, 82 - d * 6);

        // Compara contra cada token (nombre parcial dictado).
        int best = 0;
        for (String n : nt) {
            if (n.length() < 3) continue;
            if (n.startsWith(nq) || nq.startsWith(n)) best = Math.max(best, 70);
            int dt = levenshtein(nqJoin, n);
            if (dt <= Math.max(1, nqJoin.length() / 5)) best = Math.max(best, 68 - dt * 4);
        }

        // Penaliza si solo coincidió una parte de varias palabras dictadas.
        if (hits > 0 && hits < qt.length) {
            best = Math.max(best, 50 + (45 * hits / qt.length));
        }
        return best;
    }

    /**
     * Compara una palabra dictada con una palabra del contacto: igual, prefijo o
     * distancia de edición pequeña (tolera «jonatan»/«jonathan», «fontalbo»/«fontalvo»).
     */
    private static boolean tokenCoincide(String q, String n) {
        if (n.equals(q)) return true;
        if (q.length() >= 4 && n.startsWith(q)) return true;
        if (n.length() >= 4 && q.startsWith(n)) return true;
        int min = Math.min(q.length(), n.length());
        if (min < 4) return false;
        int tol = Math.max(1, min / 5);
        return levenshtein(q, n) <= tol;
    }

    private static int levenshtein(String a, String b) {
        int la = a.length(), lb = b.length();
        if (la == 0) return lb;
        if (lb == 0) return la;
        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(prev[j] + 1, cur[j - 1] + 1);
                cur[j] = Math.min(v, prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[lb];
    }

    private static boolean mismoNumero(String a, String b) {
        String na = ContactNames.soloDigitos(a);
        String nb = ContactNames.soloDigitos(b);
        if (na.length() == 0 || nb.length() == 0) return false;
        int len = Math.min(9, Math.min(na.length(), nb.length()));
        if (len < 7) return na.equals(nb);
        return na.substring(na.length() - len).equals(nb.substring(nb.length() - len));
    }

    private static boolean esMovil(String number) {
        String n = ContactNames.soloDigitos(number);
        // En Colombia los móviles empiezan por 3 (10 dígitos) o +57 3...
        String d = n.startsWith("+57") ? n.substring(3) : n;
        return d.length() >= 10 && d.charAt(0) == '3';
    }
}
