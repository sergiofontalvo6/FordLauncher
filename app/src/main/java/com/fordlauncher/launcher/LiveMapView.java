package com.fordlauncher.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * Mapa en vivo: teselas oscuras de OpenStreetMap (estilo CARTO Dark Matter) centradas en tu posición GPS.
 * Guarda las teselas en el equipo para verlas sin internet por donde ya pasaste.
 */
public class LiveMapView extends View {
    // proveedores en orden: si uno falla en esta pantalla (TLS antiguo, bloqueo), se prueba el siguiente
    // {z} {x} {y}. CARTO ya exige clave (devolvía "API KEY REQUIRED"), así que se usan fuentes sin clave.
    static final String ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/";
    // Las fuentes por http redirigen a https (HTTP 301), y los CDN grandes (Esri, OSM) exigen cifrados
    // que Android 4.x no tiene. Los servidores comunitarios (Alemania, Francia, OpenTopoMap) suelen
    // aceptar cifrados más antiguos. El usuario también puede poner su propia fuente.
    static final String[] URLS = {
        "",                                                          // 0: fuente personalizada (ajustes)
        "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
        "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png",
        "https://tile-a.openstreetmap.fr/hot/{z}/{x}/{y}.png",
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        ESRI + "World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}",
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    };
    static final String[] LABELS = {null, null, null, null, null, ESRI + "World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}", null};
    static final boolean[] DARKEN = {true, true, true, true, true, false, true};
    static final String[] NAMES = {"Personalizada", "OSM Alemania", "OSM Francia", "OSM Humanitario", "OpenTopoMap", "Esri gris oscuro", "OpenStreetMap"};
    static final int[] MAXZ = {18, 18, 18, 18, 17, 16, 18};
    static final String[] ERR = new String[7];
    static int rounds;      // vueltas completas sin éxito
    static int provider = 1;             // índice del que funciona
    static String lastError = "—";
    static int okCount;

    private final float s;
    private final float radius;
    private final boolean hi;                   // teselas @2x en pantallas densas
    private final File dir;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(48);
    private final HashSet<String> pending = new HashSet<String>();
    private final HashSet<String> failed = new HashSet<String>();
    private final LinkedList<String> queue = new LinkedList<String>();
    private int workers;

    private double lat = Double.NaN, lon = Double.NaN;
    private float bearing = Float.NaN;
    private int zoom = 16;
    private int netErrors;

    private final Paint tile = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint grid = new Paint();
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path tri = new Path();

    public LiveMapView(Context c, float scale, float radiusPx) {
        super(c);
        s = scale;
        radius = radiusPx;
        hi = scale >= 1.4f;
        dir = new File(c.getCacheDir(), "tiles_v3");   // v3: se guarda la tesela original; el estilo se aplica al mostrarla
        dir.mkdirs();
        deleteOld(new File(c.getCacheDir(), "tiles"));
        deleteOld(new File(c.getCacheDir(), "tiles_v2"));
        bright = c.getSharedPreferences("launcher", 0).getInt("map_bright", 1);
        grid.setColor(Main.LIGHT ? 0xFFD5D9E0 : 0xFF22252C);
        grid.setStrokeWidth(Math.max(1, s));
        text.setColor(Main.LIGHT ? 0x99000000 : 0x99FFFFFF);
        text.setTextSize(10 * s);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        Net.init(c);
        String custom = c.getSharedPreferences("launcher", 0).getString("map_url", "").trim();
        URLS[0] = custom;
        if (custom.length() > 0) provider = 0;
    }

    public boolean hasFix() { return !Double.isNaN(lat); }
    private final int bright;

