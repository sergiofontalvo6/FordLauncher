package com.fordlauncher.launcher.ai;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animaciones del botón flotante de IA al estilo Siri / Gemini Live.
 *
 * Un único ValueAnimator maestro a ~30 fps (frameDelay 33 ms) actualiza
 * simultáneamente rotación, escala y ondas, evitando la sobrecarga de
 * múltiples ObjectAnimator concurrentes.
 */
public class AiFabAnimator {
    public static final int OFF = 0;
    public static final int IDLE = 1;
    public static final int WAITING = 2;
    public static final int LISTENING = 3;
    public static final int SPEAKING = 4;

    private final View wrap;
    private final View glow;
    private final View wave;
    private final View wave2;
    private final View icon;
    private final AiGlowDrawable glowDrawable;

    private int state = OFF;
    private ValueAnimator master;
    private float phase; // 0..1 acumulado

    // Parámetros por estado
    private float rotSpeed = 0f;   // vueltas por segundo
    private float scaleAmp = 0f;   // amplitud de escala
    private float scaleFreq = 0f;  // Hz
    private float glowBase = 0.35f;
    private float glowDelta = 0f;
    private float glowFreq = 0f;
    private float waveSpeed = 0f;  // Hz
    private float waveMaxAlpha = 0f;
    private float wave2Speed = 0f;
    private float wave2MaxAlpha = 0f;
    private boolean wavesOn = false;

    public AiFabAnimator(View wrap, View glow, View wave, View wave2, View icon) {
        this.wrap = wrap;
        this.glow = glow;
        this.wave = wave;
        this.wave2 = wave2;
        this.icon = icon;

        glowDrawable = new AiGlowDrawable(0.20f, 0.70f, 46);
        glow.setBackground(glowDrawable);
        wave.setBackground(new AiGlowDrawable(0.07f, 0.88f, 0));
        wave2.setBackground(new AiGlowDrawable(0.05f, 0.90f, 0));
        hideWaves();
        glow.setAlpha(0.35f);
    }

    public int state() { return state; }

    public void idle() {
        if (state == IDLE) return;
        state = IDLE;
        wavesOn = false;
        hideWaves();
        rotSpeed = 1f / 14f;
        scaleAmp = 0.035f;
        scaleFreq = 1f / 2.4f;
        glowBase = 0.32f;
        glowDelta = 0.46f;
        glowFreq = 1f / 2.4f;
        waveSpeed = 0f;
        waveMaxAlpha = 0f;
        wave2Speed = 0f;
        wave2MaxAlpha = 0f;
        startMaster();
    }

    public void waiting() {
        state = WAITING;
        wavesOn = true;
        hideWaves();
        rotSpeed = 1f / 2.6f;
        scaleAmp = 0.03f;
        scaleFreq = 1f / 0.26f;
        glowBase = 0.92f;
        glowDelta = 0f;
        glowFreq = 0f;
        waveSpeed = 1f / 1.5f;
        waveMaxAlpha = 0.55f;
        wave2Speed = 0f;
        wave2MaxAlpha = 0f;
        startMaster();
    }

    public void listening() {
        state = LISTENING;
        wavesOn = true;
        hideWaves();
        rotSpeed = 1f / 1.9f;
        scaleAmp = 0.12f;
        scaleFreq = 1f / 0.8f;
        glowBase = 1f;
        glowDelta = 0f;
        glowFreq = 0f;
        waveSpeed = 1f / 1.3f;
        waveMaxAlpha = 0.70f;
        wave2Speed = 1f / 1.3f;
        wave2MaxAlpha = 0.45f;
        startMaster();
    }

    public void speaking() {
        state = SPEAKING;
        wavesOn = true;
        hideWaves();
        rotSpeed = 1f / 3.2f;
        scaleAmp = 0.07f;
        scaleFreq = 1f / 0.9f;
        glowBase = 0.92f;
        glowDelta = 0f;
        glowFreq = 0f;
        waveSpeed = 1f / 1.8f;
        waveMaxAlpha = 0.45f;
        wave2Speed = 0f;
        wave2MaxAlpha = 0f;
        startMaster();
    }

    public void stop() {
        state = OFF;
        if (master != null) { master.cancel(); master = null; }
        hideWaves();
        glow.setAlpha(0f);
        glowDrawable.setRotation(0f);
        wrap.setScaleX(1f);
        wrap.setScaleY(1f);
        icon.setRotation(0f);
    }

    public void pause() {
        if (master != null) master.pause();
    }

    public void resume() {
        if (master != null && state != OFF) master.resume();
    }

    private void startMaster() {
        if (master != null && master.isRunning()) return;
        if (master != null) { try { master.cancel(); } catch (Throwable ignored) {} }
        master = ValueAnimator.ofFloat(0f, 1f);
        master.setDuration(10000);
        master.setRepeatCount(ValueAnimator.INFINITE);
        master.setInterpolator(new LinearInterpolator());
        master.setFrameDelay(33); // ~30 fps
        master.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            private long lastMs = SystemClock.uptimeMillis();
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                apply(f);
            }
        });
        master.start();
    }

    private void apply(float f) {
        // Rotación del halo
        float rot = (f * rotSpeed * 3600f) % 360f; // rotSpeed vueltas/s * 10s? f is 0..1 over 10s => f*10s
        // Corregir: f va de 0 a 1 en 10s, así que segundos = f*10
        float secs = f * 10f;
        rot = (secs * rotSpeed * 360f) % 360f;
        // Invalidar solo si cambió > 2 grados
        float prev = glowDrawable.getRotation();
        if (Math.abs(((rot - prev + 540f) % 360f) - 180f) > 2f) {
            glowDrawable.setRotation(rot);
        }

        // Escala
        float s = 1f + scaleAmp * (float) Math.sin(secs * scaleFreq * 2 * Math.PI);
        wrap.setScaleX(s);
        wrap.setScaleY(s);

        // Alpha del halo
        if (glowDelta > 0f) {
            float a = glowBase + glowDelta * (float) Math.sin(secs * glowFreq * 2 * Math.PI);
            glow.setAlpha(Math.max(0f, Math.min(1f, a)));
        } else {
            glow.setAlpha(glowBase);
        }

        // Ondas
        if (wavesOn) {
            updateWave(wave, waveSpeed, waveMaxAlpha, secs, 0f);
            updateWave(wave2, wave2Speed, wave2MaxAlpha, secs, 0.5f);
        }
    }

    private void updateWave(View v, float speed, float maxAlpha, float secs, float offset) {
        if (v == null || speed <= 0f || maxAlpha <= 0f) {
            if (v != null) { v.setVisibility(View.INVISIBLE); v.setAlpha(0f); }
            return;
        }
        v.setVisibility(View.VISIBLE);
        float t = (secs * speed + offset) % 1f;
        float sc = 0.82f + 0.73f * t;
        float al = maxAlpha * (1f - t);
        v.setScaleX(sc);
        v.setScaleY(sc);
        v.setAlpha(Math.max(0f, al));
    }

    private void hideWaves() {
        hideWave(wave);
        hideWave(wave2);
    }

    private static void hideWave(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setVisibility(View.INVISIBLE);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    // AiGlowDrawable needs getRotation(); add via extension? Use reflection-free: store rotation in drawable via a field.
}
