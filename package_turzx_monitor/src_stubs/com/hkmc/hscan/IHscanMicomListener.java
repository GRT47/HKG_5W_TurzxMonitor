package com.hkmc.hscan;

public interface IHscanMicomListener extends android.os.IInterface {
    void onEventFromMicom(byte b1, byte b2, byte b3, byte[] data);

    void onMicomReadyChanged(boolean ready);

    void onSystemReadyChanged(boolean ready);

    public static abstract class Stub extends android.os.Binder implements IHscanMicomListener {
        public Stub() {
        }

        public static IHscanMicomListener asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}

