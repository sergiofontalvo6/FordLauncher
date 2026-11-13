package com.fordlauncher.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Marca que el sistema arrancó/despertó para que el launcher emita el saludo por voz una sola vez. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        try {
            SharedPreferences prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                    || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
                prefs.edit().putBoolean("pending_boot_greeting", true)
                        .putLong("boot_event_ms", System.currentTimeMillis()).apply();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // Desbloqueo/presencia del usuario tras arranque o despertar
                prefs.edit().putBoolean("pending_boot_greeting", true).apply();
            }
        } catch (Throwable ignored) {}
    }
}
