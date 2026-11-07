package com.fordlauncher.launcher;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.os.Bundle;

/** Lo que recibe RcDisplay (pantalla de control remoto de Android 4.0–4.3). */
public interface RcCallback {
    void clientId(PendingIntent pi, boolean clearing);
    /** pos = -1 si la versión de Android no la informa. */
    void playbackState(int state, long pos);
    void metadata(Bundle m);
    void artwork(Bitmap b);
    void allMetadata(Bundle m, Bitmap b);
}
