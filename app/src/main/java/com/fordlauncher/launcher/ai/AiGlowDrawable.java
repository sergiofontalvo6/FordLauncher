package com.fordlauncher.launcher.ai;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

/**
 * Halo cromático estilo Siri / Gemini Live: un gradiente de barrido con los cuatro
 * colores del copiloto que gira alrededor del botón flotante.
 *
 * Se usa tanto para el halo principal como para las ondas expansivas (con distinto
 * grosor de anillo y radio interior).
 */
public class AiGlowDrawable extends Drawable {

    private static final int[] COLORS = {
            0xFF4285F4, // azul Google
            0xFF9B51E0, // violeta
            0xFF00E676, // verde
            0xFFFF007F, // rosa
            0xFF4285F4  // cierra el círculo sin costura
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final float ringWidthRatio;
    private final float innerRatio;
    private final int discAlpha;

    private SweepGradient sweep;
    private float rotation;
    private float intensity = 1f;
    private int baseAlpha = 255;
    private float lastCx = Float.NaN, lastCy = Float.NaN, lastR = -1f;

    public AiGlowDrawable(float ringWidthRatio, float innerRatio, int discAlpha) {
        this.ringWidthRatio = ringWidthRatio;
        this.innerRatio = innerRatio;
        this.discAlpha = discAlpha;
        paint.setStyle(Paint.Style.STROKE);
    }

    /** Ángulo actual del gradiente en grados. */
    public float getRotation() { return rotation; }

    /** Ángulo del gradiente en grados. */
    public void setRotation(float degrees) {
        rotation = degrees % 360f;
        applyMatrix();
        invalidateSelf();
    }

    /** 0..1: opacidad interna del dibujo (la vista también aplica su propio alpha). */
    public void setIntensity(float value) {
        intensity = Math.max(0f, Math.min(1f, value));
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        ensureShader(bounds);
    }

    private void ensureShader(Rect b) {
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f;
        if (r <= 0f) return;
        if (sweep == null || cx != lastCx || cy != lastCy || r != lastR) {
            sweep = new SweepGradient(cx, cy, COLORS, null);
            lastCx = cx;
            lastCy = cy;
            lastR = r;
        }
        applyMatrix();
    }

    private void applyMatrix() {
        if (sweep == null || Float.isNaN(lastCx)) return;
        matrix.setRotate(rotation, lastCx, lastCy);
        sweep.setLocalMatrix(matrix);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f;
        if (r <= 0f) return;
        ensureShader(b);
        if (sweep == null) return;

        paint.setShader(sweep);

        // Disco suave de fondo (muy tenue) para dar sensación de resplandor.
        if (discAlpha > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(clamp((int) (discAlpha * intensity * (baseAlpha / 255f))));
            canvas.drawCircle(cx, cy, r * 0.92f, paint);
        }

        // Anillo principal.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, r * ringWidthRatio));
        paint.setAlpha(clamp((int) (230 * intensity * (baseAlpha / 255f))));
        canvas.drawCircle(cx, cy, r * innerRatio, paint);

        // Anillo interior más fino: profundidad tipo aura.
        paint.setStrokeWidth(Math.max(1f, r * ringWidthRatio * 0.42f));
        paint.setAlpha(clamp((int) (150 * intensity * (baseAlpha / 255f))));
        canvas.drawCircle(cx, cy, r * innerRatio * 0.78f, paint);
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    @Override
    public void setAlpha(int alpha) {
        baseAlpha = clamp(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
