package com.fordlauncher.launcher.obd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.fordlauncher.launcher.Main;
import com.fordlauncher.launcher.R;
import com.fordlauncher.launcher.VoiceTrigger;

/**
 * Servicio en primer plano, silencioso y de prioridad mínima, que mantiene vivo
 * el proceso del launcher y con él la conexión OBD2 del singleton.
 *
 * Android 8.1 no suspende un proceso con un foreground service activo, así que
 * el BluetoothSocket del ELM327 no se cierra al salir de Diagnóstico, cambiar
 * de botón en el Dock o minimizar el launcher.
 */
public class ObdForegroundService extends Service {
    private static final String TAG = "FordLauncher/ObdSvc";
    public static final String CHANNEL_ID = "obd_bg";
    public static final int NOTIF_ID = 4102;

    /**
     * Receptor de voz del volante (SWC/HCN). El servicio vive desde el arranque,
     * así que la función «Hablar» funciona aunque la actividad Main no exista.
     */
    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            VoiceTrigger.fire(context, "service broadcast " + action);
        }
    };

    /**
     * Sniffer de depuración del módulo SWC del volante: registra la acción y
     * todos los extras que envía el MCU al pulsar una tecla. Sirve para
     * descubrir el id real de la tecla «Hablar» sin root.
     */
    private final BroadcastReceiver swcSniffer = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            StringBuilder sb = new StringBuilder("action=").append(intent.getAction());
            try {
                android.os.Bundle b = intent.getExtras();
                if (b != null) {
                    for (String k : b.keySet()) {
                        Object v;
                        try { v = b.get(k); } catch (Throwable t) { v = "?"; }
                        sb.append(" | ").append(k).append("=").append(v);
                    }
                }
            } catch (Throwable ignored) {}
            Log.i("FordLauncher/SwcSniff", sb.toString());
        }
    };

    /** Arranca el servicio de forma compatible con Android 8+ (foreground). */
    public static void start(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, ObdForegroundService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar el servicio OBD: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanal();
        try {
            registerReceiver(voiceReceiver, VoiceTrigger.filter());
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo registrar el receptor de voz SWC: " + t);
        }
        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction("autochips.intent.action.ACTION_SEND_SWC_STATUS");
            sf.addAction("com.hcn.swc.action.KEY");
            sf.addAction("com.hcn.swc.KEY");
            sf.addAction("com.hcn.key.define");
            registerReceiver(swcSniffer, sf);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo registrar el sniffer SWC: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, notificacion());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground falló: " + t);
        }
        try {
            ObdRepository repo = ObdRepository.getInstance(this);
            repo.iniciarAutoconexion(this);
            Log.i(TAG, "Servicio OBD en primer plano (estado=" + repo.getState() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo iniciar la autoconexión OBD: " + t);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(voiceReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(swcSniffer); } catch (Throwable ignored) {}
        Log.i(TAG, "Servicio OBD destruido; el repositorio sigue vivo en el proceso");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void crearCanal() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "OBD2 en segundo plano", NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("Mantiene la lectura del adaptador ELM327 activa.");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo crear el canal de notificación: " + t);
        }
    }

    private Notification notificacion() {
        Intent open = new Intent(this, Main.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("OBD2 en segundo plano")
                .setContentText("Lectura de sensores activa")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pi);
        return b.build();
    }
}
