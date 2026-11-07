package com.fordlauncher.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class LogcatRecorderService extends Service {

    private Thread recorderThread;
    private volatile boolean running = false;
    private static final String CHANNEL_ID = "logcat_recorder_channel";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(777, createNotification());
        if (!running) {
            running = true;
            startRecorderThread();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (recorderThread != null) {
            recorderThread.interrupt();
        }
        super.onDestroy();
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Grabación de Logcat",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = new Notification.Builder(this)
            .setContentTitle("Ford Launcher · Grabando Logcat")
            .setContentText("Los registros del sistema se están guardando en un archivo.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }
        return builder.build();
    }

    private File getLogFile() {
        try {
            File extDir = getExternalFilesDir(null);
            if (extDir != null) {
                if (!extDir.exists()) extDir.mkdirs();
                return new File(extDir, "logcat_fordlauncher.txt");
            }
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                File[] externals = getExternalFilesDirs(null);
                if (externals != null && externals.length > 1) {
                    for (int i = 1; i < externals.length; i++) {
                        File ext = externals[i];
                        if (ext != null) {
                            File usbDir = new File(ext, "FordLauncherLogs");
                            if (usbDir.exists() || usbDir.mkdirs()) {
                                return new File(usbDir, "logcat_fordlauncher.txt");
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) {
                if (!downloads.exists()) downloads.mkdirs();
                return new File(downloads, "logcat_fordlauncher.txt");
            }
        } catch (Throwable ignored) {}

        return new File(getFilesDir(), "logcat_fordlauncher.txt");
    }

    private void startRecorderThread() {
        recorderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                File logFile = getLogFile();
                PrintWriter writer = null;
                BufferedReader reader = null;
                try {
                    writer = new PrintWriter(new FileOutputStream(logFile, true));
                    Process process = Runtime.getRuntime().exec("logcat -v time");
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        writer.println(line);
                        writer.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (writer != null) writer.close();
                    } catch (Throwable ignored) {}
                    try {
                        if (reader != null) reader.close();
                    } catch (Throwable ignored) {}
                }
            }
        });
        recorderThread.start();
    }
}