    /** Mapa claro → estilo oscuro (invierte y gira el tono 180°, como el "modo noche" de muchos mapas). */
    /** Modo oscuro para mapas claros: invierte, gira el tono 180° (el agua sigue azul, los parques verdes,
     *  el texto queda blanco) y aclara según el nivel de brillo (0 bajo, 1 medio, 2 alto). */
    static Bitmap darken(Bitmap src, int level) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix(new float[]{
                -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
        m.postConcat(new android.graphics.ColorMatrix(new float[]{
                -0.574f, 1.43f, 0.144f, 0, 0,
                0.426f, 0.43f, 0.144f, 0, 0,
                0.426f, 1.43f, -0.856f, 0, 0,
                0, 0, 0, 1, 0}));
        float lift = level <= 0 ? 14 : level == 1 ? 40 : 64, k = level <= 0 ? 0.85f : level == 1 ? 0.80f : 0.78f;
        m.postConcat(new android.graphics.ColorMatrix(new float[]{
                k, 0, 0, 0, lift, 0, k, 0, 0, lift, 0, 0, k * 1.05f, 0, lift + 4, 0, 0, 0, 1, 0}));
        Paint pt = new Paint(Paint.FILTER_BITMAP_FLAG);
        pt.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(src, 0, 0, pt);
        src.recycle();
        return out;
    }

    private static void deleteOld(final File d) {
        if (!d.exists()) return;
        new Thread(new Runnable() { public void run() {
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }}).start();
    }

    public String diag() {
        int cached = 0;
        File[] fs = dir.listFiles();
        if (fs != null) cached = fs.length;
        return "Posición: " + (Double.isNaN(lat) ? "sin GPS" : String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon))
                + "\nZoom: " + zoom + "\nProveedor: " + NAMES[provider]
                + (okCount == 0 && rounds > 0 ? " · ninguna fuente respondió (" + rounds + " vueltas)" : "")
                + "\nTeselas descargadas: " + okCount + " · en caché: " + cached
                + "\nErrores seguidos: " + netErrors + "\nÚltimo error: " + lastError
                + "\n\nFuentes probadas:" + sources()
                + "\n" + Net.initInfo + "\nAndroid " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")";
    }
    public boolean offline() { return netErrors >= 3; }

