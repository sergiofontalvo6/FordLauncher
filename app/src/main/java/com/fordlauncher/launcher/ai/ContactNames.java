package com.fordlauncher.launcher.ai;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Normaliza y ordena nombres de contactos al formato «Nombre Apellido».
 *
 * <p>En esta radio la agenda llega por PBAP/vCard desde BTSuite, y muchas veces el
 * nombre viene como «Apellido, Nombre» o «Apellido;Nombre» (formato vCard N:). También
 * puede traer apodos entre paréntesis, p. ej. «Fernanda (corazón)». Todo el launcher
 * (lista de contactos, buscador por voz y ventana de llamada) usa estas mismas reglas
 * para que el nombre se vea y se compare siempre igual.
 */
public final class ContactNames {

    private ContactNames() {}

    /** Devuelve el nombre ya ordenado «Nombre Apellido». */
    public static String formatear(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() == 0) return "";

        // vCard "N:Apellido;Nombre;;;"  ->  "Nombre Apellido"
        int semi = s.indexOf(';');
        if (semi > 0 && s.indexOf(',') < 0) {
            String apellido = limpiar(s.substring(0, semi));
            String nombre = limpiar(s.substring(semi + 1));
            if (apellido.length() > 0 && nombre.length() > 0) return nombre + " " + apellido;
        }

        // "Apellido, Nombre"  ->  "Nombre Apellido"
        // Solo se reordena cuando el texto parece un nombre de persona: una sola coma,
        // 1-4 palabras a cada lado y sin puntos/números (así no se destrozan nombres de
        // empresas como «Digital Mtx Baterias, Pantallas, Cargadores.»).
        int coma = s.indexOf(',');
        if (coma > 0 && coma == s.lastIndexOf(',') && coma < s.length() - 1) {
            String apellido = limpiar(s.substring(0, coma));
            String nombre = limpiar(s.substring(coma + 1));
            if (pareceNombre(apellido) && pareceNombre(nombre)) return nombre + " " + apellido;
        }

        return limpiar(s);
    }

    /** Une nombre y apellido estructurados (StructuredName) en «Nombre Apellido». */
    public static String unir(String given, String family) {
        String g = given == null ? "" : given.replaceAll("\\s+", " ").trim();
        String f = family == null ? "" : family.replaceAll("\\s+", " ").trim();
        if (g.length() == 0) return formatear(f);
        if (f.length() == 0) return formatear(g);
        return formatear(g + " " + f);
    }

    /**
     * Separa nombres concatenados por BTSuite en las llamadas recientes, p. ej.
     * «CamposFernanda» -> «Fernanda Campos» y
     * «AcostaFloresJonathan» -> «Jonathan Acosta Flores».
     *
     * <p>BTSuite concatena «Apellido+Nombre» sin espacios. Solo se aplica cuando
     * el texto es una sola palabra con mayúsculas internas claras; cualquier
     * otra cosa se deja pasar por {@link #formatear(String)}.
     */
    public static String desconcatenar(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
        if (s.length() < 6) return formatear(s);
        if (s.indexOf(' ') >= 0 || s.indexOf(',') >= 0 || s.indexOf(';') >= 0) return formatear(s);
        if (s.indexOf('\'') >= 0 || s.indexOf('-') >= 0 || s.indexOf('.') >= 0) return formatear(s);
        if (s.equals(s.toUpperCase(Locale.ROOT))) return formatear(s); // siglas

        List<String> partes = new ArrayList<String>();
        int inicio = 0;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && Character.isLowerCase(s.charAt(i - 1))) {
                partes.add(s.substring(inicio, i));
                inicio = i;
            }
        }
        partes.add(s.substring(inicio));
        if (partes.size() < 2) return formatear(s);
        for (String p : partes) if (p.length() < 3) return formatear(s);

        // BTSuite usa «Apellido(s)+Nombre»: el último bloque es el nombre.
        String nombre = partes.get(partes.size() - 1);
        StringBuilder apellidos = new StringBuilder();
        for (int i = 0; i < partes.size() - 1; i++) {
            if (apellidos.length() > 0) apellidos.append(' ');
            apellidos.append(partes.get(i));
        }
        String ap = apellidos.toString();
        if (pareceNombre(nombre) && pareceNombre(ap)) return nombre + " " + ap;
        return formatear(s);
    }

    /** true si el texto parece un nombre de persona (para reordenar «Apellido, Nombre»). */
    private static boolean pareceNombre(String s) {
        if (s == null) return false;
        String x = s.trim();
        if (x.length() == 0) return false;
        if (x.matches(".*\\d.*")) return false;      // números: empresa/dirección
        if (x.endsWith(".")) return false;            // «S.A.S.», abreviaturas
        String[] tok = x.split("\\s+");
        if (tok.length > 4) return false;             // demasiado largo para un nombre
        for (String t : tok) {
            if (t.length() == 1) return false;        // iniciales sueltas («J.»)
            if (!t.matches("[\\p{L}'.\\-]+")) return false;
        }
        return true;
    }

    /** Quita espacios sobrantes y comas/símbolos en los extremos. */
    private static String limpiar(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        x = x.replaceAll("^[,;\\s]+", "").replaceAll("[,;\\s]+$", "");
        return x.trim();
    }

    /** Minúsculas, sin acentos, sin puntuación: la base para comparar por voz. */
    public static String norm(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT).trim();
        try {
            x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        } catch (Throwable ignored) {}
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Igual que {@link #norm} pero además quita apodos entre paréntesis/corchetes. */
    public static String normSinApodos(String s) {
        String x = sinApodos(s);
        return norm(x);
    }

    /** «Fernanda (corazón)» -> «Fernanda». También corta « - apodo» al final. */
    public static String sinApodos(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("<[^>]*>", " ");
        // «Fernanda - mi amor» conserva solo la parte principal.
        int dash = x.indexOf(" - ");
        if (dash > 1) x = x.substring(0, dash);
        return x.replaceAll("\\s+", " ").trim();
    }

    /**
     * Variantes de un nombre para comparar por voz: tal cual y con el orden
     * nombre/apellido invertido (por si el usuario dicta «Pérez Juan»).
     */
    public static List<String> variantes(String nombre) {
        List<String> out = new ArrayList<String>();
        String base = normSinApodos(nombre);
        if (base.length() == 0) return out;
        out.add(base);
        String[] tok = base.split(" ");
        if (tok.length >= 2) {
            StringBuilder sb = new StringBuilder();
            for (int i = tok.length - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(tok[i]);
            }
            String inv = sb.toString();
            if (!out.contains(inv)) out.add(inv);
        }
        return out;
    }

    /** true si el texto parece un número de teléfono dictado. */
    public static boolean esNumero(String q) {
        if (q == null) return false;
        String d = q.replaceAll("[^0-9+]", "");
        String sinEspacios = q.replaceAll("\\s", "");
        return d.length() >= 7 && d.length() >= sinEspacios.length() - 1;
    }

    /** Solo dígitos (y + inicial) de un número de teléfono. */
    public static String soloDigitos(String n) {
        if (n == null) return "";
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if ((c >= '0' && c <= '9') || (c == '+' && o.length() == 0)) o.append(c);
        }
        return o.toString();
    }
}
