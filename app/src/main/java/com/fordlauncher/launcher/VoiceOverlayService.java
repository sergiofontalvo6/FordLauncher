package com.fordlauncher.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fordlauncher.launcher.ai.AssistantActionEngine;

/**
 * Botón flotante global del copiloto de voz: vive en primer plano y se dibuja sobre
 * cualquier app (SerMusic, Waze, Maps, Radio) con TYPE_APPLICATION_OVERLAY.
 *
 * <p>Todo el razonamiento (comandos locales, Gemini, acciones) se delega en
 * {@link AssistantActionEngine}, el MISMO motor que usa {@link Main}. Las acciones
 * de la UI del launcher se reenvían a Main con PENDING_ACTION_JSON; el resto
 * (música, apps externas, llamadas, OBD, brillo) se ejecuta aquí sin sacar al
 * usuario de la app que esté viendo.
 */
public class VoiceOverlayService extends Service implements AssistantActionEngine.Host {

    private static final String TAG = "FordLauncher/VoiceOverlay";
    private static final String CHANNEL = "voice_overlay";
    private static final int NOTIF_ID = 9711;
    private static final String PREFS = "voice_overlay";

    /**
     * Acción del botón físico de voz del volante (SWC/CANBUS): inicia o detiene
     * la escucha exactamente igual que un toque en la burbuja flotante, sin
     * traer {@link Main} al frente ni salir de la app que esté abierta.
     */
    public static final String ACTION_TOGGLE_LISTEN = "com.fordlauncher.launcher.action.TOGGLE_LISTEN";
    /** Pregunta ya dictada (p. ej. búsqueda de música del sistema). */
    public static final String ACTION_ASK = "com.fordlauncher.launcher.action.ASK";
    public static final String EXTRA_QUERY = "com.fordlauncher.launcher.extra.QUERY";
    /** Alerta crítica del vehículo: banner flotante sobre cualquier app. */
    public static final String ACTION_SHOW_ALERT = "com.fordlauncher.launcher.action.SHOW_ALERT";
    public static final String EXTRA_ALERT_TITLE = "com.fordlauncher.launcher.extra.ALERT_TITLE";
    public static final String EXTRA_ALERT_DETAIL = "com.fordlauncher.launcher.extra.ALERT_DETAIL";
    public static final String EXTRA_ALERT_KEY = "com.fordlauncher.launcher.extra.ALERT_KEY";
    private static final long ALERT_TIMEOUT_MS = 15000L;
    private static volatile boolean running;
    private static volatile VoiceOverlayService instance;

    private WindowManager wm;
    private WindowManager.LayoutParams params;
    private View bubble;
    private View alertBanner;
    private com.fordlauncher.launcher.ai.AiFabAnimator anim;
    private com.fordlauncher.launcher.obd.VoiceInputManager voice;
    private AssistantActionEngine assistant;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable alertTimeout = new Runnable() {
        public void run() { removeAlertBanner(); }
    };
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

    /**
     * Dispara la escucha desde el botón físico del carro. No abre ninguna
     * actividad: el servicio ya vive en primer plano y dibuja su burbuja sobre
     * la app que el conductor esté viendo (SerMusic, Maps, etc.).
     */
    public static void toggleListen(Context context) {
        send(context, new Intent(context, VoiceOverlayService.class).setAction(ACTION_TOGGLE_LISTEN));
    }

    /** Envía una pregunta ya transcrita (búsquedas de voz del sistema). */
    public static void ask(Context context, String query) {
        if (query == null || query.trim().length() == 0) return;
        send(context, new Intent(context, VoiceOverlayService.class)
                .setAction(ACTION_ASK)
                .putExtra(EXTRA_QUERY, query.trim()));
    }

    /**
     * Muestra el banner de alerta crítica sobre la app que esté al frente.
     * Arranca el servicio si aún no estaba vivo (la notificación de sistema la
     * publica {@code VehicleAlertManager}).
     */
    public static void showAlert(Context context, String title, String detail, String key) {
        if (context == null) return;
        send(context, new Intent(context, VoiceOverlayService.class)
                .setAction(ACTION_SHOW_ALERT)
                .putExtra(EXTRA_ALERT_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_ALERT_DETAIL, detail == null ? "" : detail)
                .putExtra(EXTRA_ALERT_KEY, key == null ? "" : key));
    }

