package com.fordlauncher.launcher.obd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.fordlauncher.launcher.Main;
import com.fordlauncher.launcher.R;
import com.fordlauncher.launcher.VoiceOverlayService;
import com.fordlauncher.launcher.ai.TextToSpeechManager;

/**
 * Punto único de salida de las alertas críticas del vehículo.
 *
 * <p>No depende de internet ni de Gemini: publica una notificación local de
 * importancia alta con sonido propio, pide al servicio flotante que muestre un
 * banner sobre cualquier app (SerMusic, Waze, Maps) y lanza la voz por la ruta
 * 100 % local de {@link TextToSpeechManager#hablarAlertaCritica(String)}.
 *
 * <p>Se usa desde {@link SensorAnomalyTriggerManager} (umbrales OBD, DTC y
 * CANBUS/puertas) y desde el hook de pruebas de {@code DebugCommandReceiver}.
 */
public final class VehicleAlertManager {

    private static final String TAG = "FordLauncher/Alerta";
    public static final String CHANNEL_ID = "vehicle_alerts_v1";
    private static final int BASE_ID = 42000;
    private static volatile VehicleAlertManager instance;

    private final Context ctx;
    private final NotificationManager nm;

    private VehicleAlertManager(Context c) {
        this.ctx = c.getApplicationContext();
        this.nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        crearCanal();
    }

    public static VehicleAlertManager get(Context c) {
        if (instance == null) {
            synchronized (VehicleAlertManager.class) {
                if (instance == null) instance = new VehicleAlertManager(c);
            }
        }
        return instance;
    }

    /**
     * Alerta completa: notificación + banner flotante + voz local inmediata.
     * Se puede llamar desde cualquier hilo.
     */
    public void alert(String key, String titulo, String detalle, String voz) {
        notifyAndShow(key, titulo, detalle);
        try {
            TextToSpeechManager.getInstance(ctx).hablarAlertaCritica(voz);
        } catch (Throwable t) {
            Log.w(TAG, "TTS de alerta falló: " + t);
        }
    }

    /**
     * Solo aviso visual/sonoro (notificación de sistema + banner). La voz la
     * gestiona quien llama, para poder encolar avisos no críticos.
     */
    public void notifyAndShow(String key, String titulo, String detalle) {
        String k = (key == null || key.trim().length() == 0) ? "alerta" : key.trim();
        String t = (titulo == null || titulo.trim().length() == 0) ? "Alerta del vehículo" : titulo.trim();
        String d = detalle == null ? "" : detalle.trim();
        notificar(k, t, d);
        try {
            VoiceOverlayService.showAlert(ctx, t, d, k);
        } catch (Throwable e) {
            Log.w(TAG, "No se pudo mostrar el banner: " + e);
        }
        Log.w(TAG, "ALERTA " + k + ": " + t + " · " + d);
    }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || nm == null) return;
        try {
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Alertas del vehículo", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Temperatura, batería, códigos de falla y puertas. Suenan aunque no haya internet.");
            ch.setShowBadge(true);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 400, 180, 400});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(sonido(), attrs);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo crear el canal de alertas: " + t);
        }
    }

    private Uri sonido() {
        return Uri.parse("android.resource://" + ctx.getPackageName() + "/" + R.raw.alert_beep);
    }

    private void notificar(String key, String titulo, String detalle) {
        if (nm == null) return;
        Intent open = new Intent(ctx, Main.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, idPara(key), open, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_alert_warning)
                .setContentTitle(titulo)
                .setContentText(detalle)
                .setStyle(new Notification.BigTextStyle().bigText(detalle))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setSound(sonido())
                    .setVibrate(new long[]{0, 400, 180, 400})
                    .setDefaults(Notification.DEFAULT_LIGHTS);
        }
        try {
            nm.notify(idPara(key), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo publicar la notificación: " + t);
        }
    }

    private static int idPara(String key) {
        int h = key == null ? 0 : key.hashCode();
        return BASE_ID + (Math.abs(h) % 800);
    }
}
