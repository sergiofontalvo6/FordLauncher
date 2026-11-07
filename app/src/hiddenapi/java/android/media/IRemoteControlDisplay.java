package android.media;
// Solo para compilar (no se empaqueta): en el equipo se usa la clase oculta real de Android 4.0–4.3.
public interface IRemoteControlDisplay extends android.os.IInterface {
    void setCurrentClientId(int clientGeneration, android.app.PendingIntent clientMediaIntent, boolean clearing) throws android.os.RemoteException;
    void setPlaybackState(int generationId, int state, long stateChangeTimeMs) throws android.os.RemoteException;
    void setTransportControlFlags(int generationId, int transportControlFlags) throws android.os.RemoteException;
    void setMetadata(int generationId, android.os.Bundle metadata) throws android.os.RemoteException;
    void setArtwork(int generationId, android.graphics.Bitmap artwork) throws android.os.RemoteException;
    void setAllMetadata(int generationId, android.os.Bundle metadata, android.graphics.Bitmap artwork) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IRemoteControlDisplay {
        public Stub() { }
        public android.os.IBinder asBinder() { return this; }
    }
}