    private static void send(Context context, Intent i) {
        if (context == null || i == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo enviar " + i.getAction(), t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        // startForeground() lo antes posible: en el AC8227L el arranque en frío
        // es lento y Android mata el proceso si tarda más de 5 s.
        crearNotificacion();
        // Carga Supertonic 3 fuera del hilo principal; no entra en el timeout
        // de 12 s del motor del sistema ni retrasa el botón flotante.
        try {
            com.fordlauncher.launcher.ai.SherpaTtsEngine.get().precalentar(this);
        } catch (Throwable ignored) {}
        try {
            final Main m = Main.instance();
            if (m != null) main.post(new Runnable() { public void run() { m.syncFloatingAi(); } });
        } catch (Throwable ignored) {}
        assistant = new AssistantActionEngine(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
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
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE_LISTEN.equals(action)) {
            // Mismo flujo que el toque en la burbuja: escuchar/detener sin abrir Main.
            Log.i(TAG, "TOGGLE_LISTEN desde botón físico del volante");
            onBubbleTap();
        } else if (ACTION_ASK.equals(action)) {
            String q = intent.getStringExtra(EXTRA_QUERY);
            Log.i(TAG, "ASK desde intent del sistema: " + q);
            consultar(q);
        } else if (ACTION_SHOW_ALERT.equals(action)) {
            String title = intent.getStringExtra(EXTRA_ALERT_TITLE);
            String detail = intent.getStringExtra(EXTRA_ALERT_DETAIL);
            String key = intent.getStringExtra(EXTRA_ALERT_KEY);
            Log.i(TAG, "SHOW_ALERT " + key + ": " + title + " · " + detail);
            showAlertBanner(title, detail);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        if (voice != null) { try { voice.cancel(); } catch (Throwable ignored) {} }
        removeAlertBanner();
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

    /**
     * Banner de alerta crítica sobre la app en primer plano (SerMusic, Waze,
     * Maps...). No roba el foco ni cambia de actividad; se cierra solo a los
     * 15 s o con los botones Silenciar / Cerrar.
     */
    private void showAlertBanner(String titulo, String detalle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Alerta sin permiso de overlay: queda solo la notificación");
            return;
        }
        if (wm == null) wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        removeAlertBanner();

        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * d);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2181A1F);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, (int) (2 * d)), 0xFFFF5252);
        card.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_alert_warning);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = (int) (42 * d);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsLp.leftMargin = (int) (12 * d);
        textsLp.rightMargin = (int) (10 * d);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(titulo == null || titulo.trim().length() == 0
                ? "Alerta del vehículo" : titulo);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(18f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setMaxLines(1);
        texts.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvDetail = new TextView(this);
        tvDetail.setText(detalle == null ? "" : detalle);
        tvDetail.setTextColor(0xFFFFCDD2);
        tvDetail.setTextSize(15f);
        tvDetail.setMaxLines(3);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = (int) (3 * d);
        texts.addView(tvDetail, detailLp);
        card.addView(texts, textsLp);

        TextView silence = bannerButton("Silenciar");
        silence.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    com.fordlauncher.launcher.ai.TextToSpeechManager
                            .getInstance(VoiceOverlayService.this).detener();
                } catch (Throwable ignored) {}
                removeAlertBanner();
            }
        });
        card.addView(silence, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = bannerButton("Cerrar");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = (int) (8 * d);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { removeAlertBanner(); }
        });
        card.addView(close, closeLp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (30 * d);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        lp.width = Math.min(screenW - (int) (32 * d), (int) (860 * d));
        lp.windowAnimations = android.R.style.Animation_Toast;
        try {
            wm.addView(card, lp);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo añadir el banner de alerta", t);
            return;
        }
        alertBanner = card;
        main.removeCallbacks(alertTimeout);
        main.postDelayed(alertTimeout, ALERT_TIMEOUT_MS);
    }

    private TextView bannerButton(String texto) {
        float d = getResources().getDisplayMetrics().density;
        TextView b = new TextView(this);
        b.setText(texto);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(10 * d);
        b.setBackground(bg);
        return b;
    }

    private void removeAlertBanner() {
        main.removeCallbacks(alertTimeout);
        if (alertBanner != null && wm != null) {
            try { wm.removeView(alertBanner); } catch (Throwable ignored) {}
        }
        alertBanner = null;
    }

    private void onBubbleTap() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifyUser("Activa el permiso de micrófono para hablarle a la IA");
            return;
        }
        if (voice != null && voice.isListening()) {
            voice.stopListening();
            if (anim != null) anim.idle();
            return;
        }
        // Si estaba hablando/esperando, el nuevo toque corta la respuesta y
        // vuelve a escuchar (igual que el FAB del launcher).
        if (anim != null && (anim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.SPEAKING
                || anim.state() == com.fordlauncher.launcher.ai.AiFabAnimator.WAITING)) {
            try { com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(this).detener(); }
            catch (Throwable ignored) {}
        }
        if (anim != null) anim.listening();
        voice.startListening(new com.fordlauncher.launcher.obd.VoiceInputManager.VoiceCallback() {
            public void onQueryReady(String text) {
                main.post(new Runnable() { public void run() { consultar(text); } });
            }
            public void onError(String msg) {
                main.post(new Runnable() {
                    public void run() {
                        if (anim != null) anim.idle();
                        // El aviso del paquete offline debe oírse aunque ninguna app
                        // muestre texto (el servicio flotante no tiene cuadro propio).
                        if (com.fordlauncher.launcher.obd.VoiceInputManager.MSG_SIN_PAQUETE_OFFLINE.equals(msg)
                                || "Reconocimiento de voz no disponible".equals(msg)) hablar(msg);
                    }
                });
            }
        });
    }

    /** Simulación ADB del flujo real: misma ruta que el toque del botón. */
    public void simularConsulta(String query) {
        if (query == null || query.trim().length() == 0) return;
        consultar(query.trim());
    }

    /** Prueba ADB de una respuesta Gemini cruda, sin depender de la nube. */
    public void simularRespuesta(String raw, String userSpeech) {
        if (assistant == null || raw == null) return;
        assistant.procesarRespuestaGemini(raw, null, userSpeech == null ? "" : userSpeech);
    }

    /** Mismo prompt, mismo snapshot y mismo motor que Main.fabAskAi(). */
    private void consultar(final String pregunta) {
        if (pregunta == null || pregunta.trim().length() == 0) {
            if (anim != null) anim.idle();
            return;
        }
        assistant.ask(pregunta.trim());
    }

    private void hablar(String texto) {
        if (anim != null) anim.speaking();
        com.fordlauncher.launcher.ai.TextToSpeechManager.getInstance(this).hablarTextoLargo(texto, new Runnable() {
            public void run() { main.post(new Runnable() { public void run() { if (anim != null) anim.idle(); } }); }
        });
    }

    /** Las acciones que necesitan UI del launcher se entregan a Main, que ya está en el mismo proceso. */
    private void traerMainConAccion(String pendingJson) {
        Log.i(TAG, "UI action -> Main PENDING_ACTION_JSON=" + pendingJson);
        Intent i = new Intent(this, Main.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("PENDING_ACTION_JSON", pendingJson);
        try { startActivity(i); }
        catch (Throwable t) { Log.w(TAG, "No se pudo traer Main al frente", t); }
    }

    // ---- AssistantActionEngine.Host: acciones en segundo plano aquí, UI en Main ----
    @Override public Context context() { return this; }

    @Override public void showText(String text) {
        // La burbuja no tiene cuadro de texto; la confirmación se dice por TTS.
    }

    @Override public void speak(String text, boolean continuar) { hablar(text); }

    @Override public void stopVoice() {
        if (voice != null) { try { voice.stopListening(); } catch (Throwable ignored) {} }
    }

    @Override public void setConversationActive(boolean active) {
        // El servicio no mantiene bucle conversacional; el micrófono se cierra solo.
    }

    @Override public void refreshMusicSession() {
        // No hay UI de Main a la que actualizar el card de música mientras otra app está al frente.
    }

    @Override public void setWaiting() {
        main.post(new Runnable() { public void run() { if (anim != null) anim.waiting(); } });
    }

    @Override public void setIdle() {
        main.post(new Runnable() { public void run() { if (anim != null) anim.idle(); } });
    }

    @Override public void setSpeaking() {
        main.post(new Runnable() { public void run() { if (anim != null) anim.speaking(); } });
    }

    @Override public void notifyUser(String text) {
        // Los toasts del servicio se muestran sobre cualquier app.
        try { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    @Override public boolean runsUiLocally() { return false; }

    @Override public boolean openDock(String key, String pendingJson) {
        if (key == null) return false;
        traerMainConAccion(pendingJson);
        return true; // Main abre la pestaña y dice la confirmación
    }

    @Override public boolean openDiagnostic(int tab, boolean analizar, String pendingJson) {
        traerMainConAccion(pendingJson);
        return true; // Main abre la pestaña y dice la confirmación
    }

    @Override public void goHome(String pendingJson) {
        AssistantActionEngine.launchHome(this, pendingJson);
    }
}
