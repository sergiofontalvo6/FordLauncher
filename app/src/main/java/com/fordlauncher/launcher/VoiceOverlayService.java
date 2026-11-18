package com.fordlauncher.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Botón flotante global del copiloto de voz: vive en primer plano y se dibuja sobre
 * cualquier app (SerMusic, Waze, Maps, Radio) con TYPE_APPLICATION_OVERLAY.
 */
public class VoiceOverlayService extends Service {

    private static final String TAG = "FordLauncher/VoiceOverlay";
    private static final String CHANNEL = "voice_overlay";
    private static final int NOTIF_ID = 9711;
    private static final String PREFS = "voice_overlay";
    private static volatile boolean running;
    private static volatile VoiceOverlayService instance;

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private View bubble;
    private com.fordlauncher.launcher.ai.AiFabAnimator anim;
    private com.fordlauncher.launcher.obd.VoiceInputManager voice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float touchX, touchY;
    private float startX, startY;
    private boolean dragging;

    public static boolean isRunning() { return running; }
    public static VoiceOverlayService instance() { return instance; }

    public static void start(Context context) {
        if (context == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return;
            Intent i = new Intent(context, VoiceOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar el botón flotante", t);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try { context.stopService(new Intent(context, VoiceOverlayService.class)); } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        crearNotificacion();
        voice = new com.fordlauncher.launcher.obd.VoiceInputManager(this);
        voice.setOnBeginningOfSpeech(new Runnable() { public void run() { main.post(new Runnable() { public void run() { if (anim != null) anim.listening(); } }); } });
        voice.setOnSilence(new Runnable() { public void run() { main.post(new Runnable() { public void run() { if (anim != null) anim.idle(); } }); } });
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) addBubble();
        Log.i(TAG, "Servicio de botón flotante iniciado");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            if (bubble == null) addBubble();
        } else removeBubble();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (voice != null) { try { voice.cancel(); } catch (Throwable ignored) {} }
        removeBubble();
        super.onDestroy();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private void crearNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Asistente flotante", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Botón de voz disponible sobre cualquier aplicación");
                nm.createNotificationChannel(ch);
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_gemini_sparkle)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Asistente de voz flotante activo")
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true);
        try { startForeground(NOTIF_ID, b.build()); } catch (Throwable ignored) {}
    }

    private void addBubble() {
        if (bubble != null) return;
        int box = (int) (74 * getResources().getDisplayMetrics().density);
        int core = (int) (54 * getResources().getDisplayMetrics().density);
        int spark = (int) (30 * getResources().getDisplayMetrics().density);

        FrameLayout wrap = new FrameLayout(this);
        View glow = new View(this);
        View wave = new View(this);
        View wave2 = new View(this);
        FrameLayout coreView = new FrameLayout(this);
        coreView.setBackgroundResource(R.drawable.bg_floating_ai);
        ImageView sparkle = new ImageView(this);
        sparkle.setImageResource(R.drawable.ic_gemini_sparkle);
        sparkle.setScaleType(ImageView.ScaleType.FIT_CENTER);
        coreView.addView(sparkle, new FrameLayout.LayoutParams(spark, spark, Gravity.CENTER));
        wrap.addView(glow, new FrameLayout.LayoutParams(box, box));
        wrap.addView(wave, new FrameLayout.LayoutParams(core, core, Gravity.CENTER));
        wrap.addView(wave2, new FrameLayout.LayoutParams(core, core, Gravity.CENTER));
        wrap.addView(coreView, new FrameLayout.LayoutParams(core, core, Gravity.CENTER));
        anim = new com.fordlauncher.launcher.ai.AiFabAnimator(wrap, glow, wave, wave2, sparkle);
        anim.idle();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(box, box, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs().getInt("x", 20);
        params.y = prefs().getInt("y", 220);
        try { wm.addView(wrap, params); } catch (Throwable t) { Log.w(TAG, "No se pudo añadir la burbuja", t); return; }
        bubble = wrap;
        wrap.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (params == null) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    touchX = e.getRawX(); touchY = e.getRawY();
                    startX = params.x; startY = params.y;
                    dragging = false;
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = e.getRawX() - touchX, dy = e.getRawY() - touchY;
                    if (Math.hypot(dx, dy) > 10) {
                        dragging = true;
                        params.x = (int) (startX + dx);
                        params.y = (int) (startY + dy);
                        try { wm.updateViewLayout(v, params); } catch (Throwable ignored) {}
                    }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!dragging) onBubbleTap();
                    else prefs().edit().putInt("x", params.x).putInt("y", params.y).apply();
                    return true;
                }
                return false;
            }
        });
    }

    private void removeBubble() {
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Throwable ignored) {}
            bubble = null;
        }
        if (anim != null) { try { anim.stop(); } catch (Throwable ignored) {} anim = null; }
    }

    private void onBubbleTap() {
        if (voice != null && voice.isListening()) {
            voice.stopListening();
            if (anim != null) anim.idle();
            return;
        }
        if (anim != null) anim.listening();
        voice.startListening(new com.fordlauncher.launcher.obd.VoiceInputManager.VoiceCallback() {
            public void onQueryReady(String text) {
                main.post(new Runnable() { public void run() {
                    if (anim != null) anim.waiting();
                    consultar(text);
                }});
            }
            public void onError(String msg) {
                main.post(new Runnable() { public void run() { if (anim != null) anim.idle(); } });
            }
        });
    }

    /** Simulación ADB del flujo real: misma ruta que el toque del botón. */
    public void simularConsulta(String query) {
        if (query == null || query.trim().length() == 0) return;
        if (anim != null) anim.waiting();
        consultar(query.trim());
    }

    private void consultar(final String pregunta) {
        String tarea = "El conductor pregunta por voz: \"" + pregunta + "\"";
        com.fordlauncher.launcher.obd.GeminiDiagnosticClient.analyze(this, "{}", tarea, false,
                new com.fordlauncher.launcher.obd.GeminiDiagnosticClient.Callback() {
                    public void onDone(final String text, final String error) {
                        main.post(new Runnable() { public void run() { procesar(text, error, pregunta); } });
                    }
                });
    }

    private void procesar(String text, String error, String pregunta) {
        if (error != null || text == null) {
            if (anim != null) anim.idle();
            return;
        }
        com.fordlauncher.launcher.ai.VoiceActionParser.Action action =
                com.fordlauncher.launcher.ai.VoiceActionParser.parse(text, pregunta);
        if (action != null && "PLAY_MUSIC".equalsIgnoreCase(action.type)) {
            if (voice != null) voice.stopListening();
            com.fordlauncher.launcher.ai.MusicVoiceController.reproducirEnSerMusic(this, action.query);
            String decir = action.speech.length() > 0 ? action.speech : ("Poniendo " + action.query + " en SerMusic");
            hablar(decir);
            return;
        }
        String limpio = com.fordlauncher.launcher.obd.GeminiDiagnosticClient.limpiarParaVoz(text);
        hablar(limpio == null ? "Sin respuesta" : limpio);
    }

    private void hablar(String texto) {
        if (anim != null) anim.speaking();
        com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(this).hablarTextoLargo(texto, new Runnable() {
            public void run() { main.post(new Runnable() { public void run() { if (anim != null) anim.idle(); } }); }
        });
    }
}
