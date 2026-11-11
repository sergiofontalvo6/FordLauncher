package com.fordlauncher.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.widget.DatePicker;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class Main extends Activity {

    static int RED = 0xFFFE3211;   // color de acento (configurable)
    static int BG = 0xFF15171C;
    static int CARD = 0xFF1E2128;
    static int INNER = 0xFF262A33;
    static int BTN = 0xFF2A2E37;
    static int DOCK = 0xFF0D0E11;
    static int TEXT = 0xFFF2F3F5;
    static int MUTED = 0xFF8B9099;
    static int SOFT = 0xFFC5C8CE;
    static int CIRCLE = 0xFF23262E;
    static int ICON = 0xFFFFFFFF;
    static boolean LIGHT;
    static int RED_DARK = 0xFF2A1416;

    static final String PKG_HUR = "com.andrerinas.headunitrevived";
    static final String PKG_WEB = "org.bromite.bromite";
    static final String ASSET_HUR = "apps/headunit-revived.apk";

    static final Locale ES = new Locale("es", "CO");

    // ranuras configurables: id, nombre, palabras clave en el nombre de la app, palabras clave en el paquete
    static final String[][] SLOTS = {
        {"radio", "Radio", "radio|fm|收音", "radio|fm"},
        {"bt", "Bluetooth", "bluetooth|bt|teléfono|telefono|phone", "bluetooth|.bt|btphone|btmusic"},
        {"music", "Música", "música|musica|music|spotify|deezer|poweramp|youtube|reproductor", "spotify|deezer|poweramp|youtube|music|vlc|playerpro"},
        {"cam", "Cámara", "cámara|camara|camera|avin|reversa|dvr", "avin|camera|dvr|backcar|reverse"},
        {"files", "Archivos", "archivo|explorador|gestor|file|files", "filemanager|fileexplorer|documentsui|file"},
        {"settings", "Ajustes", "", ""},
    };
    static final String[] SLOT_ICONS = {"radio", "bluetooth", "music_note", "photo_camera", "folder", "settings"};

    private SharedPreferences prefs;
    private final Handler h = new Handler();
    private final List<ResolveInfo> apps = new ArrayList<ResolveInfo>();
    private final HashMap<String, Bitmap> iconCache = new HashMap<String, Bitmap>();

    private FrameLayout root;
    private FrameLayout board;
    private FrameLayout drawer;
    private float s = 1f;
    private int builtW, builtH;

    private TextView weatherTv, weatherCityTv;
    private ImageView weatherIc;
    private LinearLayout weatherBox;
    private double wLat = Double.NaN, wLon = Double.NaN;
    private long weatherAt;
    private Weather.Now weatherNow;
    private FrameLayout settingsView;
    private int settingsCat = -1;
    private TextView timeTv, dateTv, greetTv, speedTv, gpsTv, aaBtnTv, aaSubTv, trackTv, artistTv, carTv, chipTv;
    private NowPlaying nowPlaying;
    private Boolean playingState;
    private TextView sourceTv, timePosTv, mapTagTv, cpSubTv;
    // Barra inferior de telemetría (home)
    private TextView rpmText, tempText, consText, oilText;
    private RpmGauge rpmGauge;
    private MiniBar tempBar, consBar, oilBar;
    private long telemLastMs;
    private float odoKmTotal = -1f;
    private float odoLastSavedKm = -1f;
    private Location lastGpsLoc;
    private LiveMapView liveMap;
    private MediaWatcher mediaWatcher;
    private boolean askedPerms;
    private boolean askedMediaPerm;
    private com.fordlauncher.launcher.obd.ObdRepository obdRepo;
    private FrameLayout workshopView;
    private DiagnosticoView workshop;
    static final int SDK = android.os.Build.VERSION.SDK_INT;
    private PlayerArt playerArt;
    // estado de la pista
    private String curTitle, curArtist, curSource, curAlbum, curPkg;
    private Bitmap curArt;
    private long durMs = -1, posBaseMs = 0, posBaseAt = 0;
    private boolean posKnown;
    private String curOrigin;
    private BtBridge bt;
    private View gpsDot;
    private ImageView playIc;

    private LocationManager lm;
    private long lastFix;
    private boolean installing;
    private ParkedModeManager parked;
    private com.fordlauncher.launcher.obd.SensorAnomalyTriggerManager anomalies;
    private volatile float parkGpsKmh;
    float parkedScale() { return s; }
    float lastGpsKmh() { return parkGpsKmh; }
    Bitmap loadParkedImage() { return loadWallpaperForCurrentMode(); }

    // ───────────────────────── ciclo de vida ─────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        try { parked = new ParkedModeManager(this); } catch (Throwable ignored) {}
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        migrateOrSetup();
        try { phonePrivacy = new PhonePrivacy(this); } catch (Throwable ignored) {}
        IntentFilter headlightFilter = new IntentFilter();
        headlightFilter.addAction("com.zjinnova.zlink.action.OUT_DARK_STOP");
        headlightFilter.addAction("com.zjinnova.zlink.action.OUT_DARK_START");
        headlightFilter.addAction("android.intent.action.CONFIGURATION_CHANGED");
        headlightFilter.addAction("autochips.intent.action.HEADLIGHT_ON");
        headlightFilter.addAction("autochips.intent.action.HEADLIGHT_OFF");
        Intent sticky = registerReceiver(headlightReceiver, headlightFilter);
        if (sticky != null && sticky.getAction() != null) {
            String act = sticky.getAction();
            if ("com.zjinnova.zlink.action.OUT_DARK_STOP".equals(act) || "autochips.intent.action.HEADLIGHT_ON".equals(act)) {
                prefs.edit().putBoolean("headlight_night_active", true).apply();
            } else if ("com.zjinnova.zlink.action.OUT_DARK_START".equals(act) || "autochips.intent.action.HEADLIGHT_OFF".equals(act)) {
                prefs.edit().putBoolean("headlight_night_active", false).apply();
            }
        }
        checkBrightnessAndSwitch();

        brightnessObserver = new android.database.ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                checkBrightnessAndSwitch();
            }
        };
        try {
            getContentResolver().registerContentObserver(
                Settings.System.getUriFor("screen_brightness"),
                true,
                brightnessObserver
            );
        } catch (Throwable ignored) {}

        applyTheme();
        applyAccent();

        root = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int hh, int ow, int oh) {
                super.onSizeChanged(w, hh, ow, oh);
                if (w > 0 && hh > 0 && (w != builtW || hh != builtH)) {
                    builtW = w;
                    builtH = hh;
                    final int fw = w, fh = hh;
                    post(new Runnable() { public void run() { build(fw, fh); } });
                }
            }
        };
        root.setBackgroundColor(BG);
        setContentView(root);
        try {
            Location ll = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (ll == null) ll = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (ll != null) { wLat = ll.getLatitude(); wLon = ll.getLongitude(); }
        } catch (Throwable ignored) { }
        loadApps();
        // Conectar al ELM327 automáticamente al arrancar el launcher (sin tener que abrir Diagnóstico).
        try { getObdRepo().connect(null); } catch (Throwable ignored) {}

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        IntentFilter t = new IntentFilter(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, t);
        try {
            registerReceiver(usbReceiver, new IntentFilter("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
        } catch (Throwable ignored) { }
        nowPlaying = new NowPlaying(this, new NowPlaying.Listener() {
            public void onTrack(NowPlaying.Track t) { showTrack(t); }
        });
        try { nowPlaying.start(); } catch (Throwable ignored) { }
        NotifArt.listener = new NotifArt.Listener() {
            public void onAaNotification(final Bitmap art, final java.util.List<String> texts) {
                h.post(new Runnable() { public void run() { applyAaNotif(art, texts); } });
            }
        };
        if (SDK >= 21) {
            try {
                mediaWatcher = new MediaWatcher(this, new NowPlaying.Listener() {
                    public void onTrack(final NowPlaying.Track t) {
                        h.post(new Runnable() { public void run() { showTrack(t); } });
                    }
                });
                mediaWatcher.start();
            } catch (Throwable e) { mediaWatcher = null; }
        }
        bt = new BtBridge(this, new BtBridge.Listener() {
            public void onBtState(NowPlaying.Track t) { showTrack(t); }
        });
        clwBt = BtBridge.available(this);
        bt.onPhoneStarted = new Runnable() { public void run() { onPhoneStartedBt(); } };
        bt.onLost = new Runnable() { public void run() { btRouted = false; } };
        h.postDelayed(btTicker, 3000);
        try {
            IntentFilter bf = new IntentFilter("android.bluetooth.device.action.ACL_CONNECTED");
            bf.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            registerReceiver(btConnReceiver, bf);
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── audio Bluetooth ─────────────────────────

    private boolean clwBt;
    /** true cuando «Audio Bluetooth» fue la última app de fuente al frente (el sistema le dio el sonido). */
    private boolean btRouted;
    private long lastBtConnect, lastBtOpen;
    /** Apps de fuente de fábrica: si pasan al frente, el sonido deja de ser Bluetooth. */
    static final String[] OTHER_SOURCES = {"com.clw.fmam", "com.clw.aux", "com.clw.dvd", "com.clw.video",
        "com.autochips.dvdplayer", "com.autochips.ipodplayer", "com.android.music", "com.clw.music", "com.clw.media"};

    /** Pantallas Clinkway: Ford Launcher pasa el sonido a Bluetooth cuando el celular empieza a sonar. */
    private boolean btAutoOn() {
        return clwBt && prefs.getBoolean("bt_auto3", false);
    }

    private final long startedAt = SystemClock.elapsedRealtime();

    /** Paquete de la app que está al frente (Android 4.x; en versiones nuevas puede venir vacío). */
    private String topPackage() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t != null && !t.isEmpty() && t.get(0).topActivity != null) return t.get(0).topActivity.getPackageName();
        } catch (Throwable ignored) { }
        return null;
    }

    /** Sigue qué app de fuente estuvo al frente por última vez. */
    private void trackSource() {
        String top = topPackage();
        if (top == null) return;
        if (top.equals(BtBridge.PKG)) btRouted = true;
        else for (String p : OTHER_SOURCES) if (p.equals(top)) { if (btRouted) BtBridge.log("otra fuente al frente: " + top); btRouted = false; }
    }

    /** El celular empezó a sonar por Bluetooth. */
    private void onPhoneStartedBt() {
        if (!btAutoOn()) return;
        if (SystemClock.elapsedRealtime() - startedAt < 60000) { BtBridge.log("no se cambió: Ford Launcher acaba de arrancar"); return; }
        String top = topPackage();
        if (top != null && (top.contains("btphone") || top.contains("dialer") || top.contains("incallui"))) { BtBridge.log("no se cambió: llamada en curso"); return; }
        trackSource();
        if (btRouted) { BtBridge.log("el sonido ya es Bluetooth"); return; }
        openBtAudioApp(false);
    }

    /** Otras pantallas: al conectar el celular, abre un momento la app de música Bluetooth y vuelve. */
    private android.database.ContentObserver brightnessObserver;
    private int lastBrightness = -1;

    private ImageView carImageView;
    private LinearLayout carPlaceholderLayout;
    private FrameLayout carCardContainer;

    private void refreshCarPhotoInCard(FrameLayout card) {
        if (carImageView != null) {
            try { card.removeView(carImageView); } catch (Throwable ignored) {}
            carImageView = null;
        }
        if (carPlaceholderLayout != null) {
            try { card.removeView(carPlaceholderLayout); } catch (Throwable ignored) {}
            carPlaceholderLayout = null;
        }

        Bitmap photo = loadWallpaperForCurrentMode();
        if (photo != null) {
            carImageView = new ImageView(this);
            carImageView.setImageBitmap(photo);
            carImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            place(card, carImageView, 18, 78, 264, 160);
            carImageView.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    boolean curNight = prefs.getBoolean("headlight_night_active", false);
                    photoMenu(!curNight);
                    return true;
                }
            });
        } else {
            carPlaceholderLayout = new LinearLayout(this);
            carPlaceholderLayout.setOrientation(LinearLayout.VERTICAL);
            carPlaceholderLayout.setGravity(Gravity.CENTER);
            carPlaceholderLayout.setBackground(round(INNER, 16));
            ImageView ic = icon("directions_car", 72);
            ic.setAlpha(0.35f);
            carPlaceholderLayout.addView(ic, new LinearLayout.LayoutParams((int) (72 * s), (int) (72 * s)));
            TextView t = tv("Toca para poner la foto de tu vehículo", 12, MUTED, false);
            t.setPadding(0, (int) (6 * s), 0, 0);
            carPlaceholderLayout.addView(t);
            place(card, carPlaceholderLayout, 18, 82, 264, 152);
            carPlaceholderLayout.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean curNight = prefs.getBoolean("headlight_night_active", false);
                    pickPhoto(!curNight);
                }
            });
        }
    }

    private void checkBrightnessAndSwitch() {
        if (!prefs.getBoolean("auto_headlight_theme", true)) return;
        try {
            int brightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            if (brightness < 0) return;
            if (brightness == lastBrightness) return;
            lastBrightness = brightness;

            boolean isNight = brightness >= 25;
            boolean wasNight = prefs.getBoolean("headlight_night_active", false);

            if (isNight != wasNight) {
                prefs.edit().putBoolean("headlight_night_active", isNight).apply();
                if (carCardContainer != null) {
                    refreshCarPhotoInCard(carCardContainer);
                }
            }
        } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver headlightReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if ("com.zjinnova.zlink.action.OUT_DARK_STOP".equals(action) || "autochips.intent.action.HEADLIGHT_ON".equals(action)) {
                prefs.edit().putBoolean("headlight_night_active", true).apply();
                if (carCardContainer != null) {
                    refreshCarPhotoInCard(carCardContainer);
                }
            } else if ("com.zjinnova.zlink.action.OUT_DARK_START".equals(action) || "autochips.intent.action.HEADLIGHT_OFF".equals(action)) {
                prefs.edit().putBoolean("headlight_night_active", false).apply();
                if (carCardContainer != null) {
                    refreshCarPhotoInCard(carCardContainer);
                }
            } else if ("android.intent.action.CONFIGURATION_CHANGED".equals(action)) {
                checkBrightnessAndSwitch();
            }
        }
    };

    /** Otras pantallas: al conectar el celular, abre un momento la app de música Bluetooth y vuelve. */
    private final BroadcastReceiver btConnReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a != null && a.contains("CONNECTION_STATE_CHANGED")) {
                int st = -1;
                try { st = i.getIntExtra("android.bluetooth.profile.extra.STATE", -1); } catch (Throwable ignored) { }
                if (st != 2) return; // 2 = conectado
            }
            BtBridge.log("celular conectado por Bluetooth");
            if (clwBt) { if (bt != null && btAutoOn()) bt.connect(); return; }
            if (!prefs.getBoolean("bt_open_connect", false)) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastBtConnect < 20000) return;
            lastBtConnect = now;
            h.postDelayed(new Runnable() { public void run() { openBtAudioApp(false); } }, 4000);
        }
    };

    /** App de música Bluetooth de esta pantalla (la elegida o la que se detecta por el nombre). */
    private ComponentName btMusicApp() {
        String c = prefs.getString("btaudio", null);
        if (c != null) return ComponentName.unflattenFromString(c);
        ResolveInfo r = find("audio bluetooth|música bluetooth|musica bluetooth|bt music|bluetooth music|música bt|musica bt|a2dp",
                "btmusic|bt.music|bt_music|btaudio|a2dp|bluetoothmusic|music.bt");
        return r == null ? null : new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
    }

    /** Abre la app de música Bluetooth un instante (para que tome el sonido) y regresa a donde estaba el usuario. */
    private void openBtAudioApp(boolean manual) {
        final String top = topPackage();
        if (!manual) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBtOpen < 15000) return;
            if (top != null && (top.contains("backcar") || top.contains("avin") || top.contains("camera") || top.contains("dvr"))) {
                BtBridge.log("no se abrió la app BT: cámara de reversa al frente"); return;
            }
            if (top == null && !hasWindowFocus()) { BtBridge.log("no se abrió la app BT: no sé qué app está al frente"); return; }
            if (settingsView != null && hasWindowFocus()) { BtBridge.log("no se abrió la app BT: ajustes abiertos"); return; }
            lastBtOpen = now;
        }
        // tarea a la que se vuelve después (otra app, por ejemplo Waze), o Ford Launcher
        int backTask = -1;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (top != null && !top.equals(getPackageName()) && !top.equals(BtBridge.PKG) && t != null && !t.isEmpty()) backTask = t.get(0).id;
        } catch (Throwable ignored) { }
        final int back = backTask;
        ComponentName cn = btMusicApp();
        if (cn == null) {
            if (manual) { toast("Elige primero la app de música Bluetooth"); pickFor("btaudio", "Música Bluetooth"); }
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            btRouted = true;
            BtBridge.activations++;
            BtBridge.log("abierta " + cn.getPackageName() + " para tomar el sonido" + (back >= 0 ? " (luego vuelve a " + top + ")" : ""));
        } catch (Throwable e) {
            if (manual) toast("No se pudo abrir la app");
            return;
        }
        h.postDelayed(new Runnable() { public void run() {
            // si el usuario ya cambió de app, no lo movemos
            String now = topPackage();
            if (now != null && !now.equals(BtBridge.PKG) && !now.equals(btMusicApp() != null ? btMusicApp().getPackageName() : "")) {
                BtBridge.log("no se volvió: el usuario abrió " + now); return;
            }
            try {
                if (back >= 0) {
                    ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).moveTaskToFront(back, 0);
                    return;
                }
            } catch (Throwable ignored) { }
            try {
                startActivity(new Intent(Main.this, Main.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Throwable ignored) { }
        }}, prefs.getInt("bt_return_ms", clwBt ? 2000 : 2500));
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
        try { nowPlaying.stop(); } catch (Throwable ignored) { }
        try { unregisterReceiver(usbReceiver); } catch (Throwable ignored) { }
        try { bt.disconnect(); } catch (Throwable ignored) { }
        try { unregisterReceiver(btConnReceiver); } catch (Throwable ignored) { }
        try { if (phonePrivacy != null) phonePrivacy.close(); } catch (Throwable ignored) { }
        try { if (callWin != null) callWin.hide(); } catch (Throwable ignored) { }
        try { unregisterReceiver(headlightReceiver); } catch (Throwable ignored) { }
        if (brightnessObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(brightnessObserver);
            } catch (Throwable ignored) {}
        }
        h.removeCallbacks(btTicker);
        try { if (mediaWatcher != null) mediaWatcher.stop(); } catch (Throwable ignored) { }
        try { closePrefTts(); } catch (Throwable ignored) {}
        try { if (workshop != null) workshop.onDestroy(); } catch (Throwable ignored) {}
        workshop = null;
        try { if (parked != null) parked.onDestroy(); } catch (Throwable ignored) { }
        try { if (anomalies != null) anomalies.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBrightnessAndSwitch();
        immersive();
        if (SDK >= 23 && !askedPerms) {
            askedPerms = true;
            try { Modern.ensurePermissions(this, 77); } catch (Throwable ignored) { }
        }
        if (mediaWatcher != null) mediaWatcher.start();
        ensureMediaAccess();
        guardUsb();
        maybeWeather();
        h.postDelayed(new Runnable() { public void run() { autoChecks(); } }, 4000);
        updateClock();
        refreshStates();
        startGps();
        h.postDelayed(ticker, 1000);
        h.removeCallbacks(progressTick);
        h.post(progressTick);
        applyAaNotif(NotifArt.lastArt, NotifArt.lastTexts);
        try { if (parked != null) parked.onResume(); } catch (Throwable ignored) {}
    }

    /** Android 6+: respuesta a la solicitud de permisos. */
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 99) {
            try { if (phone != null && phoneView != null) phone.refresh(); } catch (Throwable ignored) {}
            return;
        }
        if (req == 100) {
            boolean ok = false;
            if (res != null) for (int r : res) if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) ok = true;
            if (res == null || res.length == 0) ok = false;
            try { if (workshop != null && workshopView != null) workshop.onMicPerm(ok); } catch (Throwable ignored) {}
            return;
        }
        if (req != 77) return;
        stopGps();
        startGps();
        seedLastLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGps();
        h.removeCallbacks(ticker);
        h.removeCallbacks(progressTick);
        try { if (parked != null) parked.onPause(); } catch (Throwable ignored) {}
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // Modo estacionado: un toque lo quita y consume el evento; si no, reinicia el temporizador.
        try {
            if (parked != null && (parked.isShowing() || parked.isEnabled())) {
                if (parked.dispatchTouch(ev)) return true;
            }
        } catch (Throwable ignored) {}
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (settingsView != null) { closeSettings(); return; }
        closeDrawer(); // es la pantalla de inicio: atrás no la cierra
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN | 0x00001000 /* IMMERSIVE_STICKY, API 19 */);
        } catch (Exception ignored) { }
    }

    /** Al conectar algo por USB: si aún no se eligió cómo conectar el celular y hay varias opciones, se pregunta. */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (prefs.getString("conn_app", null) == null && prefs.getString("aa", null) == null && connCandidates().size() > 1 && hasWindowFocus())
                chooseConnection(null);
            else guardUsb();
        }
    };

    private int btPoll, usbTick, themeTick;
    private boolean usbWarned;

    /** Apps de espejo de fábrica que se adelantan a Open Headunit al conectar el celular por USB. */
    static final String[] USB_HOGS = {"net.easyconn", "com.carbit", "net.easyconn.carman"};
    static final java.util.HashSet<String> USB_HOG_SET = new java.util.HashSet<String>(java.util.Arrays.asList(USB_HOGS));

    private boolean processRunning(String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
                if (p.processName != null && (p.processName.equals(pkg) || p.processName.startsWith(pkg + ":"))) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Si EasyConnection quedó corriendo, la cierra; si vuelve, pide al usuario «Forzar detención». */
    private void guardUsb() {
        // Solo se vigila cuando el usuario eligió OTRA app para conectar el celular.
        String chosen = prefs.getString("conn_app", null);
        if (chosen == null && prefs.getString("aa", null) != null) {
            try { chosen = ComponentName.unflattenFromString(prefs.getString("aa", "")).getPackageName(); } catch (Throwable ignored) { }
        }
        if (chosen == null || USB_HOG_SET.contains(chosen) || prefs.getBoolean("allow_easyconn", false)) return;
        final android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (final String pkg : USB_HOGS) {
            if (!installed(pkg) || !processRunning(pkg)) continue;
            // Silencioso: con los datos de EasyConnection borrados, Android Auto funciona aunque siga corriendo.
            try { am.killBackgroundProcesses(pkg); } catch (Throwable ignored) { }
        }
    }

    /** Bluetooth: sigue revisando aunque Ford Launcher esté detrás de otra app (Waze, Maps…). */
    private final Runnable btTicker = new Runnable() {
        public void run() {
            if (bt != null) {
                bt.setAutoCreate(btAutoOn());
                bt.connect();
                if (btAutoOn()) trackSource();
                if (bt.isConnected()) bt.poll(btAutoOn());
            }
            h.postDelayed(this, 2000);
        }
    };

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (lastFix > 0 && SystemClock.elapsedRealtime() - lastFix > 6000) setGps(false, 0);
            if (++usbTick % 5 == 0) guardUsb();
            if (bt != null && bt.isConnected() && (++btPoll % 3 == 0 || curTitle == null)) bt.refresh();
            updatePlayIcon();
            if (++themeTick % 30 == 0) {
                boolean was = LIGHT;
                applyTheme();
                if (was != LIGHT) { applyAccent(); rebuild(); }
                maybeWeather();
            }
            h.postDelayed(this, 2000);
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            loadApps();
            refreshStates();
        }
    };

    // ───────────────────────── construcción de la UI ─────────────────────────

    private void build(int W, int H) {
        s = Math.min(W / 1024f, H / 600f);
        root.removeAllViews();
        root.setBackgroundColor(BG);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        board = new FrameLayout(this);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams((int) (1024 * s), (int) (600 * s));
        blp.gravity = Gravity.CENTER;
        root.addView(board, blp);

        // franja del dock (60dp, letras 12sp para que se vean mejor)
        View dockBg = new View(this);
        dockBg.setBackgroundColor(DOCK);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (H - ((H - 600 * s) / 2 + 540 * s)) + 1);
        dlp.gravity = Gravity.BOTTOM;
        root.addView(dockBg, 0, dlp);

        buildTop();
        buildCarCard();
        buildAaCard();
        buildAudioCard();
        buildTelemetryBar();
        buildDock();
        buildFloatingAi();

        try { if (parked != null) parked.attach(root); } catch (Throwable ignored) {}

        drawer = null;
        settingsView = null;
        updateClock();
        refreshStates();
        showWeather();
        if (settingsCat >= 0) showSettings(settingsCat);
    }

    // ─────────────────── Botón flotante del copiloto IA (arrastrable) ───────────────────

    private FrameLayout fabAi;
    private com.fordlauncher.launcher.ai.AiFabAnimator fabAnim;
    private com.fordlauncher.launcher.obd.VoiceInputManager fabVoice;
    private boolean fabSpeechListening;
    private boolean fabMicPending;

    private void buildFloatingAi() {
        int box = (int) (74 * s);
        int core = (int) (54 * s);
        int spark = (int) (30 * s);
        fabAi = new FrameLayout(this);

        View glow = new View(this);
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientRadius(box / 2f);
        halo.setColors(new int[]{0xB34285F4, 0x009B51E0});
        glow.setBackground(halo);
        fabAi.addView(glow, new FrameLayout.LayoutParams(box, box));

        View wave = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke((int) (2 * s), 0xFF9B51E0);
        wave.setBackground(ring);
        wave.setAlpha(0f);
        wave.setVisibility(View.INVISIBLE);
        fabAi.addView(wave, new FrameLayout.LayoutParams(core, core, Gravity.CENTER));

        FrameLayout coreView = new FrameLayout(this);
        coreView.setBackground(getDrawableOrNull("bg_floating_ai"));
        if (coreView.getBackground() == null) {
            GradientDrawable fallback = new GradientDrawable();
            fallback.setShape(GradientDrawable.OVAL);
            fallback.setColor(0xF21A1C1E);
            coreView.setBackground(fallback);
        }
        fabAi.addView(coreView, new FrameLayout.LayoutParams(core, core, Gravity.CENTER));

        ImageView sparkle = new ImageView(this);
        sparkle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable sparkleDrawable = getDrawableOrNull("ic_gemini_sparkle");
        if (sparkleDrawable != null) {
            sparkle.setImageDrawable(sparkleDrawable);
        } else {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF4285F4);
            sparkle.setImageDrawable(dot);
        }
        coreView.addView(sparkle, new FrameLayout.LayoutParams(spark, spark, Gravity.CENTER));

        fabAnim = new com.fordlauncher.launcher.ai.AiFabAnimator(fabAi, glow, wave, sparkle);
        com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(this).addListener(
            new com.fordlauncher.launcher.ai.TextToSpeechManager.Listener() {
                public void onSpeakingChanged(boolean speaking) {
                    if (fabAnim == null) return;
                    if (speaking) fabAnim.speaking();
                    else if (fabAnim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.SPEAKING) fabAnim.idle();
                }
                public void onError(String msg) {
                    if (fabAnim != null && fabAnim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.SPEAKING) fabAnim.idle();
                }
            });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(box, box);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.rightMargin = (int) (6 * s);
        lp.bottomMargin = (int) (62 * s);
        root.addView(fabAi, lp);
        fabAi.setOnTouchListener(new View.OnTouchListener() {
            private float iX, iY, tX, tY;
            private boolean dragging;
            public boolean onTouch(final View v, android.view.MotionEvent e) {
                int act = e.getAction();
                if (act == android.view.MotionEvent.ACTION_DOWN) {
                    iX = v.getX(); iY = v.getY(); tX = e.getRawX(); tY = e.getRawY();
                    dragging = false;
                    return true;
                }
                if (act == android.view.MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - tX, dy = e.getRawY() - tY;
                    if (Math.hypot(dx, dy) > 10f) {
                        dragging = true;
                        v.setX(iX + dx);
                        v.setY(iY + dy);
                    }
                    return true;
                }
                if (act == android.view.MotionEvent.ACTION_UP) {
                    if (!dragging) onFabTap();
                    else try { prefs.edit().putFloat("ai_fab_x", v.getX()).putFloat("ai_fab_y", v.getY()).apply(); } catch (Throwable ignored) {}
                    return true;
                }
                return false;
            }
        });
        refreshFloatingAi();
    }

    private void refreshFloatingAi() {
        if (fabAi == null) return;
        boolean en = true;
        try { en = prefs.getBoolean("pref_enable_floating_ai_button", true); } catch (Throwable ignored) {}
        fabAi.setVisibility(en ? View.VISIBLE : View.GONE);
        if (fabAnim != null) {
            if (!en) fabAnim.stop();
            else if (fabAnim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.OFF) fabAnim.idle();
        }
        if (en) {
            float x = -1f, y = -1f;
            try { x = prefs.getFloat("ai_fab_x", -1f); y = prefs.getFloat("ai_fab_y", -1f); } catch (Throwable ignored) {}
            if (x != -1f && y != -1f) {
                fabAi.setX(x);
                fabAi.setY(y);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fabAi.getLayoutParams();
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                fabAi.setLayoutParams(lp);
            } else {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fabAi.getLayoutParams();
                lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                fabAi.setLayoutParams(lp);
            }
        }
        // Disparadores proactivos de anomalías: solo con el botón flotante de IA activo.
        try {
            if (en) {
                if (anomalies == null) anomalies = new com.fordlauncher.launcher.obd.SensorAnomalyTriggerManager(this, getObdRepo());
                anomalies.start();
            } else if (anomalies != null) {
                anomalies.stop();
            }
        } catch (Throwable ignored) {}
    }

    private void onFabTap() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission("android.permission.RECORD_AUDIO") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                fabMicPending = true;
                try { requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 101); } catch (Throwable ignored) {}
                return;
            }
        }
        startFabListening();
    }

    public void onFabMicPerm(boolean granted) {
        if (!fabMicPending) return;
        fabMicPending = false;
        if (granted) startFabListening();
        else toast("Necesito el permiso de micrófono para hablarle a la IA");
    }

    private void startFabListening() {
        if (fabVoice == null) fabVoice = new com.fordlauncher.launcher.obd.VoiceInputManager(this);
        fabVoice.setOnBeginningOfSpeech(new Runnable() {
            public void run() { if (fabAnim != null) fabAnim.listening(); }
        });
        fabVoice.startListening(new com.fordlauncher.launcher.obd.VoiceInputManager.VoiceCallback() {
            public void onQueryReady(String text) { fabAskAi(text); }
            public void onError(String msg) {
                stopFabPulse();
                toast(msg);
            }
        });
        if (fabAnim != null) fabAnim.waiting();
    }

    private void stopFabPulse() {
        if (fabAnim != null && fabAnim.state() != com.fordlauncher.launcher.ai.AiFabAnimator.OFF) fabAnim.idle();
    }

    private void fabAskAi(final String pregunta) {
        if (pregunta == null || pregunta.trim().length() == 0) { stopFabPulse(); return; }
        // Validación local: si pregunta por el vehículo y el escáner está desconectado, no se consulta a Gemini.
        boolean conectado = false;
        try { conectado = getObdRepo() != null && getObdRepo().isConnected(); } catch (Throwable ignored) {}
        if (com.fordlauncher.launcher.obd.VehicleIntent.requiereVehiculo(pregunta) && !conectado) {
            final String aviso = "Sergio, el adaptador OBD2 no está conectado. Por favor conéctalo para poder leer los sensores del Fiesta.";
            com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(Main.this).hablarTextoLargo(aviso);
            stopFabPulse();
            showFabTextBubble(aviso);
            return;
        }
        final String task = "El conductor pregunta por voz: \"" + pregunta.trim() + "\"";
        String snap = fabSnapshot();
        toast("Consultando…");
        if (fabAnim != null) fabAnim.waiting();
        com.fordlauncher.launcher.obd.GeminiDiagnosticClient.analyze(this, snap, task, false, new com.fordlauncher.launcher.obd.GeminiDiagnosticClient.Callback() {
            public void onDone(final String text, final String error) {
                h.post(new Runnable() {
                    public void run() {
                        if (error != null || text == null) {
                            stopFabPulse();
                            toast(error != null ? error : "Sin respuesta de la IA");
                            return;
                        }
                        com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(Main.this).hablarTextoLargo(text);
                        if (fabAnim != null && fabAnim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.WAITING) fabAnim.idle();
                        showFabTextBubble(text);
                        refreshFloatingAi();
                    }
                });
            }
        });
    }

    private Drawable getDrawableOrNull(String name) {
        try {
            int id = getResources().getIdentifier(name, "drawable", getPackageName());
            return id == 0 ? null : getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private String fabSnapshot() {
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean conectado = false;
            try { conectado = getObdRepo() != null && getObdRepo().isConnected(); } catch (Throwable ignored) {}
            sb.append("\"obd_conectado\":").append(conectado);
            java.util.Map<String, Float> v = getObdRepo() != null ? getObdRepo().getValues() : null;
            if (v != null) {
                String[] keys = {com.fordlauncher.launcher.obd.Pids.RPM, com.fordlauncher.launcher.obd.Pids.COOLANT, com.fordlauncher.launcher.obd.Pids.SPEED, com.fordlauncher.launcher.obd.Pids.FUEL_RATE, com.fordlauncher.launcher.obd.Pids.VOLTAGE, com.fordlauncher.launcher.obd.Pids.FUEL};
                for (String k : keys) {
                    Float x = v.get(k);
                    if (x == null) continue;
                    sb.append(",\"").append(k).append("\":").append(x.floatValue());
                }
            }
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) { return "{\"obd_conectado\":false}"; }
    }

    /** Burbuja pequeña con la respuesta de la IA: texto oculto + botón "👁 Mostrar texto". */
    private void showFabTextBubble(final String text) {
        try {
            if (fabBubble != null && fabBubble.getParent() == root) root.removeView(fabBubble);
        } catch (Throwable ignored) {}
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 12));
        card.setPadding((int) (12 * s), (int) (10 * s), (int) (12 * s), (int) (10 * s));
        TextView r = tv(text, 12, TEXT, false);
        r.setVisibility(View.GONE); // oculto: se lee en voz alta
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        TextView tg = tv("👁 Mostrar texto", 11, MUTED, true);
        tg.setGravity(Gravity.RIGHT);
        tg.setPadding(0, (int) (2 * s), 0, 0);
        tg.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean show = r.getVisibility() != View.VISIBLE;
                r.setVisibility(show ? View.VISIBLE : View.GONE);
                tg.setText(show ? "👁 Ocultar texto" : "👁 Mostrar texto");
            }
        });
        card.addView(tg, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (300 * s), -2);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        float bx = 12 * s, by = Math.max(12 * s, (fabAi != null ? fabAi.getY() - 140 * s : 100 * s));
        lp.leftMargin = (int) bx;
        lp.topMargin = (int) by;
        root.addView(card, lp);
        card.bringToFront();
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                try { if (v.getParent() == root) root.removeView(v); } catch (Throwable ignored) {}
                return true;
            }
        });
        fabBubble = card;
    }

    private LinearLayout fabBubble;

    private void buildTop() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        timeTv = tv("", 30, TEXT, true);
        dateTv = tv("", 14, MUTED, false);
        dateTv.setPadding((int) (12 * s), 0, 0, 0);
        row.addView(timeTv);
        row.addView(dateTv);
        placeWrap(board, row, 24, 10);

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        weatherBox = new LinearLayout(this);
        weatherBox.setGravity(Gravity.CENTER_VERTICAL);
        weatherIc = icon("cloud", 20);
        weatherBox.addView(weatherIc, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
        weatherTv = tv("", 15, TEXT, true);
        weatherTv.setPadding((int) (6 * s), 0, 0, 0);
        weatherBox.addView(weatherTv);
        weatherCityTv = tv("", 13, MUTED, false);
        weatherCityTv.setPadding((int) (6 * s), 0, (int) (14 * s), 0);
        weatherBox.addView(weatherCityTv);
        View sep = new View(this);
        sep.setBackgroundColor(BTN);
        weatherBox.addView(sep, new LinearLayout.LayoutParams(Math.max(1, (int) s), (int) (18 * s)));
        weatherBox.setVisibility(View.GONE);
        right.addView(weatherBox);
        greetTv = tv("", 15, SOFT, false);
        greetTv.setPadding((int) (14 * s), 0, 0, 0);
        right.addView(greetTv);
        place(board, right, 400, 14, 552, 32);
        View gear = circleIcon("settings", 40, 22, CIRCLE);
        place(board, gear, 964, 12, 40, 40);
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
        greetTv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
    }

    private Bitmap loadWallpaperForCurrentMode() {
        boolean isNight = prefs.getBoolean("headlight_night_active", false);
        String pathKey = isNight ? "wallpaper_night_path" : "wallpaper_day_path";
        String path = prefs.getString(pathKey, "");
        Bitmap photo = null;
        if (!path.isEmpty()) {
            try {
                File f = new File(path);
                if (f.exists()) photo = BitmapFactory.decodeFile(f.getAbsolutePath());
            } catch (Throwable ignored) {}
        }
        if (photo == null) {
            String altKey = isNight ? "wallpaper_day_path" : "wallpaper_night_path";
            String altPath = prefs.getString(altKey, "");
            if (!altPath.isEmpty()) {
                try {
                    File f = new File(altPath);
                    if (f.exists()) photo = BitmapFactory.decodeFile(f.getAbsolutePath());
                } catch (Throwable ignored) {}
            }
        }
        if (photo == null) {
            photo = CarPhoto.load(this);
        }
        return photo;
    }

    private void buildCarCard() {
        FrameLayout card = card(10, 64, 308, 372, CARD);
        carCardContainer = card;
        carTv = tv(prefs.getString("car_name", "Mi vehículo"), 20, TEXT, true);
        place(card, carTv, 20, 16, 260, 26);
        TextView chip = tv(prefs.getString("car_tag", ""), 11, Color.WHITE, true);
        chipTv = chip;
        chip.setPadding((int) (8 * s), (int) (3 * s), (int) (8 * s), (int) (3 * s));
        chip.setBackground(round(RED, 8));
        chip.setVisibility(prefs.getString("car_tag", "").trim().length() == 0 ? View.GONE : View.VISIBLE);
        placeWrap(card, chip, 20, 46);

        refreshCarPhotoInCard(card);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(INNER, 16));
        place(card, box, 12, 250, 276, 118);

        LinearLayout lbl = new LinearLayout(this);
        lbl.setGravity(Gravity.CENTER_VERTICAL);
        ImageView sp = icon("speed", 16);
        sp.setAlpha(0.6f);
        lbl.addView(sp, new LinearLayout.LayoutParams((int) (16 * s), (int) (16 * s)));
        TextView vl = tv("Velocidad", 13, MUTED, false);
        vl.setPadding((int) (6 * s), 0, 0, 0);
        lbl.addView(vl);
        placeWrap(box, lbl, 16, 12);

        LinearLayout g = new LinearLayout(this);
        g.setGravity(Gravity.CENTER_VERTICAL);
        gpsDot = new View(this);
        gpsDot.setBackground(round(MUTED, 4));
        g.addView(gpsDot, new LinearLayout.LayoutParams((int) (8 * s), (int) (8 * s)));
        gpsTv = tv("GPS", 11, MUTED, false);
        gpsTv.setPadding((int) (6 * s), 0, 0, 0);
        g.addView(gpsTv);
        g.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place(box, g, 120, 12, 140, 18);

        LinearLayout sv = new LinearLayout(this);
        sv.setBaselineAligned(true);
        speedTv = tv("0", 56, TEXT, true);
        TextView kmh = tv("km/h", 15, MUTED, false);
        kmh.setPadding((int) (8 * s), 0, 0, 0);
        sv.addView(speedTv);
        sv.addView(kmh);
        placeWrap(box, sv, 16, 34);
        setGps(false, 0);
    }

    private void buildAaCard() {
        FrameLayout card = new FrameLayout(this);
        place(board, card, 324, 64, 431, 372);
        // fondo decorativo mientras no hay GPS; encima, el mapa en vivo
        card.addView(new MapView(this, s, 20 * s), new FrameLayout.LayoutParams(-1, -1));
        liveMap = new LiveMapView(this, s, 20 * s);
        card.addView(liveMap, new FrameLayout.LayoutParams(-1, -1));
        liveMap.setVisibility(View.INVISIBLE);
        liveMap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { liveMap.cycleZoom(); }
        });
        liveMap.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(Main.this, dlg())
                    .setTitle("Diagnóstico del mapa").setMessage(liveMap.diag())
                    .setPositiveButton("OK", null).show();
                return true;
            }
        });
        seedLastLocation();

        LinearLayout tag = new LinearLayout(this);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.setBackground(round((CARD & 0x00FFFFFF) | 0xE0000000, 12));
        tag.setPadding((int) (12 * s), (int) (7 * s), (int) (12 * s), (int) (7 * s));
        tag.addView(icon("my_location", 16), new LinearLayout.LayoutParams((int) (16 * s), (int) (16 * s)));
        mapTagTv = tv("Buscando GPS…", 13, TEXT, true);
        mapTagTv.setPadding((int) (8 * s), 0, 0, 0);
        tag.addView(mapTagTv);
        placeWrap(card, tag, 16, 16);

        // Sin panel flotante: el mapa a pantalla completa, los botones van en la barra inferior.
        seedLastLocation();
        updateMapTag();
    }

    /** Botón de proyección: ícono + título + subtítulo (el subtítulo se guarda en getTag()). */
    private View projButton(String ic, String title, int color, boolean filled) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(round(color, 14));
        b.setPadding((int) (12 * s), 0, (int) (8 * s), 0);
        ImageView pic = icon(ic, 26);
        if (filled) pic.clearColorFilter();
        b.addView(pic, new LinearLayout.LayoutParams((int) (26 * s), (int) (26 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (10 * s), 0, 0, 0);
        col.addView(tv(title, 15, filled ? Color.WHITE : TEXT, true));
        TextView sub = tv("", 11, filled ? 0xD9FFFFFF : MUTED, false);
        sub.setPadding(0, (int) (3 * s), 0, 0);
        col.addView(sub);
        b.addView(col, new LinearLayout.LayoutParams(-1, -2));
        b.setTag(sub);
        return b;
    }

    /** Igual que projButton pero con un drawable de res/drawable (icono oficial). */
    private View projButtonRes(int resId, String title, int color, boolean filled) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(round(color, 14));
        b.setPadding((int) (12 * s), 0, (int) (8 * s), 0);
        ImageView pic = new ImageView(this);
        try { pic.setImageDrawable(getResources().getDrawable(resId)); } catch (Throwable t) { pic.setImageResource(resId); }
        if (filled) pic.clearColorFilter();
        b.addView(pic, new LinearLayout.LayoutParams((int) (26 * s), (int) (26 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (10 * s), 0, 0, 0);
        col.addView(tv(title, 15, filled ? Color.WHITE : TEXT, true));
        TextView sub = tv("", 11, filled ? 0xD9FFFFFF : MUTED, false);
        sub.setPadding(0, (int) (3 * s), 0, 0);
        col.addView(sub);
        b.addView(col, new LinearLayout.LayoutParams(-1, -2));
        b.setTag(sub);
        return b;
    }

    // ── mapa en vivo ──

    private void seedLastLocation() {
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "passive"}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) { }
        }
        if (best != null) feedMap(best);
    }

    private void feedMap(Location l) {
        if (l != null) { wLat = l.getLatitude(); wLon = l.getLongitude(); }
        if (liveMap == null || l == null) return;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        parkGpsKmh = kmh;
        liveMap.setPosition(l.getLatitude(), l.getLongitude(), l.hasBearing() && kmh > 4 ? l.getBearing() : Float.NaN, kmh);
        liveMap.setVisibility(View.VISIBLE);
        updateMapTag();
    }
    private void updateMapTag() {
        if (mapTagTv == null) return;
        boolean live = lastFix > 0;
        if (liveMap == null || !liveMap.hasFix()) mapTagTv.setText("Buscando GPS…");
        else if (liveMap.offline()) mapTagTv.setText(live ? "En vivo · sin internet" : "Última posición · sin internet");
        else mapTagTv.setText(live ? "Mapa en vivo" : "Última posición conocida");
    }

    private void buildAudioCard() {
        FrameLayout card = card(761, 64, 253, 372, CARD);
        placeWrap(card, tv("AUDIO", 11, MUTED, true), 18, 14);
        sourceTv = tv("", 11, RED, true);
        sourceTv.setGravity(Gravity.RIGHT);
        place(card, sourceTv, 80, 14, 150, 16);
        playerArt = new PlayerArt(this, s, asset("ic/music_note.png"));
        place(card, playerArt, 34, 34, 180, 180);
        trackTv = tv("Nada sonando", 17, TEXT, true);
        trackTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, trackTv, 14, 222, 220, 24);
        artistTv = tv("Radio, Bluetooth o USB", 13, MUTED, false);
        artistTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, artistTv, 14, 250, 220, 20);
        timePosTv = tv("", 12, MUTED, false);
        timePosTv.setGravity(Gravity.CENTER_HORIZONTAL);
        place(card, timePosTv, 14, 274, 220, 18);
        View prev = circleIcon("skip_previous", 52, 26, BTN);
        View play = circleIcon("play_arrow", 64, 32, RED);
        playIc = (ImageView) ((FrameLayout) play).getChildAt(0);
        View next = circleIcon("skip_next", 52, 26, BTN);
        place(card, prev, 30, 304, 52, 52);
        place(card, play, 92, 298, 64, 64);
        place(card, next, 166, 304, 52, 52);
        prev.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        play.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        next.setOnClickListener(mediaClick(KeyEvent.KEYCODE_MEDIA_NEXT));
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showPlayerDiag(); return true; }
        });
        renderTrack();
    }

    // ── Barra inferior de telemetría (entre tarjetas y dock) ──

    private void buildTelemetryBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(round(CARD, 16));
        place(board, bar, 10, 442, 1004, 82);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (10 * s), (int) (8 * s), (int) (10 * s), (int) (8 * s));
        bar.addView(row, new FrameLayout.LayoutParams(-1, -1));

        // a) Tacómetro RPM: etiqueta + valor grande + arco de color
        LinearLayout rpm = teleSection(row, 0.95f, "RPM", null);
        rpmText = tv("-- RPM", 22, TEXT, true);
        rpmText.setMaxLines(1);
        rpmText.setEllipsize(TextUtils.TruncateAt.END);
        rpm.addView(rpmText, new LinearLayout.LayoutParams(-1, -2));
        rpmGauge = new RpmGauge();
        LinearLayout.LayoutParams rgp = new LinearLayout.LayoutParams(-1, (int) (14 * s));
        rgp.topMargin = (int) (4 * s);
        rpm.addView(rpmGauge, rgp);

        // b) Temperatura del motor
        LinearLayout temp = teleSection(row, 0.95f, "MOTOR", "thermostat");
        tempText = tv("-- °C", 22, TEXT, true);
        tempText.setMaxLines(1);
        tempText.setEllipsize(TextUtils.TruncateAt.END);
        temp.addView(tempText, new LinearLayout.LayoutParams(-1, -2));
        tempBar = new MiniBar();
        LinearLayout.LayoutParams tbp = new LinearLayout.LayoutParams(-1, (int) (14 * s));
        tbp.topMargin = (int) (4 * s);
        temp.addView(tempBar, tbp);

        // c) Consumo instantáneo
        LinearLayout cons = teleSection(row, 1.0f, "CONSUMO", "speed");
        consText = tv("-- L/h", 22, TEXT, true);
        consText.setMaxLines(1);
        consText.setEllipsize(TextUtils.TruncateAt.END);
        cons.addView(consText, new LinearLayout.LayoutParams(-1, -2));
        consBar = new MiniBar();
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, (int) (14 * s));
        cbp.topMargin = (int) (4 * s);
        cons.addView(consBar, cbp);

        // d) Control de cambio de aceite (tocable)
        final LinearLayout oil = teleSection(row, 1.1f, "ACEITE", "water_drop");
        oilText = tv("Aceite: —", 13, TEXT, true);
        oilText.setMaxLines(1);
        oilText.setEllipsize(TextUtils.TruncateAt.END);
        oil.addView(oilText, new LinearLayout.LayoutParams(-1, -2));
        oilBar = new MiniBar();
        LinearLayout.LayoutParams obp = new LinearLayout.LayoutParams(-1, (int) (14 * s));
        obp.topMargin = (int) (4 * s);
        oil.addView(oilBar, obp);
        oil.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { oilDialog(); }
        });

        // e) Cápsula unificada compacta: iconos AA | CarPlay; el toque abre SOLO CarPlay.
        LinearLayout capsule = new LinearLayout(this);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER);
        capsule.setBackground(round((BTN & 0x00FFFFFF) | 0xD0000000, 14));
        capsule.setPadding((int) (13 * s), 0, (int) (13 * s), 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -1);
        clp.leftMargin = (int) (12 * s);
        row.addView(capsule, clp);
        ImageView aaIc = new ImageView(this);
        try { aaIc.setImageDrawable(getResources().getDrawable(R.drawable.android_auto)); } catch (Throwable t) { aaIc.setImageResource(R.drawable.android_auto); }
        aaIc.setAlpha(0.9f);
        capsule.addView(aaIc, new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s)));
        View sep = new View(this);
        sep.setBackgroundColor(BTN);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Math.max(1, (int) s), (int) (20 * s));
        slp.leftMargin = (int) (8 * s); slp.rightMargin = (int) (8 * s);
        capsule.addView(sep, slp);
        ImageView cpIc = new ImageView(this);
        try { cpIc.setImageDrawable(getResources().getDrawable(R.drawable.carplay)); } catch (Throwable t) { cpIc.setImageResource(R.drawable.carplay); }
        cpIc.setAlpha(0.9f);
        capsule.addView(cpIc, new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s)));
        capsule.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCarPlay(); }
        });
        capsule.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { pickFor("carplay", "CarPlay"); return true; }
        });

        updateTelemetryBar();
    }

    private LinearLayout teleSection(LinearLayout row, float weight, String label, String iconName) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -1, weight);
        if (row.getChildCount() > 0) slp.leftMargin = (int) (6 * s);
        row.addView(sec, slp);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, (int) (3 * s));
        if (iconName != null) {
            ImageView ic = icon(iconName, 14);
            ic.setAlpha(0.6f);
            head.addView(ic, new LinearLayout.LayoutParams((int) (14 * s), (int) (14 * s)));
        }
        TextView lb = tv(label, 10, MUTED, true);
        lb.setPadding((int) (5 * s), 0, 0, 0);
        lb.setMaxLines(1);
        lb.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(lb, new LinearLayout.LayoutParams(-1, -2));
        sec.addView(head, new LinearLayout.LayoutParams(-1, -2));
        return sec;
    }

    private void updateTelemetryBar() {
        if (rpmText == null) return;
        java.util.Map<String, Float> v;
        try { v = getObdRepo().getValues(); } catch (Throwable t) { v = null; }
        Float rpm = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.RPM) : null;
        Float coolant = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.COOLANT) : null;
        Float speed = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.SPEED) : null;
        Float fuelRate = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.FUEL_RATE) : null;
        Float consKmL = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.CONSUMPTION) : null;
        Float maf = v != null ? v.get(com.fordlauncher.launcher.obd.Pids.MAF) : null;

        // Odómetro propio: suma km recorridos con velocidad en el tiempo.
        // Resincronización al reconectar: nunca bajar del valor persistido.
        long nowMs = System.currentTimeMillis();
        if (odoKmTotal < 0f) { odoKmTotal = prefInt("odo_km_total", 0); odoLastSavedKm = odoKmTotal; }
        if (getObdRepo().getState() == com.fordlauncher.launcher.obd.ObdRepository.State.ON) {
            float persisted = prefInt("odo_km_total", 0);
            if (persisted > odoKmTotal) odoKmTotal = persisted; // max(odoCpu, odoGuardado)
        }
        if (speed != null && speed > 0f && telemLastMs > 0) {
            float dtH = (nowMs - telemLastMs) / 3600000f;
            if (prefs.getBoolean("odo_auto", true) && dtH > 0f && dtH < 0.1f) odoKmTotal += speed * dtH;
            if (odoKmTotal - odoLastSavedKm > 0.5f) {
                prefs.edit().putInt("odo_km_total", (int) odoKmTotal).putInt("oil_current_odometer", (int) odoKmTotal).apply();
                odoLastSavedKm = odoKmTotal;
            }
        }
        telemLastMs = nowMs;

        if (rpmGauge != null) rpmGauge.setValue(rpm != null ? rpm : -1f);
        rpmText.setText(rpm != null ? String.format(ES, "%.0f RPM", rpm) : "-- RPM");

        if (tempBar != null) {
            tempBar.setFraction(coolant != null ? (coolant - 40f) / 80f : -1f);
            tempBar.setFillColor(coolant != null ? tempColor(coolant) : tempBar.fill);
        }
        tempText.setText(coolant != null ? String.format(ES, "%.0f °C", coolant) : "-- °C");
        tempText.setTextColor(coolant != null && coolant >= 105f ? 0xFFF44336 : TEXT);

        String consTxt = "-- L/h";
        float consFrac = -1f;
        boolean idleish = rpm != null && rpm > 400f && (speed == null || speed <= 2f);
        if (idleish && maf != null && maf > 0f) {
            float lph = (maf * 3600f) / (14.7f * 745f);
            consTxt = String.format(ES, "%.1f", lph) + " L/h";
            consFrac = lph / 20f;
        } else if (idleish && rpm != null && rpm > 400f) {
            float estLph = (rpm * 1.6f * 0.5f * 60f * 0.85f * 1.2f) / (14.7f * 745f * 1000f);
            consTxt = String.format(ES, "%.1f", estLph) + " L/h";
            consFrac = estLph / 20f;
        } else if (speed != null && speed >= 5f && consKmL != null && consKmL > 0f) {
            float l100 = 100f / consKmL;
            consTxt = String.format(ES, "%.1f L/100km", l100);
            consFrac = l100 / 30f;
        } else if (fuelRate != null && fuelRate > 0f) {
            consTxt = String.format(ES, "%.1f L/h", fuelRate);
            consFrac = fuelRate / 20f;
        } else if (speed != null && speed >= 5f && fuelRate != null) {
            float l100 = fuelRate / speed * 100f;
            consTxt = String.format(ES, "%.1f L/100km", l100);
            consFrac = l100 / 30f;
        }
        consText.setText(consTxt);
        if (consBar != null) {
            consBar.setFraction(consFrac);
            consBar.setFillColor(consFrac >= 0f ? zoneColor(consFrac) : consBar.fill);
        }

        updateOilWidgetUI();
    }

    /** Widget de aceite: sincroniza texto + barra + color (recálculo exacto). */
    private void updateOilWidgetUI() {
        if (oilText == null) return;
        int actual = prefInt("oil_current_odometer", prefInt("odo_km_total", 0));
        int cambio = prefInt("oil_last_service_odometer", 0);
        int intervalo = prefInt("oil_change_interval_km", 8000);
        int kmPasados = Math.max(0, actual - cambio);
        int kmRestantes = Math.max(0, intervalo - kmPasados);
        int porcentaje = intervalo > 0 ? Math.max(0, Math.min(100, (int) ((kmRestantes / (float) intervalo) * 100f))) : 0;
        long diasUso = -1;
        try {
            long epoch = prefs.getLong("oil_service_date_epoch", 0L);
            if (epoch > 0) diasUso = (System.currentTimeMillis() - epoch) / (1000L * 60L * 60L * 24L);
        } catch (Throwable ignored) {}
        if (oilText != null) oilText.setText(String.format(ES, "%,d km recorridos", kmPasados) + (diasUso > 0 ? " · " + diasUso + " días" : ""));
        if (oilBar != null) {
            oilBar.setFraction(porcentaje / 100f);
            oilBar.setFillColor(oilZoneColor(porcentaje));
        }
    }

    /** Lee un int tolerando claves guardadas como float (instalaciones previas). */
    private int prefInt(String key, int def) {
        try { return prefs.getInt(key, def); }
        catch (ClassCastException e) {
            try { return (int) prefs.getFloat(key, def); } catch (Throwable t) { return def; }
        } catch (Throwable t) { return def; }
    }

    /** Diálogo en Ajustes para alinear el odómetro interno con la cifra del tablero. */
    private void odometerDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(TEXT);
        et.setHintTextColor(MUTED);
        int cur = prefInt("oil_current_odometer", prefInt("odo_km_total", 0));
        et.setText(String.valueOf(cur));
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (20 * s);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this, dlg())
            .setTitle("Odómetro actual (km, del tablero)")
            .setView(wrap)
            .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    int v = -1;
                    try { v = Integer.parseInt(et.getText().toString().trim()); } catch (Throwable ignored) {}
                    if (v < 0) { toast("Ingresa un número válido"); return; }
                    odoKmTotal = v;
                    odoLastSavedKm = v;
                    prefs.edit().putInt("odo_km_total", v).putInt("oil_current_odometer", v).apply();
                    updateOilWidgetUI();
                    updateTelemetryBar();
                    toast("Odómetro alineado a " + String.format(ES, "%,d", v) + " km");
                    if (settingsCat == 1) showSettings(1);
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void oilDialog() {
        final int lastOdo = prefInt("oil_last_service_odometer", 0);

        final android.widget.EditText etInterval = new android.widget.EditText(this);
        etInterval.setSingleLine(true);
        etInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etInterval.setTextColor(TEXT);
        etInterval.setHintTextColor(MUTED);
        etInterval.setText(String.valueOf(prefInt("oil_change_interval_km", 8000)));

        final android.widget.EditText etLastOdo = new android.widget.EditText(this);
        etLastOdo.setSingleLine(true);
        etLastOdo.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etLastOdo.setTextColor(TEXT);
        etLastOdo.setHintTextColor(MUTED);
        etLastOdo.setText(String.valueOf(lastOdo > 0 ? lastOdo : prefInt("odo_km_total", 0)));

        final android.widget.EditText etDate = new android.widget.EditText(this);
        etDate.setSingleLine(true);
        etDate.setTextColor(TEXT);
        etDate.setHintTextColor(MUTED);
        etDate.setHint("dd/MM/yyyy");
        final long epochSaved0 = prefs.getLong("oil_service_date_epoch", 0L);
        if (epochSaved0 > 0) {
            try { etDate.setText(new SimpleDateFormat("dd/MM/yyyy", ES).format(new Date(epochSaved0))); } catch (Throwable ignored) { }
        }
        etDate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final java.util.Calendar c = java.util.Calendar.getInstance();
                if (epochSaved0 > 0) c.setTime(new Date(epochSaved0));
                new DatePickerDialog(Main.this, dlg(), new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(DatePicker view, int year, int month, int day) {
                        c.set(year, month, day);
                        etDate.setText(new SimpleDateFormat("dd/MM/yyyy", ES).format(c.getTime()));
                    }
                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show();
            }
        });

        final android.widget.EditText etType = new android.widget.EditText(this);
        etType.setSingleLine(true);
        etType.setTextColor(TEXT);
        etType.setHintTextColor(MUTED);
        etType.setHint("Ej.: 5W-30 sintético");
        String oilType = prefs.getString("oil_type", "");
        etType.setText(oilType != null ? oilType : "");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * s);
        col.setPadding(pad, pad, pad, pad);
        TextView l1 = tv("Intervalo (km)", 13, MUTED, false);
        col.addView(l1);
        col.addView(etInterval, new LinearLayout.LayoutParams(-1, -2));
        TextView l2 = tv("Odómetro del último cambio (km, del tablero)", 13, MUTED, false);
        l2.setPadding(0, (int) (10 * s), 0, 0);
        col.addView(l2);
        col.addView(etLastOdo, new LinearLayout.LayoutParams(-1, -2));
        TextView l3 = tv("Fecha del último cambio (día/mes/año)", 13, MUTED, false);
        l3.setPadding(0, (int) (10 * s), 0, 0);
        col.addView(l3);
        col.addView(etDate, new LinearLayout.LayoutParams(-1, -2));
        TextView l4 = tv("Tipo de aceite (SAE)", 13, MUTED, false);
        l4.setPadding(0, (int) (10 * s), 0, 0);
        col.addView(l4);
        col.addView(etType, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this, dlg())
            .setTitle("Control de aceite")
            .setView(col)
            .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    int interval = prefInt("oil_change_interval_km", 8000);
                    int lastOdo2 = prefInt("oil_last_service_odometer", 0);
                    try { interval = Integer.parseInt(etInterval.getText().toString().trim()); } catch (Throwable ignored) {}
                    try { lastOdo2 = Integer.parseInt(etLastOdo.getText().toString().trim()); } catch (Throwable ignored) {}
                    long epoch = -1L;
                    try {
                        String ds = etDate.getText().toString().trim();
                        if (ds.length() > 0) epoch = new SimpleDateFormat("dd/MM/yyyy", ES).parse(ds).getTime();
                    } catch (Throwable ignored) {}
                    long epochPrev = 0L;
                    try { epochPrev = prefs.getLong("oil_service_date_epoch", 0L); } catch (Throwable ignored) {}
                    prefs.edit()
                        .putInt("oil_change_interval_km", interval)
                        .putInt("oil_last_service_odometer", lastOdo2)
                        .putString("oil_type", etType.getText().toString().trim())
                        .putLong("oil_service_date_epoch", epoch > 0 ? epoch : epochPrev)
                        .apply();
                    updateOilWidgetUI();
                    updateTelemetryBar();
                }
            })
            .setNeutralButton("Aceite nuevo", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    int currentOdo = prefInt("oil_current_odometer", prefInt("odo_km_total", 0));
                    prefs.edit().putInt("oil_last_service_odometer", currentOdo).putLong("oil_service_date_epoch", System.currentTimeMillis()).apply();
                    updateOilWidgetUI();
                    updateTelemetryBar();
                    toast("Servicio registrado hoy a los " + String.format(ES, "%,d", currentOdo) + " km");
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    /** Interpolación de color verde→naranja→rojo (suave, como ArgbEvaluator). */
    private static final int C_GREEN = 0xFF4CAF50, C_ORANGE = 0xFFFF9800, C_RED_ZONE = 0xFFF44336;

    private static int lerpColor(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        try {
            return ((Integer) new android.animation.ArgbEvaluator().evaluate(t, a, b)).intValue();
        } catch (Throwable ignored) { }
        int ar = (a >>> 24) & 0xFF, ag = (a >>> 16) & 0xFF, ab = (a >>> 8) & 0xFF, aa = a & 0xFF;
        int br = (b >>> 24) & 0xFF, bg = (b >>> 16) & 0xFF, bb = (b >>> 8) & 0xFF, ba = b & 0xFF;
        return ((ar + (int) ((br - ar) * t)) << 24)
                | ((ag + (int) ((bg - ag) * t)) << 16)
                | ((ab + (int) ((bb - ab) * t)) << 8)
                | (aa + (int) ((ba - aa) * t));
    }

    /** Zona por fracción 0..1: 100-40% verde, 40-15% verde→naranja, 15-0% naranja→rojo. */
    private static int zoneColor(float frac) {
        if (frac <= 0.15f) return C_RED_ZONE;
        if (frac <= 0.40f) return lerpColor(C_ORANGE, C_RED_ZONE, Math.max(0f, Math.min(1f, (frac - 0.15f) / 0.25f)));
        return lerpColor(C_GREEN, C_ORANGE, Math.max(0f, Math.min(1f, (1f - frac) / 0.60f)));
    }

    /** Aceite (vida restante 100%→0%): 100-50 verde, 50-20 verde→naranja, <20 naranja→rojo. */
    private static int oilZoneColor(int porcentaje) {
        if (porcentaje >= 50) return C_GREEN;
        if (porcentaje >= 20) return lerpColor(C_GREEN, C_ORANGE, (50f - porcentaje) / 30f);
        return lerpColor(C_ORANGE, C_RED_ZONE, Math.max(0f, Math.min(1f, (20f - porcentaje) / 20f)));
    }

    private static int rpmColor(float rpm) {
        if (rpm <= 3500f) return C_GREEN;
        if (rpm < 5200f) return lerpColor(C_GREEN, C_ORANGE, (rpm - 3500f) / 1700f);
        return lerpColor(C_ORANGE, C_RED_ZONE, Math.min(1f, (rpm - 5200f) / 1300f));
    }

    private static int tempColor(float t) {
        if (t < 75f) return 0xFF7FA8B8; // frío/calentamiento: azul tenue
        if (t < 94f) return C_GREEN; // temperatura óptima
        if (t < 105f) return lerpColor(C_GREEN, C_ORANGE, Math.min(1f, (t - 94f) / 11f)); // alerta
        return C_RED_ZONE; // peligro
    }

    /** Barra del tacómetro: relleno de color según RPM (0-6500). */
    private class RpmGauge extends View {
        private float value = -1f;
        RpmGauge() { super(Main.this); }
        void setValue(float v) { value = v; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Paint track = new android.graphics.Paint();
            track.setColor(BTN);
            track.setStyle(android.graphics.Paint.Style.FILL);
            float r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            if (value >= 0) {
                float frac = Math.max(0f, Math.min(1f, value / 6500f));
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(rpmColor(value));
                c.drawRoundRect(0, 0, w * frac, h, r, r, p);
            }
        }
    }

    /** Barra de progreso simple (color por zona), -1 = sin datos. */
    private class MiniBar extends View {
        private float frac = -1f;
        private int fill = C_ORANGE;
        MiniBar() { super(Main.this); }
        void setFraction(float f) { frac = f; invalidate(); }
        void setFillColor(int c) { fill = c; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Paint track = new android.graphics.Paint();
            track.setColor(BTN);
            c.drawRoundRect(0, 0, w, h, h / 2f, h / 2f, track);
            if (frac >= 0f) {
                float fw = w * Math.max(0f, Math.min(1f, frac));
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(fill);
                c.drawRoundRect(0, 0, fw, h, h / 2f, h / 2f, p);
            }
        }
    }

    private void buildWebCard() {
        FrameLayout card = card(760, 260, 248, 184, CARD);
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(round(RED_DARK, 16));
        ImageView g = icon("public", 30);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (30 * s), (int) (30 * s));
        lp.gravity = Gravity.CENTER;
        ic.addView(g, lp);
        place(card, ic, 18, 20, 56, 56);
        placeWrap(card, tv("Navegador", 18, TEXT, true), 18, 94);
        placeWrap(card, tv("Internet desde la pantalla", 12, MUTED, false), 18, 122);
        TextView arrow = tv("›", 26, RED, false);
        placeWrap(card, arrow, 214, 130);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openBrowser(); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { pickFor("web", "Navegador"); return true; }
        });
    }

    private void buildDock() {
        final String[] icons = {"radio", "bluetooth", "music_note", "phone_android", "apps", "public", "folder", "bug_report", "settings"};
        final String[] names = {"Radio", "Bluetooth", "Música", "Teléfono", "Apps", "Navegador", "Archivos", "Diagnóstico", "Ajustes"};
        for (int i = 0; i < 9; i++) {
            final int idx = i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(round(Color.TRANSPARENT, 8));
            b.addView(circleIcon(icons[i], 40, 22, CIRCLE), new LinearLayout.LayoutParams((int) (40 * s), (int) (40 * s)));
            TextView l = tv(names[i], 12, TEXT, true);
            l.setGravity(Gravity.CENTER);
            l.setPadding((int) (4 * s), 0, 0, 0);
            b.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            place(board, b, 8 + 112 * i, 540, 104, 60);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    switch (idx) {
                        case 0: openSlot(0); break;
                        case 1: openSlot(1); break;
                        case 2: openSlot(2); break;
                        case 3: openPhone(); break;
                        case 4: openDrawer(); break;
                        case 5: openBrowser(); break;
                        case 6: openSlot(4); break;
                        case 7: openObdDiag(); break;
                        case 8: openSlot(5); break;
                        default: openDrawer();
                    }
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    int[] map = {0, 1, 2, -2, -2, -1, 4, -2, 5};
                    int m = map[idx];
                    if (m == -1) { pickFor("web", "Navegador"); return true; }
                    if (m < 0) return false;
                    pickFor(SLOTS[m][0], SLOTS[m][1]);
                    return true;
                }
            });
        }
    }

    com.fordlauncher.launcher.obd.ObdRepository getObdRepo() {
        if (obdRepo == null) obdRepo = new com.fordlauncher.launcher.obd.ObdRepository(this);
        return obdRepo;
    }
    android.content.SharedPreferences getPrefs() { return prefs; }

    /** Taller LiteDash en overlay central (tema oscuro, sin tapar reloj/dock). */
    private void openObdDiag() {
        if (workshopView != null) { closeObdDiag(); return; }
        workshopView = new FrameLayout(this);
        workshopView.setBackgroundColor(BG);
        workshopView.setClickable(true);
        DiagnosticoView w = new DiagnosticoView(this, s);
        workshop = w;
        workshopView.addView(workshop.build(), new FrameLayout.LayoutParams(-1, -1));
        TextView close = tv("✕ Inicio", 12, Color.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(RED, 12));
        close.setPadding((int)(10*s),(int)(6*s),(int)(10*s),(int)(6*s));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2,-2);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.rightMargin = (int)(10*s);
        lp.topMargin = (int)(8*s);
        close.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ closeObdDiag(); } });
        workshopView.addView(close, lp);
        place(board, workshopView, 0, 56, 1024, 484);
        workshopView.bringToFront();
        // Conectar con MAC por defecto si el usuario ya emparejó ELM327
        try { getObdRepo().connect(null); } catch (Throwable ignored) {}
    }

    private void closeObdDiag() {
        try { if (workshop != null) workshop.onDestroy(); } catch (Throwable ignored) {}
        workshop = null;
        if (workshopView != null) { try { board.removeView(workshopView); } catch (Throwable ignored) {} workshopView = null; }
    }

    private FrameLayout phoneView;
    private TelefonoView phone;
    private PhonePrivacy phonePrivacy;
    private ThemedCallWindow callWin;

    ThemedCallWindow callWindow() {
        if (callWin == null) callWin = new ThemedCallWindow(this, s > 0 ? s : 1f, phonePrivacy != null ? phonePrivacy.hfp() : null);
        return callWin;
    }

    /** Estados HFP llegan desde PhonePrivacy aunque el panel Teléfono esté cerrado. */
    void onPhoneCallState(String state, String number) {
        int st;
        try { st = Integer.parseInt(state); } catch (Throwable t) { return; }
        try {
            if (st == 7) callWindow().finish();
            else if (st >= 0) callWindow().show(number, null, st);
        } catch (Throwable ignored) {}
    }

    static final String[] PHONE_PERMS = {"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.CALL_PHONE"};

    void ensurePhonePerms() {
        if (SDK < 23) return;
        java.util.ArrayList<String> need = new java.util.ArrayList<String>();
        for (String p : PHONE_PERMS) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) {
            try { requestPermissions(need.toArray(new String[0]), 99); } catch (Throwable ignored) {}
        }
    }

    boolean phonePermsGranted() {
        for (String p : PHONE_PERMS) if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** Módulo Teléfono en overlay central (igual que el taller, sin tapar reloj/dock). */
    private void openPhone() {
        if (phoneView != null) { closePhone(); return; }
        ensurePhonePerms();
        phoneView = new FrameLayout(this);
        phoneView.setBackgroundColor(BG);
        phoneView.setClickable(true);
        phone = new TelefonoView(this, s);
        phoneView.addView(phone.build(), new FrameLayout.LayoutParams(-1, -1));
        place(board, phoneView, 0, 56, 1024, 484);
        phoneView.bringToFront();
    }

    void closePhone() {
        try { if (phone != null) phone.destroy(); } catch (Throwable ignored) {}
        if (phoneView != null) { try { board.removeView(phoneView); } catch (Throwable ignored) {} phoneView = null; phone = null; }
    }

    // ───────────────────────── helpers de UI ─────────────────────────

    private TextView tv(String t, float size, int color, boolean medium) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return v;
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r * s);
        return d;
    }

    private void place(ViewGroup parent, View v, float x, float y, float w, float hh) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (w * s), (int) (hh * s));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = (int) (x * s);
        lp.topMargin = (int) (y * s);
        parent.addView(v, lp);
    }

    private void placeWrap(ViewGroup parent, View v, float x, float y) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = (int) (x * s);
        lp.topMargin = (int) (y * s);
        parent.addView(v, lp);
    }

    private FrameLayout card(float x, float y, float w, float hh, int color) {
        FrameLayout c = new FrameLayout(this);
        c.setBackground(round(color, 20));
        place(board, c, x, y, w, hh);
        return c;
    }

    private ImageView icon(String name, float size) {
        ImageView v = new ImageView(this);
        v.setImageBitmap(asset("ic/" + name + ".png"));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (ICON != 0xFFFFFFFF) v.setColorFilter(ICON);
        return v;
    }

    private FrameLayout circleIcon(String name, float d, float ic, int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        f.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (ic * s), (int) (ic * s));
        lp.gravity = Gravity.CENTER;
        ImageView iv = icon(name, ic);
        if (color == RED) iv.clearColorFilter();
        f.addView(iv, lp);
        return f;
    }

    private Bitmap asset(String path) {
        Bitmap b = iconCache.get(path);
        if (b != null) return b;
        try {
            InputStream in = getAssets().open(path);
            b = BitmapFactory.decodeStream(in);
            in.close();
            iconCache.put(path, b);
        } catch (Exception e) {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
        return b;
    }

    void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    // ───────────────────────── reloj, GPS, audio ─────────────────────────

    private void updateClock() {
        if (timeTv == null) return;
        Date now = new Date();
        String fmt = prefs.getString("clock", "auto");
        boolean h24 = "24".equals(fmt) || ("auto".equals(fmt) && android.text.format.DateFormat.is24HourFormat(this));
        timeTv.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", ES).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        dateTv.setText(d.substring(0, 1).toUpperCase(ES) + d.substring(1));
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String saludo = hr >= 5 && hr < 12 ? "Buenos días" : (hr >= 12 && hr < 19 ? "Buenas tardes" : "Buenas noches");
        String nombre = prefs.getString("user_name", "").trim();
        greetTv.setText(nombre.length() > 0 ? saludo + ", " + nombre : saludo);
    }

    private final LocationListener gpsListener = new LocationListener() {
        public void onLocationChanged(Location l) {
            lastFix = SystemClock.elapsedRealtime();
            if (l != null) {
                // Fallback de odómetro por GPS cuando OBD2 está desconectado.
                try {
                    boolean obdOn = getObdRepo().getState() == com.fordlauncher.launcher.obd.ObdRepository.State.ON;
                    float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
                    if (!obdOn && kmh > 5f && lastGpsLoc != null) {
                        float distM = l.distanceTo(lastGpsLoc);
                        if (distM > 1f && distM < 5000f) {
                            if (odoKmTotal < 0f) { odoKmTotal = prefInt("odo_km_total", 0); odoLastSavedKm = odoKmTotal; }
                            if (prefs.getBoolean("odo_auto", true)) odoKmTotal += distM / 1000f;
                            if (odoKmTotal - odoLastSavedKm > 0.5f) {
                                prefs.edit().putInt("odo_km_total", (int) odoKmTotal).putInt("oil_current_odometer", (int) odoKmTotal).apply();
                                odoLastSavedKm = odoKmTotal;
                            }
                            updateOilWidgetUI();
                        }
                    }
                    lastGpsLoc = l;
                } catch (Throwable ignored) {}
            }
            setGps(true, l.hasSpeed() ? l.getSpeed() * 3.6f : 0);
            feedMap(l);
        }
        public void onStatusChanged(String p, int st, Bundle e) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { setGps(false, 0); }
    };

    /** Ubicación por red (WiFi/datos): solo para ubicar el mapa mientras llega el GPS. */
    private final LocationListener netListener = new LocationListener() {
        public void onLocationChanged(Location l) { if (lastFix == 0) feedMap(l); }
        public void onStatusChanged(String p, int st, Bundle e) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    private void startGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener);
            try { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 0, netListener); } catch (Throwable ignored) { }
        } catch (Exception e) {
            setGps(false, 0);
        }
    }

    private void stopGps() {
        try { lm.removeUpdates(gpsListener); } catch (Exception ignored) { }
        try { lm.removeUpdates(netListener); } catch (Exception ignored) { }
    }

    private void setGps(boolean ok, float kmh) {
        parkGpsKmh = ok ? kmh : 0f;
        if (speedTv == null) return;
        if (!ok) lastFix = 0;
        speedTv.setText(ok ? String.valueOf(Math.round(kmh < 2 ? 0 : kmh)) : "--");
        gpsTv.setText(ok ? "GPS" : "Buscando GPS");
        gpsDot.setBackground(round(ok ? 0xFF3DDC84 : MUTED, 4));
        updateMapTag();
    }

    private View.OnClickListener mediaClick(final int code) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                int which = code == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? -1 : 0;
                boolean done = false;
                boolean idle = (curTitle == null && curArtist == null);
                if (idle && code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { openSlot(2); return; }
                final String beforeTitle = curTitle;
                final boolean isSkip = (which != 0);
                // Seldia Music: su skip por MediaSession no avanza (cola de 1). Usa sus acciones internas.
                if (isSeldia() && sendSeldiaCommand(which)) {
                    done = true;
                    h.postDelayed(new Runnable() { public void run() { updatePlayIcon(); if (mediaWatcher != null) mediaWatcher.start(); } }, 600);
                    return;
                }
                if (mediaWatcher != null) {
                    try { done = mediaWatcher.command(which); } catch (Throwable ignored) { }
                }
                if (!done && !sendBtCommand(code)) sendMediaKey(code);
                h.postDelayed(new Runnable() { public void run() { updatePlayIcon(); } }, 600);
                if (isSkip && done) {
                    h.postDelayed(new Runnable() {
                        public void run() {
                            String nowTitle = curTitle;
                            if (beforeTitle != null && beforeTitle.equals(nowTitle)) {
                                if (isSeldia()) {
                                    if (!sendSeldiaCommand(which)) sendMediaKey(code);
                                } else if (!sendBtCommand(code)) sendMediaKey(code);
                                if (mediaWatcher != null) mediaWatcher.start();
                            }
                            updatePlayIcon();
                        }
                    }, 900);
                }
            }
        };
    }

    private boolean isSeldia() {
        if ("com.seldia.music".equals(curPkg)) return true;
        return curSource != null && curSource.toLowerCase().contains("seldia");
    }

    /** Acciones internas de Seldia Music (PlaybackActionReceiver). Requiere receiver exportado en Seldia. */
    private boolean sendSeldiaCommand(int which) {
        String action = which == 1 ? "com.seldia.music.action.NEXT"
                : which == -1 ? "com.seldia.music.action.PREVIOUS"
                : "com.seldia.music.action.PLAY_PAUSE";
        try {
            Intent i = new Intent(action);
            i.setPackage("com.seldia.music");
            sendBroadcast(i);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Si suena Bluetooth, controla directamente la app Bluetooth de fábrica (com.clw.btmusic). */
    private boolean sendBtCommand(int code) {
        if (!"Bluetooth".equals(curSource)) return false;
        if (bt != null && bt.command(code == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? -1 : 0)) {
            h.postDelayed(new Runnable() { public void run() { if (bt != null) bt.refresh(); } }, 700);
            return true;
        }
        String a = code == KeyEvent.KEYCODE_MEDIA_NEXT ? "com.clw.btmusic.next"
                : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? "com.clw.btmusic.previous" : "com.clw.btmusic.togglepause";
        try {
            Intent i = new Intent(a);
            i.setClassName("com.clw.btmusic", "com.clw.btmusic.BTPlaybackService");
            return startService(i) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private void sendMediaKey(int code) {
        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0);
        KeyEvent up = new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            java.lang.reflect.Method m = AudioManager.class.getMethod("dispatchMediaKeyEvent", KeyEvent.class);
            m.invoke(am, down);
            m.invoke(am, up);
            return;
        } catch (Throwable ignored) { }
        Intent i1 = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i1.putExtra(Intent.EXTRA_KEY_EVENT, down);
        sendOrderedBroadcast(i1, null);
        Intent i2 = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i2.putExtra(Intent.EXTRA_KEY_EVENT, up);
        sendOrderedBroadcast(i2, null);
    }

    private void updatePlayIcon() {
        if (playIc == null) return;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean on = playingState != null ? playingState : am.isMusicActive();
        playIc.setImageBitmap(asset(on ? "ic/pause.png" : "ic/play_arrow.png"));
    }

    // ───────────────────────── pista en reproducción ─────────────────────────

    private void showTrack(NowPlaying.Track t) {
        long now = SystemClock.elapsedRealtime();
        boolean hasContent = t.title != null || t.artist != null;
        // Un aviso sin pista de otra fuente (p. ej. el sistema cuando entra la cámara de reversa)
        // no debe borrar ni pausar lo que muestra la fuente actual.
        if (curOrigin != null && !curOrigin.equals(t.origin) && !hasContent) return;
        // Con Android Auto conectado el celular suele mandar la misma canción también por Bluetooth:
        // si es la misma pista, se queda Android Auto como fuente (trae carátula) y Bluetooth solo aporta tiempo/estado.
        boolean sameAsAA = ("rcc".equals(curOrigin) || "Android Auto".equals(curSource))
                && "bt".equals(t.origin) && t.title != null && t.title.equals(curTitle);
        if (sameAsAA) {
            t.source = null;
            t.artist = null;
        } else if (hasContent && !t.clear) curOrigin = t.origin;
        if (t.clear) {
            curOrigin = null;
            curTitle = curArtist = curSource = curAlbum = curPkg = null;
            curArt = null;
            durMs = -1;
            posKnown = false;
            playingState = false;
            renderTrack();
            return;
        }
        boolean newTrack = t.title != null && !t.title.equals(curTitle);
        if (newTrack) {
            posBaseMs = t.positionMs >= 0 ? t.positionMs : 0;
            posBaseAt = now;
            posKnown = t.positionMs >= 0;
            durMs = -1;
            if (!t.artSet) curArt = null;
            // ¿La notificación de Open Headunit ya trae la carátula de esta canción?
            if (!t.artSet && NotifArt.lastArt != null && textsMatch(NotifArt.lastTexts, t.title)) {
                curArt = NotifArt.lastArt;
                t.source = "Android Auto";
            }
        }
        if (t.title != null) curTitle = t.title;
        if (t.artist != null) curArtist = t.artist;
        if (t.album != null) curAlbum = t.album;
        if (t.source != null) curSource = t.source;
        if (t.pkg != null) curPkg = t.pkg;
        if (t.durationMs > 0) durMs = t.durationMs;
        if (t.artSet && t.art != null) curArt = t.art;
        long pos = currentPos();
        if (t.positionMs >= 0) { pos = t.positionMs; posKnown = true; }
        if (t.playing != null) {
            playingState = t.playing;
        }
        posBaseMs = pos;
        posBaseAt = now;
        renderTrack();
    }

    private static boolean textsMatch(java.util.List<String> texts, String title) {
        if (texts == null || title == null) return false;
        String tt = title.trim().toLowerCase(ES);
        for (String s : texts) {
            if (s == null) continue;
            String x = s.trim().toLowerCase(ES);
            if (x.equals(tt) || (tt.length() > 3 && x.contains(tt))) return true;
        }
        return false;
    }

    /** Carátula de Android Auto leída de la notificación de Open Headunit. */
    private void applyAaNotif(Bitmap art, java.util.List<String> texts) {
        if (art == null || curTitle == null) return;
        if (!textsMatch(texts, curTitle)) return;
        curArt = art;
        curSource = "Android Auto";
        renderTrack();
    }

    private void showPlayerDiag() {
        String msg = "Fuente: " + curOrigin + " / " + curSource
                + "\nPista: " + curTitle + " — " + curArtist
                + "\nCarátula: " + (curArt != null ? curArt.getWidth() + "x" + curArt.getHeight() : "no")
                + "\n\nLector de notificaciones (accesibilidad): " + (NotifArt.running ? "ACTIVO" : "apagado")
                + "\nÚltima notificación de Open Headunit: " + (NotifArt.lastAt == 0 ? "ninguna"
                    : ((SystemClock.elapsedRealtime() - NotifArt.lastAt) / 1000) + " s, carátula "
                    + (NotifArt.lastArt != null ? "sí" : "no") + ", textos " + NotifArt.lastTexts)
                + "\nApp Bluetooth conectada: " + (bt != null && bt.isConnected() ? "sí" : "no")
                + "\nAudio Bluetooth automático: " + (clwBt ? (btAutoOn() ? "activado" : "desactivado") : "no aplica (otra marca)")
                + (clwBt ? "" : "\nApp de música Bluetooth: " + (btMusicApp() != null ? btMusicApp().getPackageName() : "no encontrada")
                    + " · abrir al conectar: " + (prefs.getBoolean("bt_open_connect", false) ? "sí" : "no"))
                + "\nSonido en Bluetooth (estimado): " + (btRouted ? "sí" : "no") + " · app al frente: " + topPackage()
                + "\nActivaciones: " + BtBridge.activations
                + "\n\nEventos Bluetooth:\n" + BtBridge.logText();
        new AlertDialog.Builder(this, dlg())
            .setTitle("Diagnóstico del reproductor")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Accesibilidad", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { openAccessibility(); }
            })
            .show();
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("Activa «Carátula de Android Auto» (Ford Launcher)");
        } catch (Throwable e) {
            toast("No se pudo abrir Accesibilidad");
        }
    }

    /** ¿El usuario ya permitió «Acceso a notificaciones» para MediaListener? (necesario para leer MediaSession en Android 5+). */
    private boolean isNotificationListenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            ComponentName mine = new ComponentName(this, MediaListener.class);
            String mineFlat = mine.flattenToString();
            for (String p : flat.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(p);
                if (cn != null && mineFlat.equals(cn.flattenToString())) return true;
                if (p != null && mineFlat.equals(p)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Pide Acceso a notificaciones al arrancar si falta, para que MediaSessionManager funcione. */
    private void ensureMediaAccess() {
        if (SDK < 18) return;
        if (askedMediaPerm) {
            if (isNotificationListenerEnabled() && mediaWatcher != null) mediaWatcher.start();
            return;
        }
        if (isNotificationListenerEnabled() || MediaListener.connected) return;
        askedMediaPerm = true;
        new AlertDialog.Builder(this, dlg())
            .setTitle("Acceso a notificaciones")
            .setMessage("Para mostrar lo que suena en la tarjeta AUDIO (canción, artista, carátula) Ford Launcher necesita «Acceso a notificaciones».\n\nActívalo y vuelve: la tarjeta se actualizará en tiempo real.")
            .setPositiveButton("Abrir ajuste", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Throwable e) { toast("No se pudo abrir el ajuste"); }
                }
            })
            .setNegativeButton("Ahora no", null)
            .show();
    }

    private long currentPos() {
        long p = posBaseMs;
        if (playingState != null && playingState) p += SystemClock.elapsedRealtime() - posBaseAt;
        if (durMs > 0 && p > durMs) p = durMs;
        return p;
    }

    private static String fmt(long ms) {
        long sec = Math.max(0, ms / 1000);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private void renderTrack() {
        if (trackTv == null) return;
        if (curTitle == null && curArtist == null) {
            trackTv.setText("Nada sonando");
            artistTv.setText("Toca ▶ para abrir tu música");
            sourceTv.setText("");
            timePosTv.setText("");
            playerArt.setArt(null);
            playerArt.setProgress(-1);
        } else {
            boolean aaOff = curSource != null && curSource.toLowerCase(ES).contains("headunit")
                    && ("Conectado".equalsIgnoreCase(curArtist) || "Connected".equalsIgnoreCase(curArtist));
            trackTv.setText(curTitle != null ? curTitle : (curSource != null ? curSource : ""));
            String sub = "";
            if (curArtist != null) sub = curArtist;
            if (curAlbum != null && curAlbum.trim().length() > 0) sub = sub.length() > 0 ? sub + " · " + curAlbum : curAlbum;
            artistTv.setText(aaOff ? "Activa «Pista actual de AA» en Open Headunit" : sub);
            sourceTv.setText(curSource != null ? curSource.toUpperCase(ES) : "");
            playerArt.setArt(curArt);
            updateProgress();
        }
        if (playIc != null) {
            boolean on = playingState != null ? playingState
                    : ((AudioManager) getSystemService(AUDIO_SERVICE)).isMusicActive();
            playIc.setImageBitmap(asset(on ? "ic/pause.png" : "ic/play_arrow.png"));
        }
    }

    private void updateProgress() {
        if (playerArt == null || (curTitle == null && curArtist == null)) return;
        if (posKnown) {
            long p = currentPos();
            if (durMs > 0) {
                playerArt.setProgress(p / (float) durMs);
                timePosTv.setText(fmt(p) + " / " + fmt(durMs));
            } else {
                playerArt.setProgress(-1);
                timePosTv.setText(fmt(p));
            }
        } else if (durMs > 0) {
            playerArt.setProgress(-1);
            timePosTv.setText(fmt(durMs));
        } else {
            playerArt.setProgress(-1);
            timePosTv.setText("");
        }
    }

    private final Runnable progressTick = new Runnable() {
        public void run() {
            updateProgress();
            updateTelemetryBar();
            h.postDelayed(this, 1000);
        }
    };

    // ───────────────────────── configuración ─────────────────────────

    private void openSettings() { showSettings(1); }

    private void usbHelp() {
        new AlertDialog.Builder(this, dlg())
            .setTitle("«Ninguna aplic. funciona con este accesorio USB»")
            .setMessage("Ese aviso lo causa una app de espejo de fábrica (EasyConnection/CarbitLink) que toma el USB antes que la app que elegiste.\n\n1. Desconecta el celular.\n2. Ajustes → Aplicaciones → EasyConnection → «Borrar datos».\n3. Apaga y enciende el carro.\n4. En Open Headunit → USB, si tu celular aparece como «Ignorado», tócalo para permitirlo.")
            .setPositiveButton("Abrir EasyConnection", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    for (String p : USB_HOGS) if (installed(p)) {
                        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable ignored) { }
                        return;
                    }
                    toast("No hay apps de espejo instaladas");
                }
            })
            .setNegativeButton("Cerrar", null).show();
    }

    private void runDiag() {
        toast("Exportando… puede tardar un minuto");
        new Thread(new Runnable() {
            public void run() {
                final String r = Diag.export(Main.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        new AlertDialog.Builder(Main.this, dlg())
                            .setTitle("Diagnóstico listo")
                            .setMessage("Se guardó en:\n" + r + "\n\nCopia la carpeta FordDiag a una memoria USB y adjúntala en un «issue» del repositorio de Ford Launcher en GitHub.")
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }).start();
    }

    private String clockLabel() {
        String c = prefs.getString("clock", "auto");
        return "12".equals(c) ? "12 horas" : "24".equals(c) ? "24 horas" : "Automático";
    }

    private void chooseClock() {
        final String[] opts = {"Automático", "12 horas (a.m./p.m.)", "24 horas"};
        final String[] vals = {"auto", "12", "24"};
        new AlertDialog.Builder(this, dlg())
            .setTitle("Formato de hora")
            .setItems(opts, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString("clock", vals[w]).apply();
                    updateClock();
                }
            }).show();
    }

    private void editText(final String key, String title, String def) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextColor(TEXT);
        et.setHintTextColor(MUTED);
        et.setText(prefs.getString(key, def));
        et.setSelection(et.getText().length());
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (20 * s);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg());
        b.setTitle(title)
            .setView(wrap)
            .setPositiveButton("Guardar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString(key, et.getText().toString().trim()).apply();
                    if ("city_manual".equals(key)) refreshWeatherNow();
                    if ("map_url".equals(key)) toast("Se aplicará al reiniciar Ford Launcher");
                    if ("extra_actions".equals(key)) {
                        try { nowPlaying.stop(); nowPlaying.start(); } catch (Throwable ignored) { }
                    }
                    builtW = 0;
                    build(root.getWidth(), root.getHeight());
                }
            });
        if ("gemini_api_key".equals(key) || "gh_token".equals(key)) {
            b.setNeutralButton("Importar .txt", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { pickImportFile(key); }
            });
        }
        b.setNegativeButton("Cancelar", null)
            .show();
    }

    private static final int REQ_API_FILE = 43;
    private String pendingImportKey;

    private void pickImportFile(String prefKey) {
        pendingImportKey = prefKey;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, "Selecciona tu archivo (.txt)"), REQ_API_FILE);
        } catch (Throwable t) {
            toast("No hay selector de archivos en esta pantalla");
        }
    }

    private void importApiKeyFile(android.net.Uri uri) {
        importTokenFile(uri, pendingImportKey);
    }

    private void importTokenFile(android.net.Uri uri, String prefKey) {
        if (uri == null || prefKey == null) { toast("No se eligió ningún archivo"); return; }
        String raw = null;
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("no stream");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            raw = new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            toast("No se pudo leer el archivo");
            return;
        }
        String key = raw == null ? "" : raw.trim();
        if (key.length() > 0 && key.charAt(0) == '﻿') key = key.substring(1).trim(); // BOM U+FEFF de algunos .txt
        if (key.length() == 0) { toast("El archivo está vacío"); return; }
        int nl = key.indexOf('\n');
        if (nl >= 0) key = key.substring(0, nl).trim(); // solo la primera línea: es la clave
        prefs.edit().putString(prefKey, key).apply();
        toast("Importado desde el archivo");
        pendingImportKey = null;
        if (settingsView != null) showSettings(settingsCat);
    }

    // ───────────────────────── universal: asistente, color, foto, CarPlay ─────────────────────────

    static final String[] ACCENT_NAMES = {"Ford Launcher", "Rojo", "Naranja", "Amarillo", "Verde", "Turquesa", "Azul", "Morado", "Rosa", "Plata"};
    static final int[] ACCENT_COLORS = {0xFFFE3211, 0xFFE3262F, 0xFFFF7A1A, 0xFFF5B800, 0xFF2DBE6C, 0xFF14B8B0, 0xFF2F7CF6, 0xFF8E5CF7, 0xFFE8457C, 0xFF9AA3AF};
    static final String[] CARPLAY_PKGS = {"com.zjinnova.zlink", "com.zjinnova.zlink5", "com.autokit", "cn.manstep.phonemirrorBox",
            "cn.manstep.phonemirrorBox.nomap", "com.suding.speedplay", "com.tlink.carplay", "com.syu.carlink", "com.carlinkit.autokit"};

    private void rebuild() {
        boolean phoneOpen = phoneView != null;
        builtW = 0;
        if (root.getWidth() > 0) {
            if (phoneOpen) {
                try { if (phone != null) phone.destroy(); } catch (Throwable ignored) {}
                phoneView = null;
                phone = null;
            }
            build(root.getWidth(), root.getHeight());
            if (phoneOpen) openPhone();
        }
    }

    private void applyAccent() {
        RED = prefs.getInt("accent", 0xFFFE3211);
        int r = (RED >> 16) & 255, g = (RED >> 8) & 255, b = RED & 255;
        int br = (BG >> 16) & 255, bgc = (BG >> 8) & 255, bb = BG & 255, pct = LIGHT ? 16 : 18;
        RED_DARK = 0xFF000000 | ((r * pct / 100 + br * (100 - pct) / 100) << 16) | ((g * pct / 100 + bgc * (100 - pct) / 100) << 8) | (b * pct / 100 + bb * (100 - pct) / 100);
    }

    /** Usuario que actualiza desde una versión anterior: conserva su carro. Usuario nuevo: asistente. */
    private void migrateOrSetup() {
        if (prefs.getBoolean("setup_done", false)) return;
        boolean upgrade = false;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            upgrade = pi.lastUpdateTime - pi.firstInstallTime > 60000;
        } catch (Throwable ignored) { }
        if (upgrade) {
            prefs.edit().putBoolean("setup_done", true).apply();
        } else {
            h.postDelayed(new Runnable() { public void run() { wizard(); } }, 600);
        }
    }

    private void wizard() {
        new AlertDialog.Builder(this, dlg())
            .setTitle("Bienvenido a Ford Launcher")
            .setMessage("Vamos a dejar el launcher a tu gusto: tu nombre, tu vehículo, el color y la foto. Toma menos de un minuto y lo puedes cambiar después en el engranaje ⚙.")
            .setPositiveButton("Empezar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    ask("user_name", "¿Cómo te llamas?", "", new Runnable() { public void run() {
                    ask("car_name", "Nombre del vehículo (ej.: Mazda 2, Spark GT)", "Mi vehículo", new Runnable() { public void run() {
                    ask("car_tag", "Etiqueta corta (versión o placa). Déjalo vacío si no quieres", "", new Runnable() { public void run() {
                    chooseAccent(new Runnable() { public void run() {
                        prefs.edit().putBoolean("setup_done", true).apply();
                        rebuild();
                        reviewShortcuts(new Runnable() { public void run() { pickPhoto(); } });
                    }});
                    }});
                    }});
                    }});
                }
            })
            .setNegativeButton("Después", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { prefs.edit().putBoolean("setup_done", true).apply(); }
            })
            .show();
    }

    // ── asistente: revisar qué app abre cada botón ──

    static final String[][] REVIEW = {{"radio", "Radio"}, {"bt", "Bluetooth"}, {"music", "Música"}, {"btaudio", "Música Bluetooth"}, {"cam", "Cámara"}, {"files", "Archivos"}, {"aa", "Android Auto"}};

    /** Qué app abre cada botón en esta pantalla: la elegida o la que Ford Launcher detectó. */
    private String shortcutLabel(String key) {
        String c = prefs.getString(key, null);
        if (c != null) return labelOf(c);
        if ("btaudio".equals(key)) { ComponentName cn = btMusicApp(); return cn != null ? "Automático · " + labelOf(cn.flattenToString()) : "No encontrada"; }
        if ("aa".equals(key)) {
            String p = prefs.getString("conn_app", null);
            if (p != null) return labelOf(p);
            List<String> cand = connCandidates();
            return cand.isEmpty() ? "No encontrada" : "Automático · " + labelOf(cand.get(0));
        }
        for (String[] sl : SLOTS) if (sl[0].equals(key)) {
            ResolveInfo r = find(sl[2], sl[3]);
            return r != null ? "Automático · " + r.loadLabel(getPackageManager()) : "No encontrada";
        }
        return "Automático";
    }

    /** Lista de botones con su app; tocar uno deja elegir otra. Funciona igual en cualquier versión de Android. */
    private void reviewShortcuts(final Runnable next) {
        final String[] items = new String[REVIEW.length];
        for (int i = 0; i < REVIEW.length; i++) items[i] = REVIEW[i][1] + "\n    → " + shortcutLabel(REVIEW[i][0]);
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿Qué app abre cada botón?")
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, final int w) {
                    pickFor(REVIEW[w][0], REVIEW[w][1], new Runnable() { public void run() { reviewShortcuts(next); } });
                }
            })
            .setCancelable(false)
            .setPositiveButton("Están bien", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { refreshStates(); if (next != null) next.run(); }
            })
            .show();
    }

    private void ask(final String key, String title, String def, final Runnable next) {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextColor(TEXT);
        et.setHintTextColor(MUTED);
        et.setText(prefs.getString(key, def));
        et.setSelection(et.getText().length());
        FrameLayout wrap = new FrameLayout(this);
        int pad = (int) (20 * s);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(wrap)
            .setCancelable(false)
            .setPositiveButton("Siguiente", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString(key, et.getText().toString().trim()).apply();
                    if (next != null) next.run();
                }
            })
            .show();
    }

    private void chooseAccent(final Runnable next) {
        new AlertDialog.Builder(this, dlg())
            .setTitle("Color de acento")
            .setAdapter(new BaseAdapter() {
                public int getCount() { return ACCENT_NAMES.length; }
                public Object getItem(int p) { return ACCENT_NAMES[p]; }
                public long getItemId(int p) { return p; }
                public View getView(int p, View cv, ViewGroup parent) {
                    LinearLayout row = new LinearLayout(Main.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int pad = (int) (14 * s);
                    row.setPadding(pad * 2, pad, pad, pad);
                    View dot = new View(Main.this);
                    GradientDrawable g = new GradientDrawable();
                    g.setShape(GradientDrawable.OVAL);
                    g.setColor(ACCENT_COLORS[p]);
                    dot.setBackground(g);
                    row.addView(dot, new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s)));
                    TextView t = tv(ACCENT_NAMES[p], 17, TEXT, false);
                    t.setPadding(pad, 0, 0, 0);
                    row.addView(t);
                    return row;
                }
            }, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putInt("accent", ACCENT_COLORS[w]).apply();
                    applyAccent();
                    if (next != null) next.run(); else rebuild();
                }
            })
            .setCancelable(next == null)
            .show();
    }

    // ── foto del vehículo / wallpaper ──

    private static final int REQ_PHOTO_DAY = 41;
    private static final int REQ_PHOTO_NIGHT = 42;

    private void photoMenu(final boolean isDay) {
        String key = isDay ? "wallpaper_day_path" : "wallpaper_night_path";
        final boolean has = !prefs.getString(key, "").isEmpty() || (isDay && CarPhoto.load(this) != null);
        String[] opts = has ? new String[]{"Elegir otra foto", "Quitar la foto"} : new String[]{"Elegir foto"};
        new AlertDialog.Builder(this, dlg())
            .setTitle(isDay ? "Foto Modo Día" : "Foto Modo Noche")
            .setItems(opts, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == 0) pickPhoto(isDay);
                    else {
                        prefs.edit().remove(key).apply();
                        if (isDay) new File(getFilesDir(), "car_day.png").delete();
                        else new File(getFilesDir(), "car_night.png").delete();
                        rebuild();
                    }
                }
            }).show();
    }

    private void pickPhoto() { pickPhoto(true); }

    private void pickPhoto(final boolean isDay) {
        new AlertDialog.Builder(this, dlg())
            .setTitle(isDay ? "Foto Modo Día" : "Foto Modo Noche")
            .setMessage("Consejo: usa una foto del carro de lado o de frente con fondo liso. El fondo se quita solo.")
            .setPositiveButton("Memoria / USB", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { browseImages(isDay); }
            })
            .setNeutralButton("Galería", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        startActivityForResult(Intent.createChooser(i, "Elige la foto"), isDay ? REQ_PHOTO_DAY : REQ_PHOTO_NIGHT);
                    } catch (Throwable e) { browseImages(isDay); }
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private final java.util.HashSet<String> scanned = new java.util.HashSet<String>();

    private void scanImages(File dir, int depth, List<File> out) {
        if (depth < 0 || dir == null || out.size() > 400) return;
        try {
            String canon = dir.getCanonicalPath();
            if (!scanned.add(canon) && depth < 4) return;
        } catch (Throwable ignored) { }
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase(ES);
            if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
                    && f.length() > 15000 && !n.startsWith(".")) out.add(f);
        }
        for (File f : fs) {
            String n = f.getName();
            if (f.isDirectory() && !n.startsWith(".") && !"Android".equals(n) && !"LOST.DIR".equals(n)
                    && !n.equalsIgnoreCase("thumbnails") && !"FordDiag".equals(n)) scanImages(f, depth - 1, out);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_API_FILE) {
            if (res == RESULT_OK && data != null) importApiKeyFile(data.getData());
            return;
        }
        if ((req == REQ_PHOTO_DAY || req == REQ_PHOTO_NIGHT) && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final boolean isDay = (req == REQ_PHOTO_DAY);
            processPhoto(new CarPhoto.Opener() {
                public InputStream open() throws Exception { return getContentResolver().openInputStream(uri); }
            }, isDay);
        }
    }

    private void browseImages(final boolean isDay) {
        final ProgressDialogLite pd = new ProgressDialogLite("Buscando fotos…");
        new Thread(new Runnable() {
            public void run() {
                final List<File> found = new ArrayList<File>();
                File ext = android.os.Environment.getExternalStorageDirectory();
                String[] roots = {ext.getAbsolutePath(), "/mnt", "/storage", "/udisk", "/mnt/usb_storage"};
                for (String r : roots) scanImages(new File(r), 4, found);
                Collections.sort(found, new Comparator<File>() {
                    public int compare(File a, File b) { long x = b.lastModified() - a.lastModified(); return x > 0 ? 1 : x < 0 ? -1 : 0; }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (found.isEmpty()) {
                            toast("No encontré fotos (.jpg/.png). Cópialas a la memoria USB o a Download.");
                            return;
                        }
                        final int n = Math.min(found.size(), 80);
                        String[] names = new String[n];
                        for (int i = 0; i < n; i++) names[i] = found.get(i).getName() + "  ·  " + found.get(i).getParentFile().getName();
                        new AlertDialog.Builder(Main.this, dlg())
                            .setTitle("Elige la foto")
                            .setItems(names, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    final File f = found.get(w);
                                    processPhoto(new CarPhoto.Opener() {
                                        public InputStream open() throws Exception { return new java.io.FileInputStream(f); }
                                    }, isDay);
                                }
                            }).setNegativeButton("Cancelar", null).show();
                    }
                });
            }
        }).start();
    }

    private void processPhoto(final CarPhoto.Opener opener, final boolean isDay) {
        final ProgressDialogLite pd = new ProgressDialogLite("Quitando el fondo…");
        new Thread(new Runnable() {
            public void run() {
                Bitmap src = null, cut = null;
                String err = null;
                boolean already = false;
                try {
                    src = CarPhoto.decode(opener);
                    already = CarPhoto.hasTransparency(src);
                    cut = already ? src : CarPhoto.removeBackground(src, 1);
                } catch (Throwable e) {
                    err = e.getMessage();
                }
                final Bitmap fsrc = src, fcut = cut;
                final String ferr = err;
                final boolean falready = already;
                runOnUiThread(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        if (fsrc == null) { toast("No se pudo abrir la imagen" + (ferr != null ? ": " + ferr : "")); return; }
                        previewPhoto(fsrc, fcut, 1, falready, isDay);
                    }
                });
            }
        }).start();
    }

    private void previewPhoto(final Bitmap src, final Bitmap cut, final int strength, boolean already, final boolean isDay) {
        final Bitmap shown = CarPhoto.fitToCard(cut != null ? cut : src);
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(CARD);
        int pad = (int) (16 * s);
        box.setPadding(pad, pad, pad, pad);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(shown);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(iv, new FrameLayout.LayoutParams((int) (396 * s), (int) (240 * s)));
        String title = already ? "La foto ya trae fondo transparente"
                : cut == null ? "No se detectó un fondo liso (se usará tal cual)"
                : "Vista previa · nivel " + (strength + 1) + " de 4" + (strength >= 2 ? " (también quita el reflejo del piso)" : "");
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Usar esta", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String fileName = isDay ? "car_day.png" : "car_night.png";
                    File f = new File(getFilesDir(), fileName);
                    try {
                        FileOutputStream os = new FileOutputStream(f);
                        shown.compress(Bitmap.CompressFormat.PNG, 100, os);
                        os.close();
                        prefs.edit().putString(isDay ? "wallpaper_day_path" : "wallpaper_night_path", f.getAbsolutePath()).apply();
                        toast("Foto guardada");
                        rebuild();
                    } catch (Throwable e) {
                        toast("No se pudo guardar la foto");
                    }
                }
            })
            .setNegativeButton("Cancelar", null);
        if (!already && strength < 3) {
            b.setNeutralButton("Quitar más fondo", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    final ProgressDialogLite pd = new ProgressDialogLite("Quitando más fondo…");
                    new Thread(new Runnable() {
                        public void run() {
                            final Bitmap c2 = CarPhoto.removeBackground(src, strength + 1);
                            runOnUiThread(new Runnable() {
                                public void run() { pd.dismiss(); previewPhoto(src, c2 != null ? c2 : cut, strength + 1, false, isDay); }
                            });
                        }
                    }).start();
                }
            });
        }
        b.show();
    }

    /** Diálogo de espera simple (compatible con Android 4.1). */
    private class ProgressDialogLite {
        private final AlertDialog d;
        ProgressDialogLite(String msg) {
            LinearLayout l = new LinearLayout(Main.this);
            l.setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (22 * s);
            l.setPadding(pad, pad, pad, pad);
            l.addView(new android.widget.ProgressBar(Main.this));
            TextView t = tv(msg, 16, TEXT, false);
            t.setPadding(pad, 0, 0, 0);
            l.addView(t);
            d = new AlertDialog.Builder(Main.this, dlg()).setView(l).setCancelable(false).create();
            d.show();
        }
        void dismiss() { try { d.dismiss(); } catch (Throwable ignored) { } }
    }

    // ── CarPlay (requiere adaptador USB con su app) ──

    /** App de proyección integrada en la pantalla (ZLink, CarLink, TLink, AutoKit…): suele manejar Android Auto y CarPlay. */
    private String nativeProjectionApp() {
        for (String p : CARPLAY_PKGS) if (installed(p)) return p;
        ResolveInfo r = find("carplay|zlink|carlink|autokit|tlink|speedplay", "carplay|zlink|carlink|autokit|tlink");
        // EasyConnection/Carbit es solo espejo de pantalla: no es Android Auto ni CarPlay
        if (r != null && r.activityInfo.packageName.startsWith("net.easyconn")) return null;
        return r != null ? r.activityInfo.packageName : null;
    }

    private String carPlayApp() {
        String saved = prefs.getString("carplay", null);
        if (saved != null) return saved;
        return nativeProjectionApp();
    }

    /** Nombre visible de un paquete o de un componente guardado. */
    private String labelOf(String pkgOrComponent) {
        try {
            String pkg = pkgOrComponent.contains("/") ? ComponentName.unflattenFromString(pkgOrComponent).getPackageName() : pkgOrComponent;
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable e) {
            return "App elegida";
        }
    }

    private void openCarPlay() {
        if (launchSaved("carplay")) return;
        String p = nativeProjectionApp();
        if (p != null && launchPkg(p)) return;
        new AlertDialog.Builder(this, dlg())
            .setTitle("Apple CarPlay")
            .setMessage("CarPlay necesita un chip de autenticación de Apple dentro del equipo, así que no se puede activar solo con una app.\n\n• Si tu pantalla trae CarPlay integrado, deja presionado este botón y elige su app (ZLink, CarLink, TLink…).\n• Si no, puedes usar un adaptador CarPlay USB para pantallas Android (tipo Carlinkit/AutoKit): instala su app y este botón la abrirá.")
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Elegir app", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { pickFor("carplay", "CarPlay"); }
            }).show();
    }

    // ───────────────────────── tema (oscuro / claro / automático) ─────────────────────────

    static int dlg() { return LIGHT ? AlertDialog.THEME_DEVICE_DEFAULT_LIGHT : AlertDialog.THEME_DEVICE_DEFAULT_DARK; }

    private void applyTheme() {
        String mode = prefs.getString("theme", "dark");
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        LIGHT = "light".equals(mode) || ("auto".equals(mode) && hr >= 6 && hr < 18);
        if (LIGHT) {
            BG = 0xFFE9ECF1; CARD = 0xFFFFFFFF; INNER = 0xFFF1F3F7; BTN = 0xFFE2E6EC; DOCK = 0xFFDDE1E8;
            TEXT = 0xFF14161B; MUTED = 0xFF6B7280; SOFT = 0xFF3A404C; CIRCLE = 0xFFFFFFFF; ICON = 0xFF2B303A;
        } else {
            BG = 0xFF15171C; CARD = 0xFF1E2128; INNER = 0xFF262A33; BTN = 0xFF2A2E37; DOCK = 0xFF0D0E11;
            TEXT = 0xFFF2F3F5; MUTED = 0xFF8B9099; SOFT = 0xFFC5C8CE; CIRCLE = 0xFF23262E; ICON = 0xFFFFFFFF;
        }
        syncThemeToSeldia();
    }

    /** Envía el tema actual a Seldia Music (si está instalada) para que lo aplique. */
    private void syncThemeToSeldia() {
        try {
            Intent i = new Intent("com.fordlauncher.launcher.action.APPLY_THEME");
            i.setPackage("com.seldia.music");
            i.putExtra("theme", LIGHT ? "light" : "dark");
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    // ───────────────────────── clima ─────────────────────────

    private void maybeWeather() {
        if (!prefs.getBoolean("weather_on", true) || Double.isNaN(wLat)) { showWeather(); return; }
        if (SystemClock.elapsedRealtime() - weatherAt < 30 * 60 * 1000L && weatherNow != null) return;
        weatherAt = SystemClock.elapsedRealtime();
        Weather.fetch(this, wLat, wLon, new Weather.Listener() {
            public void onWeather(final Weather.Now n, String err) {
                h.post(new Runnable() { public void run() {
                    if (n != null) weatherNow = n; else weatherAt = SystemClock.elapsedRealtime() - 25 * 60 * 1000L; // reintenta en 5 min
                    showWeather();
                }});
            }
        });
    }

    private void refreshWeatherNow() { weatherAt = 0; weatherNow = null; maybeWeather(); }

    private void showWeather() {
        if (weatherBox == null) return;
        if (!prefs.getBoolean("weather_on", true) || weatherNow == null) { weatherBox.setVisibility(View.GONE); return; }
        weatherBox.setVisibility(View.VISIBLE);
        weatherIc.setImageBitmap(asset("ic/" + Weather.icon(weatherNow.code, weatherNow.day) + ".png"));
        weatherTv.setText(Math.round(weatherNow.temp) + "°" + weatherNow.unit);
        weatherCityTv.setText(weatherNow.city != null ? weatherNow.city : Weather.describe(weatherNow.code));
    }

    // ───────────────────────── panel de ajustes ─────────────────────────

    interface IntCb { void on(int i); }

    static final String[] CAT_NAMES = {"Volver al inicio", "Tu vehículo", "Apariencia", "Conexión", "Accesos y apps", "Mapa y clima", "Música", "Sistema", "Ajustes IA", "Acerca de"};
    static final String[] CAT_ICONS = {"arrow_back", "directions_car", "palette", "usb", "apps", "map", "music_note", "tune", "android", "info"};

    private void closeSettings() {
        settingsCat = -1;
        if (settingsView != null) { root.removeView(settingsView); settingsView = null; }
    }

    private void showSettings(int cat) {
        if (cat == 0) { closeSettings(); return; }
        settingsCat = cat;
        if (settingsView != null) root.removeView(settingsView);
        settingsView = new FrameLayout(this);
        settingsView.setBackgroundColor(BG);
        settingsView.setClickable(true);
        root.addView(settingsView, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout panel = new FrameLayout(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams((int) (1024 * s), (int) (600 * s));
        plp.gravity = Gravity.CENTER;
        settingsView.addView(panel, plp);

        // barra lateral
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackground(round(CARD, 20));
        side.setPadding((int) (10 * s), (int) (14 * s), (int) (10 * s), (int) (14 * s));
        place(panel, side, 16, 16, 240, 568);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding((int) (8 * s), 0, 0, (int) (12 * s));
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        brand.addView(logo, new LinearLayout.LayoutParams((int) (30 * s), (int) (30 * s)));
        TextView bt = tv("Ford Launcher", 18, TEXT, true);
        bt.setPadding((int) (10 * s), 0, 0, 0);
        brand.addView(bt);
        side.addView(brand);
        for (int i = 0; i < CAT_NAMES.length; i++) {
            final int idx = i;
            boolean sel = i == cat;
            LinearLayout it = new LinearLayout(this);
            it.setGravity(Gravity.CENTER_VERTICAL);
            it.setPadding((int) (12 * s), 0, (int) (8 * s), 0);
            if (sel) it.setBackground(round(RED_DARK, 12));
            ImageView ic = icon(CAT_ICONS[i], 20);
            if (sel) ic.setColorFilter(RED);
            else if (ICON == 0xFFFFFFFF) ic.setAlpha(0.7f);
            it.addView(ic, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
            TextView lt = tv(CAT_NAMES[i], 14, sel ? RED : SOFT, sel);
            lt.setPadding((int) (12 * s), 0, 0, 0);
            it.addView(lt);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, (int) (46 * s));
            ilp.bottomMargin = (int) (4 * s);
            side.addView(it, ilp);
            if (i == 0) {
                View d = new View(this);
                d.setBackgroundColor(BTN);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, (int) s));
                dl.topMargin = (int) (2 * s);
                dl.bottomMargin = (int) (8 * s);
                side.addView(d, dl);
            }
            it.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showSettings(idx); }
            });
        }

        // contenido
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        place(panel, sc, 272, 16, 736, 568);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding((int) (8 * s), (int) (6 * s), (int) (8 * s), (int) (24 * s));
        sc.addView(body, new FrameLayout.LayoutParams(-1, -2));
        TextView title = tv(CAT_NAMES[cat], 24, TEXT, true);
        title.setPadding((int) (4 * s), 0, 0, (int) (14 * s));
        body.addView(title);

        switch (cat) {
            case 1: catVehicle(body); break;
            case 2: catLook(body); break;
            case 3: catConnection(body); break;
            case 4: catApps(body); break;
            case 5: catMapWeather(body); break;
            case 6: catMusic(body); break;
            case 7: catSystem(body); break;
            case 8: catAi(body); break;
            case 9: catAbout(body); break;
        }
    }

    // ── piezas de diseño ──

    private LinearLayout section(LinearLayout body, String name) {
        TextView h2 = tv(name.toUpperCase(ES), 11, MUTED, true);
        h2.setPadding((int) (6 * s), (int) (10 * s), 0, (int) (8 * s));
        body.addView(h2);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 16));
        card.setPadding((int) (18 * s), (int) (4 * s), (int) (18 * s), (int) (4 * s));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = (int) (10 * s);
        body.addView(card, lp);
        return card;
    }

    private LinearLayout rowBase(LinearLayout card, String title, String sub) {
        if (card.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(BTN);
            card.addView(d, new LinearLayout.LayoutParams(-1, Math.max(1, (int) s)));
        }
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, (int) (12 * s), 0, (int) (12 * s));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(title, 15, TEXT, true);
        t.setSingleLine(false);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView st = tv(sub, 12, MUTED, false);
            st.setSingleLine(false);
            st.setPadding(0, (int) (3 * s), 0, 0);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    private void row(LinearLayout card, String title, String sub, String value, final Runnable onClick) {
        LinearLayout r = rowBase(card, title, sub);
        if (value != null) {
            TextView v = tv(value, 14, MUTED, false);
            v.setGravity(Gravity.RIGHT);
            v.setPadding((int) (12 * s), 0, (int) (4 * s), 0);
            r.addView(v, new LinearLayout.LayoutParams((int) (220 * s), -2));
        }
        if (onClick != null) {
            ImageView ch = icon("chevron_right", 20);
            ch.setAlpha(0.6f);
            r.addView(ch, new LinearLayout.LayoutParams((int) (20 * s), (int) (20 * s)));
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onClick.run(); } });
        }
    }

    private void segmented(LinearLayout card, String title, String sub, String[] opts, int sel, final IntCb cb) {
        LinearLayout r = rowBase(card, title, sub);
        LinearLayout g = new LinearLayout(this);
        g.setBackground(round(BTN, 18));
        int p = (int) (3 * s);
        g.setPadding(p, p, p, p);
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView o = tv(opts[i], 13, i == sel ? Color.WHITE : SOFT, i == sel);
            o.setGravity(Gravity.CENTER);
            o.setPadding((int) (14 * s), (int) (7 * s), (int) (14 * s), (int) (7 * s));
            if (i == sel) o.setBackground(round(RED, 15));
            o.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cb.on(idx); } });
            g.addView(o);
        }
        r.addView(g);
    }

    private void toggle(LinearLayout card, String title, String sub, final boolean on, final IntCb cb) {
        LinearLayout r = rowBase(card, title, sub);
        FrameLayout sw = new FrameLayout(this);
        sw.setBackground(round(on ? RED : BTN, 14));
        View knob = new View(this);
        GradientDrawable k = new GradientDrawable();
        k.setShape(GradientDrawable.OVAL);
        k.setColor(Color.WHITE);
        knob.setBackground(k);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams((int) (22 * s), (int) (22 * s));
        klp.gravity = (on ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        klp.leftMargin = klp.rightMargin = (int) (3 * s);
        sw.addView(knob, klp);
        r.addView(sw, new LinearLayout.LayoutParams((int) (50 * s), (int) (28 * s)));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cb.on(on ? 0 : 1); } });
    }

    private String appLabel(String key, String def) {
        String c = prefs.getString(key, null);
        return c == null ? def : labelOf(c);
    }

    private Runnable edit(final String key, final String title, final String def) {
        return new Runnable() { public void run() { editText(key, title, def); } };
    }

    // ── categorías ──

    private void catVehicle(LinearLayout body) {
        LinearLayout c = section(body, "Datos");
        row(c, "Tu nombre", "Aparece en el saludo de la pantalla de inicio", prefs.getString("user_name", ""), edit("user_name", "Tu nombre", ""));
        row(c, "Nombre del vehículo", null, prefs.getString("car_name", "Mi vehículo"), edit("car_name", "Nombre del vehículo", "Mi vehículo"));
        row(c, "Etiqueta", "Versión o placa. Déjala vacía para ocultarla", prefs.getString("car_tag", ""), edit("car_tag", "Etiqueta (vacío para ocultarla)", ""));
        LinearLayout o = section(body, "Odómetro y aceite");
        toggle(o, "Odómetro automático", "Suma los km recorridos con la velocidad OBD (y GPS si el OBD está desconectado). Siempre activo para que la cifra no se congele.", prefs.getBoolean("odo_auto", true), new IntCb() {
            public void on(int i) {
                prefs.edit().putBoolean("odo_auto", i == 1).apply();
                showSettings(1);
            }
        });
        int curOdo = prefInt("oil_current_odometer", prefInt("odo_km_total", 0));
        row(o, "Odómetro actual (km)", "Cifra del tablero. Al guardar, el contador se alinea a ese valor y sigue sumando solo.", String.format(ES, "%,d", curOdo), new Runnable() {
            public void run() { odometerDialog(); }
        });
        int lastOdo = prefInt("oil_last_service_odometer", 0);
        int kmPas = Math.max(0, curOdo - lastOdo);
        row(o, "Último cambio de aceite", "Se registra en la tarjeta ACEITE de la pantalla de inicio.", lastOdo > 0 ? String.format(ES, "%,d km recorridos", kmPas) : "Sin registrar", new Runnable() {
            public void run() { oilDialog(); }
        });
        LinearLayout f = section(body, "Wallpaper (Luces)");
        boolean hasDay = !prefs.getString("wallpaper_day_path", "").isEmpty() || CarPhoto.load(this) != null;
        boolean hasNight = !prefs.getString("wallpaper_night_path", "").isEmpty();
        row(f, "Foto Luces Apagadas", "Fondo para cuando las luces están apagadas", hasDay ? "Configurada" : "Sin foto",
            new Runnable() { public void run() { photoMenu(true); } });
        row(f, "Foto Luces Encendidas", "Fondo para cuando las luces están encendidas", hasNight ? "Configurada" : "Sin foto",
            new Runnable() { public void run() { photoMenu(false); } });
    }

    private void catLook(LinearLayout body) {
        LinearLayout c = section(body, "Tema");
        toggle(c, "Wallpaper automático por luces físicas", "Cambia la foto de fondo según las luces del vehículo", prefs.getBoolean("auto_headlight_theme", true), new IntCb() {
            public void on(int i) {
                prefs.edit().putBoolean("auto_headlight_theme", i == 1).apply();
                applyTheme(); applyAccent(); rebuild();
            }
        });
        String t = prefs.getString("theme", "dark");
        segmented(c, "Modo", "Automático: claro de 6 a.m. a 6 p.m.", new String[]{"Oscuro", "Claro", "Automático"},
            "light".equals(t) ? 1 : "auto".equals(t) ? 2 : 0, new IntCb() { public void on(int i) {
                prefs.edit().putString("theme", i == 1 ? "light" : i == 2 ? "auto" : "dark").apply();
                applyTheme(); applyAccent(); rebuild();
            }});
        LinearLayout a = section(body, "Color de acento");
        LinearLayout r = rowBase(a, "Color", "Botones, anillo del reproductor, ruta y etiquetas");
        LinearLayout dots = new LinearLayout(this);
        int cur = prefs.getInt("accent", 0xFFFE3211);
        for (int i = 0; i < ACCENT_COLORS.length; i++) {
            final int col = ACCENT_COLORS[i];
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            if (col == cur) g.setStroke((int) (3 * s), TEXT);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (28 * s), (int) (28 * s));
            lp.leftMargin = (int) (8 * s);
            dots.addView(d, lp);
            d.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                prefs.edit().putInt("accent", col).apply(); applyAccent(); rebuild();
            }});
        }
        r.addView(dots);
        LinearLayout h = section(body, "Reloj");
        String ck = prefs.getString("clock", "auto");
        segmented(h, "Formato de hora", null, new String[]{"Auto", "12 h", "24 h"}, "12".equals(ck) ? 1 : "24".equals(ck) ? 2 : 0,
            new IntCb() { public void on(int i) {
                prefs.edit().putString("clock", i == 1 ? "12" : i == 2 ? "24" : "auto").apply(); updateClock(); showSettings(2);
            }});
        LinearLayout p = section(body, "Modo estacionado");
        toggle(p, "Modo estacionado", "Tras 5 minutos sin tocar el inicio (y sin ir en movimiento) se ve un reloj grande o tu imagen. Un toque lo quita.", prefs.getBoolean("parked_mode_enabled", false), new IntCb() { public void on(int i) {
            prefs.edit().putBoolean("parked_mode_enabled", i == 1).apply(); showSettings(2);
        }});
        String pt = prefs.getString("parked_mode_type", "clock");
        segmented(p, "Imagen", "Reloj grande o la foto de tu auto", new String[]{"Reloj", "Tu imagen"}, "image".equals(pt) ? 1 : 0,
            new IntCb() { public void on(int i) {
                prefs.edit().putString("parked_mode_type", i == 1 ? "image" : "clock").apply(); showSettings(2);
            }});
    }

    private void catAi(LinearLayout body) {
        LinearLayout c = section(body, "Copiloto IA");
        String apiKey = prefs.getString("gemini_api_key", "");
        row(c, "Clave API de Gemini", "Diagnóstico con IA en las pestañas Salud y Códigos (gratis en Google AI Studio)", (apiKey == null || apiKey.trim().length() == 0) ? "Sin clave" : "Configurada",
            edit("gemini_api_key", "Clave API de Gemini", ""));
        toggle(c, "Botón flotante de copiloto IA", "Muestra un botón flotante movible para hablarle a la IA", prefs.getBoolean("pref_enable_floating_ai_button", true), new IntCb() { public void on(int i) {
            prefs.edit().putBoolean("pref_enable_floating_ai_button", i == 1).apply(); refreshFloatingAi(); showSettings(8);
        }});
        toggle(c, "Leer diagnóstico en voz alta", "Lee automáticamente la respuesta de la IA al terminar de analizar", prefs.getBoolean("tts_auto_read", true), new IntCb() { public void on(int i) {
            prefs.edit().putBoolean("tts_auto_read", i == 1).apply(); showSettings(8);
        }});
        LinearLayout v = section(body, "Voz");
        String eng = prefs.getString("tts_engine", null);
        row(v, "Motor de voz", "Qué motor lee el diagnóstico", (eng == null || eng.length() == 0) ? "Automático (sistema)" : eng,
            new Runnable() { public void run() { pickTtsEngine(); } });
        String vname = prefs.getString("tts_voice", null);
        row(v, "Voz", "La voz concreta que lee (idioma, tono)", voiceLabel(vname),
            new Runnable() { public void run() { pickTtsVoice(); } });
        row(v, "Instalar / importar voces", "Descarga voces en español u otros idiomas", "Instalar",
            new Runnable() { public void run() { installTtsVoices(); } });
    }

    private void pickTtsEngine() {
        openTts(new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) { toast("No se pudo iniciar la voz"); return; }
                java.util.List<android.speech.tts.TextToSpeech.EngineInfo> engines = null;
                try { engines = getPrefsTts().getEngines(); } catch (Throwable ignored) {}
                if (engines == null || engines.isEmpty()) { toast("No hay motores de voz instalados"); return; }
                final String[] names = new String[engines.size() + 1];
                final String[] labels = new String[engines.size() + 1];
                names[0] = null; labels[0] = "Automático (la del sistema)";
                for (int i = 0; i < engines.size(); i++) {
                    names[i + 1] = engines.get(i).name;
                    String lab = null;
                    try { lab = engines.get(i).label; } catch (Throwable ignored) {}
                    labels[i + 1] = lab != null ? lab : engines.get(i).name;
                }
                new AlertDialog.Builder(Main.this, dlg())
                    .setTitle("Motor de voz")
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String chosen = names[w];
                            if (chosen == null) prefs.edit().remove("tts_engine").remove("tts_voice").apply();
                            else prefs.edit().putString("tts_engine", chosen).remove("tts_voice").apply();
                            finishTts();
                            showSettings(8);
                        }
                    })
                    .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { finishTts(); } })
                    .show();
            }
        });
    }

    private void pickTtsVoice() {
        // Voces en la nube (naturales, requieren internet): no necesitan TTS local.
        final String[] CLOUD_NAMES = new String[]{
            "edge:es-CO-GonzaloNeural", "edge:es-MX-JorgeNeural", "edge:es-AR-TomasNeural", "edge:es-ES-AlvaroNeural",
        };
        final String[] CLOUD_LABELS = new String[]{
            "Nube natural — es-CO · Gonzalo (hombre colombiano)",
            "Nube natural — es-MX · Jorge (hombre mexicano)",
            "Nube natural — es-AR · Tomas (hombre argentino)",
            "Nube natural — es-ES · Alvaro (hombre español)",
        };
        openTts(new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS && SDK >= 21) {
                    // Sin TTS local: igual ofrecer las voces de nube.
                    showVoicePicker(new java.util.ArrayList<android.speech.tts.Voice>(), CLOUD_NAMES, CLOUD_LABELS);
                    return;
                }
                if (status != TextToSpeech.SUCCESS) { toast("No se pudo iniciar la voz"); return; }
                if (SDK < 21) { toast("Voces seleccionables desde Android 5"); return; }
                java.util.Set<android.speech.tts.Voice> voices = null;
                try { voices = getPrefsTts().getVoices(); } catch (Throwable ignored) {}
                java.util.List<android.speech.tts.Voice> local =
                    (voices != null) ? new java.util.ArrayList<android.speech.tts.Voice>(voices) : new java.util.ArrayList<android.speech.tts.Voice>();
                showVoicePicker(local, CLOUD_NAMES, CLOUD_LABELS);
            }
        });
    }

    private void showVoicePicker(final java.util.List<android.speech.tts.Voice> voices, String[] cloudNames, String[] cloudLabels) {
        final int n = cloudNames.length + 1 + voices.size();
        final String[] names = new String[n];
        final String[] labels = new String[n];
        for (int i = 0; i < cloudNames.length; i++) { names[i] = cloudNames[i]; labels[i] = cloudLabels[i]; }
        names[cloudNames.length] = null; labels[cloudNames.length] = "Automática (español)";
        for (int i = 0; i < voices.size(); i++) {
            android.speech.tts.Voice voice = voices.get(i);
            names[cloudNames.length + 1 + i] = voice.getName();
            String loc = null;
            try { loc = voice.getLocale().toLanguageTag(); } catch (Throwable ignored) { loc = voice.getLocale().getLanguage(); }
            labels[cloudNames.length + 1 + i] = loc + " — " + voice.getName();
        }
        new AlertDialog.Builder(Main.this, dlg())
            .setTitle("Voz")
            .setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String chosen = names[w];
                    if (chosen == null) prefs.edit().remove("tts_voice").apply();
                    else prefs.edit().putString("tts_voice", chosen).apply();
                    finishTts();
                    com.fordlauncher.launcher.ai.TextToSpeechManager tm = com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(Main.this);
                    tm.reiniciarVoz();
                    String nombre = prefs.getString("user_name", "").trim();
                    tm.hablarTextoLargo("Hola " + (nombre.length() > 0 ? nombre : "amigo") + ", esta es mi voz actual.");
                    showSettings(8);
                }
            })
            .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { finishTts(); } })
            .show();
    }

    private static String voiceLabel(String v) {
        if (v == null || v.length() == 0) return "Automática (español)";
        if (v.startsWith("edge:")) {
            String n = v.substring(5);
            if ("es-CO-GonzaloNeural".equals(n)) return "Nube · es-CO Gonzalo";
            if ("es-MX-JorgeNeural".equals(n)) return "Nube · es-MX Jorge";
            if ("es-AR-TomasNeural".equals(n)) return "Nube · es-AR Tomas";
            if ("es-ES-AlvaroNeural".equals(n)) return "Nube · es-ES Alvaro";
            return "Nube · " + n;
        }
        return v;
    }

    private void installTtsVoices() {
        String action = "android.speech.tts.engine.INSTALL_TTS_DATA";
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try { startActivity(new Intent(action)); }
            catch (Throwable t2) { toast("Este equipo no trae instalador de voces"); }
        }
    }

    private TextToSpeech prefTts;
    private TextToSpeech getPrefsTts() { return prefTts; }

    private void openTts(TextToSpeech.OnInitListener listener) {
        closePrefTts();
        String eng = prefs.getString("tts_engine", null);
        try {
            prefTts = (eng != null && eng.length() > 0) ? new TextToSpeech(this, listener, eng) : new TextToSpeech(this, listener);
        } catch (Throwable t) {
            try { prefTts = new TextToSpeech(this, listener); } catch (Throwable t2) { toast("No se pudo iniciar la voz"); }
        }
    }

    private void closePrefTts() {
        try { if (prefTts != null) { prefTts.shutdown(); } } catch (Throwable ignored) {}
        prefTts = null;
    }

    private void finishTts() { closePrefTts(); }

    private void catConnection(LinearLayout body) {
        LinearLayout c = section(body, "Celular");
        String chosen = prefs.getString("conn_app", null);
        row(c, "Cómo conectar el celular", "La app que se abre al conectar por USB", chosen != null ? labelOf(chosen) : "Preguntar",
            new Runnable() { public void run() { chooseConnection(null); } });
        row(c, "App de Android Auto", "Botón naranja de la tarjeta central", appLabel("aa", "Automática"),
            new Runnable() { public void run() { pickFor("aa", "Android Auto"); } });
        row(c, "App de CarPlay", "Requiere pantalla con CarPlay o un adaptador", appLabel("carplay", carPlayApp() != null ? labelOf(carPlayApp()) : "Ninguna"),
            new Runnable() { public void run() { pickFor("carplay", "CarPlay"); } });
        LinearLayout hl = section(body, "Ayuda");
        row(hl, "El celular dice «Ninguna aplic. funciona…»", "Cómo evitar que EasyConnection tome el USB", null,
            new Runnable() { public void run() { usbHelp(); } });
    }

    private void catApps(LinearLayout body) {
        LinearLayout c = section(body, "Accesos de la barra inferior");
        String[][] items = {{"radio", "Radio"}, {"bt", "Bluetooth"}, {"music", "Música"}, {"cam", "Cámara"}, {"web", "Navegador"}, {"files", "Archivos"}};
        for (final String[] it : items) {
            row(c, it[1], null, "web".equals(it[0]) ? appLabel(it[0], "Automático") : shortcutLabel(it[0]), new Runnable() { public void run() { pickFor(it[0], it[1]); } });
        }
        row(c, "Diagnóstico", "OBD2 (por ahora muestra resumen)", "OBD2 próximo", new Runnable() { public void run() { openObdDiag(); } });
        row(c, "Música Bluetooth", "La app que hace sonar el Bluetooth (se usa para pasar el sonido)", shortcutLabel("btaudio"), new Runnable() { public void run() { pickFor("btaudio", "Música Bluetooth"); } });
        row(c, "Revisar todos los botones", "Lista rápida de qué abre cada uno, como en el asistente", null, new Runnable() { public void run() {
            reviewShortcuts(new Runnable() { public void run() { if (settingsView != null) showSettings(4); } });
        }});
        LinearLayout r = section(body, "Restablecer");
        row(r, "Restablecer accesos", "Vuelve a buscar las apps de fábrica automáticamente", null, new Runnable() { public void run() {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new String[]{"radio", "bt", "music", "cam", "files", "settings", "aa", "web", "carplay"}) e.remove(k);
            e.apply();
            toast("Accesos restablecidos");
            refreshStates();
            showSettings(4);
        }});
    }

    private void catMapWeather(LinearLayout body) {
        LinearLayout w = section(body, "Clima");
        toggle(w, "Mostrar el clima", "Arriba, junto al saludo (necesita internet)", prefs.getBoolean("weather_on", true), new IntCb() { public void on(int i) {
            prefs.edit().putBoolean("weather_on", i == 1).apply(); refreshWeatherNow(); showSettings(5);
        }});
        segmented(w, "Unidad", null, new String[]{"°C", "°F"}, "F".equals(prefs.getString("temp_unit", "C")) ? 1 : 0, new IntCb() { public void on(int i) {
            prefs.edit().putString("temp_unit", i == 1 ? "F" : "C").apply(); refreshWeatherNow(); showSettings(5);
        }});
        String manual = prefs.getString("city_manual", "");
        row(w, "Ciudad", "Vacío = automática por GPS", manual.length() > 0 ? manual : (weatherNow != null && weatherNow.city != null ? weatherNow.city + " (GPS)" : "Automática"),
            edit("city_manual", "Ciudad (vacío = automática por GPS)", ""));
        LinearLayout m = section(body, "Mapa");
        segmented(m, "Brillo del mapa", "Solo en modo oscuro", new String[]{"Bajo", "Medio", "Alto"}, prefs.getInt("map_bright", 1), new IntCb() { public void on(int i) {
            prefs.edit().putInt("map_bright", i).apply(); rebuild();
        }});
        row(m, "Diagnóstico del mapa", "Fuentes probadas y errores", null, new Runnable() { public void run() {
            new AlertDialog.Builder(Main.this, dlg()).setTitle("Diagnóstico del mapa")
                .setMessage(liveMap != null ? liveMap.diag() : "Mapa no disponible").setPositiveButton("OK", null).show();
        }});
        row(m, "Fuente del mapa (avanzado)", "URL propia con {z}/{x}/{y}", prefs.getString("map_url", "").length() > 0 ? "Personalizada" : "Automática",
            edit("map_url", "URL de teselas {z}/{x}/{y} (vacío = automático)", ""));
    }

    private void catMusic(LinearLayout body) {
        LinearLayout c = section(body, "Pista en reproducción");
        row(c, "Carátula de Android Auto", "Lee la notificación de Open Headunit (accesibilidad)", NotifArt.running ? "Activada" : "Desactivada",
            new Runnable() { public void run() { openAccessibility(); } });
        if (SDK >= 18) row(c, "Música de cualquier app", "Android 5+: acceso a notificaciones", MediaListener.connected ? "Activado" : "Desactivado",
            new Runnable() { public void run() {
                try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Throwable e) { toast("No se pudo abrir el ajuste"); }
            }});
        row(c, "Diagnóstico del reproductor", "Fuente activa, lector de notificaciones y Bluetooth", null, new Runnable() { public void run() { showPlayerDiag(); } });
        LinearLayout b = section(body, "Audio Bluetooth");
        if (clwBt) {
            row(b, "Escuchar el Bluetooth", "En esta pantalla, toca ▶ en el inicio: Ford Launcher pasa el sonido a Bluetooth (por ejemplo, si venías de la radio)", null, null);
            toggle(b, "Pasar a Bluetooth al dar play en el celular (en prueba)", "Abre un instante «Audio Bluetooth» y vuelve. En algunas pantallas no detecta el play del celular",
                prefs.getBoolean("bt_auto3", false), new IntCb() { public void on(int i) {
                    prefs.edit().putBoolean("bt_auto3", i == 1).apply(); showSettings(6);
                }});
            row(b, "Probar ahora", "Abre «Audio Bluetooth» un instante y vuelve al inicio", null,
                new Runnable() { public void run() { closeSettings(); openBtAudioApp(true); } });
        } else {
            toggle(b, "Abrir la app de música Bluetooth al conectar el celular", "Para pantallas que solo dejan sonar el Bluetooth con su app abierta: la abre un momento y vuelve al inicio",
                prefs.getBoolean("bt_open_connect", false), new IntCb() { public void on(int i) {
                    prefs.edit().putBoolean("bt_open_connect", i == 1).apply(); showSettings(6);
                }});
            ComponentName cn = btMusicApp();
            row(b, "App de música Bluetooth", "La que hace sonar el Bluetooth en esta pantalla",
                cn != null ? labelOf(cn.flattenToString()) : "No encontrada",
                new Runnable() { public void run() { pickFor("btaudio", "Música Bluetooth"); } });
            segmented(b, "Tiempo antes de volver al inicio", "Súbelo si el audio se corta al volver", new String[]{"1,5 s", "2,5 s", "4 s"},
                prefs.getInt("bt_return_ms", 2500) <= 1500 ? 0 : prefs.getInt("bt_return_ms", 2500) >= 4000 ? 2 : 1, new IntCb() { public void on(int i) {
                    prefs.edit().putInt("bt_return_ms", i == 0 ? 1500 : i == 2 ? 4000 : 2500).apply(); showSettings(6);
                }});
            row(b, "Probar ahora", "Abre la app de música Bluetooth y vuelve al inicio", null,
                new Runnable() { public void run() { closeSettings(); openBtAudioApp(true); } });
        }
        LinearLayout a = section(body, "Avanzado");
        row(a, "Avisos extra de música", "Nombres de avisos (broadcast) de otros equipos", prefs.getString("extra_actions", "").length() > 0 ? "Configurados" : "Ninguno",
            edit("extra_actions", "Acciones de aviso extra (separadas por coma)", ""));
    }

    private void catSystem(LinearLayout body) {
        LinearLayout c = section(body, "Equipo");
        row(c, "Ajustes de Android", null, null, new Runnable() { public void run() {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }});
        row(c, "Asistente de configuración inicial", "Nombre, vehículo, color y foto", null, new Runnable() { public void run() { closeSettings(); wizard(); } });
        LinearLayout k = section(body, "Compatibilidad");
        row(k, "Qué funciona en esta pantalla", "Cada función, desde qué Android sirve y su estado aquí", null, new Runnable() { public void run() { showCompat(); } });
        LinearLayout d = section(body, "Soporte");
        row(d, "Exportar diagnóstico", "Guarda información de las apps de audio en la memoria interna", null, new Runnable() { public void run() { runDiag(); } });
        toggle(d, "Grabar registros de sistema (Logcat)", "Guarda logcat_fordlauncher.txt en USB o Descargas", prefs.getBoolean("logcat_recording", false), new IntCb() {
            public void on(int i) {
                boolean enable = (i == 1);
                prefs.edit().putBoolean("logcat_recording", enable).apply();
                Intent svcIntent = new Intent(Main.this, LogcatRecorderService.class);
                if (enable) {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1001);
                        }
                    }
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(svcIntent);
                        } else {
                            startService(svcIntent);
                        }
                        toast("Grabación de Logcat iniciada");
                    } catch (Throwable e) {
                        toast("No se pudo iniciar el servicio de Logcat");
                    }
                } else {
                    stopService(svcIntent);
                    toast("Grabación de Logcat detenida");
                }
                showSettings(7);
            }
        });
    }

    /** Tabla de compatibilidad: qué función sirve desde qué Android o marca de pantalla, y cómo está aquí. */
    private void showCompat() {
        StringBuilder b = new StringBuilder();
        b.append("Esta pantalla: Android ").append(android.os.Build.VERSION.RELEASE).append(" (API ").append(SDK).append(")")
         .append(" · ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n");
        if (!android.os.Build.VERSION.RELEASE.startsWith(androidName(SDK).split(" ")[0]))
            b.append("Ojo: la pantalla dice Android ").append(android.os.Build.VERSION.RELEASE).append(", pero por dentro es ").append(androidName(SDK)).append(".\n");
        b.append("\n");
        compatLine(b, "Inicio, mapa, clima, velocímetro", "Android 4.1+", true, null);
        compatLine(b, "Pista y carátula por Bluetooth", "Android 4.1+", true, clwBt ? "app Bluetooth de fábrica detectada" : "depende de la marca");
        compatLine(b, "Botón ▶ del inicio pasa el sonido a Bluetooth", "Pantallas Clinkway / Autochips", clwBt, clwBt ? "funciona aunque vengas de la radio" : "en otras marcas: «Abrir la app de música Bluetooth al conectar»");
        compatLine(b, "Pasar a Bluetooth al dar play en el celular (en prueba)", "Pantallas Clinkway / Autochips", clwBt, clwBt ? (btAutoOn() ? "activado" : "desactivado; en algunas pantallas no detecta el play del celular") : null);
        compatLine(b, "Carátula de Android Auto (Open Headunit)", "Android 4.1+ · accesibilidad", true, NotifArt.running ? "activada" : "falta activar la accesibilidad");
        compatLine(b, "Música de cualquier app (Spotify, YouTube…)", "Android 5+", SDK >= 21, SDK >= 21 ? (MediaListener.connected ? "activada" : "falta dar acceso a notificaciones") : "no disponible en Android " + androidName(SDK));
        compatLine(b, "Android Auto con Open Headunit", "Android 4.1+ · cable USB", true, installed(PKG_HUR) ? "Open Headunit instalado" : "falta instalar Open Headunit");
        compatLine(b, "Android Auto / CarPlay de la pantalla", "Si la pantalla lo trae", nativeProjectionApp() != null, nativeProjectionApp() != null ? labelOf(nativeProjectionApp()) : "no detectado");
        compatLine(b, "CarPlay", "Pantalla con CarPlay o adaptador USB", carPlayApp() != null, carPlayApp() != null ? labelOf(carPlayApp()) : "no detectado");
        compatLine(b, "Mapa con internet seguro (TLS 1.2)", "Android 4.1+ (Ford Launcher lo activa)", true, SDK < 20 ? "en Android 4.x solo algunos servidores de mapas responden" : null);
        compatLine(b, "Instalar actualizaciones desde la app", "Android 4.1+", true, SDK >= 26 ? "pide permiso «Instalar apps desconocidas»" : null);
        new AlertDialog.Builder(this, dlg()).setTitle("Compatibilidad").setMessage(b.toString().trim()).setPositiveButton("OK", null).show();
    }

    private static void compatLine(StringBuilder b, String name, String needs, boolean ok, String note) {
        b.append(ok ? "✓ " : "✗ ").append(name).append("\n     Requiere: ").append(needs);
        if (note != null) b.append("\n     Aquí: ").append(note);
        b.append("\n\n");
    }

    static String androidName(int api) {
        String[] n = {"4.1", "4.2", "4.3", "4.4", "4.4W", "5.0", "5.1", "6.0", "7.0", "7.1", "8.0", "8.1", "9", "10", "11", "12", "12L", "13", "14", "15", "16"};
        int i = api - 16;
        return i >= 0 && i < n.length ? "Android " + n[i] : "API " + api;
    }

    private void catAbout(LinearLayout body) {
        LinearLayout c = section(body, "Ford Launcher");
        LinearLayout r = rowBase(c, "Ford Launcher · Auto Head Unit UI", "Gratis y de código abierto. Una nueva vida para las pantallas Android del carro.");
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        r.addView(logo, 0, new LinearLayout.LayoutParams((int) (56 * s), (int) (56 * s)));
        ((LinearLayout.LayoutParams) r.getChildAt(1).getLayoutParams()).leftMargin = (int) (14 * s);
        row(c, "Versión", null, Updates.currentVersion(this), null);
        row(c, "Android de la pantalla", "Real: " + androidName(SDK) + " · ver qué funciona aquí", android.os.Build.VERSION.RELEASE + " (API " + SDK + ")", new Runnable() { public void run() { showCompat(); } });

        LinearLayout u = section(body, "Actualizaciones");
        final boolean fd = Updates.fromFdroid(this);
        if (fd) row(u, "Instalado desde F-Droid", "Las actualizaciones llegan por la app de F-Droid", null, null);
        toggle(u, "Comprobar actualizaciones automáticamente", "Al abrir Ford Launcher con internet, una vez al día",
            prefs.getBoolean("auto_update", !fd), new IntCb() { public void on(int i) {
                prefs.edit().putBoolean("auto_update", i == 1).apply(); showSettings(9);
            }});
        row(u, "Canal de actualizaciones", null, "GitHub Releases", null);
        String ghTok = prefs.getString("gh_token", null);
        row(u, "Token de GitHub (opcional)", "Sube el límite de comprobaciones si tu red es compartida", (ghTok == null || ghTok.trim().isEmpty()) ? "Sin token" : "Configurado",
            edit("gh_token", "Token de GitHub (deja vacío para quitarlo)", ""));
        long last = prefs.getLong("upd_last", 0);
        row(u, "Buscar actualizaciones", last == 0 ? "Aún no se ha comprobado"
                : "Última comprobación: " + new SimpleDateFormat("yyyy-MM-dd HH:mm", ES).format(new Date(last)), null,
            new Runnable() { public void run() { checkUpdates(true); } });

        LinearLayout k = section(body, "Comunidad");
        row(k, "Dar una estrella en GitHub", "Ayuda a que más personas encuentren Ford Launcher", "★",
            new Runnable() { public void run() { linkDialog("Dar una estrella ⭐", "Abre el repositorio y toca «Star». ¡Gracias!", Updates.REPO_URL); } });
        row(k, "Donar", "Si Ford Launcher revivió tu pantalla, invítame un café", "☕",
            new Runnable() { public void run() { linkDialog("Apoya el proyecto ☕", "Ford Launcher es gratis. Las donaciones ayudan a mantenerlo y a probarlo en más pantallas.", Updates.donateUrl(Main.this)); } });
        row(k, "Reportar un problema o sugerir algo", "Crea un «issue» en GitHub (adjunta el diagnóstico)", null,
            new Runnable() { public void run() { linkDialog("Reportar un problema", "Cuenta tu modelo de pantalla y su versión de Android. Puedes adjuntar la carpeta FordDiag (Sistema → Exportar diagnóstico).", Updates.ISSUES_URL); } });
        row(k, "Código fuente", Updates.REPO_URL.replace("https://", ""), null,
            new Runnable() { public void run() { linkDialog("Código fuente", "Licencia GPL-3.0", Updates.REPO_URL); } });

        LinearLayout l = section(body, "Créditos y licencias");
        row(l, "Licencia", "GNU General Public License v3.0", null, null);
        row(l, "Mapas", "© colaboradores de OpenStreetMap, OpenTopoMap, Esri", null, null);
        row(l, "Clima", "Open-Meteo.com (CC BY 4.0)", null, null);
        row(l, "Android Auto", "Open Headunit (GPL-3.0) · Android Auto es marca de Google", null, null);
        row(l, "Códigos QR", "QR Code generator library · Project Nayuki (MIT)", null, null);
        row(l, "Íconos", "Material Symbols (Apache 2.0)", null, null);
        row(l, "Certificados raíz", "Mozilla CA (certifi, MPL-2.0)", null, null);
    }

    // ───────────────────────── actualizaciones, estrella y donaciones ─────────────────────────

    private boolean updBusy, promptShown;

    /** Al volver al inicio: busca versión nueva (1 vez al día) o, si toca, el recuadro «¿Te gusta Ford Launcher?». */
    private void autoChecks() {
        if (!hasWindowFocus() || settingsView != null || drawer != null) return;
        long now = System.currentTimeMillis();
        boolean auto = prefs.getBoolean("auto_update", !Updates.fromFdroid(this));
        if (auto && now - prefs.getLong("upd_last", 0) > 20L * 3600 * 1000) { checkUpdates(false); return; }
        // recuadro tipo SimpMusic: tras 5 días de uso y 15 aperturas, y luego cada 45 días si eligió «Más tarde»
        int opens = prefs.getInt("opens", 0) + 1;
        long first = prefs.getLong("first_use", 0);
        SharedPreferences.Editor e = prefs.edit().putInt("opens", opens);
        if (first == 0) e.putLong("first_use", first = now);
        e.apply();
        long next = prefs.getLong("enjoy_next", first + 5L * 24 * 3600 * 1000);
        if (!promptShown && opens >= 15 && now >= next && !prefs.getBoolean("enjoy_done", false)) {
            promptShown = true;
            enjoyDialog();
        }
    }

    private void checkUpdates(final boolean manual) {
        if (updBusy) return;
        updBusy = true;
        if (manual) toast("Buscando actualizaciones…");
        Updates.check(this, new Updates.Listener() {
            public void onResult(final Updates.Release r, final String err) {
                h.post(new Runnable() { public void run() {
                    updBusy = false;
                    if (settingsView != null && settingsCat == 9) showSettings(9);
                    if (r != null) {
                        if (manual || !r.version.equals(prefs.getString("upd_skip", ""))) updateDialog(r);
                    } else if (manual) {
                        if (err != null) linkDialog("No se pudo comprobar", "Esta pantalla no logró conectarse con GitHub (" + shortNetErr(err) + "). Fuentes:\n" + Updates.lastReport + "\n\nPuedes revisar las versiones desde tu celular:", Updates.RELEASES_URL);
                        else new AlertDialog.Builder(Main.this, dlg()).setTitle("Tienes la última versión (" + Updates.currentVersion(Main.this) + ")")
                            .setMessage("Fuentes consultadas:\n" + Updates.lastReport).setPositiveButton("OK", null).show();
                    }
                }});
            }
        });
    }

    private static String shortNetErr(String e) {
        if (e.contains("SSL") || e.contains("handshake")) return "cifrado no compatible";
        if (e.contains("UnknownHost")) return "sin internet";
        if (e.contains("Timeout")) return "tiempo agotado";
        int i = e.indexOf("HTTP ");
        if (i >= 0) return e.substring(i, Math.min(e.length(), i + 14));
        return "error de red";
    }

    private void updateDialog(final Updates.Release r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * s);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView v = tv("Tienes la " + Updates.currentVersion(this) + " · nueva: " + r.version, 13, MUTED, false);
        box.addView(v);
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        TextView notes = tv(Updates.plain(r.notes).length() > 0 ? Updates.plain(r.notes) : "Mejoras y correcciones.", 14, TEXT, false);
        notes.setSingleLine(false);
        notes.setPadding(0, (int) (10 * s), 0, (int) (10 * s));
        sc.addView(notes);
        box.addView(sc, new LinearLayout.LayoutParams(-1, (int) (200 * s)));
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg())
            .setTitle("Nueva versión de Ford Launcher")
            .setView(box)
            .setPositiveButton(r.apkUrl != null ? "Descargar e instalar" : "Ver en GitHub", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (r.apkUrl != null) downloadUpdate(r); else linkDialog("Ford Launcher " + r.version, "Descarga la nueva versión desde:", r.pageUrl);
                }
            })
            .setNeutralButton("Omitir esta versión", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { prefs.edit().putString("upd_skip", r.version).apply(); }
            })
            .setNegativeButton("Más tarde", null);
        b.show();
    }

    /** Descarga el APK y abre el instalador; si esta pantalla no puede conectarse, muestra el código QR. */
    private void downloadUpdate(final Updates.Release r) {
        if (!unknownSourcesOk()) return;
        final ProgressDialogLite pd = new ProgressDialogLite("Descargando Ford Launcher " + r.version + "…");
        new Thread(new Runnable() { public void run() {
            File out = null;
            try {
                byte[] data = Net.get(r.apkUrl, 60000);
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                dir.mkdirs();
                out = new File(dir, "FordLauncher-" + r.version + ".apk");
                java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                os.write(data);
                os.close();
                out.setReadable(true, false);
            } catch (Throwable e) { out = null; }
            final File f = out;
            runOnUiThread(new Runnable() { public void run() {
                pd.dismiss();
                if (f != null) installFile(f, "Ford Launcher " + r.version);
                else linkDialog("Descárgala desde tu celular", "Esta pantalla no pudo descargar el archivo. Escanea el código, descarga el APK y pásalo por USB.", r.pageUrl);
            }});
        }}).start();
    }

    private void enjoyDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (22 * s);
        box.setPadding(pad, pad, pad, (int) (6 * s));
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("logo.png"));
        box.addView(logo, new LinearLayout.LayoutParams((int) (64 * s), (int) (64 * s)));
        TextView t = tv("¿Disfrutando Ford Launcher?", 20, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, (int) (14 * s), 0, (int) (8 * s));
        box.addView(t);
        TextView m = tv("Si te gusta usar Ford Launcher, dale una estrella en GitHub. Y si revivió tu pantalla, considera invitarme un café ☕", 14, SOFT, false);
        m.setSingleLine(false);
        m.setGravity(Gravity.CENTER);
        box.addView(m);
        new AlertDialog.Builder(this, dlg())
            .setView(box)
            .setPositiveButton("Dar una estrella", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putBoolean("enjoy_done", true).apply();
                    linkDialog("Dar una estrella ⭐", "Abre el repositorio y toca «Star». ¡Gracias!", Updates.REPO_URL);
                }
            })
            .setNeutralButton("Donar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putBoolean("enjoy_done", true).apply();
                    linkDialog("Apoya el proyecto ☕", "Ford Launcher es gratis. Las donaciones ayudan a mantenerlo.", Updates.donateUrl(Main.this));
                }
            })
            .setNegativeButton("Más tarde", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putLong("enjoy_next", System.currentTimeMillis() + 45L * 24 * 3600 * 1000).apply();
                }
            })
            .show();
    }

    /** Enlace con código QR: en pantallas viejas el navegador no abre GitHub, pero el celular sí. */
    private void linkDialog(String title, String msg, final String url) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * s);
        box.setPadding(pad, pad, pad, (int) (6 * s));
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(url, (int) (200 * s)));
        box.addView(qr, new LinearLayout.LayoutParams((int) (200 * s), (int) (200 * s)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (20 * s), 0, 0, 0);
        TextView m = tv(msg, 14, SOFT, false);
        m.setSingleLine(false);
        col.addView(m);
        TextView hint = tv("Escanéalo con la cámara de tu celular o entra a:", 12, MUTED, false);
        hint.setSingleLine(false);
        hint.setPadding(0, (int) (12 * s), 0, (int) (4 * s));
        col.addView(hint);
        TextView link = tv(url.replace("https://", ""), 13, RED, true);
        link.setSingleLine(false);
        col.addView(link);
        box.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        new AlertDialog.Builder(this, dlg())
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Abrir aquí", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Throwable e) { toast("No hay navegador disponible"); }
                }
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private static Bitmap qrBitmap(String text, int size) {
        io.nayuki.qrcodegen.QrCode q = io.nayuki.qrcodegen.QrCode.encodeText(text, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM);
        int border = 2, n = q.size + border * 2;
        int scale = Math.max(1, size / n);
        Bitmap b = Bitmap.createBitmap(n * scale, n * scale, Bitmap.Config.RGB_565);
        b.eraseColor(Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(Color.BLACK);
        for (int y = 0; y < q.size; y++)
            for (int x = 0; x < q.size; x++)
                if (q.getModule(x, y)) c.drawRect((x + border) * scale, (y + border) * scale, (x + border + 1) * scale, (y + border + 1) * scale, p);
        return b;
    }

    // ───────────────────────── apps ─────────────────────────

    private void loadApps() {
        apps.clear();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        final PackageManager pm = getPackageManager();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (!getPackageName().equals(r.activityInfo.packageName)) apps.add(r);
        }
        final HashMap<ResolveInfo, String> labels = new HashMap<ResolveInfo, String>();
        for (ResolveInfo r : apps) labels.put(r, String.valueOf(r.loadLabel(pm)));
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return labels.get(a).compareToIgnoreCase(labels.get(b));
            }
        });
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshStates() {
        if (aaBtnTv == null) return;
        String nat = nativeProjectionApp();
        String aaSaved = prefs.getString("aa", null);
        String chosen = prefs.getString("conn_app", null);
        if (aaSaved != null) aaBtnTv.setText(labelOf(aaSaved));
        else if (chosen != null && installed(chosen)) aaBtnTv.setText(labelOf(chosen));
        else if (connCandidates().size() > 1) aaBtnTv.setText("Toca para elegir");
        else if (nat != null) aaBtnTv.setText(labelOf(nat));
        else if (installed(PKG_HUR)) aaBtnTv.setText("Open Headunit · USB");
        else aaBtnTv.setText("Toca para instalar");
        String cpApp = carPlayApp();
        cpSubTv.setText(cpApp != null ? labelOf(cpApp) : "Pantalla o adaptador");
        updatePlayIcon();
    }

    private ResolveInfo find(String labelKeys, String pkgKeys) {
        PackageManager pm = getPackageManager();
        if (labelKeys.length() > 0) {
            for (String k : labelKeys.split("\\|")) {
                for (ResolveInfo r : apps) {
                    String l = String.valueOf(r.loadLabel(pm)).toLowerCase(ES);
                    if (k.length() <= 2 ? l.equals(k) || l.startsWith(k + " ") : l.contains(k)) return r;
                }
            }
        }
        if (pkgKeys.length() > 0) {
            for (String k : pkgKeys.split("\\|")) {
                for (ResolveInfo r : apps) {
                    if (r.activityInfo.packageName.toLowerCase(ES).contains(k)) return r;
                }
            }
        }
        return null;
    }

    private boolean launchSaved(String key) {
        String c = prefs.getString(key, null);
        if (c == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(ComponentName.unflattenFromString(c));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Exception e) {
            prefs.edit().remove(key).apply();
            return false;
        }
    }

    private void launch(ResolveInfo r) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir la app");
        }
    }

    private boolean launchPkg(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openSlot(int idx) {
        String key = SLOTS[idx][0];
        if ("music".equals(key)) {
            try {
                Intent si = getPackageManager().getLaunchIntentForPackage("com.seldia.music");
                if (si != null) {
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    si.putExtra("com.fordlauncher.theme", LIGHT ? "light" : "dark");
                    startActivity(si);
                    syncThemeToSeldia();
                    return;
                }
            } catch (Throwable ignored) {}
        }
        if (launchSaved(key)) return;
        if ("settings".equals(key)) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        ResolveInfo r = find(SLOTS[idx][2], SLOTS[idx][3]);
        if (r != null) {
            launch(r);
        } else {
            toast("No encontré la app de " + SLOTS[idx][1] + ". Elígela de la lista.");
            pickFor(key, SLOTS[idx][1]);
        }
    }

    private void pickFor(final String key, String name) { pickFor(key, name, null); }

    private void pickFor(final String key, String name, final Runnable after) {
        final PackageManager pm = getPackageManager();
        final List<String> labels = new ArrayList<String>();
        labels.add("↺  Automático (por defecto)");
        for (ResolveInfo r : apps) labels.add(String.valueOf(r.loadLabel(pm)));
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿Qué app abre «" + name + "»?")
            .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        prefs.edit().remove(key).apply();
                        toast("Restablecido");
                    } else {
                        ResolveInfo r = apps.get(which - 1);
                        prefs.edit().putString(key,
                            new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString()).apply();
                        toast("Guardado: " + labels.get(which));
                    }
                    refreshStates();
                    if (after != null) after.run();
                    else if (settingsView != null) showSettings(settingsCat);
                }
            })
            .setNegativeButton("Cancelar", after == null ? null : new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { after.run(); }
            })
            .show();
    }

    // ───────────────────────── Android Auto y navegador ─────────────────────────

    private void openAndroidAuto() {
        if (launchSaved("aa")) return;
        String chosen = prefs.getString("conn_app", null);
        if (chosen != null && launchPkg(chosen)) return;
        List<String> c = connCandidates();
        if (chosen == null && c.size() > 1) {
            chooseConnection(new Runnable() { public void run() { openAndroidAuto(); } });
            return;
        }
        if (c.size() == 1 && launchPkg(c.get(0))) return;
        File apk = findApkOnUsb("revived");
        if (apk != null && compatible(apk)) {
            installFile(apk, "Open Headunit");
            return;
        }
        new AlertDialog.Builder(this, dlg())
            .setTitle("Android Auto")
            .setMessage("Esta pantalla no trae Android Auto integrado.\n\nInstala la app gratuita «Open Headunit» (github.com/andreknieriem/headunit-revived): copia su APK en la memoria USB o en Download y vuelve a tocar el botón. Luego conecta el celular por cable USB.")
            .setPositiveButton("Entendido", null)
            .setNeutralButton("Elegir app", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { pickFor("aa", "Android Auto"); }
            }).show();
    }

    // ── método de conexión del celular (lo elige el usuario) ──

    /** Apps de esta pantalla que pueden conectar el celular, en orden sugerido. */
    private List<String> connCandidates() {
        List<String> out = new ArrayList<String>();
        if (installed(PKG_HUR)) out.add(PKG_HUR);
        String nat = nativeProjectionApp();
        if (nat != null && !out.contains(nat)) out.add(nat);
        for (String p : CARPLAY_PKGS) if (installed(p) && !out.contains(p)) out.add(p);
        for (String p : USB_HOGS) if (installed(p) && !out.contains(p)) out.add(p);
        return out;
    }

    private String connDescription(String pkg) {
        if (PKG_HUR.equals(pkg)) return "Android Auto por cable USB (necesita la app Android Auto en el celular)";
        if (pkg.startsWith("net.easyconn") || pkg.startsWith("com.carbit")) return "Espejo de pantalla (necesita la app CarbitLink en el celular)";
        return "Android Auto / CarPlay integrados en la pantalla";
    }

    private void chooseConnection(final Runnable after) {
        final List<String> c = connCandidates();
        final List<String> items = new ArrayList<String>();
        for (String p : c) items.add(labelOf(p) + "\n" + connDescription(p));
        items.add("Otra app…");
        new AlertDialog.Builder(this, dlg())
            .setTitle("¿Cómo quieres conectar tu celular?")
            .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    if (w == c.size()) { pickFor("aa", "Android Auto"); return; }
                    prefs.edit().putString("conn_app", c.get(w)).remove("aa").apply();
                    usbWarned = false;
                    toast("Conexión: " + labelOf(c.get(w)) + ". Cámbiala dejando presionado el botón.");
                    refreshStates();
                    if (settingsView != null) showSettings(settingsCat);
                    if (!USB_HOG_SET.contains(c.get(w))) guardUsb();
                    if (after != null) after.run();
                }
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }


    private void openBrowser() {
        if (launchSaved("web")) return;
        for (String p : new String[]{"org.mozilla.firefox", "org.mozilla.fennec_fdroid", "com.opera.mini.native",
                "com.opera.browser", PKG_WEB}) {
            if (launchPkg(p)) return;
        }
        File apk = findApkOnUsb("firefox", "navegador", "browser", "opera");
        if (apk != null && compatible(apk)) {
            installFile(apk, "el navegador");
            return;
        }
        if (apk != null) toast("«" + apk.getName() + "» necesita un Android más nuevo que el de esta pantalla. Abro el navegador de fábrica.");
        if (launchPkg("com.android.browser")) return;
        ResolveInfo r = find("navegador|browser|internet", "browser");
        if (r != null) { launch(r); return; }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("No encontré un navegador. Déjalo presionado para elegir uno.");
        }
    }

    /** true si el APK se puede instalar en esta versión de Android. */
    private boolean compatible(File apk) {
        try {
            return getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0) != null;
        } catch (Throwable e) {
            return true;
        }
    }

    private boolean unknownSourcesOk() {
        if (SDK >= 26) {
            try {
                if (Modern.canInstall(this)) return true;
                toast("Permite «Instalar apps desconocidas» para Ford Launcher y vuelve a tocar.");
                return false;
            } catch (Throwable e) { return true; }
        }
        try {
            if (Settings.Secure.getInt(getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS) == 1) return true;
        } catch (Exception e) {
            return true; // si no se puede leer, deja que el instalador decida
        }
        toast("Activa «Orígenes desconocidos» y vuelve a tocar.");
        try {
            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
        return false;
    }

    private void installFromAsset(final String asset, final String name) {
        if (installing || !unknownSourcesOk()) return;
        installing = true;
        toast("Preparando " + name + "…");
        new Thread(new Runnable() {
            public void run() {
                File out = null;
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir == null) dir = getFilesDir();
                    dir.mkdirs();
                    out = new File(dir, new File(asset).getName());
                    InputStream in = getAssets().open(asset);
                    OutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    in.close();
                    out.setReadable(true, false);
                    dir.setExecutable(true, false);
                } catch (Exception e) {
                    out = null;
                }
                final File f = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        installing = false;
                        if (f == null) toast("No se pudo preparar " + name);
                        else installFile(f, name);
                    }
                });
            }
        }).start();
    }

    private void installFile(File f, String name) {
        if (!unknownSourcesOk()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (SDK >= 24) {
                i.setDataAndType(ApkProvider.share(f), "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("No se pudo abrir el instalador para " + name);
        }
    }

    private File findApkOnUsb(String... keys) {
        String[] roots = {"/storage", "/mnt", "/mnt/media_rw", "/mnt/usb_storage", "/udisk", "/sdcard"};
        File dl = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
        File hit0 = scanDir(dl, keys, 0);
        if (hit0 != null) return hit0;
        for (String r : roots) {
            File[] vols = new File(r).listFiles();
            if (vols == null) continue;
            for (File v : vols) {
                File hit = scanDir(v, keys, 2);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private File scanDir(File dir, String[] keys, int depth) {
        if (depth < 0 || dir == null || !dir.isDirectory()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName().toLowerCase(ES);
            if (f.isFile() && n.endsWith(".apk")) {
                for (String k : keys) if (n.contains(k)) return f;
            }
        }
        for (File f : fs) {
            if (f.isDirectory() && !f.getName().startsWith(".") && !"Android".equals(f.getName())) {
                File hit = scanDir(f, keys, depth - 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    // ───────────────────────── cajón de apps ─────────────────────────

    private void openDrawer() {
        if (drawer != null) return;
        loadApps();
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor((BG & 0x00FFFFFF) | 0xF5000000);
        drawer.setClickable(true);

        TextView title = tv("Aplicaciones  ·  Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")", 18, TEXT, true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.leftMargin = (int) (28 * s);
        tlp.topMargin = (int) (20 * s);
        drawer.addView(title, tlp);

        TextView close = tv("Cerrar  ✕", 15, SOFT, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(BTN, 18));
        close.setPadding((int) (16 * s), (int) (8 * s), (int) (16 * s), (int) (8 * s));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2);
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.rightMargin = (int) (24 * s);
        clp.topMargin = (int) (16 * s);
        drawer.addView(close, clp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDrawer(); }
        });

        final PackageManager pm = getPackageManager();
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setVerticalSpacing((int) (18 * s));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int p) { return apps.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout cell;
                ImageView im;
                TextView lb;
                if (cv == null) {
                    cell = new LinearLayout(Main.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(0, (int) (6 * s), 0, (int) (6 * s));
                    im = new ImageView(Main.this);
                    cell.addView(im, new LinearLayout.LayoutParams((int) (56 * s), (int) (56 * s)));
                    lb = tv("", 13, SOFT, false);
                    lb.setGravity(Gravity.CENTER);
                    lb.setPadding((int) (4 * s), (int) (8 * s), (int) (4 * s), 0);
                    cell.addView(lb, new LinearLayout.LayoutParams(-1, -2));
                } else {
                    cell = (LinearLayout) cv;
                    im = (ImageView) cell.getChildAt(0);
                    lb = (TextView) cell.getChildAt(1);
                }
                ResolveInfo r = apps.get(p);
                Drawable d = r.loadIcon(pm);
                im.setImageDrawable(d);
                lb.setText(r.loadLabel(pm));
                return cell;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int p, long id) {
                launch(apps.get(p));
            }
        });
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-1, -1);
        glp.topMargin = (int) (76 * s);
        glp.leftMargin = (int) (16 * s);
        glp.rightMargin = (int) (16 * s);
        drawer.addView(grid, glp);

        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeDrawer() {
        if (drawer != null) {
            root.removeView(drawer);
            drawer = null;
        }
    }
}
