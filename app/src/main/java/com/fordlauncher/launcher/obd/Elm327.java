package com.fordlauncher.launcher.obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Habla con un ELM327 (y clones): manda un comando, lee hasta ">" y deja la
 * respuesta limpia (sin eco, espacios ni SEARCHING...).
 * Port fiel de feature/obd/Elm327.kt de LiteDash (Kotlin -> Java).
 */
public class Elm327 {
    private final ElmLink link;
    private final LongSupplier clock;

    interface LongSupplier { long get(); }

    public Elm327(ElmLink link) { this(link, new LongSupplier(){ public long get(){ return System.currentTimeMillis(); } }); }
    public Elm327(ElmLink link, LongSupplier clock) { this.link = link; this.clock = clock; }

    public List<String> command(String cmd) throws IOException { return command(cmd, 3000); }

    public List<String> command(String cmd, long timeoutMs) throws IOException {
        try {
            while (link.getInput().available() > 0) link.getInput().read();
        } catch (IOException ignored) {}
        link.getOutput().write((cmd + "\r").getBytes());
        link.getOutput().flush();
        StringBuilder text = new StringBuilder();
        long deadline = clock.get() + timeoutMs;
        while (true) {
            int avail;
            try { avail = link.getInput().available(); } catch (IOException e) { throw e; }
            if (avail == 0) {
                if (clock.get() > deadline) throw new IOException("El adaptador no respondio a " + cmd);
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                continue;
            }
            int b = link.getInput().read();
            if (b < 0) throw new IOException("Se cerro la conexion");
            if ((char) b == '>') break;
            text.append((char) b);
        }
        List<String> out = new ArrayList<String>();
        String cmdClean = cmd.replace(" ", "").toUpperCase();
        for (String line : text.toString().split("[\\r\\n]+")) {
            String clean = line.replace(" ", "").toUpperCase();
            if (clean.length() == 0) continue;
            if (clean.equals(cmdClean)) continue;
            if (clean.startsWith("SEARCHING")) continue;
            if (clean.equals("OK")) continue;
            out.add(clean);
        }
        return out;
    }

    public String init() throws IOException {
        String version = "";
        try {
            List<String> v = command("ATZ", 4000);
            if (!v.isEmpty()) version = v.get(v.size()-1).replace("ELM327V", "ELM327 v");
        } catch (Exception ignored) {}
        for (String c : new String[]{"ATE0","ATL0","ATS0","ATH0","ATAT1","ATSP0"}) {
            try { command(c); } catch (Exception ignored) {}
        }
        List<String> probe = command("0100", 10000);
        boolean ok = false;
        for (String l : probe) if (l.contains("4100")) { ok = true; break; }
        if (!ok) throw new IOException(probe.isEmpty() ? "Sin respuesta del auto" : probe.get(0));
        return version;
    }

    public int[] pid(int pid) throws IOException {
        String head = String.format("41%02X", pid);
        String line = null;
        for (String l : command(String.format("01%02X", pid))) {
            if (l.startsWith(head)) { line = l; break; }
        }
        if (line == null) return null;
        return hexBytes(line.substring(head.length()));
    }

    public List<int[]> module(List<String> setup, List<String> requests, List<String> restore) throws IOException {
        try {
            for (String c : setup) command(c);
            List<int[]> out = new ArrayList<int[]>();
            for (String r : requests) {
                int[] reply = parseModuleReply(r, command(r, 1500));
                out.add(reply);
                if (reply == null) break;
            }
            return out;
        } finally {
            for (String c : restore) try { command(c); } catch (Exception ignored) {}
        }
    }

    public Set<Integer> supportedPids() throws IOException {
        Set<Integer> supported = new HashSet<Integer>();
        int base = 0x00;
        while (base <= 0xA0) {
            int[] bits;
            try { bits = pid(base); } catch (IOException e) { break; }
            if (bits == null) break;
            for (int i = 0; i < 32; i++) {
                int b = i / 8 < bits.length ? bits[i / 8] : 0;
                if ((b & (0x80 >> (i % 8))) != 0) supported.add(base + i + 1);
            }
            if (!supported.contains(base + 0x20)) break;
            base += 0x20;
        }
        return supported;
    }

