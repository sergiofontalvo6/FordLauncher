package com.fordlauncher.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Iconos de llamada dibujados a mano con el mismo lenguaje visual de los del iPhone
 * (SF Symbols no existen en Android). Diseñados en una rejilla de 24 unidades.
 */
final class IosIcons {

    static final int MIC = 0, MIC_OFF = 1, KEYPAD = 2, SPEAKER = 3, PAUSE = 4, PLAY = 5, HANDSET = 6;

    private IosIcons() {}

    static Bitmap bitmap(int type, int px, int color) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float u = px / 24f;
        stroke.setStrokeWidth(2.2f * u);

        switch (type) {
            case MIC:
            case MIC_OFF:
                c.drawRoundRect(new RectF(9 * u, 2 * u, 15 * u, 14 * u), 3 * u, 3 * u, fill);
                c.drawArc(new RectF(6 * u, 6 * u, 18 * u, 18 * u), 0, 180, false, stroke);
                c.drawLine(12 * u, 18 * u, 12 * u, 21.5f * u, stroke);
                c.drawLine(8.5f * u, 21.5f * u, 15.5f * u, 21.5f * u, stroke);
                if (type == MIC_OFF) c.drawLine(4 * u, 4 * u, 20 * u, 20 * u, stroke);
                break;
            case KEYPAD: {
                float r = 1.9f * u;
                float[] xs = {6, 12, 18};
                for (float y : xs) for (float x : xs) c.drawCircle(x * u, y * u, r, fill);
                break;
            }
            case SPEAKER:
                c.drawPath(polygon(u, new float[]{3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15}), fill);
                c.drawArc(new RectF(8 * u, 8 * u, 16 * u, 16 * u), -50, 100, false, stroke);
                c.drawArc(new RectF(4.5f * u, 4.5f * u, 19.5f * u, 19.5f * u), -50, 100, false, stroke);
                break;
            case PAUSE:
                c.drawRoundRect(new RectF(6 * u, 5 * u, 10.5f * u, 19 * u), 1.5f * u, 1.5f * u, fill);
                c.drawRoundRect(new RectF(13.5f * u, 5 * u, 18 * u, 19 * u), 1.5f * u, 1.5f * u, fill);
                break;
            case PLAY:
                c.drawPath(polygon(u, new float[]{8, 5, 19, 12, 8, 19}), fill);
                break;
            case HANDSET: {
                // Teléfono clásico, estilo iPhone (phone.fill)
                Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                hp.setColor(color);
                hp.setStyle(Paint.Style.STROKE);
                hp.setStrokeCap(Paint.Cap.ROUND);
                hp.setStrokeWidth(3.2f * u);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(6.5f * u, 15.5f * u);
                p.cubicTo(8.5f * u, 9.5f * u, 15.5f * u, 9.5f * u, 17.5f * u, 15.5f * u);
                c.drawPath(p, hp);
                // cápsula izquierda
                c.save();
                c.rotate(-35, 6.2f * u, 15.2f * u);
                c.drawRoundRect(new RectF(3.8f * u, 12.2f * u, 8.6f * u, 18.2f * u), 2.4f * u, 2.4f * u, fill);
                c.restore();
                // cápsula derecha
                c.save();
                c.rotate(35, 17.8f * u, 15.2f * u);
                c.drawRoundRect(new RectF(15.4f * u, 12.2f * u, 20.2f * u, 18.2f * u), 2.4f * u, 2.4f * u, fill);
                c.restore();
                break;
            }
            default:
                break;
        }
        return b;
    }

    private static android.graphics.Path polygon(float u, float[] pts) {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(pts[0] * u, pts[1] * u);
        for (int i = 2; i < pts.length; i += 2) p.lineTo(pts[i] * u, pts[i + 1] * u);
        p.close();
        return p;
    }
}
