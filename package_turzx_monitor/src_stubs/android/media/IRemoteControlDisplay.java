package android.media;

public interface IRemoteControlDisplay extends android.os.IInterface {
    void setCurrentClientId(int clientGeneration, android.app.PendingIntent clientMediaIntent, boolean clearing);

    void setEnabled(boolean enabled);

    void setPlaybackState(int generationId, int state, long stateChangeTimeMs, long currentPosMs, float speed);

    void setTransportControlInfo(int generationId, int transportControlFlags, int posCapabilities);

    void setMetadata(int generationId, android.os.Bundle metadata);

    void setArtwork(int generationId, android.graphics.Bitmap artwork);

    void setAllMetadata(int generationId, android.os.Bundle metadata, android.graphics.Bitmap artwork);

    public static abstract class Stub extends android.os.Binder implements IRemoteControlDisplay {
        public Stub() {
        }

        public static IRemoteControlDisplay asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
