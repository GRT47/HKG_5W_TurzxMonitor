package com.hkmc.bcm;

public interface IBCMListener extends android.os.IInterface {
    void onACCChanged(int a);
    void onAMPCallSystemStatusChanged(int a);
    void onAccInputCheckChanged(int a);
    void onAirbagStatusChanged(int a);
    void onAstSeatHeaterStatusChanged(int a);
    void onBatEvtAlarmChanged(int a);
    void onBatSocStatus(int a);
    void onBatSocStatus_M(int a);
    void onBattChargeDelay(int a, int b);
    void onBatteryPreconditioning(int a);
    void onCCUDiagStatus(int a);
    void onCDMChanged(int a, int b, int c);
    void onCanNetworkStatus(int a);
    void onChangedBattAutoCutPreWrng(int a);
    void onChangedBattMngAlert(int a);
    void onDPFLampStatChanged(int a);
    void onDigitalKeyDataChanged(byte[] data);
    void onDoorAllStateChanged(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j);
    void onDoorLockStateChanged(int a);
    void onDriveModeChanged(int a);
    void onDrvSeatHeaterStatusChanged(int a);
    void onEVSEChanged(long a, long b);
    void onEcsStatusChanged(int a, int b, int c, int d, int e, int f, int g, int h, int i);
    void onEngineIdleNotiAlarm(byte a);
    void onEngineStatusChanged(boolean a);
    void onFuelWarnStatusChanged(boolean a);
    void onGearStatusInfoChanged(int a, int b);
    void onIgnitionChanged(int a);
    void onLampStatusChanged(int a, int b, int c, int d);
    void onModemInfoChanged(byte[] data);
    void onPanic(int a);
    void onParkBrakeStatusChanged(int a);
    void onParkCrashChanged(int a);
    void onRLSeatHeaterStatusChanged(int a);
    void onRRSeatHeaterStatusChanged(int a);
    void onRctaAlarmChanged(int a, int b);
    void onRearStatusInfoChanged(int a);
    void onRemoteEngingStatusChanged(int a);
    void onRemoteTeleHeatingStatusChanged(int stWheelHeatState, int defoggerState);
    void onSBCMChanged(int a, int b, int c);
    void onSeatHeaterStatusChanged(int a, int b, int c, int d);
    void onSeatVentilationStatusChanged(int a, int b, int c, int d);
    void onSeatbeltStatusChanged(int a, int b);
    void onSideMirrorStatusChanged(int a);
    void onSmartKeyBatteryWarning(int a);
    void onSocAlaramStatusChanged(int a);
    void onSpeedUnitChanged(int a);
    void onStolen(int a);
    void onSysUpdateStatusAlarm(int a);
    void onVehicleStatusDeliveryMode(int a);
    void onWindowActStateChanged(int a, int b, int c, int d);
    void onWindowDetailOpenStateChanged(int a, int b, int c, int d);
    void onWindowOpenStateChanged(int a, int b, int c, int d);
    void onbreakOilStatus(int a);
    void onsunroofOpen(int a);

    public static abstract class Stub extends android.os.Binder implements IBCMListener {
        public Stub() {
        }

        public static IBCMListener asInterface(android.os.IBinder obj) {
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }
    }
}

