package com.fordlauncher.launcher.ai;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import java.util.ArrayList;
import java.util.List;

/** Animaciones del botón flotante de IA: reposo, espera, escucha y respuesta. */
public class AiFabAnimator {
    public static final int OFF = 0;
    public static final int IDLE = 1;
    public static final int WAITING = 2;
    public static final int LISTENING = 3;
    public static final int SPEAKING = 4;

    private final View wrap;
    private final View glow;
    private final View wave;
    private final View icon;
    private final List<ObjectAnimator> running = new ArrayList<ObjectAnimator>();
    private int state = OFF;

    public AiFabAnimator(View wrap, View glow, View wave, View icon) {
        this.wrap = wrap;
        this.glow = glow;
        this.wave = wave;
        this.icon = icon;
    }

    public int state() { return state; }

    /** Reposo: respiración suave de escala y halo. */
    public void idle() {
        stopAll();
        state = IDLE;
        glow.setAlpha(0.35f);
        ObjectAnimator scale = ObjectAnimator.ofPropertyValuesHolder(wrap,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.04f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.04f));
        scale.setDuration(2200);
        scale.setRepeatMode(ObjectAnimator.REVERSE);
        scale.setRepeatCount(ObjectAnimator.INFINITE);
        scale.setInterpolator(new AccelerateDecelerateInterpolator());
        ObjectAnimator halo = ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 0.85f);
        halo.setDuration(2200);
        halo.setRepeatMode(ObjectAnimator.REVERSE);
        halo.setRepeatCount(ObjectAnimator.INFINITE);
        halo.setInterpolator(new AccelerateDecelerateInterpolator());
        track(scale);
        track(halo);
    }

    /** Pregunta enviada o procesando: el icono gira y emite ondas, sin pulsación. */
    public void waiting() {
        stopAll();
        state = WAITING;
        glow.setAlpha(0.9f);
        startRotation();
        startWave();
    }

    /** El reconocedor ya detectó voz: pulsación enérgica además de giro y ondas. */
    public void listening() {
        stopAll();
        state = LISTENING;
        glow.setAlpha(1f);
        startRotation();
        startWave();
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(wrap,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f));
        pulse.setDuration(600);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        track(pulse);
    }

    /** La respuesta se está leyendo en voz alta: icono encendido y estático. */
    public void speaking() {
        stopAll();
        state = SPEAKING;
        glow.setAlpha(0.9f);
    }

    /** Detiene todas las animaciones (p. ej. botón desactivado) para no gastar CPU. */
    public void stop() {
        stopAll();
        state = OFF;
        glow.setAlpha(0f);
    }

    private void startRotation() {
        ObjectAnimator rot = ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f);
        rot.setDuration(4000);
        rot.setRepeatCount(ObjectAnimator.INFINITE);
        rot.setInterpolator(new LinearInterpolator());
        track(rot);
    }

    private void startWave() {
        wave.setVisibility(View.VISIBLE);
        ObjectAnimator w = ObjectAnimator.ofPropertyValuesHolder(wave,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.5f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.5f),
                PropertyValuesHolder.ofFloat("alpha", 0.9f, 0f));
        w.setDuration(1400);
        w.setRepeatCount(ObjectAnimator.INFINITE);
        w.setRepeatMode(ObjectAnimator.RESTART);
        w.setInterpolator(new LinearInterpolator());
        track(w);
    }

    private void track(ObjectAnimator a) {
        running.add(a);
        a.start();
    }

    private void stopAll() {
        for (ObjectAnimator a : running) a.cancel();
        running.clear();
        wave.setAlpha(0f);
        wave.setVisibility(View.INVISIBLE);
        icon.setRotation(0f);
        wrap.setScaleX(1f);
        wrap.setScaleY(1f);
    }
}
