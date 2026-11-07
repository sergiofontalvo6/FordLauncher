package com.fordlauncher.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Tarjeta central: mapa estilizado con ruta roja (decorativo). */
public class MapView extends View {
    private final float s;
    private final float radius;
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path line = new Path();

    // carreteras en coordenadas normalizadas: x1,y1,x2,y2,ancho(px de diseño)
    private static final float[][] ROADS = {
        {-0.1f, 0.18f, 1.1f, 0.05f, 10}, {-0.1f, 0.45f, 1.1f, 0.30f, 14},
        {-0.1f, 0.78f, 1.1f, 0.60f, 8}, {0.12f, -0.1f, 0.30f, 1.1f, 8},
        {0.55f, -0.1f, 0.62f, 1.1f, 16}, {0.86f, -0.1f, 0.80f, 1.1f, 8},
    };
    // ruta en px de diseño (tarjeta 420x380)
    private static final float[] ROUTE = {248, 330, 246, 250, 240, 140, 330, 112, 470, 92};

    public MapView(Context c, float scale, float radiusPx) {
        super(c);
        s = scale;
        radius = radiusPx;
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeCap(Paint.Cap.ROUND);
        road.setStrokeJoin(Paint.Join.ROUND);
        road.setColor(Main.LIGHT ? 0xFFD3D8E0 : 0xFF2B2F38);
        route.setStyle(Paint.Style.STROKE);
        route.setStrokeCap(Paint.Cap.ROUND);
        route.setStrokeJoin(Paint.Join.ROUND);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // clipPath fiable en GPUs antiguas
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        cv.drawColor(Main.LIGHT ? 0xFFEDF0F4 : 0xFF1A1D23);
        for (float[] r : ROADS) {
            road.setStrokeWidth(r[4] * s);
            cv.drawLine(r[0] * w, r[1] * h, r[2] * w, r[3] * h, road);
        }
        line.reset();
        line.moveTo(-0.1f * w, 0.95f * h);
        line.lineTo(0.45f * w, 0.55f * h);
        line.lineTo(1.1f * w, 0.85f * h);
        road.setStrokeWidth(6 * s);
        cv.drawPath(line, road);

        float kx = w / 420f, ky = h / 380f;
        line.reset();
        line.moveTo(ROUTE[0] * kx, ROUTE[1] * ky);
        for (int i = 2; i < ROUTE.length; i += 2) line.lineTo(ROUTE[i] * kx, ROUTE[i + 1] * ky);
        route.setColor((Main.RED & 0x00FFFFFF) | 0x40000000);
        route.setStrokeWidth(14 * s);
        cv.drawPath(line, route);
        route.setColor(Main.RED);
        route.setStrokeWidth(5 * s);
        cv.drawPath(line, route);

        float px = 242 * kx, py = 196 * ky;
        fill.setColor((Main.RED & 0x00FFFFFF) | 0x40000000);
        cv.drawCircle(px, py, 18 * s, fill);
        fill.setColor(Color.WHITE);
        cv.drawCircle(px, py, 9 * s, fill);
        fill.setColor(Main.RED);
        cv.drawCircle(px, py, 6 * s, fill);
        cv.restore();
    }
}
