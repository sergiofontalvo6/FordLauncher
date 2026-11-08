package com.fordlauncher.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Descargas HTTPS que también funcionan en Android 4.x:
 * - activa TLS 1.2 (viene apagado en Android 4.1–4.4)
 * - confía en certificados raíz actuales incluidos en la app (además de los del sistema)
 */
public class Net {
    private static SSLSocketFactory factory;
    private static final java.util.concurrent.ExecutorService POST_POOL = java.util.concurrent.Executors.newCachedThreadPool();
    static String initInfo = "sin iniciar";

    public static synchronized void init(Context c) {
        // En pantallas con Tethering/hotspot y emuladores hay IPv6 sin ruta (2606:...):
        // sin esto, GitHub/jsDelivr se cuelgan 10s y las actualizaciones fallan con "error de red".
        try { System.setProperty("java.net.preferIPv4Stack", "true"); } catch (Throwable ignored) {}
        if (factory != null) return;
        try {
            final X509TrustManager system = defaultTm(null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            InputStream in = c.getAssets().open("roots.pem");
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] rb = new byte[16384];
            int rn;
            while ((rn = in.read(rb)) > 0) raw.write(rb, 0, rn);
            in.close();
            String pem = new String(raw.toByteArray(), "US-ASCII");
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int i = 0, bad = 0, at = 0;
            while (true) {   // uno por uno: Android 4.x no entiende algunas raíces nuevas y fallaría todo el archivo
                int s0 = pem.indexOf("-----BEGIN CERTIFICATE-----", at);
                if (s0 < 0) break;
                int e0 = pem.indexOf("-----END CERTIFICATE-----", s0);
                if (e0 < 0) break;
                at = e0 + 25;
                try {
                    Certificate cert = cf.generateCertificate(new java.io.ByteArrayInputStream(pem.substring(s0, at).getBytes("US-ASCII")));
                    ks.setCertificateEntry("root" + (i++), cert);
                } catch (Throwable t) { bad++; }
            }
            final X509TrustManager bundled = defaultTm(ks);
            X509TrustManager both = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                    system.checkClientTrusted(chain, auth);
                }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                    try { system.checkServerTrusted(chain, auth); }
                    catch (java.security.cert.CertificateException e) { bundled.checkServerTrusted(chain, auth); }
                }
                public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            };
            SSLContext ctx;
            try { ctx = SSLContext.getInstance("TLSv1.2"); } catch (Throwable e) { ctx = SSLContext.getInstance("TLS"); }
            ctx.init(null, new TrustManager[]{both}, null);
            factory = new Tls12Factory(ctx.getSocketFactory());
            initInfo = "TLS listo (" + ctx.getProtocol() + "), " + i + " raíces" + (bad > 0 ? ", " + bad + " omitidas" : "");
        } catch (Throwable e) {
            factory = null;
            initInfo = "TLS no disponible: " + e;
        }
    }

    private static X509TrustManager defaultTm(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("sin TrustManager");
    }


    private static String resolveIPv4(String host) {
        if (host == null || host.length() == 0) return null;
        try {
            if (host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return host;
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress a : addrs) if (a instanceof java.net.Inet4Address) return a.getHostAddress();
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean verifyOriginalHost(String origHost, javax.net.ssl.SSLSession session) {
        try {
            java.security.cert.Certificate[] certs = session.getPeerCertificates();
            if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate)) return false;
            X509Certificate cert = (X509Certificate) certs[0];
            java.util.Collection<java.util.List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) for (java.util.List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(7).equals(san.get(0))) {
                    String name = (String) san.get(1);
                    if (origHost.equalsIgnoreCase(name)) return true;
                }
            }
            return cert.getSubjectX500Principal().getName().contains(origHost);
        } catch (Throwable e) {
            return false;
        }
    }

    public static byte[] get(String url, int timeoutMs) throws Exception {
        String current = url;
        // GitHub API /releases/latest responde 302 a /releases/tag/vX: seguirlo a mano
        // (HttpURLConnection no siempre lo sigue en Android viejo / https->https distinto host).
        for (int hop = 0; hop <= 5; hop++) {
            URL parsed = new URL(current);
            HttpURLConnection c = (HttpURLConnection) parsed.openConnection();
            if (c instanceof HttpsURLConnection && factory != null) {
                ((HttpsURLConnection) c).setSSLSocketFactory(factory);
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "FordLauncher/2.1 (Android head unit launcher)");
            c.setRequestProperty("Accept", "application/vnd.github.v3+json, application/json, */*");
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.length() == 0) throw new Exception("HTTP " + code);
                    current = new URL(parsed, loc).toString();
                    continue;
                }
                if (code != 200) {
                    // Para 403/429 de GitHub (limite) leer el cuerpo: trae el mensaje util.
                    String extra = "";
                    try {
                        InputStream err = c.getErrorStream();
                        if (err != null) {
                            ByteArrayOutputStream eb = new ByteArrayOutputStream();
                            byte[] bb = new byte[4096];
                            int en;
                            while ((en = err.read(bb)) > 0) eb.write(bb, 0, en);
                            err.close();
                            String body = new String(eb.toByteArray(), "UTF-8").trim().replaceAll("[\\r\\n]+", " ");
                            if (body.length() > 160) body = body.substring(0, 160);
                            if (body.length() > 0) extra = " (" + body + ")";
                        }
                    } catch (Throwable ignored) {}
                    if (code == 429 || code == 403) {
                        String ra = c.getHeaderField("Retry-After");
                        if (ra != null && ra.length() > 0) extra += " Retry-After:" + ra;
                    }
                    throw new Exception("HTTP " + code + extra);
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toByteArray();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("HTTP demasiadas redirecciones");
    }

    public static byte[] postJson(String url, byte[] body, int timeoutMs) throws Exception {
        // Intento 1: fábrica propia (IPv4 forzado + TLS 1.2, necesaria en head units).
        // Intento 2: pila del sistema (respaldos cuando la fábrica se cuelga con
        // ciertos hosts, p. ej. API 27 + Google). Cada intento acotado por timeoutMs.
        try {
            return timedPost(url, body, timeoutMs, true);
        } catch (Exception first) {
            try { android.util.Log.w("FordLauncher/Net", "postJson fábrica propia falló, reintento sistema: " + first); } catch (Throwable ignored) {}
            return timedPost(url, body, timeoutMs, false);
        }
    }

    private static byte[] timedPost(final String url, final byte[] body, final int timeoutMs, final boolean customFactory) throws Exception {
        java.util.concurrent.Future<byte[]> fut = POST_POOL.submit(new java.util.concurrent.Callable<byte[]>() {
            public byte[] call() throws Exception {
                return doPost(url, body, timeoutMs, customFactory);
            }
        });
        try {
            return fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            try { fut.cancel(true); } catch (Throwable ignored) {}
            throw new Exception("timeout " + timeoutMs + "ms");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof Exception) throw (Exception) c;
            throw new Exception(String.valueOf(c));
        } catch (java.util.concurrent.CancellationException e) {
            throw new Exception("cancelado");
        }
    }

    private static byte[] doPost(String url, byte[] body, int timeoutMs, boolean customFactory) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (customFactory && c instanceof HttpsURLConnection && factory != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(factory);
        }
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "FordLauncher/2.1 (Android head unit launcher)");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json, */*");
        c.setFixedLengthStreamingMode(body.length);
        try {
            java.io.OutputStream os = c.getOutputStream();
            long t1 = System.currentTimeMillis();
            os.write(body);
            os.close();
            long t2 = System.currentTimeMillis();
            int code = c.getResponseCode();
            long t3 = System.currentTimeMillis();
            try { android.util.Log.w("FordLauncher/Net", "postJson [" + (customFactory ? "custom" : "system") + "] connect=" + (t1 - t0) + "ms write=" + (t2 - t1) + "ms responseCode=" + (t3 - t2) + "ms code=" + code); } catch (Throwable ignored) {}
            if (code != 200) {
                String extra = "";
                try {
                    InputStream err = c.getErrorStream();
                    if (err != null) {
                        ByteArrayOutputStream eb = new ByteArrayOutputStream();
                        byte[] bb = new byte[4096];
                        int en;
                        while ((en = err.read(bb)) > 0) eb.write(bb, 0, en);
                        err.close();
                        String txt = new String(eb.toByteArray(), "UTF-8").trim().replaceAll("[\\r\\n]+", " ");
                        if (txt.length() > 200) txt = txt.substring(0, 200);
                        if (txt.length() > 0) extra = " (" + txt + ")";
                    }
                } catch (Throwable ignored) {}
                throw new Exception("HTTP " + code + extra);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Envuelve la fábrica para encender TLS 1.1/1.2 en cada socket. */
    private static class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12Factory(SSLSocketFactory d) { this.d = d; }
        private Socket on(Socket s) { return on(s, null); }
        private Socket on(Socket s, String host) {
            if (s instanceof SSLSocket) {
                
                
                try { // todos los cifrados que tenga el equipo (Android 4.x trae pocos activados)
                    ((SSLSocket) s).setEnabledCipherSuites(((SSLSocket) s).getSupportedCipherSuites());
                } catch (Throwable ignored) { }
                if (host != null) {
                    try { s.getClass().getMethod("setHostname", String.class).invoke(s, host); } catch (Throwable ignored) { }   // SNI
                    try { s.getClass().getMethod("setUseSessionTickets", boolean.class).invoke(s, true); } catch (Throwable ignored) { }
                }
                try {
                    String[] sup = ((SSLSocket) s).getSupportedProtocols();
                    java.util.ArrayList<String> want = new java.util.ArrayList<String>();
                    for (String p : sup) if (p.equals("TLSv1.2") || p.equals("TLSv1.3") || p.equals("TLSv1.1")) want.add(p);
                    if (!want.isEmpty()) ((SSLSocket) s).setEnabledProtocols(want.toArray(new String[0]));
                } catch (Throwable ignored) { }
            }
            return s;
        }
        public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        public Socket createSocket(Socket s, String h, int p, boolean a) throws java.io.IOException { return on(d.createSocket(s, h, p, a), h); }
        public Socket createSocket(String h, int p) throws java.io.IOException { String ip = resolveIPv4(h); if (ip != null) { try { return on(d.createSocket(java.net.InetAddress.getByName(ip), p), h); } catch (Throwable ignored) {} } return on(d.createSocket(h, p), h); }
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws java.io.IOException { return on(d.createSocket(h, p, l, lp), h); }
        public Socket createSocket(InetAddress h, int p) throws java.io.IOException { return on(d.createSocket(h, p)); }
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws java.io.IOException { return on(d.createSocket(h, p, l, lp)); }
    }
}
