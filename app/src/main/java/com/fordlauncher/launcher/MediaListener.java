package com.fordlauncher.launcher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Necesario para leer las sesiones multimedia en Android 5+ (el usuario lo activa en "Acceso a notificaciones"). */
public class MediaListener extends NotificationListenerService {
    static boolean connected;
    @Override public void onListenerConnected() { connected = true; MediaWatcher.kick(this); }
    @Override public void onListenerDisconnected() { connected = false; }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { }
}
