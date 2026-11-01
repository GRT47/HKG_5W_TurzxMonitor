package com.hkmc.datc;

public interface IDATCListener extends android.os.IInterface {
    void onDatcDiagState(int a, int b);

    void onDatcStatus(DatcStatus status);

    void onError(int err);

    void onIG3StatusChanged(boolean ig3);

    void onNotifyError(int err);

    void onTemperatureInformation(
            float drC, int drStateC,
            float drF, int drStateF,
            float psC, int psStateC,
            float psF, int psStateF,
            float ambC, int ambStateC,
            float ambF, int ambStateF,
            float rearC, int rearStateC,
            float rearF, int rearStateF
    );

    void onTunFuncOffADSStatus(int status);

    public static abstract class Stub extends android.os.Binder implements IDATCListener {
        public Stub() {
        }

        public static IDATCListener asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
