package com.hkmc.micom;

/** Compile-time stub of automotive.jar's com.hkmc.micom.IMicomDataCallback (oneway onEvent(rcvId, funcH, funcL, data)). */
public interface IMicomDataCallback extends android.os.IInterface {
    void onEvent(byte rcvId, byte funcH, byte funcL, byte[] data);

    public static abstract class Stub extends android.os.Binder implements IMicomDataCallback {
        public Stub() {
        }

        public static IMicomDataCallback asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