    private static String sources() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < URLS.length; i++) {
            if (URLS[i].length() == 0) continue;
            b.append("\n• ").append(NAMES[i]).append(": ").append(ERR[i] == null ? "sin probar" : ERR[i]);
        }
        return b.toString();
    }

    private static String shortErr(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("handshake") || m.contains("SSL")) return "cifrado no compatible";
        if (m.startsWith("HTTP")) return m;
        if (e instanceof java.net.UnknownHostException) return "sin internet / DNS";
        if (e instanceof java.net.SocketTimeoutException) return "tiempo agotado";
        if (e instanceof java.security.cert.CertificateException || m.contains("Certificate") || m.contains("Trust")) return "certificado";
        return e.getClass().getSimpleName();
    }

    public void setPosition(double la, double lo, float br, float speedKmh) {
        lat = la;
        lon = lo;
        if (!Float.isNaN(br)) bearing = br;
        if (!userZoom) zoom = speedKmh > 70 ? 15 : speedKmh > 35 ? 16 : 17;
        trimDisk();
        invalidate();
    }

    private boolean userZoom;

    /** Toque: cambia el nivel de zoom (14→17). */
    public void cycleZoom() {
        userZoom = true;
        zoom = zoom >= 17 ? 14 : zoom + 1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.drawColor(Main.LIGHT ? 0xFFE8EBF0 : 0xFF15171C);

        if (!Double.isNaN(lat)) {
            if (zoom > MAXZ[provider]) zoom = MAXZ[provider];
        int n = 1 << zoom;
            double fx = (lon + 180.0) / 360.0 * n;
            double latR = Math.toRadians(lat);
            double fy = (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0 * n;
            float ts = 256 * s;                                    // tamaño de tesela en pantalla
            float cx = w / 2f, cy = h * 0.42f;                     // la posición queda arriba del panel inferior
            int tx0 = (int) Math.floor(fx - cx / ts) - 1, tx1 = (int) Math.floor(fx + (w - cx) / ts) + 1;
            int ty0 = (int) Math.floor(fy - cy / ts) - 1, ty1 = (int) Math.floor(fy + (h - cy) / ts) + 1;
            Rect dst = new Rect();
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int ty = ty0; ty <= ty1; ty++) {
                    if (ty < 0 || ty >= n) continue;
                    int wx = ((tx % n) + n) % n;
                    float left = cx + (float) ((tx - fx) * ts), top = cy + (float) ((ty - fy) * ts);
                    dst.set(Math.round(left), Math.round(top), Math.round(left + ts) + 1, Math.round(top + ts) + 1);
                    Bitmap b = get(zoom, wx, ty, 0);
                    if (b != null) {
                        c.drawBitmap(b, null, dst, tile);
                        if (LABELS[provider] != null) {
                            Bitmap l = get(zoom, wx, ty, 1);
                            if (l != null) c.drawBitmap(l, null, dst, tile);
                        }
                    } else {
                        c.drawLine(dst.left, dst.top, dst.right, dst.top, grid);
                        c.drawLine(dst.left, dst.top, dst.left, dst.bottom, grid);
                    }
                }
            }
            drawMarker(c, cx, cy);
            String attr = provider == 5 ? "© Esri, HERE, Garmin, © OpenStreetMap" : provider == 4 ? "© OpenStreetMap, SRTM · © OpenTopoMap" : "© colaboradores de OpenStreetMap";
            c.drawText(attr, w - text.measureText(attr) - 10 * s, 14 * s, text);
        }
        c.restore();
    }

    private void drawMarker(Canvas c, float x, float y) {
        halo.setColor((Main.RED & 0x00FFFFFF) | 0x40000000);
        c.drawCircle(x, y, 22 * s, halo);
        c.save();
        c.rotate(Float.isNaN(bearing) ? 0 : bearing, x, y);
        tri.reset();
        tri.moveTo(x, y - 14 * s);
        tri.lineTo(x + 10 * s, y + 11 * s);
        tri.lineTo(x, y + 5 * s);
        tri.lineTo(x - 10 * s, y + 11 * s);
        tri.close();
        arrow.setStyle(Paint.Style.FILL);
        arrow.setColor(Main.RED);
        c.drawPath(tri, arrow);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeWidth(2 * s);
        arrow.setColor(0xFFFFFFFF);
        c.drawPath(tri, arrow);
        c.restore();
    }

    // ── teselas ──

    private Bitmap get(int z, int x, int y, int layer) {
        String k = provider + "_" + layer + "_" + z + "_" + x + "_" + y;
        Bitmap b = mem.get(k);
        if (b != null) return b;
        synchronized (queue) {
            if (!pending.contains(k) && !failed.contains(k)) {
                pending.add(k);
                queue.addFirst(k);
                if (workers < 2) { workers++; new Thread(worker).start(); }
            }
        }
        return null;
    }

    private final Runnable worker = new Runnable() {
        public void run() {
            while (true) {
                String k;
                synchronized (queue) {
                    if (queue.isEmpty()) { workers--; return; }
                    k = queue.removeFirst();
                }
                Bitmap b = null;
                try {
                    File f = new File(dir, k + (Main.LIGHT && Integer.parseInt(k.split("_")[0]) == 5 ? "L" : "") + ".png");
                    String[] p = k.split("_");
                    int pv = Integer.parseInt(p[0]), layer = Integer.parseInt(p[1]);
                    if (f.exists()) b = BitmapFactory.decodeFile(f.getAbsolutePath());
                    if (b == null) {
                        int z = Integer.parseInt(p[2]), x = Integer.parseInt(p[3]), y = Integer.parseInt(p[4]);
                        String tpl = layer == 1 ? LABELS[pv] : URLS[pv];
                        if (Main.LIGHT) tpl = tpl.replace("Dark_Gray", "Light_Gray");
                        String url = tpl.replace("{z}", String.valueOf(z)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
                        byte[] data;
                        try {
                            data = Net.get(url, 8000);
                        } catch (Throwable ex) {
                            lastError = "[" + NAMES[pv] + "] " + ex;
                            if (layer == 0) ERR[pv] = shortErr(ex);
                            if (provider == pv && okCount == 0 && layer == 0) {
                                int nx = pv;
                                do { nx = (nx + 1) % URLS.length; if (nx == 1) rounds++; } while (URLS[nx].length() == 0);
                                provider = nx;
                            }
                            throw ex;
                        }
                        okCount++;
                        if (layer == 0) ERR[pv] = "OK";
                        b = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (b != null) {
                            FileOutputStream os = new FileOutputStream(f);
                            os.write(data);
                            os.close();
                            netErrors = 0;
                        }
                    }
                    if (b != null && DARKEN[pv] && layer == 0 && !Main.LIGHT) b = darken(b, bright);
                } catch (Throwable e) {
                    netErrors++;
                }
                final String fk = k;
                final Bitmap fb = b;
                ui.post(new Runnable() {
                    public void run() {
                        synchronized (queue) { pending.remove(fk); }
                        if (fb != null) { mem.put(fk, fb); invalidate(); }
                        else retryLater(fk);
                    }
                });
            }
        }
    };

    private void retryLater(final String k) {
        synchronized (queue) { failed.add(k); }
        ui.postDelayed(new Runnable() {
            public void run() { synchronized (queue) { failed.remove(k); } invalidate(); }
        }, rounds >= 2 ? 60000 : 15000);
        trimDisk();
    }

    private long lastTrim;

    /** Limita la caché en disco a ~60 MB. */
    private void trimDisk() {
        long now = System.currentTimeMillis();
        if (now - lastTrim < 600000) return;
        lastTrim = now;
        new Thread(new Runnable() {
            public void run() {
                File[] fs = dir.listFiles();
                if (fs == null) return;
                long total = 0;
                for (File f : fs) total += f.length();
                if (total < 60L * 1024 * 1024) return;
                java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { long d = a.lastModified() - b.lastModified(); return d > 0 ? 1 : d < 0 ? -1 : 0; }
                });
                for (File f : fs) { if (total < 45L * 1024 * 1024) break; total -= f.length(); f.delete(); }
            }
        }).start();
    }
}
