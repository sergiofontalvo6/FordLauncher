package com.fordlauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;

/**
 * Privacidad del Teléfono: al desconectar el celular puede borrarse el historial
 * y ocultarse los contactos, para que no se mezclen con los de otro celular.
 *
 * <p>Vive en {@link Main} (no en el panel Teléfono) para que la limpieza automática
 * funcione aunque el panel esté cerrado.
 */
final class PhonePrivacy {

    static final String K_AUTO = "phone_auto_clean";
    static final String K_HIDDEN = "phone_contacts_hidden";
    static final String K_PENDING = "phone_clean_pending";
    static final String K_OWNER = "phone_owner_mac";
    static final long DELAY_MS = 8000L;

    private final Main host;
    private final HfpClient hfp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean seenConnected;
    private boolean cleanScheduled;
    private final Runnable cleanLater = new Runnable() {
        public void run() { cleanScheduled = false; clean(host); }
    };

    PhonePrivacy(Main h) {
        host = h;
        hfp = new HfpClient(h);
        hfp.setListener(new HfpClient.CallListener() {
            @Override public void onDialResult(boolean success, String detail) { }
            @Override public void onConnectionChanged(String deviceName) { onConnection(deviceName != null); }
            @Override public void onCallChanged(String state, String number) { host.onPhoneCallState(state, number); }
        });
        hfp.requestProxy();
    }

    HfpClient hfp() { return hfp; }

    void close() {
        ui.removeCallbacks(cleanLater);
        try { hfp.close(); } catch (Throwable ignored) {}
    }

    private void onConnection(boolean connected) {
        SharedPreferences p = host.getPrefs();
        if (connected) {
            ui.removeCallbacks(cleanLater);
            cleanScheduled = false;
            seenConnected = true;
            if (!p.getBoolean(K_AUTO, false)) return;
            String mac = hfp.getConnectedDevice() != null ? hfp.getConnectedDevice().getAddress() : null;
            String owner = p.getString(K_OWNER, null);
            if (owner == null && mac != null) {
                owner = mac;
                p.edit().putString(K_OWNER, mac).apply();
            }
            boolean isOwner = owner != null && mac != null && owner.equalsIgnoreCase(mac);
            if (isOwner) {
                p.edit().putBoolean(K_PENDING, false).putBoolean(K_HIDDEN, false).apply();
            } else {
                clean(host); // otro celular: no mezclar datos
            }
        } else {
            if (!seenConnected || !host.getPrefs().getBoolean(K_AUTO, false)) return;
            host.getPrefs().edit().putBoolean(K_PENDING, true).apply();
            // No reiniciar el temporizador: el radio emite varios eventos de desconexión seguidos.
            if (!cleanScheduled) {
                cleanScheduled = true;
                ui.postDelayed(cleanLater, DELAY_MS);
            }
        }
    }

    /**
     * Borra historial Bluetooth y CallLog estándar, olvida favoritos y oculta contactos.
     * Los contactos del sistema no se borran: serían sincronizados con la cuenta de Google.
     * Devuelve el número de registros de llamadas eliminados.
     */
    static int clean(Context c) {
        int deleted = 0;
        try { deleted += c.getContentResolver().delete(TelefonoView.BT_CALLLOG_URI, null, null); } catch (Throwable ignored) {}
        try { deleted += c.getContentResolver().delete(CallLog.Calls.CONTENT_URI, null, null); } catch (Throwable ignored) {}
        try {
            c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
                    .remove("phone_fav_ids")
                    .remove("phone_fav_imported")
                    .putBoolean(K_HIDDEN, true)
                    .putBoolean(K_PENDING, false)
                    .apply();
        } catch (Throwable ignored) {}
        return deleted;
    }
}
