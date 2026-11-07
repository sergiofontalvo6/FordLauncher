package com.fordlauncher.launcher;

import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.os.Bundle;

/**
 * Se compila aparte (contra el stub de src/hiddenapi) y se carga por reflexión, para que el resto
 * del código nunca vea la API oculta android.media.IRemoteControlDisplay. En el equipo, la clase
 * padre es la real del sistema (Android 4.0–4.3), con su propio onTransact.
 */
public class RcDisplay extends android.media.IRemoteControlDisplay.Stub {
    private final RcCallback cb;

    public RcDisplay(RcCallback cb) { this.cb = cb; }

    public void setCurrentClientId(int gen, PendingIntent pi, boolean clearing) { cb.clientId(pi, clearing); }
    public void setPlaybackState(int gen, int state, long t) { cb.playbackState(state, -1); }
    public void setTransportControlFlags(int gen, int flags) { }
    public void setMetadata(int gen, Bundle m) { cb.metadata(m); }
    public void setArtwork(int gen, Bitmap b) { cb.artwork(b); }
    public void setAllMetadata(int gen, Bundle m, Bitmap b) { cb.allMetadata(m, b); }
    // variantes de otras versiones de Android 4.x
    public void setEnabled(boolean e) { }
    public void setCurrentClientId(int gen, PendingIntent pi, boolean clearing, boolean x) { cb.clientId(pi, clearing); }
    public void setPlaybackState(int gen, int state, long t, long pos, float speed) { cb.playbackState(state, pos); }
}
