package com.fordlauncher.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Carátula circular con anillo de progreso (estilo de la referencia). */
public class PlayerArt extends View {
    private final float s;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint prog = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private Bitmap bitmap, placeholder;
    private float progress = -1f; // -1 = sin duración

    public PlayerArt(Context c, float scale, Bitmap placeholderIcon) {
        super(c);
        s = scale;
        placeholder = placeholderIcon;
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Main.BTN);
        ring.setStrokeWidth(4 * s);
        prog.setStyle(Paint.Style.STROKE);
        prog.setColor(Main.RED);
        prog.setStrokeWidth(4 * s);
        prog.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(Main.RED);
        bg.setColor(Main.INNER);
    }

    public void setArt(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    public void setProgress(float p) {
        progress = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float rr = Math.min(w, h) / 2f - 8 * s;      // radio del anillo
        float ra = rr - 12 * s;                       // radio de la carátula

        box.set(cx - rr, cy - rr, cx + rr, cy + rr);
        c.drawCircle(cx, cy, rr, ring);
        if (progress >= 0) {
            float sweep = Math.max(0f, Math.min(1f, progress)) * 360f;
            c.drawArc(box, -90f, sweep, false, prog);
            double a = Math.toRadians(-90f + sweep);
            c.drawCircle(cx + (float) (rr * Math.cos(a)), cy + (float) (rr * Math.sin(a)), 6 * s, knob);
        }

        if (bitmap != null && !bitmap.isRecycled()) {
            BitmapShader sh = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = Math.max(2 * ra / bitmap.getWidth(), 2 * ra / bitmap.getHeight());
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(cx - bitmap.getWidth() * scale / 2f, cy - bitmap.getHeight() * scale / 2f);
            sh.setLocalMatrix(m);
            art.setShader(sh);
            c.drawCircle(cx, cy, ra, art);
        } else {
            c.drawCircle(cx, cy, ra, bg);
            if (placeholder != null) {
                float sz = ra * 0.8f;
                RectF d = new RectF(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setAlpha(150);
                if (Main.LIGHT) p.setColorFilter(new android.graphics.PorterDuffColorFilter(Main.MUTED, android.graphics.PorterDuff.Mode.SRC_IN));
                c.drawBitmap(placeholder, null, d, p);
            }
        }
    }
}
