package com.fordlauncher.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;

/**
 * Decodificación de imágenes con escalado real al tamaño que se va a dibujar.
 *
 * <p>En una pantalla de 1024x600 decodificar un JPEG de 1920x1080 en ARGB_8888
 * cuesta más de 8 MiB por imagen. Aquí se usa {@code inSampleSize} y, cuando la
 * imagen no necesita transparencia, {@code RGB_565} (la mitad de memoria).
 * El ajuste fino se hace con density en el propio decode: crear un segundo
 * bitmap con createScaledBitmap dejaba el native heap varios MiB más alto.
 */
final class BitmapUtil {

    private BitmapUtil() {}

    /** Potencia de 2 más grande que mantiene la imagen al menos tan grande como el objetivo. */
    static int sampleSize(int w, int h, int reqW, int reqH) {
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) return 1;
        int s = 1;
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2;
        return s;
    }

    private static BitmapFactory.Options options(int w, int h, int reqW, int reqH, boolean alpha) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        if (reqW <= 0 || reqH <= 0 || w <= 0 || h <= 0) return o;
        int s = sampleSize(w, h, reqW, reqH);
        o.inSampleSize = s;
        o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        int sw = Math.max(1, w / s), sh = Math.max(1, h / s);
        if (sw <= reqW && sh <= reqH) {
            o.inScaled = false;
        } else {
            // BitmapFactory aplica inTargetDensity/inDensity después de inSampleSize.
            // Así se obtiene el tamaño final en una sola asignación nativa.
            float k = Math.min(reqW / (float) sw, reqH / (float) sh);
            o.inScaled = true;
            o.inDensity = 1000;
            o.inTargetDensity = Math.max(1, Math.round(1000 * k));
        }
        return o;
    }

    static Bitmap decodeFile(String path, int reqW, int reqH, boolean alpha) {
        if (path == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            return BitmapFactory.decodeFile(path,
                    options(bounds.outWidth, bounds.outHeight, reqW, reqH, alpha));
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Bitmap decodeStream(InputStream in, int reqW, int reqH, boolean alpha) {
        if (in == null) return null;
        try {
            return decodeByteArray(readAll(in), reqW, reqH, alpha);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    static Bitmap decodeByteArray(byte[] data, int reqW, int reqH, boolean alpha) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            return BitmapFactory.decodeByteArray(data, 0, data.length,
                    options(bounds.outWidth, bounds.outHeight, reqW, reqH, alpha));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Copia reducida para liberar una carátula grande sin perderla del todo.
     * Devuelve el bitmap original si ya cabe en {@code maxDim}.
     */
    static Bitmap downscaleCopy(Bitmap src, int maxDim) {
        if (src == null || maxDim <= 0) return src;
        int w = src.getWidth(), h = src.getHeight();
        int biggest = Math.max(w, h);
        if (biggest <= maxDim) return src;
        float k = maxDim / (float) biggest;
        Bitmap small = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
        Bitmap.Config want = src.hasAlpha() ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        if (small.getConfig() != want) {
            Bitmap converted = small.copy(want, false);
            small.recycle();
            small = converted;
        }
        return small;
    }
}
