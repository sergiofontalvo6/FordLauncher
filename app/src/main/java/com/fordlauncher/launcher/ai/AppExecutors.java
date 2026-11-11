package com.fordlauncher.launcher.ai;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gestor central de hilos para evitar la proliferación de pools (pool-1..pool-8).
 * - diskIO: 1 hilo para I/O local (SQLite, SharedPreferences, contactos).
 * - network: 2 hilos para red (Gemini, Edge TTS, HTTP).
 * - scheduler: ScheduledExecutorService para telemetría OBD y tareas periódicas.
 */
public final class AppExecutors {

    private static final AppExecutors INSTANCE = new AppExecutors();

    public final Executor diskIO;
    public final Executor network;
    public final ScheduledExecutorService scheduler;

    private AppExecutors() {
        diskIO = Executors.newSingleThreadExecutor();
        network = Executors.newFixedThreadPool(2);
        scheduler = Executors.newScheduledThreadPool(1);
    }

    public static AppExecutors get() { return INSTANCE; }

    public static void shutdown() {
        try { INSTANCE.diskIO.execute(new Runnable() { public void run() {} }); } catch (Throwable ignored) {}
    }
}