    public List<String> troubleCodes(String mode) throws IOException {
        return parseTroubleCodes(mode, command(mode, 5000));
    }

    public boolean clearCodes() throws IOException {
        for (String l : command("04", 5000)) if (l.startsWith("44")) return true;
        return false;
    }

    public String vin() throws IOException { return parseVin(command("0902", 5000)); }

    public Float voltage() throws IOException {
        List<String> r = command("ATRV");
        if (r.isEmpty()) return null;
        String s = r.get(0);
        if (s.endsWith("V")) s = s.substring(0, s.length()-1);
        try { return Float.parseFloat(s); } catch (Exception e) { return null; }
    }

    static int[] parseModuleReply(String request, List<String> lines) {
        String req = request.replace(" ", "").toUpperCase();
        if (req.length() < 2) return null;
        String positive;
        try {
            int first = Integer.parseInt(req.substring(0,2), 16) + 0x40;
            positive = String.format("%02X", first) + req.substring(2);
        } catch (Exception e) { return null; }
        StringBuilder joined = new StringBuilder();
        Pattern multiLen = Pattern.compile("[0-9A-F]{3}");
        Pattern multiIdx = Pattern.compile("^[0-9A-F]:");
        for (String l : lines) {
            if (multiLen.matcher(l).matches()) continue;
            Matcher m = multiIdx.matcher(l);
            if (m.find()) l = l.substring(m.end());
            joined.append(l);
        }
        int at = joined.toString().indexOf(positive);
        if (at < 0) return null;
        return hexBytes(joined.substring(at + positive.length()).toString());
    }

    static int[] hexBytes(String hex) {
        if (hex.length() % 2 != 0) return null;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!((c>='0'&&c<='9')||(c>='A'&&c<='F'))) return null;
        }
        int[] out = new int[hex.length()/2];
        for (int i = 0; i < out.length; i++) out[i] = Integer.parseInt(hex.substring(i*2, i*2+2), 16);
        return out;
    }

    static List<String> parseTroubleCodes(String mode, List<String> lines) {
        String head;
        try { head = String.format("%02X", Integer.parseInt(mode,16)+0x40); } catch (Exception e) { return new ArrayList<String>(); }
        List<String> codes = new ArrayList<String>();
        for (String line : lines) {
            String clean = line;
            int colon = line.indexOf(':');
            if (colon >= 0) clean = line.substring(colon+1);
            if (!clean.startsWith(head)) continue;
            int[] bytes = hexBytes(clean.substring(head.length()));
            if (bytes == null) continue;
            if (bytes.length % 2 == 1) {
                int[] nb = new int[bytes.length-1];
                System.arraycopy(bytes,1,nb,0,nb.length); bytes = nb;
            }
            for (int i = 0; i < bytes.length && i+1 < bytes.length; i += 2) {
                int a = bytes[i], b = bytes[i+1];
                if (a==0 && b==0) continue;
                char letter = "PCBU".charAt(a >> 6);
                codes.add(String.format("%c%d%X%02X", letter, (a>>4)&0x3, a&0xF, b));
            }
        }
        // distinct
        List<String> distinct = new ArrayList<String>();
        for (String c : codes) if (!distinct.contains(c)) distinct.add(c);
        return distinct;
    }

    static String parseVin(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String l : lines) {
            if (!(l.contains(":") || l.startsWith("49"))) continue;
            int c = l.indexOf(':');
            joined.append(c>=0 ? l.substring(c+1) : l);
        }
        int start = joined.toString().indexOf("4902");
        if (start < 0) return null;
        String hex = joined.substring(start+4);
        if (hex.length() % 2 != 0) hex = hex.substring(0, hex.length()-1);
        int[] bytes = hexBytes(hex);
        if (bytes == null) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < bytes.length; i++) {
            int v = bytes[i];
            if (v >= 0x30 && v <= 0x5A) text.append((char)v);
        }
        String s = text.toString();
        if (s.length() > 17) s = s.substring(s.length()-17);
        return s.length() == 17 ? s : null;
    }
}
