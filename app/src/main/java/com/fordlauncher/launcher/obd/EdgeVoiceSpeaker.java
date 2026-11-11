package com.fordlauncher.launcher.obd;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Voz neural en la nube (Microsoft Edge TTS, gratis, sin clave).
 * Voces disponibles: es-CO-GonzaloNeural (hombre colombiano),
 * es-MX-JorgeNeural (hombre mexicano), es-AR-TomasNeural (argentino),
 * es-ES-AlvaroNeural (hombre español). Mucho más natural que el TTS local.
 * Si el servicio cambia el protocolo o no hay red, el llamador debe
 * caer al CarVoiceSpeaker local (ver DiagnosticoView).
 */
public class EdgeVoiceSpeaker {
    public interface Listener {
        void onSpeaking(boolean speaking);
        void onError(String msg);
        default void onDone() {}
    }

    private static final String TAG = "FordLauncher/VozNube";
    /** Tono más grave para voz masculina gruesa (0 = original). */
    private static final String PITCH_HZ = "-8Hz";
    private static final String HOST = "speech.platform.bing.com";
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String VERSION = "2-143.0.3650.75";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";

    private final Context ctx;
    private final AudioManager am;
    private final String voiceName;
    private final ExecutorService synthPool = Executors.newCachedThreadPool();
    private final Handler ui = new Handler();
    private Listener listener;
    private MediaPlayer player;
    private Future<?> synthTask;
    private volatile Socket socket;
    private volatile boolean speaking;
    private volatile long generation;
    private volatile File mp3File;

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {}
    };

    public EdgeVoiceSpeaker(Context c, String voiceName) {
        ctx = c.getApplicationContext();
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        this.voiceName = voiceName;
    }

    public void setListener(Listener l) { listener = l; }
    public boolean isSpeaking() { return speaking; }

    public void speak(final String rawText) {
        if (rawText == null || rawText.trim().length() == 0) return;
        final String clean = CarVoiceSpeaker.clean(rawText);
        stop(); // corta la sesión anterior (y su generación) si la hubiere
        final long myGen = ++generation;
        setSpeaking(true); // visible desde ya: el botón pasa a "Detener" durante la síntesis
        synthTask = synthPool.submit(new Runnable() {
            public void run() {
                byte[] mp3;
                try {
                    mp3 = synth(clean);
                } catch (Throwable e) {
                    if (myGen != generation) return; // cancelado por stop(): silencioso
                    Log.w(TAG, "Edge synth error: " + e);
                    setSpeaking(false);
                    if (listener != null) listener.onError("No se pudo obtener la voz en línea.");
                    return;
                }
                if (myGen != generation) return;
                if (mp3 == null || mp3.length < 100) {
                    Log.w(TAG, "Edge synth audio vacio: " + (mp3 == null ? -1 : mp3.length) + " bytes (voz=" + voiceName + ")");
                    setSpeaking(false);
                    if (listener != null) listener.onError("No se pudo obtener la voz en línea.");
                    return;
                }
                try {
                    File f = new File(ctx.getCacheDir(), System.currentTimeMillis() + ".mp3");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(mp3);
                    fos.close();
                    mp3File = f;
                } catch (Throwable e) {
                    Log.w(TAG, "Edge mp3 write error: " + e);
                    setSpeaking(false);
                    if (listener != null) listener.onError("No se pudo obtener la voz en línea.");
                    return;
                }
                ui.post(new Runnable() { public void run() { if (myGen == generation) play(); } });
            }
        });
    }

    private void play() {
        if (mp3File == null) return;
        try {
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (Throwable ignored) {}
        try {
            try { if (player != null) { player.release(); } } catch (Throwable ignored) {}
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(mp3File.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { finishPlay(); }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "Edge play error what=" + what + " extra=" + extra);
                    finishPlay();
                    return true;
                }
            });
            player.prepare();
            player.start();
            setSpeaking(true);
            Log.w(TAG, "Edge play started (" + mp3File.length() + " bytes, voz=" + voiceName + ")");
        } catch (Throwable e) {
            Log.w(TAG, "Edge play error: " + e);
            setSpeaking(false);
            abandon();
        }
    }

    private void finishPlay() {
        try { if (player != null) { player.release(); } } catch (Throwable ignored) {}
        player = null;
        setSpeaking(false);
        abandon();
        try { if (mp3File != null) { mp3File.delete(); mp3File = null; } } catch (Throwable ignored) {}
        if (listener != null) listener.onDone();
    }

    private void setSpeaking(boolean s) {
        speaking = s;
        if (listener != null) listener.onSpeaking(s);
    }

    private void abandon() {
        try { am.abandonAudioFocus(focusListener); } catch (Throwable ignored) {}
    }

    public void stop() {
        generation++; // invalida síntesis pendientes y reproducciones en cola
        try { if (synthTask != null) { synthTask.cancel(true); synthTask = null; } } catch (Throwable ignored) {}
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        try { if (player != null) { player.stop(); player.release(); } } catch (Throwable ignored) {}
        player = null;
        setSpeaking(false);
        abandon();
        try { if (mp3File != null) { mp3File.delete(); mp3File = null; } } catch (Throwable ignored) {}
    }

    public void shutdown() {
        stop();
        try { synthPool.shutdownNow(); } catch (Throwable ignored) {}
    }

    // ---------- síntesis (WebSocket manual sobre SSLSocket) ----------

    private byte[] synth(String text) throws Exception {
        String token = secMsGec();
        String connId = uuidHex();
        String urlPath = "/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" + TOKEN
                + "&ConnectionId=" + connId + "&Sec-MS-GEC=" + token + "&Sec-MS-GEC-Version=" + VERSION;
        // Intento con fábrica propia (IPv4), luego con la del sistema.
        try {
            return synthOnce(urlPath, text, true);
        } catch (Throwable first) {
            Log.w(TAG, "Edge fábrica propia falló, reintento sistema: " + first);
            return synthOnce(urlPath, text, false);
        }
    }

    private byte[] synthOnce(String urlPath, String text, boolean custom) throws Exception {
        final Socket s;
        if (custom && com.fordlauncher.launcher.Net.customFactory() != null) {
            Socket underlying = new Socket();
            underlying.connect(new java.net.InetSocketAddress(HOST, 443), 15000);
            s = com.fordlauncher.launcher.Net.customFactory().createSocket(underlying, HOST, 443, false);
        } else {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, null, null);
            s = sc.getSocketFactory().createSocket();
            s.connect(new java.net.InetSocketAddress(HOST, 443), 15000);
        }
        socket = s;
        s.setSoTimeout(15000);
        OutputStream out = s.getOutputStream();
        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
        // Handshake WebSocket
        String key = base64Of16Random();
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(urlPath).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(HOST).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("Origin: ").append(ORIGIN).append("\r\n");
        req.append("User-Agent: ").append(UA).append("\r\n");
        req.append("Accept-Language: en-US,en;q=0.9\r\n");
        req.append("Pragma: no-cache\r\n");
        req.append("Cache-Control: no-cache\r\n");
        req.append("Cookie: muid=").append(muidHex()).append(";\r\n");
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();
        String statusLine = readLineBytes(in);
        if (statusLine == null || !statusLine.contains("101")) {
            try { s.close(); } catch (Throwable ignored) {}
            throw new Exception("WebSocket handshake no aceptado: " + statusLine);
        }
        // Leer el resto de cabeceras hasta la linea vacia (descartar).
        String line;
        while ((line = readLineBytes(in)) != null && line.length() > 0) { /* cabeceras */ }
        // Config + SSML
        String ts = dateToString();
        String cfg = "X-Timestamp:" + ts + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + displayVoiceName(voiceName) + "'>"
                + "<prosody pitch='" + PITCH_HZ + "' rate='+0%' volume='+0%'>"
                + xmlEscape(text)
                + "</prosody></voice></speak>";
        String reqId = uuidHex();
        String ss = "X-RequestId:" + reqId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + ts + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml;
        writeTextFrame(out, cfg);
        writeTextFrame(out, ss);
        // Leer audio
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 30000;
        int nText = 0, nBin = 0; String firstText = "";
        String exitReason = "deadline";
        while (System.currentTimeMillis() < deadline) {
            Frame f = readFrame(in);
            if (f == null) { exitReason = "EOF"; break; } // EOF
            if (f.opcode == 8) { exitReason = "close"; break; } // close
            if (f.opcode == 9) { sendPong(out, f.payload); continue; } // ping
            if (f.opcode == 2) { // binary
                nBin++;
                byte[] p = f.payload;
                if (p.length >= 2) {
                    int hlen = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
                    int dataStart = hlen + 2;
                    if (dataStart < p.length) audio.write(p, dataStart, p.length - dataStart);
                }
            } else if (f.opcode == 1) { // text
                nText++;
                String t = new String(f.payload, "UTF-8");
                if (firstText.length() == 0) firstText = t.substring(0, Math.min(500, t.length())).replace("\r", "\\r").replace("\n", ".");
                if (t.contains("turn.end")) { exitReason = "turn.end"; break; }
            }
        }
        Log.w(TAG, "Edge frames text=" + nText + " bin=" + nBin + " audio=" + audio.size() + " exit=" + exitReason + " firstText=[" + firstText + "]");
        try { s.close(); } catch (Throwable ignored) {}
        return audio.toByteArray();
    }

    private static final class Frame {
        int opcode;
        byte[] payload = new byte[0];
    }

    private Frame readFrame(BufferedInputStream in) throws Exception {
        int b1 = in.read();
        if (b1 < 0) return null;
        int b2 = in.read();
        Frame f = new Frame();
        f.opcode = b1 & 0x0F;
        long len = b2 & 0x7F;
        if (len == 126) len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        else if (len == 127) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (in.read() & 0xFF);
            len = v;
        }
        byte[] mask = null;
        if ((b2 & 0x80) != 0) { mask = new byte[4]; readFully(in, mask, 4); }
        int n = (int) len;
        byte[] payload = new byte[n];
        readFully(in, payload, n);
        if (mask != null) for (int i = 0; i < n; i++) payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        f.payload = payload;
        return f;
    }

    private void writeTextFrame(OutputStream out, String text) throws Exception {
        writeFrame(out, 1, text.getBytes("UTF-8"));
    }

    private void sendPong(OutputStream out, byte[] payload) throws Exception {
        writeFrame(out, 10, payload);
    }

    private void writeFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        byte[] mask = new byte[4];
        new java.security.SecureRandom().nextBytes(mask);
        out.write(0x80 | (opcode & 0x0F));
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            out.write(0x80 | 126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(0x80 | 127);
            long l = payload.length;
            for (int i = 0; i < 8; i++) out.write((int) ((l >> (56 - 8 * i)) & 0xFF));
        }
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i % 4]);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws Exception {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new Exception("EOF WebSocket");
            off += r;
        }
    }

    private static String readLineBytes(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    private static String uuidHex() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static String muidHex() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    private static String base64Of16Random() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static String xmlEscape(String s) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') o.append("&amp;");
            else if (c == '<') o.append("&lt;");
            else if (c == '>') o.append("&gt;");
            else if (c == '"') o.append("&quot;");
            else if (c == '\'') o.append("&apos;");
            else o.append(c);
        }
        return o.toString();
    }

    private static String dateToString() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new java.util.Date()) + " GMT+0000 (Coordinated Universal Time)";
    }

    /** Port de TTSConfig.__post_init__: el servicio exige el nombre largo del Edge. */
    static String displayVoiceName(String shortName) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$").matcher(shortName);
        if (m.matches()) {
            String lang = m.group(1), region = m.group(2), name = m.group(3);
            if (name.indexOf('-') >= 0) {
                region = region + "-" + name.substring(0, name.indexOf('-'));
                name = name.substring(name.indexOf('-') + 1);
            }
            return "Microsoft Server Speech Text to Speech Voice (" + lang + "-" + region + ", " + name + ")";
        }
        return shortName;
    }

    // ---------- token Sec-MS-GEC (port exacto de edge_tts v2) ----------

    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final double WIN_EPOCH = 11644473600.0;
    private static final long S_TO_NS = 1000000000L;

    private static String secMsGec() {
        double ticks = System.currentTimeMillis() / 1000.0;
        ticks += WIN_EPOCH;
        ticks -= ticks % 300.0;
        ticks *= (double) S_TO_NS / 100.0;
        String data = Long.toString(Math.round(ticks)) + TRUSTED_TOKEN;
        byte[] bytes;
        try { bytes = data.getBytes("ASCII"); } catch (UnsupportedEncodingException e) { throw new RuntimeException(e); }
        byte[] digest = sha256Digest(bytes);
        int[] cs64 = cs64Digest(bytes, digest);
        // Python empaqueta con "<II" (little-endian): cada palabra de bajo a alto.
        byte[] le = new byte[8];
        le[0] = (byte) cs64[0]; le[1] = (byte) (cs64[0] >> 8); le[2] = (byte) (cs64[0] >> 16); le[3] = (byte) (cs64[0] >> 24);
        le[4] = (byte) cs64[1]; le[5] = (byte) (cs64[1] >> 8); le[6] = (byte) (cs64[1] >> 16); le[7] = (byte) (cs64[1] >> 24);
        return hexUpper(le) + "-" + hexUpper(digest);
    }

    private static String hexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }

    private static int rotr(int x, int n) { return (x >>> n) | (x << (32 - n)); }
    private static int rol16(int x) { return (x << 16) | (x >>> 16); }
    private static int sig0(int x) { return ((x >>> 7) ^ (x << 25) ^ (x >>> 18) ^ (x << 14) ^ (x >>> 3)); }
    private static int sig1(int x) { return ((x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10)); }

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };
    private static final int[] KBLOCK = K.clone();
    static { KBLOCK[14] = 0xC9DC06A7; }
    private static final int[] INIT = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    private static final int[] STATE = {
        0xfd9fbea2, 0x91243ce7, 0xbe499094, 0xcd2e1e0a, 0x6c006f49, 0x04bd35ab, 0xfeb0a7e4, 0x03effbd2,
    };
    private static final int[] SCHED = {
        0xeb24bf9e, 0x1558691f, 0xa0c09ee2, 0x0cfa32ba, 0xa622ee8c, 0xd1dd9491, 0xc0dd25a0, 0xf9e70ab6,
        0xf0c31573, 0x7058ce4e, 0x7188e606, 0x00e5ab33, 0xd258b060, 0x72766564,
    };

    private static int[] shaRounds(int[] state, int[] w, int first, int[] consts) {
        int a = state[0], b = state[1], c = state[2], d = state[3], e = state[4], f = state[5], g = state[6], h = state[7];
        for (int t = first; t < 64; t++) {
            int t1 = h
                    + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25))
                    + ((e & f) ^ (~e & g))
                    + consts[t]
                    + w[t];
            int t2 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22))
                    + ((a & b) ^ (a & c) ^ (b & c));
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[]{a, b, c, d, e, f, g, h};
    }

    private static int[] shaInit(byte[] data) {
        int[] w = new int[31];
        w[14] = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        w[15] = ((data[3] & 0xFF) << 24) | ((data[4] & 0xFF) << 16) | ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
        for (int index = 16; index < 30; index++) {
            int value = SCHED[index - 16];
            if (index - 2 >= 14) {
                int s = sig1(w[index - 2]);
                value += (index == 16) ? (s ^ 0x000B92C0) : s;
            }
            if (index - 7 >= 14) value += w[index - 7];
            if (index - 15 >= 14) {
                int s = sig0(w[index - 15]);
                value += (index == 29) ? (s ^ 0x059C0B80) : s;
            }
            w[index] = value;
        }
        w[30] = sig1(w[28]) + w[23] + sig0(w[15]) + w[14] + 0xC9DC06A7 - K[14];
        int[] full = new int[64];
        System.arraycopy(w, 0, full, 0, 31);
        for (int index = 31; index < 64; index++) {
            int x = full[index - 2], y = full[index - 15];
            full[index] = sig1(x) + full[index - 7] + sig0(y) + full[index - 16];
        }
        int[] work = shaRounds(STATE, full, 14, KBLOCK);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = INIT[i] + work[i];
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int zeros = ((55 - length) % 64 + 64) % 64;
        int total = data.length - 7 + 1 + zeros + 8;
        byte[] padded = new byte[total];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bits = (long) length * 8L;
        for (int i = 0; i < 8; i++) padded[total - 8 + i] = (byte) (bits >> (56 - 8 * i));
        int[] state = shaInit(java.util.Arrays.copyOfRange(data, 0, 7));
        for (int off = 0; off < padded.length; off += 64) {
            int[] w = new int[64];
            for (int i = 0; i < 16; i++) {
                w[i] = ((padded[off + 4 * i] & 0xFF) << 24)
                        | ((padded[off + 4 * i + 1] & 0xFF) << 16)
                        | ((padded[off + 4 * i + 2] & 0xFF) << 8)
                        | (padded[off + 4 * i + 3] & 0xFF);
            }
            for (int index = 16; index < 64; index++) {
                w[index] = sig1(w[index - 2]) + w[index - 7] + sig0(w[index - 15]) + w[index - 16];
            }
            int[] work = shaRounds(state, w, 0, K);
            for (int i = 0; i < 8; i++) state[i] += work[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (state[i] >> 24);
            out[4 * i + 1] = (byte) (state[i] >> 16);
            out[4 * i + 2] = (byte) (state[i] >> 8);
            out[4 * i + 3] = (byte) state[i];
        }
        return out;
    }

    private static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = ((digest[0] & 0xFF) | ((digest[1] & 0xFF) << 8) | ((digest[2] & 0xFF) << 16) | ((digest[3] & 0xFF) << 24)) | 1;
        int keyB = ((digest[4] & 0xFF) | ((digest[5] & 0xFF) << 8) | ((digest[6] & 0xFF) << 16) | ((digest[7] & 0xFF) << 24)) | 1;
        int fA = keyA * 0x82893933;
        int fB = keyB * 0x82893933;
        final int[][] coef = {
            {0xE82E91B1, 0xB32700D0}, {0x44DE24C6, 0x88DFF51D}, {0x3BEB65BC, 0x29514038},
            {0xC89C5933, 0xB328CBDF}, {0x8D1EC211, 0xD405B4D5}, {0x314EF5D5, 0x26E55F3C},
            {0xE92491B1, 0x607801C6}, {0xF0A5B9D5, 0x82B2C1FB}, {0x06064CF3, 0xFA51A3DA},
            {0xDA79FA60, 0x207CC464}, {0xBED20560, 0x3C3C74F3}, {0x89F9C9F8, 0xD2D14B61},
            {0x35FA38C6, 0xBB04A2C1}, {0xCB49E3C6, 0xC5626167},
        };
        java.util.ArrayList<Integer> terms = new java.util.ArrayList<Integer>();
        java.util.ArrayList<Integer> offsets = new java.util.ArrayList<Integer>();
        for (int i = 0; i < coef.length; i++) {
            terms.add(coef[i][0] * (i % 2 == 0 ? fA : fB));
            offsets.add(coef[i][1]);
        }
        // _words_le4_even(b"\0" + data)
        int zeros = ((-(data.length + 1)) % 4 + 4) % 4;
        byte[] padded = new byte[data.length + 1 + zeros];
        System.arraycopy(data, 0, padded, 1, data.length);
        int nwords = padded.length / 4;
        if (nwords % 2 != 0) nwords++; // words numericas; la última es 0
        for (int index = 0; index < nwords; index++) {
            int base = index * 4;
            int word = base < padded.length ? ((padded[base] & 0xFF) | ((padded[base + 1] & 0xFF) << 8) | ((padded[base + 2] & 0xFF) << 16) | ((padded[base + 3] & 0xFF) << 24)) : 0;
            int term = word * (index % 2 == 0 ? keyA : keyB);
            int offset = rol16(word) * (index % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (index == 0) { term += 0xF2F90B1A * fA; offset += 0x0B1A0000; }
            terms.add(term);
            offsets.add(offset);
        }
        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int index = 0; index < terms.size(); index += 2) {
            int x = terms.get(index), y = terms.get(index + 1);
            int t = rol16(offsets.get(index) + x);
            int q = t * 0x76F50F34;
            swapAcc += (rol16(q) * 0xC3EFEA97 + q);
            t = rol16(offsets.get(index + 1) + y);
            q = t * 0x1881CE3C;
            swapLast = (rol16(q) * 0x35BD1EC9 + q);
            swapAcc += swapLast;
            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;
            t = state * keyB + y;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[]{swapLast ^ state, swapAcc ^ acc};
    }
}
